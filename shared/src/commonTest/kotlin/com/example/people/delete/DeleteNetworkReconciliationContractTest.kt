package com.example.people.delete

import com.example.people.data.remote.ApiResult
import com.example.people.domain.repository.UserDataError
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** A DELETE 204 or 404 resolves the intent; uncertain outcomes still require reconciliation. */
@OptIn(ExperimentalCoroutinesApi::class)
class DeleteNetworkReconciliationContractTest {
    @Test fun exact204FinalizesOneTransactionWithoutRevivingItsRow() = runTest {
        val f = DeleteContractFixture(testScheduler)
        try {
            f.seed(1, 2)
            f.recordVisible(); runCurrent()
            val tx = f.accept(1)
            f.awaitVisible(listOf(2))
            f.awaitVisibleEmissions(listOf(1, 2), listOf(2))
            advanceTimeBy(5_000); runCurrent()
            f.replyDelete(1, ApiResult.Success(Unit)); runCurrent()
            f.awaitStatus(tx.id, DeleteStatus.Confirmed204)
            f.awaitDeleteCalls(1)
            assertEquals(listOf(1L), f.api.deleteCalls)
            assertEquals(emptyList(), f.api.getCalls)
            f.awaitVisible(listOf(2))
            f.awaitVisibleEmissions(listOf(1, 2), listOf(2))
            assertEmissionOrder(f.visibleEmissions, listOf(1, 2), listOf(2))
        } finally { f.close() }
    }

    @Test fun delete404ConfirmsAbsenceRemovesCacheAndDoesNotReportFailure() = runTest {
        val f = DeleteContractFixture(testScheduler)
        try {
            f.seed(1)
            f.markSeededUserAsCreated(1)
            val tx = f.accept(1)
            f.replyDelete(1, ApiResult.Failure(UserDataError.NotFound))
            advanceTimeBy(5_000); runCurrent()
            f.awaitDeleteCalls(1)
            assertEquals(listOf(1L), f.api.deleteCalls)
            assertEquals(emptyList(), f.api.getCalls)
            f.awaitStatus(tx.id, DeleteStatus.ConfirmedAbsent)
            assertNotEquals(DeleteStatus.Confirmed204, f.rows().single().status)
            f.awaitVisible(emptyList())
            assertEquals(emptyList(), f.blocking("confirmed absence to remove cached user") {
                f.database.userDao().observeUsers().first().map { it.id }
            })
            assertEquals(null, withTimeoutOrNull(10) { f.processor.failedDeletes.first() })
            f.api.snapshot = listOf(dto(1))
            f.refresh()
            f.awaitVisible(emptyList())
        } finally { f.close() }
    }

    @Test fun delete404DoesNotNeedAGetEvenIfAnotherReadWouldFindTheUser() = runTest {
        val f = DeleteContractFixture(testScheduler)
        try {
            f.seed(1)
            f.recordVisible(); runCurrent()
            val tx = f.accept(1)
            f.awaitVisible(emptyList())
            f.awaitVisibleEmissions(listOf(1), emptyList())
            f.api.present(1)
            f.replyDelete(1, ApiResult.Failure(UserDataError.NotFound))
            advanceTimeBy(5_000); runCurrent()
            f.awaitDeleteCalls(1)
            assertEquals(emptyList(), f.api.getCalls)
            f.awaitStatus(tx.id, DeleteStatus.ConfirmedAbsent)
            assertNotEquals(DeleteStatus.Confirmed204, f.rows().single().status)
            f.awaitVisible(emptyList())
            f.awaitVisibleEmissions(listOf(1), emptyList())
            assertEmissionOrder(f.visibleEmissions, listOf(1), emptyList())
        } finally { f.close() }
    }

    @Test fun lostDeleteResponseUsesGetAndNeverReportsConfirmed204() = runTest {
        for (found in listOf(false, true)) {
            val f = DeleteContractFixture(testScheduler)
            try {
                f.seed(1)
                val tx = f.accept(1)
                if (found) f.api.present(1) else f.api.absent(1)
                f.replyDelete(1, ApiResult.Failure(UserDataError.Timeout))
                advanceTimeBy(5_000); runCurrent()
                f.awaitDeleteCalls(1)
                f.awaitGetCalls(1)
                assertEquals(listOf(1L), f.api.deleteCalls)
                assertEquals(listOf(1L), f.api.getCalls)
                val status = f.awaitStatus(tx.id, if (found) DeleteStatus.Retryable else DeleteStatus.AbsentOrInaccessible).status
                assertNotEquals(DeleteStatus.Confirmed204, status)
                if (found) {
                    assertEquals(DeleteStatus.Retryable, status)
                    f.awaitVisible(emptyList())
                } else {
                    assertEquals(DeleteStatus.AbsentOrInaccessible, status)
                    f.awaitVisible(emptyList())
                }
            } finally { f.close() }
        }
    }

    @Test fun authFailuresRestoreRowsAndTransientFailuresRetainDurableIntent() = runTest {
        for ((error, expected) in listOf(
            UserDataError.Unauthorized to DeleteStatus.Failed,
            UserDataError.Forbidden to DeleteStatus.Failed,
            UserDataError.Network to DeleteStatus.Retryable,
            UserDataError.Server(503) to DeleteStatus.Retryable,
        )) {
            val f = DeleteContractFixture(testScheduler)
            try {
                f.seed(1)
                val tx = f.accept(1)
                f.api.getReplies[1] = ApiResult.Failure(UserDataError.Network) // Inconclusive reconciliation.
                f.replyDelete(1, ApiResult.Failure(error))
                advanceTimeBy(5_000); runCurrent()
                f.awaitStatus(tx.id, expected)
                f.awaitDeleteCalls(1)
                if (expected == DeleteStatus.Failed) f.awaitVisible(listOf(1)) else f.awaitVisible(emptyList())
                assertEquals(listOf(1L), f.api.deleteCalls)
                assertFalse(f.rows().single().status == DeleteStatus.Confirmed204)
            } finally { f.close() }
        }
    }

    @Test fun aFailureCannotClearBUndoAndOfflineExpiryCannotDuplicateRequests() = runTest {
        val f = DeleteContractFixture(testScheduler)
        try {
            f.seed(1, 2)
            val a = f.accept(1)
            advanceTimeBy(2_000); runCurrent()
            val b = f.accept(2)
            f.api.getReplies[1] = ApiResult.Failure(UserDataError.Network)
            f.replyDelete(1, ApiResult.Failure(UserDataError.Network))
            advanceTimeBy(3_000); runCurrent()
            f.awaitStatus(a.id, DeleteStatus.Retryable)
            f.awaitDeleteCalls(1)
            assertEquals(b.id, f.processor.undoTarget.value?.transactionId)
            assertTrue(f.undo(b.id))
            f.awaitVisible(listOf(2))
            f.processor.startRecovery(); runCurrent()
            assertEquals(1, f.api.deleteCalls.count { it == 1L })
            assertEquals(0, f.api.deleteCalls.count { it == 2L })
        } finally { f.close() }
    }

    @Test fun undoCannotRecreateAResourceAnotherClientAlreadyDeleted() = runTest {
        val f = DeleteContractFixture(testScheduler)
        try {
            f.seed(1)
            f.markSeededUserAsCreated(1)
            val tx = f.accept(1)
            assertTrue(f.undo(tx.id))
            f.api.absent(1)
            f.api.snapshot = emptyList()
            f.refresh()
            f.awaitVisible(emptyList())
            assertEquals(emptyList(), f.api.deleteCalls)
            assertEquals(listOf(1L), f.api.getCalls)
        } finally { f.close() }
    }
}
