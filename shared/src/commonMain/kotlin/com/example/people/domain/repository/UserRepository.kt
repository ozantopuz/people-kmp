package com.example.people.domain.repository

import com.example.people.domain.model.User
import com.example.people.domain.model.NewUser
import com.example.people.domain.model.UserField
import kotlinx.coroutines.flow.Flow

interface UserRepository {
    /** Observing never starts a network request. Room is the source of truth. */
    fun observeUsers(): Flow<List<User>>
    suspend fun refresh(): RefreshResult
    suspend fun createUser(user: NewUser): CreateUserResult
    /** Retries only the local write for an already confirmed server-created user. Never POSTs. */
    suspend fun saveCreatedUser(user: User): CreateUserResult
}

sealed interface CreateUserResult {
    data class Success(val user: User) : CreateUserResult
    data class NeedsLocalSave(val user: User) : CreateUserResult
    data class Failure(
        val error: UserDataError,
        val fieldErrors: Map<UserField, CreateFieldError> = emptyMap(),
    ) : CreateUserResult
}

enum class CreateFieldError { Invalid, AlreadyTaken }

sealed interface RefreshResult {
    data object Success : RefreshResult
    data class Failure(val error: UserDataError) : RefreshResult
}

/** Safe to pass to presentation: no response bodies, request URLs or credentials. */
sealed interface UserDataError {
    data object MissingToken : UserDataError
    /** A 201 was received but its body could not be trusted; repeating POST risks duplication. */
    data object CreateOutcomeUnknown : UserDataError
    data object Network : UserDataError
    data object Timeout : UserDataError
    data object Unauthorized : UserDataError
    data object Forbidden : UserDataError
    data object NotFound : UserDataError
    data object Validation : UserDataError
    data object RateLimited : UserDataError
    data class Server(val status: Int) : UserDataError
    data class Http(val status: Int) : UserDataError
    data object InvalidPagination : UserDataError
    data object InvalidResponse : UserDataError
    data object Cache : UserDataError
    data object Unexpected : UserDataError
}
