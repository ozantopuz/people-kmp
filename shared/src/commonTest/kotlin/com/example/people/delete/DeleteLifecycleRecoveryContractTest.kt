package com.example.people.delete

import androidx.sqlite.execSQL
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.example.people.data.remote.ApiResult
import com.example.people.data.delete.DeleteAcceptanceResult
import com.example.people.domain.repository.UserDataError
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/** RED CONTRACT: a fresh process recovers intents but never restores old Undo actions. */
@OptIn(ExperimentalCoroutinesApi::class)
class DeleteLifecycleRecoveryContractTest {
    @Test fun joinedShutdownStopsOnlyTheProcessorAndFreshStartUsesThePersistedDeadline() = runTest {
        val f = DeleteContractFixture(testScheduler)
        try {
            f.seed(1)
            val tx = f.accept(1)
            f.blocking("processor shutdown to join") { f.processor.shutdownAndJoin() }
            f.blocking("processor shutdown to join") { f.processor.shutdownAndJoin() } // Repeated disposal is safe.
            assertNull(f.processor.undoTarget.value?.transactionId)
            assertIs<DeleteAcceptanceResult.Rejected>(f.blocking("acceptance after terminal shutdown to reject") { f.processor.accept(1) })
            advanceTimeBy(6_000); runCurrent()
            assertEquals(emptyList(), f.api.deleteCalls)
            // Shutdown is terminal for this instance; a new owner resumes from Room.
            f.startCompetingProcessor(); runCurrent()
            f.awaitStatus(tx.id, DeleteStatus.InFlight)
            f.awaitDeleteCalls(1)
            assertEquals(listOf(1L), f.api.deleteCalls)
        } finally { f.close() }
    }

    @Test fun removingTheScreenObserverDoesNotCancelTheAppProcessor() = runTest {
        val f = DeleteContractFixture(testScheduler)
        try {
            f.seed(1)
            val observed = CompletableDeferred<Unit>()
            val screen = launch { f.processor.observeVisibleUsers().collect { observed.complete(Unit) } }
            try {
                runCurrent()
                observed.await()
                val tx = f.accept(1)
                screen.cancelAndJoin()
                advanceTimeBy(5_000); runCurrent()
                f.awaitStatus(tx.id, DeleteStatus.InFlight)
                f.awaitDeleteCalls(1)
                assertEquals(listOf(1L), f.api.deleteCalls)
            } finally { screen.cancelAndJoin() }
        } finally { f.close() }
    }

    @Test fun relaunchBeforeDeadlineKeepsOriginalDeadlineButDropsOldUndo() = runTest {
        val f = DeleteContractFixture(testScheduler)
        try {
            f.seed(1)
            val tx = f.accept(1)
            advanceTimeBy(3_000); runCurrent()
            f.terminate()
            f.reopen(); runCurrent()
            assertNull(f.processor.undoTarget.value?.transactionId)
            assertEquals(tx.deadlineAtEpochMillis, f.rows().single().deadlineAtEpochMillis)
            f.awaitVisible(emptyList())
            advanceTimeBy(1_999); runCurrent()
            assertEquals(emptyList(), f.api.deleteCalls)
            advanceTimeBy(1); runCurrent()
            f.awaitStatus(tx.id, DeleteStatus.InFlight)
            f.awaitDeleteCalls(1)
            assertEquals(listOf(1L), f.api.deleteCalls)
        } finally { f.close() }
    }

    @Test fun terminationImmediatelyAfterAcceptanceAndExpiredRelaunchRecoverIntent() = runTest {
        val f = DeleteContractFixture(testScheduler)
        try {
            f.seed(1)
            val tx = f.accept(1)
            assertEquals(DeleteStatus.PendingUndo, f.rows().single().status)
            f.terminate() // No ViewModel timer or snackbar has to run.
            advanceTimeBy(6_000); runCurrent()
            assertEquals(emptyList(), f.api.deleteCalls)
            f.reopen(); runCurrent()
            assertNull(f.processor.undoTarget.value?.transactionId)
            assertEquals(tx.id, f.rows().single().id)
            f.awaitStatus(tx.id, DeleteStatus.InFlight)
            f.awaitDeleteCalls(1)
            assertEquals(listOf(1L), f.api.deleteCalls)
            f.awaitVisible(emptyList())
        } finally { f.close() }
    }

    @Test fun deathDuringInFlightDeleteReconcilesBeforeAnyResend() = runTest {
        val f = DeleteContractFixture(testScheduler)
        try {
            f.seed(1)
            val tx = f.accept(1)
            advanceTimeBy(5_000); runCurrent()
            f.awaitStatus(tx.id, DeleteStatus.InFlight)
            f.awaitDeleteCalls(1)
            assertEquals(listOf(1L), f.api.deleteCalls)
            f.terminate() // The HTTP response is lost with the old app scope.
            f.api.absent(1)
            f.reopen(startRecovery = false)
            assertEquals(DeleteStatus.InFlight, f.rows().single().status)
            f.processor.startRecovery(); runCurrent()
            f.awaitStatus(tx.id, DeleteStatus.AbsentOrInaccessible)
            f.awaitGetCalls(1)
            assertEquals(listOf(1L), f.api.getCalls)
            assertEquals(listOf(1L), f.api.deleteCalls)
            f.awaitVisible(emptyList())
        } finally { f.close() }
    }

