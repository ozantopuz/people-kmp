package com.example.people.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

internal sealed interface DeleteIntentResult {
    data class Accepted(val transaction: DeleteTransaction) : DeleteIntentResult
    data object UserMissing : DeleteIntentResult
    data object AlreadyActiveOrDeleted : DeleteIntentResult
}

@Dao
internal abstract class DeleteTransactionDao {
    @Query("SELECT * FROM delete_transactions ORDER BY acceptedAtEpochMs, transactionId")
    abstract fun observeAll(): Flow<List<DeleteTransaction>>

    @Query("SELECT * FROM delete_transactions ORDER BY acceptedAtEpochMs, transactionId")
    abstract suspend fun all(): List<DeleteTransaction>

    @Query("SELECT * FROM delete_transactions WHERE transactionId = :transactionId")
    abstract suspend fun byTransactionId(transactionId: String): DeleteTransaction?

    @Query("SELECT * FROM delete_transactions WHERE userId = :userId")
    protected abstract suspend fun byUserId(userId: Long): DeleteTransaction?

    @Query("SELECT EXISTS(SELECT 1 FROM users WHERE id = :userId)")
    protected abstract suspend fun userExists(userId: Long): Boolean

    @Query("SELECT * FROM delete_transactions WHERE status = 'PendingUndo' AND undoEligible = 1 ORDER BY acceptedAtEpochMs DESC, transactionId DESC LIMIT 1")
    protected abstract suspend fun latestUndoEligible(): DeleteTransaction?

    @Query("UPDATE delete_transactions SET undoEligible = 0, updatedAtEpochMs = :nowEpochMs WHERE transactionId = :transactionId AND status = 'PendingUndo' AND undoEligible = 1")
    protected abstract suspend fun revokeUndo(transactionId: String, nowEpochMs: Long): Int

    @Query("DELETE FROM delete_transactions WHERE userId = :userId AND status IN ('Undone', 'Failed')")
    protected abstract suspend fun removeReplaceable(userId: Long): Int

    @Insert
    protected abstract suspend fun insert(transaction: DeleteTransaction)

    /** Room holds one write transaction across the existence check, revocation and insert. */
    @Transaction
    open suspend fun accept(transaction: DeleteTransaction): DeleteIntentResult {
        require(transaction.id.isNotBlank())
        require(transaction.status == DeleteStatus.PendingUndo)
        require(transaction.undoable && transaction.attemptCount == 0)
        require(transaction.deadlineAtEpochMillis > transaction.acceptedAtEpochMs)
        require(transaction.nextAttemptAtEpochMs == null && transaction.requestStartedAtEpochMs == null)
        require(transaction.lastFailureKind == null && !transaction.hadUnknownOutcome)
        if (!userExists(transaction.userId)) return DeleteIntentResult.UserMissing
        val previous = byUserId(transaction.userId)
        if (previous != null && previous.status !in setOf(DeleteStatus.Undone, DeleteStatus.Failed)) {
            return DeleteIntentResult.AlreadyActiveOrDeleted
        }
        latestUndoEligible()?.let { revokeUndo(it.id, transaction.acceptedAtEpochMs) }
        // An ABORT on insert rolls this removal and the previous Undo revocation back too.
        if (previous != null) removeReplaceable(transaction.userId)
        insert(transaction)
        return DeleteIntentResult.Accepted(transaction)
    }

    /** The exact ID and newest eligibility are checked by one SQLite UPDATE. */
    @Query("""
        UPDATE delete_transactions
        SET status = 'Undone', undoEligible = 0, updatedAtEpochMs = :nowEpochMs
        WHERE transactionId = :transactionId AND status = 'PendingUndo'
          AND undoEligible = 1 AND deadlineAtEpochMs > :nowEpochMs
          AND transactionId = (
              SELECT transactionId FROM delete_transactions
              WHERE status = 'PendingUndo' AND undoEligible = 1
              ORDER BY acceptedAtEpochMs DESC, transactionId DESC LIMIT 1
          )
    """)
    abstract suspend fun undo(transactionId: String, nowEpochMs: Long): Int

    @Query("""
        SELECT * FROM delete_transactions
        WHERE status IN ('PendingUndo', 'InFlight', 'Verifying', 'Retryable')
        ORDER BY deadlineAtEpochMs, transactionId
    """)
    abstract suspend fun recoverable(): List<DeleteTransaction>

