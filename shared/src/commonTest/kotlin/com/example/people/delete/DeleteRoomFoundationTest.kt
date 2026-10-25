package com.example.people.delete

import androidx.room.execSQL
import androidx.room.useWriterConnection
import androidx.sqlite.execSQL as sqliteExecSQL
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.example.people.data.local.DeleteFailureKind
import com.example.people.data.local.DeleteIntentResult
import com.example.people.data.local.UserEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Direct DAO checks, independent of the future network processor. */
class DeleteRoomFoundationTest {
    @Test fun finalizationRejectsAnotherUsersIdForBoth204And404() = runTest {
        for (confirmedAbsent in listOf(false, true)) {
            val db = openDeleteTestDatabase(newDeleteTestDatabasePath("foundation-target-$confirmedAbsent"))
            try {
                db.userDao().replaceSnapshot(
                    snapshot = listOf(1L, 2L).mapIndexed { index, id ->
                        UserEntity(id, "User $id", "$id@example.com", 100, snapshotPosition = index)
                    },
                    verifiedCreated = emptyList(), missingCreatedIds = emptyList(),
                )
                val dao = db.deleteTransactionDao()
                val target = transaction(1, "target-$confirmedAbsent", 1_000)
                assertIs<DeleteIntentResult.Accepted>(dao.accept(target))
                assertEquals(1, dao.claimForDelete(target.id, 6_000))
                val wrongIdAccepted = if (confirmedAbsent) dao.confirmAbsentAndRemove(target.id, 2, 6_001)
                else dao.confirm204AndRemove(target.id, 2, 6_001)
                assertFalse(wrongIdAccepted)
                assertEquals(DeleteStatus.InFlight, dao.byTransactionId(target.id)?.status)
                assertEquals(listOf(1L, 2L), db.userDao().observeUsers().first().map { it.id })

                val targetAccepted = if (confirmedAbsent) dao.confirmAbsentAndRemove(target.id, 1, 6_002)
                else dao.confirm204AndRemove(target.id, 1, 6_002)
                assertTrue(targetAccepted)
                assertEquals(listOf(2L), db.userDao().observeUsers().first().map { it.id })
                assertEquals(listOf(1L), dao.all().map { it.userId })
            } finally { db.close() }
        }
    }

    @Test fun saveCreatedUserUpsertsAnExistingSnapshotIdWithoutLosingFirstSeen() = runTest {
        val db = openDeleteTestDatabase(newDeleteTestDatabasePath("foundation-upsert"))
        try {
            db.userDao().replaceSnapshot(
                snapshot = listOf(UserEntity(1, "Remote", "remote@example.com", 123, snapshotPosition = 4)),
                verifiedCreated = emptyList(), missingCreatedIds = emptyList(),
            )
            assertEquals(null, db.userDao().observeUsers().first().single().addedAt)
            saveCreatedUserWithDiagnostics(
                db, UserEntity(1, "Created", "created@example.com", 999, addedAt = 456),
            )
            val row = db.userDao().observeUsers().first().single()
            assertEquals(1L, row.id)
            assertEquals("Created", row.name)
            assertEquals(123L, row.firstSeenAt)
            assertEquals(456L, row.addedAt)
        } finally { db.close() }
    }

    @Test fun v1MigrationPreservesRemoteAndLocalRows() = runTest {
        val path = newDeleteTestDatabasePath("foundation-v1")
        BundledSQLiteDriver().open(path).use { connection ->
            connection.sqliteExecSQL("CREATE TABLE IF NOT EXISTS users (id INTEGER NOT NULL PRIMARY KEY, name TEXT NOT NULL, email TEXT NOT NULL, firstSeenAt INTEGER NOT NULL, addedAt INTEGER, snapshotPosition INTEGER NOT NULL)")
            connection.sqliteExecSQL("INSERT INTO users (id, name, email, firstSeenAt, addedAt, snapshotPosition) VALUES (7, 'Remote', 'r@example.com', 123, NULL, 4)")
            connection.sqliteExecSQL("INSERT INTO users (id, name, email, firstSeenAt, addedAt, snapshotPosition) VALUES (8, 'Local', 'l@example.com', 456, 456, 0)")
            connection.sqliteExecSQL("PRAGMA user_version = 1")
        }
        val db = openDeleteTestDatabase(path)
        try {
            val users = db.userDao().observeUsers().first()
            assertEquals(listOf(8L, 7L), users.map { it.id })
            assertEquals(123L, users.single { it.id == 7L }.firstSeenAt)
            assertEquals(4, users.single { it.id == 7L }.snapshotPosition)
            assertEquals(456L, users.single { it.id == 8L }.addedAt)
            assertEquals(emptyList(), db.deleteTransactionDao().all())
        } finally { db.close() }
    }

