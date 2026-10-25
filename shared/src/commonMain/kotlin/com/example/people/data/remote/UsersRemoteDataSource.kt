package com.example.people.data.remote

import com.example.people.domain.repository.UserDataError
import com.example.people.domain.repository.CreateFieldError
import com.example.people.domain.model.NewUser
import com.example.people.domain.model.UserField

internal interface UsersRemoteDataSource {
    suspend fun fetchLastPage(): ApiResult<List<UserDto>>
    suspend fun findUser(id: Long): ApiResult<UserDto>
    suspend fun createUser(user: NewUser): ApiResult<UserDto>
    suspend fun deleteUser(id: Long): ApiResult<Unit>
}

internal sealed interface ApiResult<out T> {
    data class Success<T>(val value: T) : ApiResult<T>
    data class Failure(
        val error: UserDataError,
        val fieldErrors: Map<UserField, CreateFieldError> = emptyMap(),
        /** Absolute server retry time, parsed from a valid Retry-After header. */
        val retryAfterEpochMs: Long? = null,
    ) : ApiResult<Nothing>
}
