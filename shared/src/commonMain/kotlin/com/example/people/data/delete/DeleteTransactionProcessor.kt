package com.example.people.data.delete

import com.example.people.data.local.DeleteFailureKind
import com.example.people.data.local.DeleteIntentResult
import com.example.people.data.local.DeleteStatus
import com.example.people.data.local.DeleteTransaction
import com.example.people.data.local.PeopleDatabase
import com.example.people.data.local.UserEntity
import com.example.people.data.remote.ApiResult
import com.example.people.data.remote.UsersRemoteDataSource
import com.example.people.data.repository.DefaultUserRepository
import com.example.people.domain.repository.RefreshResult
import com.example.people.domain.repository.UserDataError
import com.example.people.domain.repository.UserRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.coroutineContext
import kotlin.math.max
import kotlin.random.Random
import kotlin.time.Clock
import kotlin.time.TimeSource

private fun defaultMonotonicClock(): () -> Long {
    val origin = TimeSource.Monotonic.markNow()
    return { origin.elapsedNow().inWholeMilliseconds }
}

internal sealed interface DeleteAcceptanceResult {
    data class Accepted(val transaction: DeleteTransaction) : DeleteAcceptanceResult
    data class Rejected(val reason: UserDataError) : DeleteAcceptanceResult
}

internal data class DeleteUndoTarget(val transactionId: String, val userId: Long)
internal data class DeleteFailure(
    val transactionId: String,
    val kind: DeleteFailureKind,
    val outcomeUnconfirmed: Boolean = false,
)

internal interface DeleteProcessor {
    val undoTarget: StateFlow<DeleteUndoTarget?>
    /** Live notifications only; durable ledger rows are not replayed as snackbars. */
    val failedDeletes: Flow<DeleteFailure>
    suspend fun accept(userId: Long): DeleteAcceptanceResult
    suspend fun undo(transactionId: String): Boolean
}

/** Prevents two processor instances in one live process from adopting the same HTTP request. */
private object LiveRequests {
    private val mutex = Mutex()
    private val owners = mutableMapOf<String, Job>()
    suspend fun reserve(id: String, owner: Job): Boolean = mutex.withLock {
        if (owners[id]?.isCompleted == false) false else {
            owners[id] = owner
            true
        }
    }
    suspend fun isOwned(id: String): Boolean = mutex.withLock { owners[id]?.isCompleted == false }
    suspend fun release(id: String, owner: Job) {
        mutex.withLock { if (owners[id] === owner) owners.remove(id) }
    }
}

/**
 * App-owned, foreground-only worker. Its caller owns [scope]; shutdown cancels only this worker's
 * children. No screen lifecycle starts it. Room is authoritative for every transition.
 */
