package com.example.people.data.local

import kotlin.test.Test
import kotlin.test.assertEquals

class SnapshotReconciliationTest {
    @Test
    fun removesStaleRemoteRowsButPreservesLocalOverlay() {
        val local = row(9).copy(addedAt = 5)
        val result = reconcileSnapshot(listOf(row(1), local), listOf(row(2)), emptyList(), emptyList())
        assertEquals(setOf(9L, 2L), result.map { it.id }.toSet())
        assertEquals(local, result.single { it.id == 9L })
    }

    @Test
    fun overlappingIdPreservesFirstSeenAndAddedTimeAndUsesRemoteFields() {
        val local = row(9).copy(firstSeenAt = 10, addedAt = 8)
        val remote = row(9).copy(name = "Updated", firstSeenAt = 1_000)
        val result = reconcileSnapshot(listOf(local), listOf(remote), emptyList(), emptyList())
        assertEquals(listOf(remote.copy(firstSeenAt = 10, addedAt = 8)), result)
    }

    @Test
    fun refreshPreservesExistingRemoteFirstSeenTime() {
        val old = row(1).copy(firstSeenAt = 10)
        val fresh = row(1).copy(firstSeenAt = 1_000, email = "new@example.com")
        assertEquals(
            listOf(fresh.copy(firstSeenAt = 10)),
            reconcileSnapshot(listOf(old), listOf(fresh), emptyList(), emptyList()),
        )
    }

    @Test
    fun duplicateServerIdsProduceOneRow() {
        val local = row(7).copy(addedAt = 1)
        val result = reconcileSnapshot(listOf(local), listOf(row(7), row(7), row(2), row(2)), emptyList(), emptyList())
        assertEquals(2, result.size)
        assertEquals(1L, result.single { it.id == 7L }.addedAt)
    }

    @Test
    fun emptySnapshotClearsRemoteRowsAndKeepsCreatedRows() {
        val local = row(9).copy(addedAt = 5)
        assertEquals(listOf(local), reconcileSnapshot(listOf(row(1), local), emptyList(), emptyList(), emptyList()))
    }

    @Test
    fun confirmedMissingCreatedUserIsRemovedAndVerifiedUserUpdated() {
        val missing = row(9).copy(addedAt = 5)
        val kept = row(8).copy(addedAt = 4)
        val checked = row(8).copy(name = "Changed remotely")
        assertEquals(
            listOf(kept.copy(name = checked.name)),
            reconcileSnapshot(listOf(missing, kept), emptyList(), listOf(checked), listOf(9)),
        )
    }

    @Test
    fun transactionTimeRowsPreserveAUserCreatedWhileRefreshWasInFlight() {
        val newLocal = row(99).copy(addedAt = 100)
        val result = reconcileSnapshot(listOf(newLocal), listOf(row(1)), emptyList(), emptyList())
        assertEquals(newLocal, result.single { it.id == 99L })
    }

    @Test
    fun recordInSnapshotKeptEvenIfMissingListIncludesIt() {
        val local = row(9).copy(addedAt = 5)
        val remote = row(9).copy(name = "Server Name")
        val result = reconcileSnapshot(
            existing = listOf(local),
            snapshot = listOf(remote),
            verifiedCreated = emptyList(),
            missingCreatedIds = listOf(9L),
        )
        assertEquals(listOf(remote.copy(addedAt = 5, firstSeenAt = 100)), result)
    }

    private fun row(id: Long) = UserEntity(id, "User $id", "$id@example.com", firstSeenAt = 100)
}
