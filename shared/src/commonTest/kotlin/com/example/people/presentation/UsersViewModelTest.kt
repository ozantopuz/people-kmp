package com.example.people.presentation

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.people.data.delete.DeleteAcceptanceResult
import com.example.people.data.delete.DeleteProcessor
import com.example.people.data.delete.DeleteUndoTarget
import kotlinx.coroutines.flow.StateFlow
import com.example.people.domain.model.User
import com.example.people.domain.model.NewUser
import com.example.people.domain.repository.CreateUserResult
import com.example.people.domain.repository.RefreshResult
import com.example.people.domain.repository.UserDataError
import com.example.people.domain.repository.UserRepository
import com.example.people.presentation.model.UsersUiState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class UsersViewModelTest {
    private val stores = mutableListOf<ViewModelStore>()

    @AfterTest
    fun tearDown() {
        stores.forEach { it.clear() }
        Dispatchers.resetMain()
    }

    @Test
    fun initialLoadingThenSuccessfulRefreshUsesCacheRows() = runTest {
        val repository = FakeRepository()
        val model = createViewModel(repository)
        assertEquals(UsersUiState.Loading, model.uiState.value)
        assertEquals(1, repository.refreshCalls)

        repository.response.complete(RefreshResult.Success)
        runCurrent()

        val state = assertIs<UsersUiState.Success>(model.uiState.value)
        assertEquals(listOf(2L), state.users.map { it.id })
        assertEquals("User 2", state.users.single().name)
        assertEquals("2@example.com", state.users.single().email)
        assertEquals("Seen 5 minutes ago", state.users.single().relativeTimestamp)
        assertFalse(state.isRefreshing)
        assertNull(state.refreshError)
    }

    @Test
    fun cacheIsVisibleBeforeInitialRefreshCompletes() = runTest {
        val model = createViewModel(FakeRepository(listOf(user(1))))
        runCurrent()
        val state = assertIs<UsersUiState.Success>(model.uiState.value)
        assertEquals(1L, state.users.single().id)
        assertFalse(state.isRefreshing)
    }

    @Test
    fun successfulEmptySnapshotShowsEmptyWithoutShimmer() = runTest {
        val repository = FakeRepository().apply { nextUsers = emptyList() }
        val model = createViewModel(repository)
        repository.response.complete(RefreshResult.Success)
        runCurrent()
        assertEquals(UsersUiState.Empty(), model.uiState.value)
    }

    @Test
    fun noCacheNetworkFailureShowsRetryableError() = runTest {
        val repository = FakeRepository()
        val model = createViewModel(repository)
        repository.response.complete(RefreshResult.Failure(UserDataError.Network))
        runCurrent()
        val error = assertIs<UsersUiState.Error>(model.uiState.value)
        assertEquals("No internet connection", error.title)
        assertTrue(error.isNetworkError)
        assertFalse(error.isRefreshing)

        repository.response = CompletableDeferred()
        model.retry()
        assertEquals(UsersUiState.Loading, model.uiState.value)
        repository.response.complete(RefreshResult.Success)
        runCurrent()
        assertIs<UsersUiState.Success>(model.uiState.value)
        assertEquals(2, repository.refreshCalls)
    }

    @Test
    fun cachedRowsSurviveRefreshFailureWithNonBlockingMessage() = runTest {
        val repository = FakeRepository(listOf(user(1)))
        val model = createViewModel(repository)
        repository.response.complete(RefreshResult.Failure(UserDataError.Timeout))
        runCurrent()
        val state = assertIs<UsersUiState.Success>(model.uiState.value)
        assertEquals(1L, state.users.single().id)
        assertEquals("Request timed out", state.refreshError?.title)
        assertFalse(state.isRefreshing)
    }

    @Test
    fun pullRefreshKeepsListAndStopsProgressOnBothSuccessAndFailure() = runTest {
        val repository = FakeRepository(listOf(user(1)))
        val model = createViewModel(repository)
        repository.response.complete(RefreshResult.Success)
        runCurrent()

        for (response in listOf(RefreshResult.Success, RefreshResult.Failure(UserDataError.Network))) {
            repository.response = CompletableDeferred()
            val before = assertIs<UsersUiState.Success>(model.uiState.value).users
            model.refresh()
            val during = assertIs<UsersUiState.Success>(model.uiState.value)
            assertEquals(before, during.users)
            assertTrue(during.isRefreshing)
            assertNull(during.refreshError)
            repository.response.complete(response)
            runCurrent()
            assertFalse(model.uiState.value.isRefreshing)
        }
    }

    @Test
    fun repeatedRefreshCallsAreDroppedRatherThanQueued() = runTest {
        val repository = FakeRepository()
        val model = createViewModel(repository)
        repeat(10) { model.refresh() }
        assertEquals(1, repository.refreshCalls)
        repository.response.complete(RefreshResult.Success)
        runCurrent()
        assertEquals(1, repository.refreshCalls)

        repository.response = CompletableDeferred()
        model.refresh()
        repeat(10) { model.refresh() }
        assertEquals(2, repository.refreshCalls)
        repository.response.complete(RefreshResult.Success)
        runCurrent()
        assertEquals(2, repository.refreshCalls)
    }

    @Test
    fun additionalStateCollectorsDoNotCauseAnotherRequest() = runTest {
        val repository = FakeRepository()
        val model = createViewModel(repository)
        repeat(2) { backgroundScope.launch { model.uiState.collect {} } }
        runCurrent()
        assertEquals(1, repository.refreshCalls)
    }

    @Test
    fun cacheChangesRemainReactiveAfterRefreshAndUseAddedAt() = runTest {
        val repository = FakeRepository()
        val model = createViewModel(repository)
        repository.response.complete(RefreshResult.Success)
        runCurrent()
        repository.rows.value = listOf(user(9).copy(addedAt = NOW - 60_000))
        runCurrent()
        val state = assertIs<UsersUiState.Success>(model.uiState.value)
        assertEquals(9L, state.users.single().id)
        assertEquals("Added 1 minute ago", state.users.single().relativeTimestamp)
        assertEquals(1, repository.refreshCalls)
    }

    @Test
    fun refreshReformatsPersistedFirstSeenRatherThanResettingIt() = runTest {
        val original = user(1)
        val repository = FakeRepository(listOf(original)).apply { nextUsers = listOf(original) }
        var now = NOW
        val model = createViewModel(repository, nowMillis = { now })
        repository.response.complete(RefreshResult.Success)
        runCurrent()
        now += 60_000
        repository.response = CompletableDeferred()
        model.refresh()
        val state = assertIs<UsersUiState.Success>(model.uiState.value)
        assertEquals("Seen 6 minutes ago", state.users.single().relativeTimestamp)
        assertEquals(original.firstSeenAt, repository.rows.value.single().firstSeenAt)
    }

    @Test
    fun emptyFeedCanBePulledAgainWithoutReturningToInitialShimmer() = runTest {
        val repository = FakeRepository().apply { nextUsers = emptyList() }
        val model = createViewModel(repository)
        repository.response.complete(RefreshResult.Success)
        runCurrent()
        repository.response = CompletableDeferred()
        model.refresh()
        assertEquals(UsersUiState.Empty(isRefreshing = true), model.uiState.value)
        repository.response.complete(RefreshResult.Failure(UserDataError.Network))
        runCurrent()
        assertIs<UsersUiState.Error>(model.uiState.value)
        assertFalse(model.uiState.value.isRefreshing)
    }

    @Test
    fun clearingViewModelCancelsObservationAndRefreshWithoutNetworkError() = runTest {
        val repository = FakeRepository(listOf(user(1)))
        val model = createViewModel(repository)
        stores.single().clear()
        runCurrent()
        assertEquals(1, repository.cancelledRefreshes)
        assertEquals(1, repository.stoppedObservations)
        val state = assertIs<UsersUiState.Success>(model.uiState.value)
        assertFalse(state.isRefreshing)
        assertNull(state.refreshError)
        repository.rows.value = listOf(user(7))
        repository.response.complete(RefreshResult.Success)
        model.refresh()
        runCurrent()
        assertEquals(state, model.uiState.value)
        assertEquals(1, repository.refreshCalls)
    }

    @Test
    fun cancelledRequestReleasesGateAndDoesNotBecomeConnectivityError() = runTest {
        val repository = FakeRepository()
        val model = createViewModel(repository)
        repository.response.completeExceptionally(CancellationException("Cancelled"))
        runCurrent()
        val state = assertIs<UsersUiState.Error>(model.uiState.value)
        assertEquals("Loading interrupted", state.title)
        assertFalse(state.isNetworkError)
        assertFalse(state.isRefreshing)
        repository.response = CompletableDeferred()
        model.refresh()
        repository.response.complete(RefreshResult.Success)
        runCurrent()
        assertIs<UsersUiState.Success>(model.uiState.value)
        assertEquals(2, repository.refreshCalls)
    }

    @Test
    fun unexpectedFailureStopsProgressAndRetainsCache() = runTest {
        val repository = FakeRepository(listOf(user(1)))
        val model = createViewModel(repository)
        repository.response.completeExceptionally(IllegalStateException("Private detail"))
        runCurrent()
        val state = assertIs<UsersUiState.Success>(model.uiState.value)
        assertFalse(state.isRefreshing)
        assertEquals(UserDataError.Unexpected.toUiError(), state.refreshError)
        assertFalse(state.refreshError!!.message.contains("Private detail"))
    }

    @Test
    fun cacheReadFailureDoesNotLeavePermanentShimmerAndRetryResubscribes() = runTest {
        val repository = FakeRepository().apply { failObservation = true }
        val model = createViewModel(repository)
        repository.response.complete(RefreshResult.Success)
        runCurrent()
        assertEquals("Saved users unavailable", assertIs<UsersUiState.Error>(model.uiState.value).title)
        repository.failObservation = false
        repository.response = CompletableDeferred()
        model.retry()
        repository.response.complete(RefreshResult.Success)
        runCurrent()
        assertIs<UsersUiState.Success>(model.uiState.value)
    }

    @Test
    fun successfulRequestWaitsForDelayedInitialCacheReadBeforeChoosingEmpty() = runTest {
        val ready = CompletableDeferred<Unit>()
        val repository = FakeRepository().apply { observationReady = ready }
        val model = createViewModel(repository)
        repository.response.complete(RefreshResult.Success)
        runCurrent()
        assertEquals(UsersUiState.Loading, model.uiState.value)
        ready.complete(Unit)
        runCurrent()
        assertEquals(2L, assertIs<UsersUiState.Success>(model.uiState.value).users.single().id)
    }

    @Test
    fun lateCacheReadAfterNetworkFailureRevealsSavedUsersWithContextualError() = runTest {
        val ready = CompletableDeferred<Unit>()
        val repository = FakeRepository(listOf(user(1))).apply { observationReady = ready }
        val model = createViewModel(repository)
        repository.response.complete(RefreshResult.Failure(UserDataError.Network))
        runCurrent()
        assertIs<UsersUiState.Error>(model.uiState.value)
        ready.complete(Unit)
        runCurrent()
        val state = assertIs<UsersUiState.Success>(model.uiState.value)
        assertEquals(1L, state.users.single().id)
        assertEquals("No internet connection", state.refreshError?.title)
        assertFalse(state.isRefreshing)
    }

    @Test
    fun initialAndRetryFullScreenLoadsKeepShimmerForOneSecond() = runTest {
        val repository = FakeRepository()
        val model = createViewModel(repository, minimumShimmerMillis = 1_000)
        repository.response.complete(RefreshResult.Failure(UserDataError.Network))
        runCurrent()
        assertEquals(UsersUiState.Loading, model.uiState.value)
        advanceTimeBy(999)
        runCurrent()
        assertEquals(UsersUiState.Loading, model.uiState.value)
        advanceTimeBy(1)
        runCurrent()
        assertIs<UsersUiState.Error>(model.uiState.value)

        repository.response = CompletableDeferred()
        model.retry()
        assertEquals(UsersUiState.Loading, model.uiState.value)
        assertFalse(model.uiState.value.isRefreshing)
        repeat(5) { model.retry() }
        assertEquals(2, repository.refreshCalls)
        repository.response.complete(RefreshResult.Success)
        runCurrent()
        assertEquals(UsersUiState.Loading, model.uiState.value)
        advanceTimeBy(1_000)
        runCurrent()
        assertIs<UsersUiState.Success>(model.uiState.value)
    }

    @Test
    fun retryWithCachedRowsWaitsForBothRequestAndShimmerFloor() = runTest {
        val repository = FakeRepository(listOf(user(1)))
        val model = createViewModel(repository, minimumShimmerMillis = 1_000)
        repository.response.complete(RefreshResult.Success)
        advanceTimeBy(1_000)
        runCurrent()
        assertIs<UsersUiState.Success>(model.uiState.value)

        repository.response = CompletableDeferred()
        repository.nextUsers = listOf(user(3))
        model.retry()
        assertEquals(UsersUiState.Loading, model.uiState.value)
        assertFalse(model.uiState.value.isRefreshing)
        assertEquals(listOf(2L), repository.rows.value.map { it.id })

        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(UsersUiState.Loading, model.uiState.value)
        repository.response.complete(RefreshResult.Success)
        runCurrent()
        assertEquals(listOf(3L), assertIs<UsersUiState.Success>(model.uiState.value).users.map { it.id })

        repository.response = CompletableDeferred()
        repository.nextUsers = listOf(user(4))
        model.retry()
        repository.response.complete(RefreshResult.Success)
        runCurrent()
        assertEquals(UsersUiState.Loading, model.uiState.value)
        assertEquals(listOf(4L), repository.rows.value.map { it.id })
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(listOf(4L), assertIs<UsersUiState.Success>(model.uiState.value).users.map { it.id })
    }

    @Test
    fun failedRetryWithCachedRowsRestoresCacheAfterShimmerFloor() = runTest {
        val repository = FakeRepository(listOf(user(1)))
        val model = createViewModel(repository, minimumShimmerMillis = 1_000)
        repository.response.complete(RefreshResult.Success)
        advanceTimeBy(1_000)
        runCurrent()

        repository.response = CompletableDeferred()
        model.retry()
        assertEquals(UsersUiState.Loading, model.uiState.value)
        repository.response.complete(RefreshResult.Failure(UserDataError.Network))
        runCurrent()
        assertEquals(UsersUiState.Loading, model.uiState.value)
        advanceTimeBy(999)
        runCurrent()
        assertEquals(UsersUiState.Loading, model.uiState.value)
        advanceTimeBy(1)
        runCurrent()

        val state = assertIs<UsersUiState.Success>(model.uiState.value)
        assertEquals(listOf(2L), state.users.map { it.id })
        assertEquals("No internet connection", state.refreshError?.title)
        assertFalse(state.isRefreshing)
        assertEquals(listOf(2L), repository.rows.value.map { it.id })
    }

    @Test
    fun cachedRowsAppearAfterShimmerFloorWhileInitialNetworkIsStillRunning() = runTest {
        val repository = FakeRepository(listOf(user(1)))
        val model = createViewModel(repository, minimumShimmerMillis = 1_000)
        assertEquals(UsersUiState.Loading, model.uiState.value)
        advanceTimeBy(1_000)
        runCurrent()
        val state = assertIs<UsersUiState.Success>(model.uiState.value)
        assertEquals(listOf(1L), state.users.map { it.id })
        assertFalse(state.isRefreshing)
        assertEquals(1, repository.refreshCalls)
        repository.response.complete(RefreshResult.Success)
        runCurrent()
    }

    @Test
    fun cancellingDuringShimmerReleasesRefreshWithoutPublishingNetworkFailure() = runTest {
        val repository = FakeRepository()
        val model = createViewModel(repository, minimumShimmerMillis = 1_000)
        stores.single().clear()
        runCurrent()
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(1, repository.cancelledRefreshes)
        assertFalse((model.uiState.value as? UsersUiState.Error)?.isNetworkError == true)
        model.retry()
        assertEquals(1, repository.refreshCalls)
    }

    @Test
    fun pullRefreshSkipsShimmerFloorAndOnlyPullSetsIndicatorState() = runTest {
        val repository = FakeRepository(listOf(user(1)))
        val model = createViewModel(repository, minimumShimmerMillis = 1_000)
        repository.response.complete(RefreshResult.Success)
        advanceTimeBy(1_000)
        runCurrent()

        repository.response = CompletableDeferred()
        model.refresh()
        assertTrue(model.uiState.value.isRefreshing)
        repository.response.complete(RefreshResult.Success)
        runCurrent()
        assertFalse(model.uiState.value.isRefreshing)

        repository.response = CompletableDeferred()
        model.retry()
        assertEquals(UsersUiState.Loading, model.uiState.value)
        assertFalse(model.uiState.value.isRefreshing)
        repository.response.complete(RefreshResult.Success)
        runCurrent()
        assertEquals(UsersUiState.Loading, model.uiState.value)
        advanceTimeBy(1_000)
        runCurrent()
        assertIs<UsersUiState.Success>(model.uiState.value)
    }

    @Test
    fun pullFromErrorKeepsErrorVisibleAndShowsOnlyPullProgress() = runTest {
        val repository = FakeRepository()
        val model = createViewModel(repository)
        repository.response.complete(RefreshResult.Failure(UserDataError.Network))
        runCurrent()
        repository.response = CompletableDeferred()

        model.refresh()
        val pulling = assertIs<UsersUiState.Error>(model.uiState.value)
        assertTrue(pulling.isRefreshing)
        assertEquals("No internet connection", pulling.title)
        repository.response.complete(RefreshResult.Success)
        runCurrent()
        assertIs<UsersUiState.Success>(model.uiState.value)
    }

    @Test
    fun connectivityChangesGateAddWithoutRemovingCachedRows() = runTest {
        val online = MutableStateFlow(false)
        val repository = FakeRepository(listOf(user(1)))
        val model = createViewModel(repository, connectivity = online)
        runCurrent()
        assertFalse(model.isOnline.value)
        model.openAddUser()
        assertFalse(model.addForm.value.isVisible)
        assertEquals(listOf(1L), assertIs<UsersUiState.Success>(model.uiState.value).users.map { it.id })

        online.value = true
        runCurrent()
        assertTrue(model.isOnline.value)
        model.openAddUser()
        assertTrue(model.addForm.value.isVisible)
        model.dismissAddUser()
        online.value = false
        runCurrent()
        assertFalse(model.isOnline.value)
        repository.response.complete(RefreshResult.Success)
        runCurrent()
    }

    private fun TestScope.createViewModel(
        repository: UserRepository,
        nowMillis: () -> Long = { NOW },
        connectivity: Flow<Boolean> = MutableStateFlow(true),
        minimumShimmerMillis: Long = 0,
    ): UsersViewModel {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val store = ViewModelStore().also { stores += it }
        val factory = viewModelFactory {
            initializer { UsersViewModel(repository, NoOpDeleteProcessor(), nowMillis, connectivity, minimumShimmerMillis) }
        }
        return ViewModelProvider.create(store, factory)[UsersViewModel::class]
    }

    private class NoOpDeleteProcessor : DeleteProcessor {
        override val undoTarget: StateFlow<DeleteUndoTarget?> = MutableStateFlow(null)
        override val failedDeletes: Flow<com.example.people.data.delete.DeleteFailure> = kotlinx.coroutines.flow.emptyFlow()
        override suspend fun accept(userId: Long): DeleteAcceptanceResult =
            DeleteAcceptanceResult.Rejected(UserDataError.Unexpected)
        override suspend fun undo(transactionId: String): Boolean = false
    }

    private class FakeRepository(initial: List<User> = emptyList()) : UserRepository {
        val rows = MutableStateFlow(initial)
        var nextUsers = listOf(user(2))
        var response = CompletableDeferred<RefreshResult>()
        var refreshCalls = 0
        var cancelledRefreshes = 0
        var stoppedObservations = 0
        var failObservation = false
        var observationReady: CompletableDeferred<Unit>? = null

        override fun observeUsers(): Flow<List<User>> = flow {
            if (failObservation) error("Cache unavailable")
            try {
                observationReady?.await()
                emitAll(rows)
            } finally {
                stoppedObservations++
            }
        }

        override suspend fun createUser(user: NewUser): CreateUserResult = error("Unused")
        override suspend fun saveCreatedUser(user: User): CreateUserResult = error("Unused")

        override suspend fun refresh(): RefreshResult {
            refreshCalls++
            return try {
                response.await().also { if (it == RefreshResult.Success) rows.value = nextUsers }
            } catch (cancelled: CancellationException) {
                cancelledRefreshes++
                throw cancelled
            }
        }
    }

    companion object {
        private const val NOW = 1_000_000L
        private fun user(id: Long) = User(id, "User $id", "$id@example.com", NOW - 300_000)
    }
}