internal class DeleteTransactionProcessor(
    private val database: PeopleDatabase,
    private val remote: UsersRemoteDataSource,
    private val scope: CoroutineScope,
    private val wallTimeMillis: () -> Long = { Clock.System.now().toEpochMilliseconds() },
    private val monotonicTimeMillis: () -> Long = defaultMonotonicClock(),
    private val writesEnabled: Boolean,
    private val refreshRepository: UserRepository = DefaultUserRepository(remote, database.userDao()),
) : DeleteProcessor {
    private val dao = database.deleteTransactionDao()
    private val userDao = database.userDao()
    // Serializes Room acceptance with its local timing/Undo publication and with Undo/claim.
    private val timingMutex = Mutex()
    private val stalledMutex = Mutex()
    private val stalled = mutableSetOf<String>()
    private val recoveryEpoch = MutableStateFlow(0)
    private class WorkerHandle(val job: Job, val signal: Channel<Unit>)
    private val worker = MutableStateFlow<WorkerHandle?>(null)
    private val stopped = MutableStateFlow(false)
    private val _undoTarget = MutableStateFlow<DeleteUndoTarget?>(null)
    override val undoTarget: StateFlow<DeleteUndoTarget?> = _undoTarget.asStateFlow()
    private val failureEvents = Channel<DeleteFailure>(Channel.UNLIMITED)
    override val failedDeletes: Flow<DeleteFailure> = failureEvents.receiveAsFlow()

    fun observeVisibleUsers(): Flow<List<UserEntity>> = userDao.observeVisibleUsers()

    /** Idempotent start and foreground-resume trigger until terminal shutdown. */
    fun startRecovery() {
        if (stopped.value) return
        recoveryEpoch.value += 1
        while (true) {
            if (stopped.value) return
            val current = worker.value
            if (current != null && !current.job.isCompleted) {
                current.signal.trySend(Unit)
                return
            }
            val signal = Channel<Unit>(Channel.CONFLATED)
            val job = scope.launch(start = CoroutineStart.LAZY) {
                val childrenJob = SupervisorJob(coroutineContext[Job])
                val children = CoroutineScope(coroutineContext + childrenJob)
                try { schedule(children, signal) }
                finally {
                    // Child requests may still be persisting a received HTTP 204.
                    withContext(NonCancellable) { childrenJob.cancelAndJoin() }
                    signal.close()
                }
            }
            if (worker.compareAndSet(current, WorkerHandle(job, signal))) {
                if (stopped.value) {
                    job.cancel()
                    signal.close()
                    return
                }
                job.start()
                signal.trySend(Unit)
                return
            }
            job.cancel()
            signal.close()
        }
    }

    /** Foreground resumption for a live instance; terminal shutdown needs a fresh instance. */
    fun resume() = startRecovery()

    /** Request terminal stop without blocking. Call [shutdownAndJoin] before closing Room. */
    fun shutdown() {
        stopped.value = true
        worker.value?.job?.cancel()
        _undoTarget.value = null
    }

    /** Terminal, idempotent database ownership boundary; never waits on the UI thread. */
    suspend fun shutdownAndJoin() = withContext(NonCancellable) {
        shutdown()
        // An acceptance already inside the local critical section must finish before Room closes.
        timingMutex.withLock { _undoTarget.value = null }
        worker.value?.job?.join()
    }

    fun selectForConfirmation(@Suppress("UNUSED_PARAMETER") userId: Long) = Unit

    override suspend fun accept(userId: Long): DeleteAcceptanceResult {
        if (!writesEnabled) return DeleteAcceptanceResult.Rejected(UserDataError.MissingToken)
        coroutineContext.ensureActive()
        return try {
            // Once entered, finish the local Room commit and its publication together even if
            // the caller is cancelled. The scheduler also takes this mutex before using timing.
            withContext(NonCancellable) {
                timingMutex.withLock {
                    if (stopped.value) return@withLock DeleteAcceptanceResult.Rejected(UserDataError.Cache)
                    // Sample both clocks before Room commit so the durable wall deadline and
                    // the live monotonic gate describe the same five-second interval.
                    val acceptedMono = monotonicTimeMillis()
                    val now = wallTimeMillis()
                    val id = "$now-${Random.nextLong().toULong().toString(16)}"
                    val transaction = DeleteTransaction(
                        userId = userId,
                        id = id,
                        acceptedAtEpochMs = now,
                        deadlineAtEpochMillis = now.saturatedAdd(UNDO_WINDOW_MS),
                        status = DeleteStatus.PendingUndo,
                        undoable = true,
                        updatedAtEpochMs = now,
                    )
                    when (dao.accept(transaction)) {
                        is DeleteIntentResult.Accepted -> {
                            acceptedMonotonic[id] = acceptedMono
                            eligible[id] = Eligibility(
                                DeleteStatus.PendingUndo, transaction.deadlineAtEpochMillis,
                                acceptedMono.saturatedAdd(UNDO_WINDOW_MS),
                            )
                            if (!stopped.value) _undoTarget.value = DeleteUndoTarget(id, userId)
                            worker.value?.signal?.trySend(Unit)
                            DeleteAcceptanceResult.Accepted(transaction)
                        }
                        DeleteIntentResult.UserMissing -> DeleteAcceptanceResult.Rejected(UserDataError.NotFound)
                        DeleteIntentResult.AlreadyActiveOrDeleted -> DeleteAcceptanceResult.Rejected(UserDataError.Http(409))
                    }
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            DeleteAcceptanceResult.Rejected(UserDataError.Cache)
        }
    }

    override suspend fun undo(transactionId: String): Boolean {
        coroutineContext.ensureActive()
        return withContext(NonCancellable) {
            timingMutex.withLock {
                if (stopped.value || _undoTarget.value?.transactionId != transactionId) return@withLock false
                val row = dao.byTransactionId(transactionId) ?: return@withLock false
                val acceptedAt = acceptedMonotonic[transactionId] ?: return@withLock false
                val elapsed = monotonicTimeMillis() - acceptedAt
                // A live wall-clock jump cannot shorten the five-second Undo interval.
                val now = if (elapsed >= UNDO_WINDOW_MS) max(wallTimeMillis(), row.deadlineAtEpochMillis)
                else minOf(wallTimeMillis(), row.deadlineAtEpochMillis - 1)
                val changed = dao.undo(transactionId, now) == 1
                if (_undoTarget.value?.transactionId == transactionId && (changed || elapsed >= UNDO_WINDOW_MS)) _undoTarget.value = null
                if (changed) {
                    acceptedMonotonic.remove(transactionId)
                    eligible.remove(transactionId)
                    worker.value?.signal?.trySend(Unit)
                }
                changed
            }
        }
    }

    suspend fun refresh(): RefreshResult = refreshRepository.refresh()

    private data class Eligibility(val status: DeleteStatus, val wallAt: Long, val monoAt: Long)
    private val eligible = mutableMapOf<String, Eligibility>()
    private val acceptedMonotonic = mutableMapOf<String, Long>()

    private suspend fun schedule(children: CoroutineScope, signal: Channel<Unit>) {
        val running = mutableMapOf<String, Job>()
        var observedRecoveryEpoch = -1
        val observer = children.launch { dao.observeAll().collect {
            signal.trySend(Unit)
        } }
        try {
            dao.resolveLegacyConfirmedAbsent(wallTimeMillis())
            while (coroutineContext[Job]?.isActive == true) {
                if (observedRecoveryEpoch != recoveryEpoch.value) {
                    observedRecoveryEpoch = recoveryEpoch.value
                    stalledMutex.withLock { stalled.clear() }
                }
                running.entries.removeAll { it.value.isCompleted }
                val rows = dao.recoverable()
                if (!writesEnabled) {
                    // A read-only launch must not turn durable prior intents into terminal failures.
                    signal.receive()
                    continue
                }
                val nowWall = wallTimeMillis()
                val nowMono = monotonicTimeMillis()
                val activeIds = rows.mapTo(mutableSetOf()) { it.id }
                timingMutex.withLock {
                    eligible.keys.retainAll(activeIds)
                }
                var nextWake: Long? = null
                for (row in rows) {
                    if (row.id in running) continue
                    if (running.size >= MAX_CONCURRENT_REQUESTS) continue
                    if (stalledMutex.withLock { row.id in stalled }) continue
                    if (row.status == DeleteStatus.InFlight || row.status == DeleteStatus.Verifying) {
                        if (LiveRequests.isOwned(row.id)) {
                            nextWake = minOf(nextWake ?: 1_000L, 1_000L)
                            continue
                        }
                    }
                    val dueAt = when (row.status) {
                        DeleteStatus.PendingUndo -> row.deadlineAtEpochMillis
                        DeleteStatus.Retryable -> row.nextAttemptAtEpochMs ?: continue
                        else -> null
                    }
                    if (dueAt != null) {
                        val timing = timingMutex.withLock {
                            val previous = eligible[row.id]
                            val entry = if (previous?.status == row.status && previous.wallAt == dueAt) previous
                            else Eligibility(
                                row.status, dueAt,
                                if (row.status == DeleteStatus.PendingUndo && acceptedMonotonic[row.id] != null) {
                                    acceptedMonotonic.getValue(row.id).saturatedAdd(UNDO_WINDOW_MS)
                                } else nowMono.saturatedAdd(max(0, dueAt - nowWall)),
                            )
                                .also { eligible[row.id] = it }
                            entry
                        }
                        val remaining = timing.monoAt - nowMono
                        if (remaining > 0) {
                            nextWake = minOf(nextWake ?: remaining, remaining)
                            continue
                        }
                    }
                    val job = children.launch {
                        try { process(row) }
                        finally { signal.trySend(Unit) }
                    }
                    running[row.id] = job
                }
                if (nextWake == null) signal.receive()
                else withTimeoutOrNull(max(1, nextWake)) { signal.receive() }
            }
        } finally { observer.cancel() }
    }

    private suspend fun process(row: DeleteTransaction) {
        val owner = requireNotNull(coroutineContext[Job])
        if (!LiveRequests.reserve(row.id, owner)) return
        try {
            if (stopped.value) return
            val now = max(wallTimeMillis(), row.deadlineAtEpochMillis)
            when (row.status) {
                DeleteStatus.PendingUndo, DeleteStatus.Retryable -> {
                    val eligibleAt = row.nextAttemptAtEpochMs ?: row.deadlineAtEpochMillis
                    val claimNow = max(now, eligibleAt)
                    if (row.status == DeleteStatus.Retryable && row.hadUnknownOutcome) {
                        if (dao.claimForVerification(row.id, claimNow) == 1) verify(row.id, row.userId, row.lastFailureKind)
                    } else if (timingMutex.withLock {
                        if (stopped.value || dao.claimForDelete(row.id, claimNow) != 1) false
                        else {
                            if (_undoTarget.value?.transactionId == row.id) _undoTarget.value = null
                            acceptedMonotonic.remove(row.id)
                            true
                        }
                    }) {
                        executeDelete(row.id, row.userId)
                    }
                }
                DeleteStatus.InFlight -> {
                    // A live owner is excluded by LiveRequests. This row survived a lost owner.
                    if (dao.claimForVerification(row.id, now) == 1) verify(row.id, row.userId, row.lastFailureKind)
                }
                DeleteStatus.Verifying -> {
                    val stamp = max(now, row.updatedAtEpochMs.saturatedAdd(1))
                    if (dao.resumeVerification(row.id, row.updatedAtEpochMs, stamp) == 1) {
                        verify(row.id, row.userId, row.lastFailureKind)
                    }
                }
                else -> Unit
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Keep the persisted claim. A later resume verifies its uncertain outcome.
            stalledMutex.withLock { stalled.add(row.id) }
        } finally {
            withContext(NonCancellable) { LiveRequests.release(row.id, owner) }
        }
    }

    private suspend fun executeDelete(id: String, userId: Long) {
        coroutineContext.ensureActive()
        val result = try { remote.deleteUser(userId) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { ApiResult.Failure(UserDataError.Network) }
        when (result) {
            is ApiResult.Success -> withContext(NonCancellable) {
                dao.confirm204AndRemove(id, userId, wallTimeMillis())
            }
            is ApiResult.Failure -> when (val error = result.error) {
                UserDataError.NotFound -> withContext(NonCancellable) {
                    dao.confirmAbsentAndRemove(id, userId, wallTimeMillis())
                }
                UserDataError.Network, UserDataError.Timeout, is UserDataError.Server ->
                    beginAndVerify(id, userId, error.failureKind(), result.retryAfterEpochMs)
                UserDataError.RateLimited -> withContext(NonCancellable) {
                    retry(id, DeleteFailureKind.RateLimited, false, result.retryAfterEpochMs)
                }
                UserDataError.Unauthorized, UserDataError.Forbidden, UserDataError.MissingToken,
                UserDataError.Validation, is UserDataError.Http ->
                    reportFailure(id, error.failureKind())
                else -> beginAndVerify(id, userId, DeleteFailureKind.Unknown)
            }
        }
    }

    private suspend fun beginAndVerify(
        id: String, userId: Long, kind: DeleteFailureKind, retryAfter: Long? = null,
    ) {
        val claimed = withContext(NonCancellable) {
            dao.beginVerification(id, kind, retryAfter, wallTimeMillis()) == 1
        }
        if (claimed) verify(id, userId, kind)
    }

    private suspend fun verify(id: String, userId: Long, reason: DeleteFailureKind?) {
        coroutineContext.ensureActive()
        val result = try { remote.findUser(userId) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { ApiResult.Failure(UserDataError.Network) }
        when (result) {
            is ApiResult.Success -> {
                if (reason == DeleteFailureKind.NotFound) reportFailure(id, reason)
                else retry(id, reason ?: DeleteFailureKind.Unknown, false)
            }
            is ApiResult.Failure -> when (val error = result.error) {
                UserDataError.NotFound -> withContext(NonCancellable) {
                    if (dao.markAbsentOrInaccessible(id, wallTimeMillis()) == 1) {
                        failureEvents.trySend(DeleteFailure(id, reason ?: DeleteFailureKind.Unknown, outcomeUnconfirmed = true))
                    }
                }
                UserDataError.Unauthorized, UserDataError.Forbidden ->
                    withContext(NonCancellable) {
                        if (dao.markInaccessible(id, error.failureKind(), wallTimeMillis()) == 1) {
                            failureEvents.trySend(DeleteFailure(id, error.failureKind(), outcomeUnconfirmed = true))
                        }
                    }
                else -> retry(id, error.failureKind(), true, result.retryAfterEpochMs)
            }
        }
    }

    private suspend fun reportFailure(id: String, kind: DeleteFailureKind) = withContext(NonCancellable) {
        if (dao.markFailed(id, kind, wallTimeMillis()) == 1) {
            failureEvents.trySend(DeleteFailure(id, kind))
        }
    }

    private suspend fun retry(id: String, kind: DeleteFailureKind, unknown: Boolean, retryAfter: Long? = null) {
        val row = dao.byTransactionId(id) ?: return
        val now = wallTimeMillis()
        val exponent = (row.attemptCount - 1).coerceIn(0, 6)
        val base = (5_000L shl exponent).coerceAtMost(300_000L)
        val jitter = (id.hashCode().toLong() and 0x7fffffffL) % 1_000L
        val advisedAt = max(retryAfter ?: now, row.nextAttemptAtEpochMs ?: now)
        val delay = max(base + jitter, advisedAt - now)
        val nextAt = max(now, row.deadlineAtEpochMillis).saturatedAdd(delay)
        if (dao.markRetryable(id, kind, unknown, nextAt, now) == 1) {
            timingMutex.withLock {
                eligible[id] = Eligibility(DeleteStatus.Retryable, nextAt,
                    monotonicTimeMillis().saturatedAdd(delay))
            }
        }
    }

    private fun UserDataError.failureKind(): DeleteFailureKind = when (this) {
        UserDataError.Unauthorized, UserDataError.MissingToken -> DeleteFailureKind.Unauthorized
        UserDataError.Forbidden -> DeleteFailureKind.Forbidden
        UserDataError.NotFound -> DeleteFailureKind.NotFound
        UserDataError.RateLimited -> DeleteFailureKind.RateLimited
        UserDataError.Network -> DeleteFailureKind.Network
        UserDataError.Timeout -> DeleteFailureKind.Timeout
        is UserDataError.Server -> DeleteFailureKind.Server
        UserDataError.Cache -> DeleteFailureKind.Cache
        else -> DeleteFailureKind.Unknown
    }

    private fun Long.saturatedAdd(other: Long): Long =
        if (this > Long.MAX_VALUE - other) Long.MAX_VALUE else this + other

    private companion object {
        const val UNDO_WINDOW_MS = 5_000L
        const val MAX_CONCURRENT_REQUESTS = 4
    }
}
