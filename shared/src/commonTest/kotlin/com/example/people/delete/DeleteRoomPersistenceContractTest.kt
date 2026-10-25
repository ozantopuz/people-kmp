package com.example.people.delete

import androidx.room.execSQL
import androidx.room.useWriterConnection
import com.example.people.data.delete.DeleteAcceptanceResult
import com.example.people.data.local.DeleteFailureKind
import com.example.people.data.local.UserEntity
import com.example.people.data.remote.ApiResult
import com.example.people.domain.repository.UserDataError
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** RED CONTRACT: these assertions use the actual Room file and DAO, not a fake cache. */
@OptIn(ExperimentalCoroutinesApi::class)
class DeleteRoomPersistenceContractTest {
    @Test fun createdUserDeleteAndRefreshKeepUnrelatedLastPageUserAcrossReopen() = runTest {
        val f = DeleteContractFixture(testScheduler)
        try {
            f.seed(20, 30)
            f.blocking("save newly created user 90") {
                f.database.userDao().saveCreatedUser(UserEntity(90, "Created", "created@example.com", 200, addedAt = 200))
            }
            f.awaitVisible(listOf(90, 20, 30))
            val deleted = f.accept(90)
            f.replyDelete(90, ApiResult.Success(Unit))
            advanceTimeBy(5_000); runCurrent()
            f.awaitStatus(deleted.id, DeleteStatus.Confirmed204)
            f.api.snapshot = listOf(dto(20), dto(30))
            f.refresh()
            f.awaitVisible(listOf(20, 30))
            assertEquals(listOf(90L), f.api.deleteCalls)
            assertEquals(listOf(90L), f.rows().map { it.userId })
            assertEquals(listOf(20L, 30L), f.blocking("read cached users after refresh") {
                f.database.userDao().observeUsers().first().map { it.id }
            })

            f.terminate()
            f.reopen(); runCurrent()
            f.awaitVisible(listOf(20, 30))
            assertEquals(listOf(90L), f.rows().map { it.userId })
            assertEquals(listOf(90L), f.api.deleteCalls)
        } finally { f.close() }
    }

    @Test fun changedLastPageMembershipRemovesOutOfPageRowWithoutDeletingOrTombstoningIt() = runTest {
        val f = DeleteContractFixture(testScheduler)
        try {
            f.seed(20, 30)
            f.blocking("save newly created user 90") {
                f.database.userDao().saveCreatedUser(UserEntity(90, "Created", "created@example.com", 200, addedAt = 200))
            }
            val deleted = f.accept(90)
            f.replyDelete(90, ApiResult.Success(Unit))
            advanceTimeBy(5_000); runCurrent()
            f.awaitStatus(deleted.id, DeleteStatus.Confirmed204)

            // The server's new final page excludes 20. It is no longer in this feed,
            // even though this app has never requested its deletion.
            f.api.snapshot = listOf(dto(30), dto(40))
            f.refresh()
            f.awaitVisible(listOf(30, 40))
            assertEquals(listOf(90L), f.api.deleteCalls)
            assertEquals(listOf(90L), f.rows().map { it.userId })
            assertEquals(listOf(30L, 40L), f.blocking("read cached final-page users") {
                f.database.userDao().observeUsers().first().map { it.id }
            })
        } finally { f.close() }
    }

    @Test fun delete404TombstonesOnlyCreatedTargetAndKeepsUnrelatedUserOnRestart() = runTest {
        val f = DeleteContractFixture(testScheduler)
        try {
            f.seed(20)
            f.blocking("save newly created user 90") {
                f.database.userDao().saveCreatedUser(UserEntity(90, "Created", "created@example.com", 200, addedAt = 200))
            }
            val deleted = f.accept(90)
            f.replyDelete(90, ApiResult.Failure(UserDataError.NotFound))
            advanceTimeBy(5_000); runCurrent()
            f.awaitStatus(deleted.id, DeleteStatus.ConfirmedAbsent)
            f.api.snapshot = listOf(dto(90), dto(20)) // stale response for deleted ID
            f.refresh()
            f.awaitVisible(listOf(20))
            assertEquals(listOf(90L), f.api.deleteCalls)
            assertEquals(listOf(90L), f.rows().map { it.userId })
            assertEquals(listOf(20L), f.blocking("read cached user after DELETE 404") {
                f.database.userDao().observeUsers().first().map { it.id }
            })

            f.terminate()
            f.reopen(); runCurrent()
            f.awaitVisible(listOf(20))
            assertEquals(listOf(90L), f.rows().map { it.userId })
        } finally { f.close() }
    }

