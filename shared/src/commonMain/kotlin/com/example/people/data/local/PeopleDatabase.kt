package com.example.people.data.local

import androidx.room.ConstructedBy
import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.RoomDatabaseConstructor
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO

@Database(entities = [UserEntity::class, DeleteTransaction::class], version = 2, exportSchema = false)
@ConstructedBy(PeopleDatabaseConstructor::class)
@TypeConverters(DeleteStatusConverters::class)
internal abstract class PeopleDatabase : RoomDatabase() {
    abstract fun userDao(): UserDao
    abstract fun deleteTransactionDao(): DeleteTransactionDao
}

/** Additive migration: the v1 users table and its timestamps are never rewritten. */
internal val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(connection: SQLiteConnection) {
        connection.execSQL("""
            CREATE TABLE IF NOT EXISTS `delete_transactions` (
                `userId` INTEGER NOT NULL,
                `transactionId` TEXT NOT NULL,
                `acceptedAtEpochMs` INTEGER NOT NULL,
                `deadlineAtEpochMs` INTEGER NOT NULL,
                `status` TEXT NOT NULL,
                `undoEligible` INTEGER NOT NULL,
                `attemptCount` INTEGER NOT NULL,
                `nextAttemptAtEpochMs` INTEGER,
                `requestStartedAtEpochMs` INTEGER,
                `lastFailureKind` TEXT,
                `hadUnknownOutcome` INTEGER NOT NULL,
                `updatedAtEpochMs` INTEGER NOT NULL,
                PRIMARY KEY(`userId`)
            )
        """.trimIndent())
        connection.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_delete_transactions_transactionId` ON `delete_transactions` (`transactionId`)")
        connection.execSQL("CREATE INDEX IF NOT EXISTS `index_delete_transactions_status_deadlineAtEpochMs` ON `delete_transactions` (`status`, `deadlineAtEpochMs`)")
        connection.execSQL("CREATE INDEX IF NOT EXISTS `index_delete_transactions_status_nextAttemptAtEpochMs` ON `delete_transactions` (`status`, `nextAttemptAtEpochMs`)")
    }
}

// Room's KSP processor generates the actual constructors for all configured targets.
@Suppress("KotlinNoActualForExpect")
internal expect object PeopleDatabaseConstructor : RoomDatabaseConstructor<PeopleDatabase> {
    override fun initialize(): PeopleDatabase
}

internal fun buildPeopleDatabase(builder: RoomDatabase.Builder<PeopleDatabase>): PeopleDatabase =
    builder.setDriver(BundledSQLiteDriver())
        .addMigrations(MIGRATION_1_2)
        .setQueryCoroutineContext(Dispatchers.IO)
        .build()
