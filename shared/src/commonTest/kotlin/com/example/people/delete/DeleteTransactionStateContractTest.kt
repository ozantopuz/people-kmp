package com.example.people.delete

import androidx.room.execSQL
import androidx.room.useWriterConnection
import com.example.people.data.delete.DeleteAcceptanceResult
import com.example.people.domain.repository.UserDataError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** RED CONTRACT: transaction acceptance and Undo must be atomic Room writes. */
@OptIn(ExperimentalCoroutinesApi::class)
class DeleteTransactionStateContractTest {
    @Test fun openingBDialogDoesNotRevokeAUndoButAcceptingBDoes() = runTest {
        val f = DeleteContractFixture(testScheduler)
        try {
            f.seed(1, 2)
            val a = f.accept(1)
            runCurrent()
            val deadline = a.deadlineAtEpochMillis
            f.processor.selectForConfirmation(2)
            assertEquals(a.id, f.processor.undoTarget.value?.transactionId)
            assertEquals(deadline, f.rows().single().deadlineAtEpochMillis)
            val b = f.accept(2)
            assertNotEquals(a.id, b.id)
            assertEquals(b.id, f.processor.undoTarget.value?.transactionId)
            assertEquals(deadline, f.rows().first { it.id == a.id }.deadlineAtEpochMillis)
            assertFalse(f.undo(a.id))
            assertTrue(f.undo(b.id))
            f.awaitVisible(listOf(2))
            assertEquals(emptyList(), f.api.deleteCalls)
        } finally { f.close() }
    }

    @Test fun undoARacesWithAcceptBAndHasOneSerialOutcomeWithNoLostIntent() = runTest {
        val f = DeleteContractFixture(testScheduler)
        try {
            f.seed(1, 2)
            val a = f.accept(1)
            val undo = async(Dispatchers.Default) { f.processor.undo(a.id) }
            val accept = async(Dispatchers.Default) { f.processor.accept(2) }
            try {
                val b = (f.blocking("concurrent acceptance of B to complete") { accept.await() } as DeleteAcceptanceResult.Accepted).transaction
                val undoWon = f.blocking("concurrent Undo of A to complete") { undo.await() }
                val aRow = f.rows().single { it.id == a.id }
                if (undoWon) {
                    assertEquals(DeleteStatus.Undone, aRow.status)
                    f.awaitVisible(listOf(1))
                    assertEquals(1, f.rows().count { it.status != DeleteStatus.Undone })
                } else {
                    assertEquals(DeleteStatus.PendingUndo, aRow.status)
                    assertFalse(aRow.undoable)
                    f.awaitVisible(emptyList())
                    assertEquals(2, f.rows().count { it.status != DeleteStatus.Undone })
                }
                assertEquals(b.id, f.processor.undoTarget.value?.transactionId)
            } finally {
                f.blocking("Undo and acceptance race jobs to cancel and join") { undo.cancelAndJoin(); accept.cancelAndJoin() }
            }
        } finally { f.close() }
    }

    @Test fun acceptingBFirstMakesLateUndoOfAInvalid() = runTest {
        val f = DeleteContractFixture(testScheduler)
        try {
            f.seed(1, 2)
            val a = f.accept(1)
            val b = f.accept(2)
            assertFalse(f.undo(a.id))
            assertEquals(b.id, f.processor.undoTarget.value?.transactionId)
            assertEquals(DeleteStatus.PendingUndo, f.rows().single { it.id == a.id }.status)
            assertFalse(f.rows().single { it.id == a.id }.undoable)
            f.awaitVisible(emptyList())
        } finally { f.close() }
    }

    @Test fun concurrentBAcceptanceAndCAcceptanceLeaveExactlyTheLastCommittedUndo() = runTest {
        val f = DeleteContractFixture(testScheduler)
        try {
            f.seed(1, 2, 3)
            val a = f.accept(1)
            val b = async(Dispatchers.Default) { f.processor.accept(2) }
            val c = async(Dispatchers.Default) { f.processor.accept(3) }
            try {
                val accepted = f.blocking("both concurrent acceptances to complete") { listOf(b.await(), c.await()) }
                    .filterIsInstance<DeleteAcceptanceResult.Accepted>()
                assertEquals(2, accepted.size)
                val rows = f.rows()
                assertEquals(setOf(1L, 2L, 3L), rows.map { it.userId }.toSet())
                assertFalse(rows.single { it.id == a.id }.undoable)
                val latest = rows.single { it.undoable }
                assertTrue(latest.id in accepted.map { it.transaction.id })
                assertEquals(latest.id, f.processor.undoTarget.value?.transactionId)
                assertEquals(a.deadlineAtEpochMillis, rows.single { it.id == a.id }.deadlineAtEpochMillis)
                assertFalse(f.undo(a.id))
            } finally {
                f.blocking("both concurrent acceptance jobs to cancel and join") { b.cancelAndJoin(); c.cancelAndJoin() }
            }
        } finally { f.close() }
    }