    @Test fun refreshCannotResurrectEitherOfTwoPendingUsers() = runTest {
        val f = DeleteContractFixture(testScheduler)
        try {
            f.seed(1, 2, 3)
            f.recordVisible(); runCurrent()
            val a = f.accept(1)
            f.awaitVisible(listOf(2, 3))
            f.awaitVisibleEmissions(listOf(1, 2, 3), listOf(2, 3))
            val b = f.accept(2)
            f.awaitVisible(listOf(3))
            f.api.snapshot = listOf(dto(1), dto(2), dto(3))
            f.refresh()
            f.awaitVisible(listOf(3))
            f.awaitVisibleEmissions(listOf(1, 2, 3), listOf(2, 3), listOf(3))
            assertEquals(setOf(a.id, b.id), f.rows().map { it.id }.toSet())
            assertEquals(1, f.api.fetchCalls)
            assertEmissionOrder(f.visibleEmissions, listOf(1, 2, 3), listOf(2, 3), listOf(3))
            assertFalse(f.visibleEmissions.dropWhile { it != listOf(3L) }.any { 1L in it || 2L in it })
        } finally { f.close() }
    }

    @Test fun completedTombstoneMasksStalePageAcrossDatabaseReopen() = runTest {
        val f = DeleteContractFixture(testScheduler)
        try {
            f.seed(1, 2)
            val tx = f.accept(1)
            f.replyDelete(1, ApiResult.Success(Unit))
            advanceTimeBy(5_000); runCurrent()
            f.awaitStatus(tx.id, DeleteStatus.Confirmed204)
            f.terminate()
            f.reopen(); runCurrent()
            f.api.snapshot = listOf(dto(1), dto(2)) // stale last-page read
            f.refresh()
            f.awaitVisible(listOf(2))
            assertEquals(DeleteStatus.Confirmed204, f.rows().single { it.id == tx.id }.status)
            assertEquals(listOf(1L), f.api.deleteCalls)
        } finally { f.close() }
    }

    @Test fun confirmedAbsentRemovesCachedRowAndDoesNotReplayErrorAfterReopen() = runTest {
        val f = DeleteContractFixture(testScheduler)
        try {
            f.seed(1)
            val tx = f.accept(1)
            f.replyDelete(1, ApiResult.Failure(UserDataError.NotFound))
            advanceTimeBy(5_000); runCurrent()
            f.awaitStatus(tx.id, DeleteStatus.ConfirmedAbsent)
            assertEquals(emptyList(), f.blocking("cached row removed after DELETE 404") {
                f.database.userDao().observeUsers().first().map { it.id }
            })
            f.terminate()
            f.reopen(); runCurrent()
            assertEquals(null, withTimeoutOrNull(10) { f.processor.failedDeletes.first() })
            f.api.snapshot = listOf(dto(1))
            f.refresh()
            f.awaitVisible(emptyList())
            assertEquals(emptyList(), f.blocking("stale snapshot filtered from cache") {
                f.database.userDao().observeUsers().first().map { it.id }
            })
            assertEquals(DeleteStatus.ConfirmedAbsent, f.rows().single().status)
        } finally { f.close() }
    }

    @Test fun oldFailureSnackbarDoesNotReplayButANewFailureStillReports() = runTest {
        val f = DeleteContractFixture(testScheduler)
        try {
            f.seed(1, 2)
            val old = f.accept(1)
            f.replyDelete(1, ApiResult.Failure(UserDataError.Forbidden))
            advanceTimeBy(5_000); runCurrent()
            f.awaitStatus(old.id, DeleteStatus.Failed)
            f.terminate()
            f.reopen(); runCurrent()
            assertEquals(null, withTimeoutOrNull(10) { f.processor.failedDeletes.first() })
            val fresh = f.accept(2)
            f.replyDelete(2, ApiResult.Failure(UserDataError.Forbidden))
            advanceTimeBy(5_000); runCurrent()
            f.awaitStatus(fresh.id, DeleteStatus.Failed)
            assertEquals(
                com.example.people.data.delete.DeleteFailure(fresh.id, DeleteFailureKind.Forbidden),
                f.processor.failedDeletes.first(),
            )
            f.awaitVisible(listOf(1, 2))
        } finally { f.close() }
    }

