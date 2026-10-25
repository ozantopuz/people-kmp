package com.example.people.delete

import com.example.people.data.delete.DeleteAcceptanceResult
import com.example.people.data.delete.DeleteTransactionProcessor
import com.example.people.data.local.DeleteStatus
import com.example.people.data.local.DeleteTransaction
import com.example.people.data.local.PeopleDatabase
import com.example.people.data.local.UserEntity
import com.example.people.data.remote.ApiResult
import com.example.people.data.remote.UserDto
import com.example.people.data.remote.UsersRemoteDataSource
import com.example.people.domain.model.NewUser
import com.example.people.domain.repository.UserDataError
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlin.test.assertEquals

// Thin aliases keep the contracts independent of the production package layout.
internal typealias DeleteTransaction = com.example.people.data.local.DeleteTransaction
internal typealias DeleteStatus = com.example.people.data.local.DeleteStatus
internal typealias DeleteTransactionProcessor = com.example.people.data.delete.DeleteTransactionProcessor
internal typealias DeleteAcceptanceResult = com.example.people.data.delete.DeleteAcceptanceResult

/**
 * Shared contract against the production processor and real Room database. Keep this
 * adapter thin; never replace Room with an in-memory map.
 *
 * Expected shared contract: accept(id) atomically persists a transaction and returns
 * Accepted(transaction) or Rejected(reason); undo(transactionId) is a compare-and-set;
 * startRecovery() is idempotent; observeVisibleUsers() is Room-backed; refresh() keeps
 * tombstones; undoTransactionId exposes only the current process's newest Undo.
 * Transaction fields: stable String id, Long userId/deadlineAtEpochMillis, status,
 * and undoable. Terminal statuses remain durable for stale-snapshot masking.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal class DeleteContractFixture(
    val scheduler: TestCoroutineScheduler,
    val api: ControlledDeleteApi = ControlledDeleteApi(),
    val clock: DeleteTestClock = DeleteTestClock(scheduler),
    val path: String = newDeleteTestDatabasePath("people-delete"),
    val writesEnabled: Boolean = true,
) {
    var database: PeopleDatabase = openDatabase()
        private set
    private var owner: Job = SupervisorJob()
    // Room runs on real IO. An unconfined test dispatcher resumes Room continuations
    // on that IO thread; only actual deadline delays use the virtual scheduler.
    private var scope: CoroutineScope = CoroutineScope(owner + UnconfinedTestDispatcher(scheduler))
    private val competitors = mutableListOf<Pair<DeleteTransactionProcessor, Job>>()
    private val operationOwner = SupervisorJob()
    private val operationScope = CoroutineScope(operationOwner + Dispatchers.Default)
    private val operations = mutableListOf<Pair<String, Job>>()
    private var closed = false

    // One foreground processor per app, not per screen.
    var processor = newProcessor()
        private set

    init { processor.startRecovery() }

    private val visibleEmissionEvents = MutableStateFlow<List<List<Long>>>(emptyList())
    val visibleEmissions: List<List<Long>> get() = visibleEmissionEvents.value

    private fun openDatabase() = openDeleteTestDatabase(path)

    private fun newProcessor(forScope: CoroutineScope = scope) = DeleteTransactionProcessor(
        database = database,
        remote = api,
        scope = forScope,
        wallTimeMillis = clock::wallMillis,
        monotonicTimeMillis = clock::monotonicMillis,
        writesEnabled = writesEnabled,
    )

    /** The caller waits on a separate job so NonCancellable Room commits cannot trap runBlocking. */
    fun <T> blocking(expected: String, block: suspend () -> T): T {
        val operation = operationScope.async { block() }
        operations += expected to operation
        return try {
            // This runBlocking has no test dispatcher: its timeout uses elapsed real time.
            runBlocking { withTimeout(10_000) { operation.await() } }
        } catch (timeout: TimeoutCancellationException) {
            // Cancellation may not stop a NonCancellable Room commit; teardown joins it before close.
            operation.cancel()
            throw AssertionError(
                "Timed out after 10 s of real time waiting for $expected " +
                        "(virtual time=${scheduler.currentTime} ms, operation completed=${operation.isCompleted}): " +
                        timeout.message
            )
        } finally {
            operations.removeAll { it.second.isCompleted }
        }
    }

    private fun <T> awaitRoomState(description: String, block: suspend () -> T): T =
        blocking(description, block)

    private fun joinOutstandingOperations() {
        val outstanding = operations.filterNot { it.second.isCompleted }
        if (outstanding.isNotEmpty()) {
            blocking("previous fixture operations to finish before Room closes: ${outstanding.map { it.first }}") {
                outstanding.map { it.second }.joinAll()
            }
        }
    }

    fun seed(vararg ids: Long) {
        blocking("seed users ${ids.toList()} in Room") { database.userDao().replaceSnapshot(
            snapshot = ids.mapIndexed { index, id ->
                UserEntity(id, "User $id", "$id@example.com", firstSeenAt = 100, snapshotPosition = index)
            },
            verifiedCreated = emptyList(),
            missingCreatedIds = emptyList(),
        ) }
        api.snapshot = ids.map(::dto)
        awaitVisible(ids.toList())
    }

    /** Exercise the real upsert conflict path used by the two 404 reconciliation fixtures. */
    fun markSeededUserAsCreated(id: Long) = blocking("mark seeded user $id as created") {
        val before = database.userDao().observeUsers().first().single { it.id == id }
        assertEquals(null, before.addedAt, "Fixture must start with a remote snapshot row")
        saveCreatedUserWithDiagnostics(database, before.copy(addedAt = 100))
        val after = database.userDao().observeUsers().first().single { it.id == id }
        assertEquals(before.firstSeenAt, after.firstSeenAt, "Upsert must retain first-seen time")
        assertEquals(100L, after.addedAt, "Upsert must mark the same server ID as locally created")
    }

    fun recordVisible() = awaitRoomState("initial visible collector") {
        val started = CompletableDeferred<Unit>()
        scope.launch {
            processor.observeVisibleUsers().collect {
                visibleEmissionEvents.update { rows -> rows + listOf(it.map { user -> user.id }) }
                started.complete(Unit)
            }
        }
        started.await()
    }

    fun awaitVisible(ids: List<Long>) = awaitRoomState("visible users $ids") {
        assertEquals(ids, processor.observeVisibleUsers().first { it.map { user -> user.id } == ids }.map { it.id })
    }

    fun accept(id: Long): DeleteTransaction = blocking("accept delete for user $id") {
        (processor.accept(id) as DeleteAcceptanceResult.Accepted).transaction
    }

    fun rows(): List<DeleteTransaction> = blocking("read delete ledger rows") { database.deleteTransactionDao().all() }

    fun awaitStatus(id: String, status: DeleteStatus): DeleteTransaction = awaitRoomState("$id -> $status") {
        database.deleteTransactionDao().observeAll().first { rows ->
            rows.any { it.id == id && it.status == status }
        }.single { it.id == id }
    }

    fun awaitDeleteCalls(vararg ids: Long) = awaitRoomState("DELETE calls ${ids.toList()}") {
        val calls = api.deleteCallEvents.first { it.size >= ids.size }
        assertEquals(ids.toList(), calls)
    }

    fun awaitDeleteCallCount(count: Int) = awaitRoomState("$count DELETE calls") {
        api.deleteCallEvents.first { it.size >= count }
    }

    fun awaitFetchCalls(count: Int) = awaitRoomState("$count fetch calls") {
        api.fetchCallEvents.first { it >= count }
    }

    fun awaitGetCalls(vararg ids: Long) = awaitRoomState("GET calls ${ids.toList()}") {
        val calls = api.getCallEvents.first { it.size >= ids.size }
        assertEquals(ids.toList(), calls)
    }

    fun awaitDeleteResponses(count: Int) = awaitRoomState("$count consumed DELETE responses") {
        api.deleteResponseEvents.first { it >= count }
    }

    fun replyDelete(id: Long, result: ApiResult<Unit>) = blocking("supply DELETE response for user $id") { api.replyDelete(id, result) }

    fun undo(id: String): Boolean = blocking("undo transaction $id") { processor.undo(id) }

    fun refresh() = blocking("refresh Room snapshot") { processor.refresh() }

    fun awaitVisibleEmissions(vararg expected: List<Long>) = awaitRoomState("visible emission sequence") {
        visibleEmissionEvents.first { emissions ->
            var position = 0
            for (emission in emissions) {
                if (position < expected.size && emission == expected[position]) position++
            }
            position == expected.size
        }
    }

    fun startCompetingProcessor(): DeleteTransactionProcessor {
        val job = SupervisorJob()
        return newProcessor(CoroutineScope(job + UnconfinedTestDispatcher(scheduler))).also {
            competitors += it to job
            it.startRecovery()
        }
    }

    /** Abrupt death cancels unanswered HTTP work; it never supplies a server response. */
    fun terminate() {
        if (closed) return
        processor.shutdown()
        competitors.forEach { (processor, _) -> processor.shutdown() }
        blocking("abrupt process cancellation and worker joins") {
            owner.cancelAndJoin()
            processor.shutdownAndJoin()
            for ((processor, job) in competitors) {
                job.cancelAndJoin()
                processor.shutdownAndJoin()
            }
        }
        joinOutstandingOperations()
        blocking("Room database to close after abrupt process death") { database.close() }
        closed = true
        competitors.clear()
    }

    fun reopen(startRecovery: Boolean = true) {
        check(closed) { "Terminate the old process before reopening Room" }
        database = openDatabase()
        owner = SupervisorJob()
        scope = CoroutineScope(owner + UnconfinedTestDispatcher(scheduler))
        processor = newProcessor()
        closed = false
        if (startRecovery) processor.startRecovery()
    }

    /** Graceful disposal uses the processor's awaitable ownership boundary. */
    fun close() {
        if (closed) return
        blocking("graceful processor and owner shutdown") {
            processor.shutdownAndJoin()
            for ((processor, _) in competitors) processor.shutdownAndJoin()
            owner.cancelAndJoin()
            for ((_, job) in competitors) job.cancelAndJoin()
        }
        joinOutstandingOperations()
        blocking("Room database to close after graceful shutdown") { database.close() }
        closed = true
        competitors.clear()
        operationOwner.cancel()
    }
}