    @Test fun undoingABeforeAcceptingBRestoresOnlyA() = runTest {
        val f = DeleteContractFixture(testScheduler)
        try {
            f.seed(1, 2)
            val a = f.accept(1)
            assertTrue(f.undo(a.id))
            val b = f.accept(2)
            assertEquals(DeleteStatus.Undone, f.rows().single { it.id == a.id }.status)
            assertEquals(b.id, f.processor.undoTarget.value?.transactionId)
            f.awaitVisible(listOf(1))
            assertEquals(emptyList(), f.api.deleteCalls)
        } finally { f.close() }
    }

    @Test fun duplicateConfirmationsCreateOneIntentAndOnlyOneClaim() = runTest {
        val f = DeleteContractFixture(testScheduler)
        try {
            f.seed(1)
            val first = async(Dispatchers.Default) { f.processor.accept(1) }
            val second = async(Dispatchers.Default) { f.processor.accept(1) }
            try {
                val accepted = f.blocking("duplicate acceptance race to complete") { listOf(first.await(), second.await()) }
                    .filterIsInstance<DeleteAcceptanceResult.Accepted>()
                val transactionId = f.rows().single().id
                assertEquals(setOf(transactionId), accepted.map { it.transaction.id }.toSet())
                assertEquals(1, f.rows().count { it.userId == 1L && it.status == DeleteStatus.PendingUndo })
                advanceTimeBy(5_000)
                runCurrent()
                f.processor.startRecovery() // A second claim attempt cannot issue a second HTTP call.
                f.startCompetingProcessor() // A concurrent processor must not steal the Room claim.
                runCurrent()
                f.awaitDeleteCalls(1)
                assertEquals(listOf(1L), f.api.deleteCalls)
                f.awaitStatus(transactionId, DeleteStatus.InFlight)
            } finally {
                f.blocking("duplicate acceptance jobs to cancel and join") { first.cancelAndJoin(); second.cancelAndJoin() }
            }
        } finally { f.close() }
    }

    @Test fun missingTokenCannotRecordADeletionOrHideTheUser() = runTest {
        val f = DeleteContractFixture(testScheduler, writesEnabled = false)
        try {
            f.seed(1)
            val rejected = f.blocking("missing-token acceptance to reject") { f.processor.accept(1) } as DeleteAcceptanceResult.Rejected
            assertEquals(UserDataError.MissingToken, rejected.reason)
            assertEquals(emptyList(), f.rows())
            assertNull(f.processor.undoTarget.value?.transactionId)
            f.awaitVisible(listOf(1))
            assertEquals(emptyList(), f.api.deleteCalls)
        } finally { f.close() }
    }

    @Test fun rowRemovedByRefreshBeforeAcceptanceCannotBecomeADeleteIntent() = runTest {
        val f = DeleteContractFixture(testScheduler)
        try {
            f.seed(1)
            f.processor.selectForConfirmation(1)
            f.api.snapshot = emptyList()
            f.refresh()
            f.awaitVisible(emptyList())
            assertTrue(f.blocking("missing-token acceptance to reject") { f.processor.accept(1) } is DeleteAcceptanceResult.Rejected)
            assertEquals(emptyList(), f.rows())
            assertEquals(emptyList(), f.api.deleteCalls)
        } finally { f.close() }
    }

    @Test fun failedBAcceptanceRollsBackUndoRevocationOfA() = runTest {
        val f = DeleteContractFixture(testScheduler)
        try {
            f.seed(1, 2)
            val a = f.accept(1)
            // Real SQLite failure inside the same transaction that must revoke A and insert B.
            f.blocking("failure trigger to be installed in Room") { f.database.useWriterConnection { connection ->
                connection.execSQL("CREATE TRIGGER reject_b BEFORE INSERT ON delete_transactions WHEN NEW.userId = 2 BEGIN SELECT RAISE(ABORT, 'test write failure'); END")
            } }
            val result = f.blocking("failed acceptance of B to reject") { f.processor.accept(2) }
            assertTrue(result is DeleteAcceptanceResult.Rejected)
            assertEquals(listOf(a.id), f.rows().map { it.id })
            assertEquals(a.id, f.processor.undoTarget.value?.transactionId)
            assertTrue(f.undo(a.id))
            f.awaitVisible(listOf(1, 2))
            assertEquals(emptyList(), f.api.deleteCalls)
        } finally { f.close() }
    }
}