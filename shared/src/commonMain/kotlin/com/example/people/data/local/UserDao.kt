package com.example.people.data.local

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
internal abstract class UserDao : UserCache {
    @Query("""
        SELECT users.* FROM users
        ORDER BY (addedAt IS NULL) ASC, addedAt DESC, snapshotPosition ASC, id ASC
    """)
    abstract override fun observeUsers(): Flow<List<UserEntity>>

    @Query("""
        SELECT users.* FROM users
        WHERE NOT EXISTS (
            SELECT 1 FROM delete_transactions AS deletion
            WHERE deletion.userId = users.id AND deletion.status IN
                ('PendingUndo', 'InFlight', 'Verifying', 'Retryable',
                 'Confirmed204', 'ConfirmedAbsent', 'AbsentOrInaccessible')
        )
        ORDER BY (addedAt IS NULL) ASC, addedAt DESC, snapshotPosition ASC, id ASC
    """)
    abstract override fun observeVisibleUsers(): Flow<List<UserEntity>>

    @Query("""
        SELECT users.* FROM users WHERE addedAt IS NOT NULL
          AND NOT EXISTS (
              SELECT 1 FROM delete_transactions AS deletion
              WHERE deletion.userId = users.id AND deletion.status NOT IN ('Undone', 'Failed')
          )
    """)
    abstract override suspend fun locallyCreatedUsers(): List<UserEntity>

    @Query("SELECT userId FROM delete_transactions WHERE status IN ('Confirmed204', 'ConfirmedAbsent', 'AbsentOrInaccessible')")
    protected abstract suspend fun terminalDeleteIds(): List<Long>

    @Query("SELECT * FROM users")
    protected abstract suspend fun allUsers(): List<UserEntity>

    @Query("""
        SELECT users.* FROM users JOIN delete_transactions AS deletion ON deletion.userId = users.id
        WHERE deletion.status IN ('PendingUndo', 'InFlight', 'Verifying', 'Retryable')
    """)
    protected abstract suspend fun usersHeldForDelete(): List<UserEntity>

    @Query("SELECT * FROM users WHERE id = :id")
    protected abstract suspend fun userById(id: Long): UserEntity?

    @Query("DELETE FROM users")
    protected abstract suspend fun clearUsers()

    @Upsert
    protected abstract suspend fun upsertUsers(users: List<UserEntity>)

    @Update
    protected abstract suspend fun updateUser(user: UserEntity): Int

    @Transaction
    open override suspend fun saveCreatedUser(user: UserEntity) {
        val existing = userById(user.id)
        if (existing == null) {
            upsertUsers(listOf(user))
        } else {
            val updated = user.copy(
                firstSeenAt = existing.firstSeenAt,
                addedAt = existing.addedAt ?: user.addedAt,
            )
            check(updateUser(updated) == 1) { "Existing user ${user.id} was not updated" }
        }
    }

    @Transaction
    open override suspend fun replaceSnapshot(
        snapshot: List<UserEntity>,
        verifiedCreated: List<UserEntity>,
        missingCreatedIds: List<Long>,
    ) {
        val before = allUsers()
        val tombstones = terminalDeleteIds().toSet()
        val reconciled = reconcileSnapshot(before, snapshot, verifiedCreated, missingCreatedIds)
            .filterNot { it.id in tombstones }
        val reconciledIds = reconciled.mapTo(mutableSetOf()) { it.id }
        // Keep a hidden cached row for Undo or failure restoration even if the
        // last-page snapshot no longer contains it. This is part of the same write.
        val retained = usersHeldForDelete().filterNot { it.id in reconciledIds }
        // The small directory is replaced atomically; observers never see the intermediate clear.
        clearUsers()
        upsertUsers(reconciled + retained)
    }
}
