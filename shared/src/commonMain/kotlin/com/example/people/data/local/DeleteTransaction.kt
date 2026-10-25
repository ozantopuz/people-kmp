package com.example.people.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.TypeConverter

/** One current intent per user. Terminal success rows are durable tombstones. */
@Entity(
    tableName = "delete_transactions",
    indices = [
        Index(value = ["transactionId"], unique = true),
        Index(value = ["status", "deadlineAtEpochMs"]),
        Index(value = ["status", "nextAttemptAtEpochMs"]),
    ],
)
internal data class DeleteTransaction(
    @PrimaryKey val userId: Long,
    @ColumnInfo(name = "transactionId") val id: String,
    val acceptedAtEpochMs: Long,
    @ColumnInfo(name = "deadlineAtEpochMs") val deadlineAtEpochMillis: Long,
    val status: DeleteStatus,
    @ColumnInfo(name = "undoEligible") val undoable: Boolean,
    val attemptCount: Int = 0,
    val nextAttemptAtEpochMs: Long? = null,
    val requestStartedAtEpochMs: Long? = null,
    val lastFailureKind: DeleteFailureKind? = null,
    val hadUnknownOutcome: Boolean = false,
    val updatedAtEpochMs: Long,
)

internal enum class DeleteStatus {
    PendingUndo,
    InFlight,
    Verifying,
    Retryable,
    Confirmed204,
    ConfirmedAbsent,
    AbsentOrInaccessible,
    Failed,
    Undone,
}

/** Fixed categories only; never persist a response body, address, or credential. */
internal enum class DeleteFailureKind {
    Unauthorized, Forbidden, NotFound, RateLimited, Network, Timeout, Server, Cache, Unknown,
}

/** Unknown persisted values fail to decode instead of silently changing a deletion's meaning. */
internal class DeleteStatusConverters {
    @TypeConverter
    fun fromStatus(status: DeleteStatus): String = status.name

    @TypeConverter
    fun toStatus(value: String): DeleteStatus = when (value) {
        "PendingUndo" -> DeleteStatus.PendingUndo
        "InFlight" -> DeleteStatus.InFlight
        "Verifying" -> DeleteStatus.Verifying
        "Retryable" -> DeleteStatus.Retryable
        "Confirmed204" -> DeleteStatus.Confirmed204
        "ConfirmedAbsent" -> DeleteStatus.ConfirmedAbsent
        "AbsentOrInaccessible" -> DeleteStatus.AbsentOrInaccessible
        "Failed" -> DeleteStatus.Failed
        "Undone" -> DeleteStatus.Undone
        else -> error("Unknown delete transaction status")
    }

    @TypeConverter
    fun fromFailureKind(kind: DeleteFailureKind?): String? = kind?.name

    @TypeConverter
    fun toFailureKind(value: String?): DeleteFailureKind? = when (value) {
        null -> null
        "Unauthorized" -> DeleteFailureKind.Unauthorized
        "Forbidden" -> DeleteFailureKind.Forbidden
        "NotFound" -> DeleteFailureKind.NotFound
        "RateLimited" -> DeleteFailureKind.RateLimited
        "Network" -> DeleteFailureKind.Network
        "Timeout" -> DeleteFailureKind.Timeout
        "Server" -> DeleteFailureKind.Server
        "Cache" -> DeleteFailureKind.Cache
        "Unknown" -> DeleteFailureKind.Unknown
        else -> error("Unknown delete failure kind")
    }
}