    @Test fun threeExpiredPendingTransactionsRecoverAndCompleteIndependently() = runTest {
        val f = DeleteContractFixture(testScheduler)
        try {
            f.seed(1, 2, 3)
            val ids = listOf(f.accept(1).id, f.accept(2).id, f.accept(3).id)
            f.terminate()
            advanceTimeBy(6_000); runCurrent()
            f.reopen(); runCurrent()
            assertNull(f.processor.undoTarget.value?.transactionId)
            f.awaitStatus(ids[0], DeleteStatus.InFlight)
            f.awaitStatus(ids[1], DeleteStatus.InFlight)
            f.awaitStatus(ids[2], DeleteStatus.InFlight)
            f.awaitDeleteCallCount(3)
            assertEquals(setOf(1L, 2L, 3L), f.api.deleteCalls.toSet())
            assertEquals(3, f.api.deleteCalls.size)
            f.replyDelete(3, ApiResult.Success(Unit)); runCurrent()
            f.replyDelete(1, ApiResult.Failure(UserDataError.Forbidden)); runCurrent()
            f.replyDelete(2, ApiResult.Success(Unit)); runCurrent()
            f.awaitStatus(ids[0], DeleteStatus.Failed)
            f.awaitStatus(ids[1], DeleteStatus.Confirmed204)
            f.awaitStatus(ids[2], DeleteStatus.Confirmed204)
            val byId = f.rows().associateBy { it.id }
            assertEquals(DeleteStatus.Failed, byId.getValue(ids[0]).status)
            assertEquals(DeleteStatus.Confirmed204, byId.getValue(ids[1]).status)
            assertEquals(DeleteStatus.Confirmed204, byId.getValue(ids[2]).status)
            f.awaitVisible(listOf(1))
        } finally { f.close() }
    }

    @Test fun v1DatabaseMigratesToV2WithUsersIntactAndEmptyDeleteLedger() = runTest {
        val path = newDeleteTestDatabasePath("people-v1")
        BundledSQLiteDriver().open(path).use { connection ->
            connection.execSQL("CREATE TABLE IF NOT EXISTS users (id INTEGER NOT NULL PRIMARY KEY, name TEXT NOT NULL, email TEXT NOT NULL, firstSeenAt INTEGER NOT NULL, addedAt INTEGER, snapshotPosition INTEGER NOT NULL)")
            connection.execSQL("INSERT INTO users (id, name, email, firstSeenAt, addedAt, snapshotPosition) VALUES (7, 'Old remote', 'r@example.com', 123, NULL, 0)")
            connection.execSQL("INSERT INTO users (id, name, email, firstSeenAt, addedAt, snapshotPosition) VALUES (8, 'Old local', 'l@example.com', 456, 456, 0)")
            connection.execSQL("PRAGMA user_version = 1")
        }
        val f = DeleteContractFixture(testScheduler, path = path)
        try {
            val users = f.blocking("migrated users to be readable") { f.database.userDao().observeUsers().first() }
            assertEquals(listOf(8L, 7L), users.map { it.id })
            assertEquals(123L, users.single { it.id == 7L }.firstSeenAt)
            assertEquals(456L, users.single { it.id == 8L }.addedAt)
            assertEquals(emptyList(), f.rows())
            f.terminate()
            f.reopen(); runCurrent()
            assertEquals(listOf(8L, 7L), f.blocking("migrated users to survive reopen") { f.database.userDao().observeUsers().first().map { it.id } })
        } finally { f.close() }
    }

    @Test fun appLifecycleManagerOnForegroundTriggersRecoveryIdempotently() = runTest {
        val f = DeleteContractFixture(testScheduler)
        try {
            f.seed(1)
            val tx = f.accept(1)
            f.terminate()
            f.reopen(startRecovery = false)
            val lifecycleManager = com.example.people.lifecycle.AppLifecycleManager(
                processor = f.processor,
                database = f.database,
                httpClient = io.ktor.client.HttpClient(),
            )
            lifecycleManager.onForeground()
            runCurrent()
            assertEquals(emptyList(), f.api.deleteCalls)
            advanceTimeBy(5_000); runCurrent()
            f.awaitStatus(tx.id, DeleteStatus.InFlight)
            f.awaitDeleteCalls(1)
            assertEquals(listOf(1L), f.api.deleteCalls)

            // Idempotent second call causes no duplicate execution
            lifecycleManager.onForeground()
            runCurrent()
            assertEquals(listOf(1L), f.api.deleteCalls)
        } finally { f.close() }
    }
}
