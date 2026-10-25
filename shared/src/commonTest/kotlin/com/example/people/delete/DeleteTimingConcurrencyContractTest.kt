package com.example.people.delete

import com.example.people.data.remote.ApiResult
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** RED CONTRACT: timers belong to the app processor, never a snackbar or ViewModel. */
@OptIn(ExperimentalCoroutinesApi::class)
class DeleteTimingConcurrencyContractTest {
    @Test fun aAndBHaveIndependentFiveSecondDeadlines() = runTest {
        val f = DeleteContractFixture(testScheduler)
        try {
            f.seed(1, 2)
            f.recordVisible(); runCurrent()
            val a = f.accept(1)
            f.awaitVisible(listOf(2))
            f.awaitVisibleEmissions(listOf(1, 2), listOf(2))
            advanceTimeBy(2_000); runCurrent()
            val b = f.accept(2)
            f.awaitVisible(emptyList())
            assertEquals(2_000L, b.deadlineAtEpochMillis - a.deadlineAtEpochMillis)
            advanceTimeBy(2_999); runCurrent()
            assertEquals(emptyList(), f.api.deleteCalls)
            advanceTimeBy(1); runCurrent()
            try {
                f.awaitDeleteCalls(1)
            } catch (failure: AssertionError) {
                val ledger = runCatching { f.rows() }
                    .fold(onSuccess = { it.map { row -> "${row.userId}:${row.status}@${row.deadlineAtEpochMillis}" } },
                        onFailure = { listOf("ledger read failed: ${it.message}") })
                throw AssertionError(
                    "A did not reach DELETE at its deadline: virtual=${testScheduler.currentTime} ms, " +
                        "wall=${f.clock.wallMillis()}, monotonic=${f.clock.monotonicMillis()}, " +
                        "A=${a.deadlineAtEpochMillis}, B=${b.deadlineAtEpochMillis}, " +
                        "ledger=$ledger, undo=${f.processor.undoTarget.value}, " +
                        "DELETE calls=${f.api.deleteCalls}; ${failure.message}"
                )
            }
            assertEquals(listOf(1L), f.api.deleteCalls)
            advanceTimeBy(1_999); runCurrent()
            assertEquals(listOf(1L), f.api.deleteCalls)
            advanceTimeBy(1); runCurrent()
            f.awaitDeleteCalls(1, 2)
            assertEquals(listOf(1L, 2L), f.api.deleteCalls)
            f.awaitVisibleEmissions(listOf(1, 2), listOf(2), emptyList())
            assertEmissionOrder(f.visibleEmissions, listOf(1, 2), listOf(2), emptyList())
        } finally { f.close() }
    }

    @Test fun undoAt4999SucceedsAndAt5000Or5001Fails() = runTest {
        for ((at, expectedUndo) in listOf(4_999L to true, 5_000L to false, 5_001L to false)) {
            val f = DeleteContractFixture(testScheduler)
            try {
                f.seed(1)
                val tx = f.accept(1)
                advanceTimeBy(at); runCurrent()
                assertEquals(expectedUndo, f.undo(tx.id), "Undo result at $at ms")
                runCurrent()
                if (expectedUndo) {
                    advanceTimeBy(1); runCurrent()
                } else {
                    f.awaitDeleteCalls(1)
                }
                assertEquals(if (expectedUndo) emptyList() else listOf(1L), f.api.deleteCalls)
                if (expectedUndo) f.awaitVisible(listOf(1)) else f.awaitVisible(emptyList())
            } finally { f.close() }
        }
    }

    @Test fun wallClockAdjustmentsCannotCauseEarlyDeleteInLiveProcess() = runTest {
        val f = DeleteContractFixture(testScheduler)
        try {
            f.seed(1)
            val tx = f.accept(1)
            f.clock.shiftWallBy(86_400_000)
            advanceTimeBy(4_999); runCurrent()
            assertEquals(emptyList(), f.api.deleteCalls)
            assertEquals(tx.id, f.processor.undoTarget.value?.transactionId)
            f.clock.shiftWallBy(-172_800_000)
            advanceTimeBy(1); runCurrent()
            f.awaitDeleteCalls(1)
            assertEquals(listOf(1L), f.api.deleteCalls)
        } finally { f.close() }
    }