    @Test fun acceptanceUndoAndVisibleQueryAreOneRoomSourceOfTruth() = runTest {
        val db = openDeleteTestDatabase(newDeleteTestDatabasePath("foundation"))
        try {
            db.userDao().replaceSnapshot(
                snapshot = listOf(1L, 2L).mapIndexed { index, id ->
                    UserEntity(id, "User $id", "$id@example.com", 100, snapshotPosition = index)
                },
                verifiedCreated = emptyList(), missingCreatedIds = emptyList(),
            )
            val dao = db.deleteTransactionDao()
            val first = transaction(1, "first", 1_000)
            assertIs<DeleteIntentResult.Accepted>(dao.accept(first))
            assertEquals(listOf(2L), db.userDao().observeVisibleUsers().first().map { it.id })
            assertIs<DeleteIntentResult.AlreadyActiveOrDeleted>(dao.accept(transaction(1, "duplicate", 1_001)))
            assertIs<DeleteIntentResult.Accepted>(dao.accept(transaction(2, "second", 2_000)))
            assertFalse(dao.byTransactionId("first")!!.undoable)
            assertEquals(6_000L, dao.byTransactionId("first")!!.deadlineAtEpochMillis)
            assertEquals(0, dao.undo("first", 2_001))
            assertEquals(1, dao.undo("second", 2_001))
            assertEquals(listOf(2L), db.userDao().observeVisibleUsers().first().map { it.id })
            assertEquals(0, dao.claimForDelete("first", 5_999))
            assertEquals(1, dao.claimForDelete("first", 6_000))
            assertEquals(0, dao.claimForDelete("first", 6_000))
            assertEquals(1, dao.markConfirmed204("first", 6_001))
            assertIs<DeleteIntentResult.AlreadyActiveOrDeleted>(dao.accept(transaction(1, "stale", 7_000)))
            assertEquals(listOf(2L), db.userDao().observeVisibleUsers().first().map { it.id })
        } finally { db.close() }
    }

    @Test fun failedInsertRollsBackPriorUndoRevocationAndRefreshRetainsHiddenRow() = runTest {
        val db = openDeleteTestDatabase(newDeleteTestDatabasePath("foundation-rollback"))
        try {
            db.userDao().replaceSnapshot(
                snapshot = listOf(1L, 2L).mapIndexed { index, id ->
                    UserEntity(id, "User $id", "$id@example.com", 100, snapshotPosition = index)
                },
                verifiedCreated = emptyList(), missingCreatedIds = emptyList(),
            )
            val dao = db.deleteTransactionDao()
            dao.accept(transaction(1, "first", 1_000))
            db.useWriterConnection { it.execSQL("CREATE TRIGGER reject_second BEFORE INSERT ON delete_transactions WHEN NEW.userId = 2 BEGIN SELECT RAISE(ABORT, 'write failure'); END") }
            var failed = false
            try { dao.accept(transaction(2, "second", 2_000)) } catch (_: Exception) { failed = true }
            assertTrue(failed)
            assertTrue(dao.byTransactionId("first")!!.undoable)
            assertEquals(1, dao.all().size)
            db.userDao().replaceSnapshot(emptyList(), emptyList(), emptyList())
            assertEquals(listOf(1L), db.userDao().observeUsers().first().map { it.id })
            assertEquals(emptyList(), db.userDao().observeVisibleUsers().first())
            assertEquals(1, dao.undo("first", 2_001))
            assertEquals(listOf(1L), db.userDao().observeVisibleUsers().first().map { it.id })
        } finally { db.close() }
    }

    @Test fun retryAndVerificationClaimsStayBoundToTheExactTransaction() = runTest {
        val db = openDeleteTestDatabase(newDeleteTestDatabasePath("foundation-retry"))
        try {
            db.userDao().saveCreatedUser(UserEntity(1, "One", "one@example.com", 100))
            val dao = db.deleteTransactionDao()
            dao.accept(transaction(1, "first", 1_000))
            assertEquals(1, dao.claimForDelete("first", 6_000))
            assertEquals(1, dao.markRetryable("first", DeleteFailureKind.Timeout, true, 9_000, 6_100))
            assertEquals(emptyList(), dao.dueForDelete(9_000))
            assertEquals(1, dao.dueForVerification(9_000).size)
            assertEquals(1, dao.claimForVerification("first", 9_000))
            assertEquals(0, dao.claimForVerification("first", 9_000))
            assertEquals(1, dao.resumeVerification("first", 9_000, 9_001))
            assertEquals(0, dao.resumeVerification("first", 9_000, 9_001))
            assertEquals(1, dao.markAbsentOrInaccessible("first", 9_002))
            assertEquals(0, dao.markConfirmed204("first", 9_003))
            assertEquals(emptyList(), db.userDao().observeVisibleUsers().first())
        } finally { db.close() }
    }

    private fun transaction(userId: Long, id: String, acceptedAt: Long) = DeleteTransaction(
        userId = userId, id = id, acceptedAtEpochMs = acceptedAt,
        deadlineAtEpochMillis = acceptedAt + 5_000,
        status = DeleteStatus.PendingUndo, undoable = true, updatedAtEpochMs = acceptedAt,
    )
}
