package com.example.people.data.repository

import com.example.people.data.local.UserDao
import com.example.people.data.local.UserEntity
import com.example.people.data.remote.ApiResult
import com.example.people.data.remote.UserDto
import com.example.people.data.remote.UsersRemoteDataSource
import com.example.people.domain.model.Gender
import com.example.people.domain.model.NewUser
import com.example.people.domain.repository.CreateUserResult
import com.example.people.domain.repository.RefreshResult
import com.example.people.domain.repository.UserDataError
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/** Exercises the real DAO save/reconciliation methods with in-memory query primitives.
 * Native Room/SQLite transaction and disk behavior still require manual platform checks. */
class CreateUserRepositoryTest {
    private val draft = NewUser("Ada", "ada@example.com", Gender.Female)
    private val dto = UserDto(42, "Ada", "ada@example.com")

    @Test
    fun confirmedCreationPersistsProvenanceEmitsFirstAndSurvivesNewRepository() = runTest {
        val cache = MemoryDao(listOf(UserEntity(1, "Remote", "r@example.com", 100), UserEntity(9, "Local", "l@example.com", 200, addedAt = 200)))
        val remote = Remote(dto)
        val repository = DefaultUserRepository(remote, cache) { 1_000 }
        assertIs<CreateUserResult.Success>(repository.createUser(draft))
        val users = repository.observeUsers().first()
        assertEquals(listOf(42L, 9L, 1L), users.map { it.id })
        assertEquals(1_000L, users.first().firstSeenAt)
        assertEquals(1_000L, users.first().addedAt)
        assertEquals(0, remote.fetchCalls)
        assertEquals(users, DefaultUserRepository(remote, cache).observeUsers().first())
    }

    @Test
    fun failedLocalWriteCanRetrySavingWithoutRepeatingPost() = runTest {
        val cache = MemoryDao().apply { failWrite = true }
        val remote = Remote(dto)
        val repository = DefaultUserRepository(remote, cache) { 1_000 }
        val pending = assertIs<CreateUserResult.NeedsLocalSave>(repository.createUser(draft))
        assertEquals(emptyList(), repository.observeUsers().first())
        assertIs<CreateUserResult.NeedsLocalSave>(repository.saveCreatedUser(pending.user))
        cache.failWrite = false
        assertIs<CreateUserResult.Success>(repository.saveCreatedUser(pending.user))
        assertIs<CreateUserResult.Success>(repository.saveCreatedUser(pending.user))
        assertEquals(listOf(42L), repository.observeUsers().first().map { it.id })
        assertEquals(1, remote.createCalls)
    }

    @Test
    fun refreshDeduplicatesCreatedUserAndPreservesTimestampsAndTopOrdering() = runTest {
        val cache = MemoryDao()
        val remote = Remote(dto)
        val repository = DefaultUserRepository(remote, cache) { 1_000 }
        repository.createUser(draft)
        remote.snapshot = listOf(UserDto(1, "Remote", "r@example.com"), dto.copy(name = "Server Ada"))
        assertEquals(RefreshResult.Success, repository.refresh())
        val users = repository.observeUsers().first()
        assertEquals(listOf(42L, 1L), users.map { it.id })
        assertEquals("Server Ada", users.first().name)
        assertEquals(1_000L, users.first().addedAt)
        assertEquals(1_000L, users.first().firstSeenAt)
    }

    @Test
    fun inFlightRefreshPreservesNewlyCreatedRowOutsideItsSnapshot() = runTest {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val cache = MemoryDao()
        val remote = Remote(dto).apply { beforeFetch = { started.complete(Unit); release.await() } }
        val repository = DefaultUserRepository(remote, cache) { 1_000 }
        val refresh = async { repository.refresh() }
        started.await()
        repository.createUser(draft)
        release.complete(Unit)
        assertEquals(RefreshResult.Success, refresh.await())
        assertEquals(listOf(42L), repository.observeUsers().first().map { it.id })
    }

    @Test
    fun failedCreationNeverMutatesCache() = runTest {
        for (error in listOf(UserDataError.MissingToken, UserDataError.Validation, UserDataError.Network, UserDataError.Server(503))) {
            val cache = MemoryDao()
            val remote = Remote(dto).apply { result = ApiResult.Failure(error) }
            val repository = DefaultUserRepository(remote, cache)
            assertEquals(CreateUserResult.Failure(error), repository.createUser(draft))
            assertEquals(emptyList(), repository.observeUsers().first())
        }
    }

    @Test
    fun saveDeduplicatesIdAndRetainsOriginalFirstSeenTime() = runTest {
        val cache = MemoryDao(listOf(UserEntity(42, "Old", "old@example.com", 100)))
        val repository = DefaultUserRepository(Remote(dto), cache) { 1_000 }
        repository.createUser(draft)
        val saved = repository.observeUsers().first().single()
        assertEquals(100L, saved.firstSeenAt)
        assertEquals(1_000L, saved.addedAt)
    }

    private class Remote(private val created: UserDto) : UsersRemoteDataSource {
        override suspend fun deleteUser(id: Long): ApiResult<Unit> = error("Unused")
        var createCalls = 0
        var fetchCalls = 0
        var result: ApiResult<UserDto> = ApiResult.Success(created)
        var snapshot = emptyList<UserDto>()
        var beforeFetch: suspend () -> Unit = {}
        override suspend fun createUser(user: NewUser): ApiResult<UserDto> { createCalls++; return result }
        override suspend fun findUser(id: Long): ApiResult<UserDto> = ApiResult.Success(created)
        override suspend fun fetchLastPage(): ApiResult<List<UserDto>> {
            fetchCalls++
            beforeFetch()
            return ApiResult.Success(snapshot)
        }
    }

    private class MemoryDao(initial: List<UserEntity> = emptyList()) : UserDao() {
        private val rows = MutableStateFlow(initial)
        var failWrite = false
        override fun observeUsers(): Flow<List<UserEntity>> = rows.map { list ->
            list.sortedWith(compareBy<UserEntity> { it.addedAt == null }.thenByDescending { it.addedAt }
                .thenBy { it.snapshotPosition }.thenBy { it.id })
        }
        override fun observeVisibleUsers(): Flow<List<UserEntity>> = observeUsers()
        override suspend fun terminalDeleteIds(): List<Long> = emptyList()
        override suspend fun usersHeldForDelete(): List<UserEntity> = emptyList()
        override suspend fun locallyCreatedUsers() = rows.value.filter { it.addedAt != null }
        override suspend fun allUsers() = rows.value
        override suspend fun userById(id: Long) = rows.value.firstOrNull { it.id == id }
        override suspend fun clearUsers() { rows.value = emptyList() }
        override suspend fun upsertUsers(users: List<UserEntity>) {
            if (failWrite) error("Storage unavailable")
            val merged = rows.value.associateBy { it.id }.toMutableMap()
            users.forEach { merged[it.id] = it }
            rows.value = merged.values.toList()
        }
        override suspend fun updateUser(user: UserEntity): Int {
            val current = rows.value
            val index = current.indexOfFirst { it.id == user.id }
            if (index < 0) return 0
            rows.value = current.toMutableList().apply { this[index] = user }
            return 1
        }
    }
}
