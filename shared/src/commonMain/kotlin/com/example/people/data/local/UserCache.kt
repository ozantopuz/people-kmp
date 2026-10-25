package com.example.people.data.local

import kotlinx.coroutines.flow.Flow

internal interface UserCache {
    fun observeUsers(): Flow<List<UserEntity>>
    fun observeVisibleUsers(): Flow<List<UserEntity>>
    suspend fun locallyCreatedUsers(): List<UserEntity>
    suspend fun saveCreatedUser(user: UserEntity)
    suspend fun replaceSnapshot(
        snapshot: List<UserEntity>,
        verifiedCreated: List<UserEntity>,
        missingCreatedIds: List<Long>,
    )
}
