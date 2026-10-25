package com.example.people.data.repository

import com.example.people.data.local.UserCache
import com.example.people.domain.model.NewUser
import com.example.people.data.local.UserEntity
import com.example.people.data.local.reconcileSnapshot
import com.example.people.data.remote.ApiResult
import com.example.people.data.remote.UserDto
import com.example.people.data.remote.UsersRemoteDataSource
import com.example.people.domain.repository.RefreshResult
import com.example.people.domain.repository.UserDataError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

@OptIn(ExperimentalCoroutinesApi::class)
class DefaultUserRepositoryTest {
    @Test
    fun observationReturnsCacheWithoutAnyNetworkRequest() = runTest {
        val cache = FakeCache(listOf(row(1)))
        val remote = FakeRemote(ApiResult.Failure(UserDataError.Network))
        val repository = repository(remote, cache)
        assertEquals(listOf(1L), repository.observeUsers().first().map { it.id })
        assertEquals(0, remote.fetchCalls)
        assertEquals(0, cache.replacements)
    }

    @Test
    fun refreshReplacesRemoteSnapshotPreservingFirstSeen() = runTest {
        val cache = FakeCache(listOf(row(1), row(2).copy(firstSeenAt = 10)))
        val remote = FakeRemote(ApiResult.Success(listOf(dto(2).copy(name = "Updated"), dto(3))))
        val repository = repository(remote, cache)
        assertEquals(RefreshResult.Success, repository.refresh())
        val users = repository.observeUsers().first()
        assertEquals(listOf(2L, 3L), users.map { it.id })
        assertEquals("Updated", users[0].name)
        assertEquals(10L, users[0].firstSeenAt)
        assertEquals(1_000L, users[1].firstSeenAt)
        assertEquals(1, cache.replacements)
    }

    @Test
    fun emptySnapshotRemovesOnlyRemoteRows() = runTest {
        val cache = FakeCache(listOf(row(1), row(9).copy(addedAt = 5)))
        val remote = FakeRemote(ApiResult.Success(emptyList()))
        remote.checks[9] = ApiResult.Success(dto(9))
        val repository = repository(remote, cache)
        assertEquals(RefreshResult.Success, repository.refresh())
        assertEquals(listOf(9L), repository.observeUsers().first().map { it.id })
    }

    @Test
    fun createdUsersRemainPinnedAndDeduplicateAgainstSnapshot() = runTest {
        val local = row(9).copy(addedAt = 20)
        val cache = FakeCache(listOf(local, row(8).copy(addedAt = 10)))
        val remote = FakeRemote(ApiResult.Success(listOf(dto(1), dto(9), dto(9), dto(1))))
        remote.checks[8] = ApiResult.Success(dto(8).copy(name = "Verified"))
        val repository = repository(remote, cache)
        assertEquals(RefreshResult.Success, repository.refresh())
        val users = repository.observeUsers().first()
        assertEquals(listOf(9L, 8L, 1L), users.map { it.id })
        assertEquals(20L, users[0].addedAt)
        assertEquals(local.firstSeenAt, users[0].firstSeenAt)
        assertEquals("Verified", users[1].name)
        assertEquals(listOf(8L), remote.checkedIds) // No redundant GET for the overlapping local ID.
    }

    @Test
    fun duplicateIdsKeepFirstSnapshotOccurrence() = runTest {
        val cache = FakeCache()
        val remote = FakeRemote(ApiResult.Success(listOf(dto(1), dto(1).copy(name = "Duplicate"))))
        val repository = repository(remote, cache)
        assertEquals(RefreshResult.Success, repository.refresh())
        assertEquals(listOf("User 1"), repository.observeUsers().first().map { it.name })
    }

    @Test
    fun failedRefreshPreservesEntireCache() = runTest {
        for (error in listOf(
            UserDataError.Network, UserDataError.Timeout, UserDataError.Unauthorized,
            UserDataError.Forbidden, UserDataError.RateLimited, UserDataError.Server(500),
            UserDataError.InvalidPagination, UserDataError.InvalidResponse,
        )) {
            val original = listOf(row(1), row(9).copy(addedAt = 10))
            val cache = FakeCache(original)
            val repository = repository(FakeRemote(ApiResult.Failure(error)), cache)
            assertEquals(RefreshResult.Failure(error), repository.refresh())
            assertEquals(original.toSet(), cache.rows.value.toSet())
            assertEquals(0, cache.replacements)
        }
    }

    @Test
    fun conclusive404RemovesTrackedCreatedUser() = runTest {
        val cache = FakeCache(listOf(row(9).copy(addedAt = 10)))
        val remote = FakeRemote(ApiResult.Success(listOf(dto(1))))
        remote.checks[9] = ApiResult.Failure(UserDataError.NotFound)
        val repository = repository(remote, cache)
        assertEquals(RefreshResult.Success, repository.refresh())
        assertEquals(listOf(1L), repository.observeUsers().first().map { it.id })
    }

