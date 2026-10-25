package com.example.people.data.repository

import com.example.people.data.local.UserCache
import com.example.people.data.local.UserEntity
import com.example.people.data.local.toDomain
import com.example.people.data.local.toEntity
import com.example.people.data.remote.ApiResult
import com.example.people.data.remote.UserDto
import com.example.people.data.remote.UsersRemoteDataSource
import com.example.people.domain.model.User
import com.example.people.domain.model.NewUser
import com.example.people.domain.repository.CreateUserResult
import com.example.people.domain.repository.RefreshResult
import com.example.people.domain.repository.UserDataError
import com.example.people.domain.repository.UserRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Clock

internal class DefaultUserRepository(
    private val remote: UsersRemoteDataSource,
    private val cache: UserCache,
    private val nowMillis: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) : UserRepository {
    private val refreshMutex = Mutex()

    override suspend fun createUser(user: NewUser): CreateUserResult =
        when (val result = remote.createUser(user)) {
            is ApiResult.Failure -> CreateUserResult.Failure(result.error, result.fieldErrors)
            is ApiResult.Success -> {
                val now = nowMillis()
                val created = User(result.value.id, result.value.name, result.value.email, now, addedAt = now)
                withContext(NonCancellable) { saveCreatedUser(created) }
            }
        }

    override suspend fun saveCreatedUser(user: User): CreateUserResult = try {
        cache.saveCreatedUser(UserEntity(
            id = user.id, name = user.name, email = user.email,
            firstSeenAt = user.firstSeenAt, addedAt = user.addedAt,
        ))
        CreateUserResult.Success(user)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        CreateUserResult.NeedsLocalSave(user)
    }

    override fun observeUsers(): Flow<List<User>> = cache.observeVisibleUsers().map { rows ->
        rows.map { it.toDomain() }
    }

    override suspend fun refresh(): RefreshResult = refreshMutex.withLock {
        val snapshot = when (val result = remote.fetchLastPage()) {
            is ApiResult.Success -> result.value.distinctBy { it.id }
            is ApiResult.Failure -> return@withLock RefreshResult.Failure(result.error)
        }
        try {
            val visibleSnapshot = snapshot
            val snapshotIds = visibleSnapshot.mapTo(mutableSetOf()) { it.id }
            val verifiedCreated = mutableListOf<UserDto>()
            val missingCreatedIds = mutableListOf<Long>()
            for (local in cache.locallyCreatedUsers()) {
                if (local.id in snapshotIds) continue // Snapshot already verifies existence.
                when (val result = remote.findUser(local.id)) {
                    is ApiResult.Success -> verifiedCreated += result.value
                    is ApiResult.Failure -> {
                        if (result.error == UserDataError.NotFound) missingCreatedIds += local.id
                        else return@withLock RefreshResult.Failure(result.error)
                    }
                }
            }
            val seenAt = nowMillis()
            cache.replaceSnapshot(
                snapshot = visibleSnapshot.mapIndexed { index, dto -> dto.toEntity(seenAt, index) },
                verifiedCreated = verifiedCreated.map { it.toEntity(seenAt) },
                missingCreatedIds = missingCreatedIds,
            )
            RefreshResult.Success
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            RefreshResult.Failure(UserDataError.Cache)
        }
    }
}
