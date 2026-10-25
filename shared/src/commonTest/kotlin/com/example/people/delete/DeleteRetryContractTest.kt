package com.example.people.delete

import com.example.people.data.remote.ApiResult
import com.example.people.domain.repository.UserDataError
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class DeleteRetryContractTest {
    @Test fun rateLimitPersistsServerRetryTimeAndDoesNotRetryEarly() = runTest {
        val f = DeleteContractFixture(testScheduler)
        try {
            f.seed(1)
            val transaction = f.accept(1)
            val serverRetryAt = f.clock.wallMillis() + 35_000
            f.replyDelete(1, ApiResult.Failure(
                UserDataError.RateLimited,
                retryAfterEpochMs = serverRetryAt,
            ))
            advanceTimeBy(5_000); runCurrent()
            val row = f.awaitStatus(transaction.id, DeleteStatus.Retryable)
            f.awaitDeleteCalls(1)
            assertEquals(serverRetryAt, row.nextAttemptAtEpochMs)
            assertEquals(1, row.attemptCount)
            advanceTimeBy(29_999); runCurrent()
            assertEquals(listOf(1L), f.api.deleteCalls)
            advanceTimeBy(1); runCurrent()
            f.awaitDeleteCalls(1, 1)
            assertEquals(listOf(1L, 1L), f.api.deleteCalls)
            assertTrue(f.rows().single().attemptCount >= 2)
        } finally { f.close() }
    }

    @Test fun retryDeadlineSurvivesDatabaseReopen() = runTest {
        val f = DeleteContractFixture(testScheduler)
        try {
            f.seed(1)
            val transaction = f.accept(1)
            f.replyDelete(1, ApiResult.Failure(UserDataError.RateLimited))
            advanceTimeBy(5_000); runCurrent()
            val retryAt = requireNotNull(f.awaitStatus(transaction.id, DeleteStatus.Retryable).nextAttemptAtEpochMs)
            f.awaitDeleteCalls(1)
            f.terminate()
            f.reopen(); runCurrent()
            advanceTimeBy(retryAt - f.clock.wallMillis() - 1); runCurrent()
            assertEquals(listOf(1L), f.api.deleteCalls)
            advanceTimeBy(1); runCurrent()
            f.awaitDeleteCalls(1, 1)
            assertEquals(listOf(1L, 1L), f.api.deleteCalls)
        } finally { f.close() }
    }
}