    @Test fun snackbarRecreationNeverResetsTheTransactionDeadline() = runTest {
        val f = DeleteContractFixture(testScheduler)
        try {
            f.seed(1)
            val tx = f.accept(1)
            advanceTimeBy(3_000); runCurrent()
            // A snackbar can leave and re-enter composition; it only re-collects state.
            repeat(2) { assertEquals(tx.id, f.blocking("current Undo to remain transaction ${tx.id} after snackbar recreation") { f.processor.undoTarget.first { it?.transactionId == tx.id }?.transactionId }) }
            assertEquals(tx.deadlineAtEpochMillis, f.rows().single().deadlineAtEpochMillis)
            advanceTimeBy(2_000); runCurrent()
            f.awaitDeleteCalls(1)
            assertEquals(listOf(1L), f.api.deleteCalls)
        } finally { f.close() }
    }

    @Test fun threeDeletesMayBeInFlightAndCompleteOutOfOrderByTransactionId() = runTest {
        val f = DeleteContractFixture(testScheduler)
        try {
            f.seed(1, 2, 3)
            val a = f.accept(1)
            val b = f.accept(2)
            val c = f.accept(3)
            advanceTimeBy(5_000); runCurrent()
            f.awaitDeleteCallCount(3)
            assertEquals(setOf(1L, 2L, 3L), f.api.deleteCalls.toSet())
            assertEquals(3, f.api.deleteCalls.size)
            f.replyDelete(2, ApiResult.Success(Unit)); runCurrent()
            f.awaitStatus(b.id, DeleteStatus.Confirmed204)
            f.awaitStatus(a.id, DeleteStatus.InFlight)
            f.awaitStatus(c.id, DeleteStatus.InFlight)
            f.replyDelete(3, ApiResult.Success(Unit)); runCurrent()
            f.awaitStatus(c.id, DeleteStatus.Confirmed204)
            f.awaitStatus(a.id, DeleteStatus.InFlight)
            f.replyDelete(1, ApiResult.Success(Unit)); runCurrent()
            listOf(a, b, c).forEach { f.awaitStatus(it.id, DeleteStatus.Confirmed204) }
            assertEquals(setOf(a.id, b.id, c.id), f.rows().filter { it.status == DeleteStatus.Confirmed204 }.map { it.id }.toSet())
            f.awaitVisible(emptyList())
            assertEquals(3, f.api.deleteCalls.size)
        } finally { f.close() }
    }

    @Test fun threeStaggeredDeadlinesDoNotMoveWhenLaterUsersAreAccepted() = runTest {
        val f = DeleteContractFixture(testScheduler)
        try {
            f.seed(1, 2, 3)
            val a = f.accept(1)
            advanceTimeBy(1_000); runCurrent()
            val b = f.accept(2)
            advanceTimeBy(1_000); runCurrent()
            val c = f.accept(3)
            assertEquals(listOf(1_000L, 1_000L), listOf(
                b.deadlineAtEpochMillis - a.deadlineAtEpochMillis,
                c.deadlineAtEpochMillis - b.deadlineAtEpochMillis,
            ))
            advanceTimeBy(2_999); runCurrent()
            assertEquals(emptyList(), f.api.deleteCalls)
            advanceTimeBy(1); runCurrent()
            f.awaitDeleteCalls(1)
            advanceTimeBy(999); runCurrent()
            assertEquals(listOf(1L), f.api.deleteCalls)
            advanceTimeBy(1); runCurrent()
            f.awaitDeleteCalls(1, 2)
            advanceTimeBy(999); runCurrent()
            assertEquals(listOf(1L, 2L), f.api.deleteCalls)
            advanceTimeBy(1); runCurrent()
            f.awaitDeleteCalls(1, 2, 3)
        } finally { f.close() }
    }
}