    @Test
    fun inconclusiveCreatedUserChecksDoNotRemoveAnythingOrCommitPartialResults() = runTest {
        for (error in listOf(
            UserDataError.Network, UserDataError.Unauthorized, UserDataError.Forbidden,
            UserDataError.Server(503), UserDataError.RateLimited,
        )) {
            val original = listOf(row(1), row(8).copy(addedAt = 8), row(9).copy(addedAt = 9))
            val cache = FakeCache(original)
            val remote = FakeRemote(ApiResult.Success(listOf(dto(2))))
            remote.checks[8] = ApiResult.Failure(UserDataError.NotFound)
            remote.checks[9] = ApiResult.Failure(error)
            assertEquals(RefreshResult.Failure(error), repository(remote, cache).refresh())
            assertEquals(original.toSet(), cache.rows.value.toSet())
            assertEquals(0, cache.replacements)
        }
    }

    @Test
    fun cacheFailureIsTypedAndDoesNotReportSuccessfulRefresh() = runTest {
        val original = listOf(row(1))
        val cache = FakeCache(original).apply { failWrite = true }
        val remote = FakeRemote(ApiResult.Success(listOf(dto(2))))
        assertEquals(RefreshResult.Failure(UserDataError.Cache), repository(remote, cache).refresh())
        assertEquals(original, cache.rows.value)
    }

    @Test
    fun cancellationDoesNotModifyCache() = runTest {
        val original = listOf(row(1))
        val cache = FakeCache(original)
        val remote = FakeRemote(ApiResult.Success(emptyList())).apply {
            beforeFetch = { throw CancellationException("Cancelled") }
        }
        assertFailsWith<CancellationException> { repository(remote, cache).refresh() }
        assertEquals(original, cache.rows.value)
        assertEquals(0, cache.replacements)
    }

    @Test
    fun concurrentRefreshesAreSerializedSoOlderSnapshotCannotWin() = runTest {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val cache = FakeCache()
        val remote = FakeRemote(ApiResult.Success(listOf(dto(1)))).apply {
            beforeFetch = {
                if (fetchCalls == 1) {
                    started.complete(Unit)
                    release.await()
                }
            }
        }
        val repository = repository(remote, cache)
        val first = async { repository.refresh() }
        started.await()
        remote.snapshot = ApiResult.Success(listOf(dto(2)))
        val second = async { repository.refresh() }
        runCurrent()
        assertEquals(1, remote.fetchCalls)
        release.complete(Unit)
        assertEquals(RefreshResult.Success, first.await())
        assertEquals(RefreshResult.Success, second.await())
        assertEquals(2, remote.fetchCalls)
        assertEquals(listOf(2L), repository.observeUsers().first().map { it.id })
    }

    @Test
    fun mixedLocallyCreatedUserVerificationUpdatesVerifiedAndRemovesMissing() = runTest {
        val cache = FakeCache(listOf(row(8).copy(addedAt = 8), row(9).copy(addedAt = 9)))
        val remote = FakeRemote(ApiResult.Success(listOf(dto(1))))
        remote.checks[8] = ApiResult.Success(dto(8).copy(name = "Verified 8"))
        remote.checks[9] = ApiResult.Failure(UserDataError.NotFound)
        val repository = repository(remote, cache)
        assertEquals(RefreshResult.Success, repository.refresh())
        val users = repository.observeUsers().first()
        assertEquals(listOf(8L, 1L), users.map { it.id })
        assertEquals("Verified 8", users[0].name)
    }

    private fun repository(remote: FakeRemote, cache: FakeCache) =
        DefaultUserRepository(remote, cache, nowMillis = { 1_000 })

    private fun row(id: Long) = UserEntity(id, "User $id", "$id@example.com", firstSeenAt = 100)
    private fun dto(id: Long) = UserDto(id, "User $id", "$id@example.com")

    private class FakeRemote(var snapshot: ApiResult<List<UserDto>>) : UsersRemoteDataSource {
        override suspend fun deleteUser(id: Long): ApiResult<Unit> = error("Unused")
        var fetchCalls = 0
        var beforeFetch: suspend () -> Unit = {}
        val checks = mutableMapOf<Long, ApiResult<UserDto>>()
        val checkedIds = mutableListOf<Long>()

        override suspend fun fetchLastPage(): ApiResult<List<UserDto>> {
            fetchCalls++
            val response = snapshot
            beforeFetch()
            return response
        }

        override suspend fun createUser(user: NewUser): ApiResult<UserDto> = error("Unused")

        override suspend fun findUser(id: Long): ApiResult<UserDto> {
            checkedIds += id
            return checks.getValue(id)
        }
    }

    private class FakeCache(initial: List<UserEntity> = emptyList()) : UserCache {
        val rows = MutableStateFlow(initial)
        var replacements = 0
        var failWrite = false

        override fun observeUsers(): Flow<List<UserEntity>> = rows
        override fun observeVisibleUsers(): Flow<List<UserEntity>> = rows
        override suspend fun locallyCreatedUsers(): List<UserEntity> = rows.value.filter { it.addedAt != null }

        override suspend fun saveCreatedUser(user: UserEntity): Unit = error("Unused")

        override suspend fun replaceSnapshot(
            snapshot: List<UserEntity>,
            verifiedCreated: List<UserEntity>,
            missingCreatedIds: List<Long>,
        ) {
            if (failWrite) error("Storage unavailable")
            val reconciled = reconcileSnapshot(rows.value, snapshot, verifiedCreated, missingCreatedIds)
            rows.value = reconciled.sortedWith(
                compareBy<UserEntity> { it.addedAt == null }
                    .thenByDescending { it.addedAt }
                    .thenBy { it.snapshotPosition }
                    .thenBy { it.id }
            )
            replacements++
        }
    }
}