    @Test fun legacyNotFoundTerminalRowIsResolvedOnRecovery() = runTest {
        val f = DeleteContractFixture(testScheduler)
        try {
            f.seed(1)
            val tx = f.accept(1)
            f.blocking("stop processor before creating legacy state") { f.processor.shutdownAndJoin() }
            f.blocking("create legacy DELETE 404 state") {
                val dao = f.database.deleteTransactionDao()
                val at = tx.deadlineAtEpochMillis
                assertEquals(1, dao.claimForDelete(tx.id, at))
                assertEquals(1, dao.beginVerification(tx.id, DeleteFailureKind.NotFound, null, at))
                assertEquals(1, dao.markAbsentOrInaccessible(tx.id, at))
            }
            f.terminate()
            f.reopen(); runCurrent()
            f.awaitStatus(tx.id, DeleteStatus.ConfirmedAbsent)
            assertEquals(emptyList(), f.blocking("legacy cached user removed") {
                f.database.userDao().observeUsers().first().map { it.id }
            })
            assertEquals(null, f.rows().single().lastFailureKind)
            assertEquals(null, withTimeoutOrNull(10) { f.processor.failedDeletes.first() })
        } finally { f.close() }
    }

    @Test fun concurrentSnapshotCommitAndAcceptPreserveRowsAndIntents() = runTest {
        val f = DeleteContractFixture(testScheduler)
        try {
            f.seed(1, 2, 3)
            val a = f.accept(1)
            val gate = CompletableDeferred<Unit>()
            f.api.snapshotGate = gate
            f.api.snapshot = listOf(dto(1), dto(2), dto(3), dto(4))
            val refresh = async(Dispatchers.Default) { f.processor.refresh() }
            try {
                f.awaitFetchCalls(1)
                val b = f.accept(2)
                gate.complete(Unit)
                f.blocking("concurrent refresh to complete") { refresh.await() }
                f.awaitVisible(listOf(3, 4))
                assertEquals(setOf(a.id, b.id), f.rows().map { it.id }.toSet())
                val storedIds = f.blocking("snapshot users to be readable") { f.database.userDao().observeUsers().first().map { it.id } }
                assertTrue(3L in storedIds && 4L in storedIds)
                assertEquals(storedIds.size, storedIds.distinct().size)
                assertEquals(emptyList(), f.api.deleteCalls)
            } finally {
                gate.complete(Unit)
                f.blocking("concurrent refresh to cancel and join") { refresh.cancelAndJoin() }
            }
        } finally { f.close() }
    }

    @Test fun failedFinalizeWriteRollsBackAndNeverSendsAnImmediateSecondDelete() = runTest {
        val f = DeleteContractFixture(testScheduler)
        try {
            f.seed(1)
            val tx = f.accept(1)
            advanceTimeBy(5_000); runCurrent()
            f.awaitStatus(tx.id, DeleteStatus.InFlight)
            f.awaitDeleteCalls(1)
            // Force the Room terminal write to fail after the server has answered 204.
            f.blocking("failure trigger to be installed in Room") { f.database.useWriterConnection { it.execSQL("CREATE TRIGGER reject_finish BEFORE UPDATE ON delete_transactions WHEN OLD.userId = 1 AND NEW.status != OLD.status BEGIN SELECT RAISE(ABORT, 'test finalize failure'); END") } }
            f.replyDelete(1, ApiResult.Success(Unit)); runCurrent()
            f.awaitDeleteResponses(1)
            f.blocking("processor shutdown to join after failed finalization") { f.processor.shutdownAndJoin() }
            assertEquals(DeleteStatus.InFlight, f.rows().single().status)
            assertEquals(listOf(1L), f.api.deleteCalls)
            f.awaitVisible(emptyList())
            f.blocking("failure trigger to be removed from Room") { f.database.useWriterConnection { it.execSQL("DROP TRIGGER reject_finish") } }
            f.api.absent(1)
            f.terminate()
            f.reopen(); runCurrent()
            f.awaitStatus(tx.id, DeleteStatus.AbsentOrInaccessible)
            assertEquals(listOf(1L), f.api.deleteCalls)
        } finally { f.close() }
    }

    @Test fun completedTombstoneRejectsAStaleReintroducedUserId() = runTest {
        val f = DeleteContractFixture(testScheduler)
        try {
            f.seed(1)
            val first = f.accept(1)
            f.replyDelete(1, ApiResult.Success(Unit))
            advanceTimeBy(5_000); runCurrent()
            f.awaitStatus(first.id, DeleteStatus.Confirmed204)
            // A stale server snapshot must not make ID 1 deletable again.
            f.api.snapshot = listOf(dto(1))
            f.refresh()
            f.awaitVisible(emptyList())
            assertIs<DeleteAcceptanceResult.Rejected>(f.blocking("stale user acceptance to reject") { f.processor.accept(1) })
            assertEquals(1, f.rows().size)
            assertEquals(DeleteStatus.Confirmed204, f.rows().single().status)
            assertEquals(first.id, f.rows().single().id)
        } finally { f.close() }
    }
}