    @Query("""
        SELECT * FROM delete_transactions
        WHERE deadlineAtEpochMs <= :nowEpochMs
          AND (status = 'PendingUndo'
               OR (status = 'Retryable' AND hadUnknownOutcome = 0
                   AND nextAttemptAtEpochMs IS NOT NULL AND nextAttemptAtEpochMs <= :nowEpochMs))
        ORDER BY deadlineAtEpochMs, transactionId
    """)
    abstract suspend fun dueForDelete(nowEpochMs: Long): List<DeleteTransaction>

    @Query("""
        SELECT * FROM delete_transactions
        WHERE status = 'Retryable' AND hadUnknownOutcome = 1
          AND deadlineAtEpochMs <= :nowEpochMs
          AND nextAttemptAtEpochMs IS NOT NULL AND nextAttemptAtEpochMs <= :nowEpochMs
        ORDER BY nextAttemptAtEpochMs, transactionId
    """)
    abstract suspend fun dueForVerification(nowEpochMs: Long): List<DeleteTransaction>

    /** A compare-and-set claim: concurrent callers cannot both move the same row to InFlight. */
    @Query("""
        UPDATE delete_transactions
        SET status = 'InFlight', undoEligible = 0, attemptCount = attemptCount + 1,
            requestStartedAtEpochMs = :nowEpochMs, nextAttemptAtEpochMs = NULL,
            updatedAtEpochMs = :nowEpochMs
        WHERE transactionId = :transactionId AND deadlineAtEpochMs <= :nowEpochMs
          AND (status = 'PendingUndo'
               OR (status = 'Retryable' AND hadUnknownOutcome = 0
                   AND nextAttemptAtEpochMs IS NOT NULL AND nextAttemptAtEpochMs <= :nowEpochMs))
    """)
    abstract suspend fun claimForDelete(transactionId: String, nowEpochMs: Long): Int

    /** The processor must establish that an InFlight request is orphaned before using this. */
    @Query("""
        UPDATE delete_transactions
        SET status = 'Verifying', undoEligible = 0, attemptCount = attemptCount + 1,
            requestStartedAtEpochMs = :nowEpochMs, nextAttemptAtEpochMs = NULL,
            updatedAtEpochMs = :nowEpochMs
        WHERE transactionId = :transactionId AND deadlineAtEpochMs <= :nowEpochMs
          AND (status = 'InFlight'
               OR (status = 'Retryable' AND hadUnknownOutcome = 1
                   AND nextAttemptAtEpochMs IS NOT NULL AND nextAttemptAtEpochMs <= :nowEpochMs))
    """)
    abstract suspend fun claimForVerification(transactionId: String, nowEpochMs: Long): Int

    /** Reclaim a persisted Verifying row only if nobody changed it since it was read. */
    @Query("""
        UPDATE delete_transactions SET attemptCount = attemptCount + 1,
            requestStartedAtEpochMs = :nowEpochMs, updatedAtEpochMs = :nowEpochMs
        WHERE transactionId = :transactionId AND status = 'Verifying'
          AND deadlineAtEpochMs <= :nowEpochMs
          AND updatedAtEpochMs = :observedUpdatedAtEpochMs
          AND :nowEpochMs > :observedUpdatedAtEpochMs
    """)
    abstract suspend fun resumeVerification(
        transactionId: String,
        observedUpdatedAtEpochMs: Long,
        nowEpochMs: Long,
    ): Int

    @Query("""
        UPDATE delete_transactions
        SET status = 'Confirmed204', undoEligible = 0, nextAttemptAtEpochMs = NULL,
            lastFailureKind = NULL, hadUnknownOutcome = 0, updatedAtEpochMs = :nowEpochMs
        WHERE transactionId = :transactionId AND status = 'InFlight'
    """)
    abstract suspend fun markConfirmed204(transactionId: String, nowEpochMs: Long): Int

    @Query("DELETE FROM users WHERE id = :userId")
    protected abstract suspend fun removeConfirmedUser(userId: Long)

    /** One local commit for the exact 204 and its cached row; a failed write rolls both back. */
    @Transaction
    open suspend fun confirm204AndRemove(transactionId: String, userId: Long, nowEpochMs: Long): Boolean {
        if (byTransactionId(transactionId)?.userId != userId) return false
        if (markConfirmed204(transactionId, nowEpochMs) != 1) return false
        removeConfirmedUser(userId)
        return true
    }

    @Query("""
        UPDATE delete_transactions
        SET status = 'ConfirmedAbsent', undoEligible = 0, nextAttemptAtEpochMs = NULL,
            lastFailureKind = NULL, hadUnknownOutcome = 0, updatedAtEpochMs = :nowEpochMs
        WHERE transactionId = :transactionId AND status = 'InFlight'
    """)
    protected abstract suspend fun markConfirmedAbsent(transactionId: String, nowEpochMs: Long): Int

