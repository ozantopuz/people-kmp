package com.example.people.presentation

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.people.data.delete.DeleteAcceptanceResult
import com.example.people.data.delete.DeleteFailure
import com.example.people.data.delete.DeleteProcessor
import com.example.people.data.delete.DeleteUndoTarget
import com.example.people.data.local.DeleteTransaction
import com.example.people.data.local.DeleteFailureKind
import com.example.people.data.local.DeleteStatus
import com.example.people.domain.model.NewUser
import com.example.people.domain.model.User
import com.example.people.domain.repository.CreateUserResult
import com.example.people.domain.repository.RefreshResult
import com.example.people.domain.repository.UserDataError
import com.example.people.domain.repository.UserRepository
import com.example.people.presentation.model.UserItemUiState
import com.example.people.presentation.model.UsersUiState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class DeleteUserViewModelTest {
    private val stores = mutableListOf<ViewModelStore>()

    @AfterTest
    fun tearDown() {
        stores.forEach { it.clear() }
        Dispatchers.resetMain()
    }

    @Test
    fun selectionShowsCorrectConfirmationAndCancelDoesNothing() = runTest {
        val repository = Repository()
        val processor = FakeProcessor(repository)
        val model = model(repository, processor)
        model.selectUserForDelete(item(1))
        assertEquals("User 1", model.deleteState.value.selectedUser?.name)
        model.selectUserForDelete(item(2))
        assertEquals(1L, model.deleteState.value.selectedUser?.id)
        model.cancelDelete()
        assertNull(model.deleteState.value.selectedUser)
        assertEquals(0, processor.acceptCalls)
    }

    @Test
    fun confirmDeleteRoutesThroughProcessorAndHidesUser() = runTest {
        val repository = Repository()
        val processor = FakeProcessor(repository)
        val model = model(repository, processor)
        model.selectUserForDelete(item(1))
        model.confirmDelete()
        runCurrent()
        assertEquals(1, processor.acceptCalls)
        assertEquals("tx-1", model.deleteState.value.undoTransactionId)
        assertEquals(listOf(2L), visibleIds(model))
    }

    @Test
    fun latestOnlyUndoPresentation() = runTest {
        val repository = Repository()
        val processor = FakeProcessor(repository)
        val model = model(repository, processor)

        // Accept Delete A (user 1)
        model.selectUserForDelete(item(1))
        model.confirmDelete()
        runCurrent()
        assertEquals("tx-1", model.deleteState.value.undoTransactionId)
        assertEquals(listOf(2L), visibleIds(model))

        // Accept Delete B (user 2)
        model.selectUserForDelete(item(2))
        model.confirmDelete()
        runCurrent()
        assertEquals("tx-2", model.deleteState.value.undoTransactionId)
        assertEquals(emptyList(), visibleIds(model))

        // Undo B: restores only B
        val restoredId = model.undoDelete("tx-2")
        runCurrent()
        assertEquals(2L, restoredId)
        assertEquals(listOf(2L), visibleIds(model))
        assertNull(model.deleteState.value.undoTransactionId)
    }

    @Test
    fun failedBAcceptancePreservesAUndo() = runTest {
        val repository = Repository()
        val processor = FakeProcessor(repository)
        val model = model(repository, processor)

        // Accept Delete A (user 1)
        model.selectUserForDelete(item(1))
        model.confirmDelete()
        runCurrent()
        assertEquals("tx-1", model.deleteState.value.undoTransactionId)
        assertEquals(1L, model.deleteState.value.activeUndoUserId)

        // Make processor fail for user 2
        processor.rejectUserId = 2L
        model.selectUserForDelete(item(2))
        model.confirmDelete()
        runCurrent()

        // B acceptance failed with error message
        assertTrue(model.deleteState.value.errorMessage?.isNotBlank() == true)
        // Undo for A is still active and valid
        assertEquals("tx-1", model.deleteState.value.undoTransactionId)
        assertEquals(1L, model.deleteState.value.activeUndoUserId)
    }

    @Test
    fun overlappingAcceptancesKeepEachViewportReservationUntilItsOwnCompletion() = runTest {
        val repository = Repository()
        val processor = FakeProcessor(repository)
        val aGate = CompletableDeferred<Unit>()
        val bGate = CompletableDeferred<Unit>()
        processor.acceptGates[1L] = aGate
        processor.acceptGates[2L] = bGate
        val model = model(repository, processor)

        model.selectUserForDelete(item(1))
        model.confirmDelete()
        model.selectUserForDelete(item(2))
        model.confirmDelete()
        assertEquals(setOf(1L, 2L), model.deleteState.value.acceptingUserIds)
        assertNull(model.deleteState.value.undoTransactionId)

        aGate.complete(Unit)
        runCurrent()
        assertEquals(setOf(2L), model.deleteState.value.acceptingUserIds)
        assertEquals("tx-1" to 1L, model.deleteState.value.let { it.undoTransactionId to it.activeUndoUserId })

        bGate.complete(Unit)
        runCurrent()
        assertEquals(emptySet(), model.deleteState.value.acceptingUserIds)
        assertEquals("tx-2" to 2L, model.deleteState.value.let { it.undoTransactionId to it.activeUndoUserId })
    }

    @Test
    fun overlappingBRejectionKeepsAUndoAndClearsOnlyBReservation() = runTest {
        val repository = Repository()
        val processor = FakeProcessor(repository)
        val aGate = CompletableDeferred<Unit>()
        val bGate = CompletableDeferred<Unit>()
        processor.acceptGates[1L] = aGate
        processor.acceptGates[2L] = bGate
        processor.rejectUserId = 2L
        val model = model(repository, processor)

        model.selectUserForDelete(item(1))
        model.confirmDelete()
        model.selectUserForDelete(item(2))
        model.confirmDelete()
        aGate.complete(Unit)
        runCurrent()
        bGate.complete(Unit)
        runCurrent()

        assertEquals(emptySet(), model.deleteState.value.acceptingUserIds)
        assertEquals("tx-1" to 1L, model.deleteState.value.let { it.undoTransactionId to it.activeUndoUserId })
        assertTrue(model.deleteState.value.errorMessage?.isNotBlank() == true)
        assertEquals(listOf(2L), visibleIds(model))
    }

    @Test
    fun everyPublishedUndoStateKeepsTransactionAndUserTogether() = runTest {
        val repository = Repository()
        val processor = FakeProcessor(repository)
        val model = model(repository, processor)
        val observed = mutableListOf<Pair<String?, Long?>>()
        backgroundScope.launch {
            model.deleteState.collect { observed += (it.undoTransactionId to it.activeUndoUserId) }
        }
        runCurrent()

        model.selectUserForDelete(item(1))
        model.confirmDelete()
        runCurrent()
        model.selectUserForDelete(item(2))
        model.confirmDelete()
        runCurrent()
        model.undoDelete("tx-2")
        runCurrent()

        assertTrue(observed.isNotEmpty())
        assertTrue(observed.all { (txId, userId) ->
            (txId == null && userId == null) || (userId != null && txId == "tx-$userId")
        })
    }

    @Test
    fun activeUndoUserIdTracksCurrentUndoTransactionAndClearsOnUndo() = runTest {
        val repository = Repository()
        val processor = FakeProcessor(repository)
        val model = model(repository, processor)

        assertNull(model.deleteState.value.activeUndoUserId)

        model.selectUserForDelete(item(1))
        model.confirmDelete()
        runCurrent()
        assertEquals(1L, model.deleteState.value.activeUndoUserId)

        model.selectUserForDelete(item(2))
        model.confirmDelete()
        runCurrent()
        assertEquals(2L, model.deleteState.value.activeUndoUserId)

        val restoredId = model.undoDelete("tx-2")
        runCurrent()
        assertEquals(2L, restoredId)
        assertNull(model.deleteState.value.activeUndoUserId)
    }

    @Test
    fun multiplePendingUsersCanBeDeletedConcurrently() = runTest {
        val repository = Repository(listOf(user(1), user(2), user(3)))
        val processor = FakeProcessor(repository)
        val model = model(repository, processor)

        model.selectUserForDelete(item(1))
        model.confirmDelete()
        runCurrent()

        model.selectUserForDelete(item(2))
        model.confirmDelete()
        runCurrent()

        model.selectUserForDelete(item(3))
        model.confirmDelete()
        runCurrent()

        assertEquals(3, processor.acceptCalls)
        assertEquals("tx-3", model.deleteState.value.undoTransactionId)
        assertEquals(emptyList(), visibleIds(model))
    }

    @Test
    fun staleSnackbarActionsAndExpiredUndoRejected() = runTest {
        val repository = Repository()
        val processor = FakeProcessor(repository)
        val model = model(repository, processor)

        model.selectUserForDelete(item(1))
        model.confirmDelete()
        runCurrent()

        // Stale txId undo attempt returns null and does not restore user
        val restoredId = model.undoDelete("stale-tx-id")
        runCurrent()
        assertNull(restoredId)
        assertEquals(listOf(2L), visibleIds(model))

        processor.expireUndo()
        runCurrent()
        assertNull(model.undoDelete("tx-1"))
        assertEquals(listOf(2L), visibleIds(model))

        model.selectUserForDelete(item(2))
        model.confirmDelete()
        runCurrent()
        assertNull(model.undoDelete("tx-1"))
        assertEquals("tx-2", model.deleteState.value.undoTransactionId)
        assertEquals(emptyList(), visibleIds(model))
    }

    @Test
    fun pressedUndoCompletesAfterTargetClearsDuringSuspension() = runTest {
        val repository = Repository()
        val processor = FakeProcessor(repository)
        val model = model(repository, processor)
        model.selectUserForDelete(item(1))
        model.confirmDelete()
        runCurrent()

        val restoreGate = CompletableDeferred<Unit>()
        processor.undoAfterTargetCleared = restoreGate
        val completion = CompletableDeferred<Long?>()
        backgroundScope.launch { completion.complete(model.undoDelete("tx-1")) }
        runCurrent()
        assertNull(model.deleteState.value.undoTransactionId)
        assertTrue(!completion.isCompleted)

        restoreGate.complete(Unit)
        runCurrent()
        assertEquals(1L, completion.await())
        assertEquals(listOf(1L, 2L), visibleIds(model))
    }

    @Test
    fun viewModelInitDoesNotResumeProcessor() = runTest {
        val repository = Repository()
        val processor = FakeProcessor(repository)
        model(repository, processor)
        runCurrent()
        assertEquals(0, processor.acceptCalls)
    }

    @Test
    fun permanentFailureRestoresRowAndReportsPermissionErrorOnce() = runTest {
        val repository = Repository()
        val processor = FakeProcessor(repository)
        val model = model(repository, processor)
        model.selectUserForDelete(item(1))
        model.confirmDelete()
        runCurrent()

        processor.fail(1, DeleteFailureKind.Forbidden)
        runCurrent()
        assertEquals(listOf(1L, 2L), visibleIds(model))
        assertEquals("This token does not have permission to delete this user.", model.deleteState.value.errorMessage)

        model.consumeDeleteError()
        processor.fail(1, DeleteFailureKind.Forbidden)
        runCurrent()
        assertNull(model.deleteState.value.errorMessage)
    }

    @Test
    fun simultaneousPermanentFailuresAreReportedSeparately() = runTest {
        val repository = Repository()
        val processor = FakeProcessor(repository)
        val model = model(repository, processor)
        processor.fail(1, DeleteFailureKind.Forbidden)
        processor.fail(2, DeleteFailureKind.Forbidden)
        runCurrent()

        val firstVersion = model.deleteState.value.errorVersion
        assertEquals("This token does not have permission to delete this user.", model.deleteState.value.errorMessage)
        model.consumeDeleteError()
        assertEquals(firstVersion + 1, model.deleteState.value.errorVersion)
        assertEquals("This token does not have permission to delete this user.", model.deleteState.value.errorMessage)
        model.consumeDeleteError()
        assertNull(model.deleteState.value.errorMessage)
    }

    @Test
    fun unconfirmedTerminalOutcomeIsExplained() = runTest {
        val repository = Repository()
        val processor = FakeProcessor(repository)
        val model = model(repository, processor)
        processor.reportUnconfirmed(1)
        runCurrent()

        assertEquals(
            "We couldn't confirm whether this user was deleted. Their row remains hidden on this device.",
            model.deleteState.value.errorMessage,
        )
    }

    private fun visibleIds(model: UsersViewModel): List<Long> = when (val state = model.uiState.value) {
        is UsersUiState.Success -> state.users.map { it.id }
        is UsersUiState.Empty -> emptyList()
        else -> assertIs<UsersUiState.Success>(state).users.map { it.id }
    }

    private fun TestScope.model(
        repository: Repository,
        processor: DeleteProcessor = FakeProcessor(repository),
    ): UsersViewModel {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val store = ViewModelStore().also { stores += it }
        return ViewModelProvider.create(store, viewModelFactory {
            initializer { UsersViewModel(repository, processor, nowMillis = { 1_000 }, minimumShimmerMillis = 0) }
        })[UsersViewModel::class]
    }

    private class Repository(initial: List<User> = listOf(user(1), user(2))) : UserRepository {
        val rows = MutableStateFlow(initial)
        override fun observeUsers(): Flow<List<User>> = rows
        override suspend fun refresh(): RefreshResult = RefreshResult.Success
        override suspend fun createUser(user: NewUser): CreateUserResult = error("Unused")
        override suspend fun saveCreatedUser(user: User): CreateUserResult = error("Unused")
    }

    private class FakeProcessor(val repository: Repository) : DeleteProcessor {
        var acceptCalls = 0
        var rejectUserId: Long? = null
        val acceptGates = mutableMapOf<Long, CompletableDeferred<Unit>>()
        var undoAfterTargetCleared: CompletableDeferred<Unit>? = null
        private val mutableUndoTarget = MutableStateFlow<DeleteUndoTarget?>(null)
        override val undoTarget: StateFlow<DeleteUndoTarget?> = mutableUndoTarget.asStateFlow()
        private val failureEvents = Channel<DeleteFailure>(Channel.UNLIMITED)
        private val failedTransactionIds = mutableSetOf<String>()
        override val failedDeletes: Flow<DeleteFailure> = failureEvents.receiveAsFlow()

        override suspend fun accept(userId: Long): DeleteAcceptanceResult {
            acceptCalls++
            acceptGates[userId]?.await()
            if (rejectUserId == userId) {
                return DeleteAcceptanceResult.Rejected(UserDataError.MissingToken)
            }
            val txId = "tx-$userId"
            mutableUndoTarget.value = DeleteUndoTarget(txId, userId)
            repository.rows.value = repository.rows.value.filterNot { it.id == userId }
            val transaction = DeleteTransaction(
                id = txId,
                userId = userId,
                acceptedAtEpochMs = 1000,
                deadlineAtEpochMillis = 6000,
                status = DeleteStatus.PendingUndo,
                undoable = true,
                updatedAtEpochMs = 1000,
            )
            return DeleteAcceptanceResult.Accepted(transaction)
        }

        override suspend fun undo(transactionId: String): Boolean {
            if (mutableUndoTarget.value?.transactionId != transactionId) return false
            val userId = transactionId.removePrefix("tx-").toLongOrNull() ?: return false
            mutableUndoTarget.value = null
            undoAfterTargetCleared?.await()
            repository.rows.value = (repository.rows.value + user(userId)).sortedBy { it.id }
            return true
        }

        fun expireUndo() {
            mutableUndoTarget.value = null
        }

        fun fail(userId: Long, kind: DeleteFailureKind) {
            mutableUndoTarget.value = null
            repository.rows.value = (repository.rows.value + user(userId)).distinctBy { it.id }.sortedBy { it.id }
            val failure = DeleteFailure("tx-$userId", kind)
            if (failedTransactionIds.add(failure.transactionId)) failureEvents.trySend(failure)
        }

        fun reportUnconfirmed(userId: Long) {
            failureEvents.trySend(DeleteFailure("tx-$userId", DeleteFailureKind.Unknown, outcomeUnconfirmed = true))
        }
    }

    companion object {
        private fun user(id: Long) = User(id, "User $id", "$id@example.com", 100)
        private fun item(id: Long) = UserItemUiState(id, "User $id", "$id@example.com", "Seen just now")
    }
}
