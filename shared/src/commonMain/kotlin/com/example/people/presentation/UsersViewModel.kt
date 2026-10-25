package com.example.people.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.people.data.delete.DeleteAcceptanceResult
import com.example.people.data.delete.DeleteProcessor
import com.example.people.domain.model.Gender
import com.example.people.domain.model.User
import com.example.people.domain.model.UserField
import com.example.people.domain.repository.CreateFieldError
import com.example.people.domain.repository.CreateUserResult
import com.example.people.domain.repository.RefreshResult
import com.example.people.domain.repository.UserDataError
import com.example.people.domain.repository.UserRepository
import com.example.people.presentation.model.AddUserFormState
import com.example.people.presentation.model.DeleteUiState
import com.example.people.presentation.model.UserItemUiState
import com.example.people.presentation.model.UsersUiState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlin.time.Clock

class UsersViewModel internal constructor(
    private val repository: UserRepository,
    private val deleteProcessor: DeleteProcessor,
    private val nowMillis: () -> Long = { Clock.System.now().toEpochMilliseconds() },
    connectivity: Flow<Boolean> = flowOf(true),
    private val minimumShimmerMillis: Long = 1_000,
) : ViewModel() {
    private val formatter = RelativeTimeFormatter(nowMillis)
    private val refreshMutex = Mutex()
    private val mutableUiState = MutableStateFlow<UsersUiState>(UsersUiState.Loading)
    val uiState: StateFlow<UsersUiState> = mutableUiState.asStateFlow()
    private val mutableIsOnline = MutableStateFlow(false)
    val isOnline: StateFlow<Boolean> = mutableIsOnline.asStateFlow()
    private val mutableAddForm = MutableStateFlow(AddUserFormState())
    val addForm: StateFlow<AddUserFormState> = mutableAddForm.asStateFlow()
    private val mutableCreatedUserId = MutableStateFlow<Long?>(null)
    val createdUserId: StateFlow<Long?> = mutableCreatedUserId.asStateFlow()
    private val mutableDeleteState = MutableStateFlow(DeleteUiState())
    val deleteState: StateFlow<DeleteUiState> = mutableDeleteState.asStateFlow()
    private val queuedDeleteErrors = ArrayDeque<String>()

    private var pendingCreatedUser: User? = null

    private fun syncUndoTarget() {
        val target = deleteProcessor.undoTarget.value
        val current = mutableDeleteState.value
        if (current.undoTransactionId != target?.transactionId || current.activeUndoUserId != target?.userId) {
            mutableDeleteState.value = current.copy(
                undoTransactionId = target?.transactionId,
                activeUndoUserId = target?.userId,
            )
        }
    }

    fun selectUserForDelete(user: UserItemUiState) {
        if (!viewModelScope.isActive || mutableDeleteState.value.selectedUser != null ||
            user.id in mutableDeleteState.value.acceptingUserIds ||
            users.orEmpty().none { it.id == user.id }) return
        mutableDeleteState.value = mutableDeleteState.value.copy(selectedUser = user)
    }

    fun cancelDelete() {
        mutableDeleteState.value = mutableDeleteState.value.copy(selectedUser = null)
    }

    fun confirmDelete() {
        val selected = mutableDeleteState.value.selectedUser ?: return
        if (!viewModelScope.isActive) return
        val selectedId = selected.id
        if (users.orEmpty().none { it.id == selectedId }) {
            mutableDeleteState.value = mutableDeleteState.value.copy(selectedUser = null)
            enqueueDeleteError("This user is no longer in the directory. Refresh and try again.")
            return
        }
        if (selectedId in mutableDeleteState.value.acceptingUserIds) return
        mutableDeleteState.value = mutableDeleteState.value.copy(
            selectedUser = null,
            acceptingUserIds = mutableDeleteState.value.acceptingUserIds + selectedId,
        )
        viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) {
            val result = try {
                deleteProcessor.accept(selectedId)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                DeleteAcceptanceResult.Rejected(UserDataError.Cache)
            }
            val target = deleteProcessor.undoTarget.value
            val current = mutableDeleteState.value
            val remaining = current.acceptingUserIds - selectedId
            mutableDeleteState.value = current.copy(
                acceptingUserIds = remaining,
                undoTransactionId = target?.transactionId,
                activeUndoUserId = target?.userId,
            )
            if (result is DeleteAcceptanceResult.Rejected) enqueueDeleteError(result.reason.toDeleteMessage())
        }
    }

    suspend fun undoDelete(transactionId: String): Long? {
        val target = deleteProcessor.undoTarget.value
        if (target?.transactionId != transactionId) return null
        val userId = target.userId
        val success = deleteProcessor.undo(transactionId)
        return if (success) userId else null
    }

    fun consumeDeleteError() {
        val current = mutableDeleteState.value
        mutableDeleteState.value = if (queuedDeleteErrors.isEmpty()) current.copy(errorMessage = null)
        else current.copy(errorMessage = queuedDeleteErrors.removeFirst(), errorVersion = current.errorVersion + 1)
    }

    private fun enqueueDeleteError(message: String) {
        val current = mutableDeleteState.value
        if (current.errorMessage == null) {
            mutableDeleteState.value = current.copy(errorMessage = message, errorVersion = current.errorVersion + 1)
        } else queuedDeleteErrors.addLast(message)
    }

    fun openAddUser() {
        if (mutableIsOnline.value && !mutableAddForm.value.isSubmitting) {
            mutableAddForm.value = mutableAddForm.value.copy(isVisible = true)
        }
    }

    fun dismissAddUser() {
        val form = mutableAddForm.value
        if (form.isSubmitting) return
        mutableAddForm.value = if (form.needsLocalSave || form.outcomeUnknown) form.copy(isVisible = false)
        else AddUserFormState()
    }

    fun editName(value: String) = editForm(UserField.Name) { copy(name = value) }
    fun editEmail(value: String) = editForm(UserField.Email) { copy(email = value) }
    fun selectGender(value: Gender) = editForm(UserField.Gender) { copy(gender = value) }

    private fun editForm(field: UserField, edit: AddUserFormState.() -> AddUserFormState) {
        val form = mutableAddForm.value
        if (form.isVisible && form.canEdit) {
            mutableAddForm.value = form.edit().copy(message = null).validate(field)
        }
    }

    fun blurField(field: UserField) {
        val form = mutableAddForm.value
        if (form.isVisible && form.canEdit) mutableAddForm.value = form.validate(field)
    }

    fun consumeCreatedUser(id: Long) {
        if (mutableCreatedUserId.value == id) mutableCreatedUserId.value = null
    }

    fun submitAddUser() {
        val form = mutableAddForm.value
        if (!viewModelScope.isActive || !form.isVisible || form.isSubmitting || form.outcomeUnknown) return
        val validated = form.validateAll()
        val request = validated.validatedUser()
        mutableAddForm.value = validated
        if (request == null && pendingCreatedUser == null) return
        mutableAddForm.value = validated.copy(isSubmitting = true, message = null)
        viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                val pending = pendingCreatedUser
                val result = if (pending != null) repository.saveCreatedUser(pending)
                else repository.createUser(requireNotNull(request))
                when (result) {
                    is CreateUserResult.Success -> {
                        pendingCreatedUser = null
                        observeCache()
                        mutableAddForm.value = AddUserFormState()
                        mutableCreatedUserId.value = result.user.id
                    }
                    is CreateUserResult.NeedsLocalSave -> {
                        pendingCreatedUser = result.user
                        mutableAddForm.value = mutableAddForm.value.copy(
                            needsLocalSave = true,
                            message = "User created, but couldn't be saved on this device. Retry saving; this won't create another user.",
                        )
                    }
                    is CreateUserResult.Failure -> {
                        val fieldErrors = result.fieldErrors.mapValues { (field, error) ->
                            when {
                                field == UserField.Email && error == CreateFieldError.AlreadyTaken -> "This email address is already in use."
                                field == UserField.Name -> "Please check this name."
                                field == UserField.Email -> "Please check this email address."
                                else -> "Please select a gender again."
                            }
                        }
                        mutableAddForm.value = mutableAddForm.value.copy(
                            errors = fieldErrors,
                            message = result.error.toCreateMessage(),
                            outcomeUnknown = result.error == UserDataError.CreateOutcomeUnknown,
                        )
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                mutableAddForm.value = mutableAddForm.value.copy(message = UserDataError.Unexpected.toCreateMessage())
            } finally {
                mutableAddForm.value = mutableAddForm.value.copy(isSubmitting = false)
            }
        }
    }

    private var users: List<User>? = null
    private var refreshing = false
    private var fullScreenLoading = false
    private var retryLoading = false
    private var minimumShimmerElapsed = true
    private var refreshInFlight = false
    private var refreshFinished = false
    private var hasSuccessfulRefresh = false
    private var refreshError: UsersUiState.Error? = null
    private var cacheError: UsersUiState.Error? = null
    private var observation: Job? = null

    init {
        viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) {
            connectivity.collect { mutableIsOnline.value = it }
        }
        viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) {
            // Read the current pair, so a delayed collector never republishes an older target.
            deleteProcessor.undoTarget.collect { syncUndoTarget() }
        }
        viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) {
            deleteProcessor.failedDeletes.collect { failure ->
                enqueueDeleteError(
                    if (failure.outcomeUnconfirmed) {
                        "We couldn't confirm whether this user was deleted. Their row remains hidden on this device."
                    } else failure.kind.toDeleteMessage()
                )
            }
        }
        observeCache()
        startRefresh(fullScreen = true, pull = false)
    }

    private fun observeCache() {
        if (observation?.isActive == true) return
        observation = viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                repository.observeUsers().collect { cached ->
                    users = cached
                    cacheError = null
                    publishState()
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                cacheError = UserDataError.Cache.toUiError()
                publishState()
            }
        }
    }

    /** Only an explicit pull sets the refresh indicator state. */
    fun refresh() = startRefresh(fullScreen = false, pull = true)

    fun retry() = startRefresh(fullScreen = true, pull = false, retry = true)

    private fun startRefresh(fullScreen: Boolean, pull: Boolean, retry: Boolean = false) {
        if (!viewModelScope.isActive || !refreshMutex.tryLock()) return
        refreshing = pull
        fullScreenLoading = fullScreen
        retryLoading = retry
        minimumShimmerElapsed = !fullScreen
        refreshInFlight = true
        refreshError = if (pull && users.orEmpty().isEmpty()) {
            (mutableUiState.value as? UsersUiState.Error)?.copy(isRefreshing = false)
        } else null
        observeCache()
        publishState()
        viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) {
            val minimumShimmer = if (fullScreen) async {
                delay(minimumShimmerMillis)
                minimumShimmerElapsed = true
                publishState()
            } else null
            try {
                when (val result = repository.refresh()) {
                    RefreshResult.Success -> {
                        hasSuccessfulRefresh = true
                        refreshError = null
                    }
                    is RefreshResult.Failure -> refreshError = result.error.toUiError()
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                refreshError = UserDataError.Unexpected.toUiError()
            } finally {
                try {
                    minimumShimmer?.await()
                } finally {
                    refreshing = false
                    fullScreenLoading = false
                    retryLoading = false
                    refreshInFlight = false
                    refreshFinished = true
                    publishState()
                    refreshMutex.unlock()
                }
            }
        }
    }

    private fun publishState() {
        val error = cacheError ?: refreshError
        val cached = users.orEmpty()
        mutableUiState.value = when {
            fullScreenLoading && (!minimumShimmerElapsed || (refreshInFlight && (retryLoading || cached.isEmpty()))) ->
                UsersUiState.Loading
            cached.isNotEmpty() -> {
                val now = nowMillis()
                UsersUiState.Success(
                    users = cached.map { user ->
                        UserItemUiState(user.id, user.name, user.email, formatter.format(user, now))
                    },
                    isRefreshing = refreshing,
                    refreshError = error,
                )
            }
            error != null -> error.copy(isRefreshing = refreshing)
            refreshing && !hasSuccessfulRefresh -> UsersUiState.Loading
            !refreshFinished -> UsersUiState.Loading
            !hasSuccessfulRefresh -> UsersUiState.Error(
                title = "Loading interrupted", message = "Please try loading users again.",
                isRefreshing = refreshing,
            )
            users == null && viewModelScope.isActive -> UsersUiState.Loading
            else -> UsersUiState.Empty(isRefreshing = refreshing)
        }
    }
}