/** Used only by the three existing-ID contract scenarios; leaves the DAO and database untouched. */
internal suspend fun saveCreatedUserWithDiagnostics(database: PeopleDatabase, user: UserEntity) {
    val before = database.userDao().observeUsers().first()
    try {
        database.userDao().saveCreatedUser(user)
    } catch (failure: Exception) {
        val after = runCatching { database.userDao().observeUsers().first() }
            .fold(onSuccess = { it.toString() }, onFailure = { "read failed: $it" })
        val ledger = runCatching { database.deleteTransactionDao().all() }
            .fold(onSuccess = { it.toString() }, onFailure = { "read failed: $it" })
        val causes = generateSequence(failure as Throwable) { it.cause }
            .take(8)
            .joinToString(" -> ") { "${it::class.simpleName}: ${it.message}" }
        throw IllegalStateException(
            "saveCreatedUser failed for existing user id=${user.id}. " +
                "DAO first runs SELECT * FROM `users` WHERE `id`=?; " +
                "generated Room @Upsert then runs " +
                "INSERT INTO `users` (`id`,`name`,`email`,`firstSeenAt`,`addedAt`,`snapshotPosition`) " +
                "VALUES (?,?,?,?,?,?); on a recognized uniqueness exception it runs " +
                "UPDATE `users` SET `id`=?,`name`=?,`email`=?,`firstSeenAt`=?," +
                "`addedAt`=?,`snapshotPosition`=? WHERE `id`=?. " +
                "Inspect the preserved stack trace to identify the failing statement. " +
                "before=$before; after=$after; deleteTransactions=$ledger; " +
                "exceptionChain=$causes; stackTrace=${failure.stackTraceToString()}",
            failure,
        )
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
internal class DeleteTestClock(private val scheduler: TestCoroutineScheduler) {
    var wallOffsetMillis = 1_000_000L
    fun wallMillis(): Long = scheduler.currentTime + wallOffsetMillis
    fun monotonicMillis(): Long = scheduler.currentTime
    fun shiftWallBy(millis: Long) { wallOffsetMillis += millis }
}

internal class ControlledDeleteApi : UsersRemoteDataSource {
    var snapshot: List<UserDto> = emptyList()
    val deleteCallEvents = MutableStateFlow<List<Long>>(emptyList())
    val deleteCalls: List<Long> get() = deleteCallEvents.value
    val deleteResponseEvents = MutableStateFlow(0)
    val getCallEvents = MutableStateFlow<List<Long>>(emptyList())
    val getCalls: List<Long> get() = getCallEvents.value
    private val replyMutex = Mutex()
    private val deleteReplies = mutableMapOf<Long, CompletableDeferred<ApiResult<Unit>>>()
    val getReplies = mutableMapOf<Long, ApiResult<UserDto>>()
    var snapshotGate: CompletableDeferred<Unit>? = null
    val fetchCallEvents = MutableStateFlow(0)
    val fetchCalls: Int get() = fetchCallEvents.value

    override suspend fun fetchLastPage(): ApiResult<List<UserDto>> {
        fetchCallEvents.update { it + 1 }
        val captured = snapshot
        snapshotGate?.await()
        return ApiResult.Success(captured)
    }

    override suspend fun findUser(id: Long): ApiResult<UserDto> {
        getCallEvents.update { it + id }
        return getReplies[id] ?: ApiResult.Success(dto(id))
    }

    override suspend fun deleteUser(id: Long): ApiResult<Unit> {
        val reply = replyMutex.withLock { deleteReplies.getOrPut(id) { CompletableDeferred() } }
        deleteCallEvents.update { it + id }
        return reply.await().also { deleteResponseEvents.update { count -> count + 1 } }
    }

    override suspend fun createUser(user: NewUser): ApiResult<UserDto> = error("Create is outside this contract")

    suspend fun replyDelete(id: Long, result: ApiResult<Unit>) {
        replyMutex.withLock { deleteReplies.getOrPut(id) { CompletableDeferred() }.complete(result) }
    }

    fun absent(id: Long) { getReplies[id] = ApiResult.Failure(UserDataError.NotFound) }
    fun present(id: Long) { getReplies[id] = ApiResult.Success(dto(id)) }
}

internal fun dto(id: Long) = UserDto(id, "User $id", "$id@example.com")

internal fun assertEmissionOrder(emissions: List<List<Long>>, vararg expected: List<Long>) {
    var position = 0
    for (emission in emissions) {
        if (position < expected.size && emission == expected[position]) position++
    }
    assertEquals(expected.size, position, "Missing visible-user emission sequence: $expected; got $emissions")
}