    /** A DELETE 404 confirms absence; retain only a terminal tombstone against stale snapshots. */
    @Transaction
    open suspend fun confirmAbsentAndRemove(transactionId: String, userId: Long, nowEpochMs: Long): Boolean {
        if (byTransactionId(transactionId)?.userId != userId) return false
        if (markConfirmedAbsent(transactionId, nowEpochMs) != 1) return false
        removeConfirmedUser(userId)
        return true
    }

    @Query("SELECT * FROM delete_transactions WHERE status = 'AbsentOrInaccessible' AND lastFailureKind = 'NotFound'")
    protected abstract suspend fun legacyConfirmedAbsent(): List<DeleteTransaction>

    @Query("""
        UPDATE delete_transactions
        SET status = 'ConfirmedAbsent', lastFailureKind = NULL, hadUnknownOutcome = 0,
            nextAttemptAtEpochMs = NULL, updatedAtEpochMs = :nowEpochMs
        WHERE transactionId = :transactionId AND status = 'AbsentOrInaccessible'
          AND lastFailureKind = 'NotFound'
    """)
    protected abstract suspend fun markLegacyConfirmedAbsent(transactionId: String, nowEpochMs: Long): Int

    /** Repair rows left by the former DELETE-404-then-GET-404 path on first recovery. */
    @Transaction
    open suspend fun resolveLegacyConfirmedAbsent(nowEpochMs: Long) {
        for (row in legacyConfirmedAbsent()) {
            if (markLegacyConfirmedAbsent(row.id, nowEpochMs) == 1) removeConfirmedUser(row.userId)
        }
    }

    @Query("""
        UPDATE delete_transactions
        SET status = 'Verifying', undoEligible = 0,
            requestStartedAtEpochMs = :nowEpochMs, lastFailureKind = :failureKind,
            nextAttemptAtEpochMs = :retryAfterEpochMs,
            hadUnknownOutcome = 1, updatedAtEpochMs = :nowEpochMs
        WHERE transactionId = :transactionId AND status = 'InFlight'
    """)
    abstract suspend fun beginVerification(
        transactionId: String,
        failureKind: DeleteFailureKind,
        retryAfterEpochMs: Long?,
        nowEpochMs: Long,
    ): Int

    @Query("""
        UPDATE delete_transactions
        SET status = 'AbsentOrInaccessible', undoEligible = 0,
            nextAttemptAtEpochMs = NULL, updatedAtEpochMs = :nowEpochMs
        WHERE transactionId = :transactionId AND status IN ('InFlight', 'Verifying')
    """)
    abstract suspend fun markAbsentOrInaccessible(transactionId: String, nowEpochMs: Long): Int

    @Query("""
        UPDATE delete_transactions
        SET status = 'AbsentOrInaccessible', undoEligible = 0,
            nextAttemptAtEpochMs = NULL, lastFailureKind = :failureKind,
            hadUnknownOutcome = 1, updatedAtEpochMs = :nowEpochMs
        WHERE transactionId = :transactionId AND status = 'Verifying'
    """)
    abstract suspend fun markInaccessible(
        transactionId: String,
        failureKind: DeleteFailureKind,
        nowEpochMs: Long,
    ): Int

    @Query("""
        UPDATE delete_transactions
        SET status = 'Failed', undoEligible = 0, nextAttemptAtEpochMs = NULL,
            lastFailureKind = :failureKind, updatedAtEpochMs = :nowEpochMs
        WHERE transactionId = :transactionId AND status IN ('InFlight', 'Verifying')
    """)
    abstract suspend fun markFailed(transactionId: String, failureKind: DeleteFailureKind, nowEpochMs: Long): Int

    @Query("""
        UPDATE delete_transactions
        SET status = 'Retryable', undoEligible = 0,
            nextAttemptAtEpochMs = :nextAttemptAtEpochMs,
            lastFailureKind = :failureKind, hadUnknownOutcome = :hadUnknownOutcome,
            updatedAtEpochMs = :nowEpochMs
        WHERE transactionId = :transactionId AND status IN ('InFlight', 'Verifying')
          AND :nextAttemptAtEpochMs >= deadlineAtEpochMs
    """)
    abstract suspend fun markRetryable(
        transactionId: String,
        failureKind: DeleteFailureKind,
        hadUnknownOutcome: Boolean,
        nextAttemptAtEpochMs: Long,
        nowEpochMs: Long,
    ): Int
}
