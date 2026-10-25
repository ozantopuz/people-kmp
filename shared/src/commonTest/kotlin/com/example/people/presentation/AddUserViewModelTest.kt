package com.example.people.presentation

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.people.data.delete.DeleteAcceptanceResult
import com.example.people.data.delete.DeleteProcessor
import com.example.people.domain.model.Gender
import com.example.people.domain.model.NewUser
import com.example.people.domain.model.User
import com.example.people.domain.model.UserField
import com.example.people.domain.repository.CreateFieldError
import com.example.people.domain.repository.CreateUserResult
import com.example.people.domain.repository.RefreshResult
import com.example.people.domain.repository.UserDataError
import com.example.people.domain.repository.UserRepository
import com.example.people.presentation.model.UsersUiState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
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
class AddUserViewModelTest {
    private val stores = mutableListOf<ViewModelStore>()
    private val created = User(42, "Ada", "ada@example.com", 1_000, addedAt = 1_000)

    @AfterTest
    fun tearDown() {
        stores.forEach { it.clear() }
        Dispatchers.resetMain()
    }

    @Test
    fun openingDoesNotValidateAndEditBlurAndSubmitShowOnlyAppropriateErrors() = runTest {
        val repository = Repository()
        val model = model(repository)
        model.openAddUser()
        assertTrue(model.addForm.value.errors.isEmpty())
        model.blurField(UserField.Name)
        assertEquals(setOf(UserField.Name), model.addForm.value.errors.keys)
        model.editName("李小龍")
        model.editEmail("bad")
        assertEquals(setOf(UserField.Email), model.addForm.value.errors.keys)
        model.submitAddUser()
        assertEquals(setOf(UserField.Email, UserField.Gender), model.addForm.value.errors.keys)
        assertEquals(0, repository.posts)
    }

    @Test
    fun validSubmitTrimsValuesAndBlocksRepeatedTapsEditsAndDismissalWhileBusy() = runTest {
        val repository = Repository()
        val model = model(repository)
        fill(model)
        model.submitAddUser()
        repeat(5) { model.submitAddUser(); model.openAddUser(); model.dismissAddUser() }
        model.editName("Changed")
        assertEquals(1, repository.posts)
        assertEquals(NewUser("Ada", "ada@example.com", Gender.Female), repository.request)
        assertTrue(model.addForm.value.isSubmitting)
        assertTrue(model.addForm.value.isVisible)
        assertEquals(" Ada ", model.addForm.value.name)
        repository.response.complete(CreateUserResult.Success(created))
        runCurrent()
        assertFalse(model.addForm.value.isVisible)
        assertFalse(model.addForm.value.isSubmitting)
        assertEquals(42L, model.createdUserId.value)
        assertEquals(42L, assertIs<UsersUiState.Success>(model.uiState.value).users.first().id)
        assertEquals(1, repository.refreshes)
        model.consumeCreatedUser(7)
        assertEquals(42L, model.createdUserId.value)
        model.consumeCreatedUser(42)
        assertNull(model.createdUserId.value)
    }

    @Test
    fun missingTokenIsReportedOnlyAfterValidSubmissionAndFormStaysEditable() = runTest {
        val repository = Repository()
        val model = model(repository)
        model.openAddUser()
        model.submitAddUser()
        assertEquals(0, repository.posts)
        assertNull(model.addForm.value.message)
        fill(model)
        model.submitAddUser()
        repository.response.complete(CreateUserResult.Failure(UserDataError.MissingToken))
        runCurrent()
        assertTrue(model.addForm.value.message!!.contains("configuration"))
        assertTrue(model.addForm.value.canEdit)
        assertNull(model.createdUserId.value)
    }

    @Test
    fun validationFailureShowsFieldErrorRetainsAllValuesAndCanBeCorrected() = runTest {
        val repository = Repository()
        val model = model(repository)
        fill(model)
        model.submitAddUser()
        repository.response.complete(CreateUserResult.Failure(UserDataError.Validation, mapOf(UserField.Email to CreateFieldError.AlreadyTaken)))
        runCurrent()
        assertEquals("This email address is already in use.", model.addForm.value.errors[UserField.Email])
        assertEquals(" Ada ", model.addForm.value.name)
        assertEquals(" ada@example.com ", model.addForm.value.email)
        assertEquals(Gender.Female, model.addForm.value.gender)
        model.editEmail("other@example.com")
        assertTrue(model.addForm.value.errors.isEmpty())
    }

    @Test
    fun networkAuthRateLimitAndServerErrorsPreserveFormWithoutSuccess() = runTest {
        for (error in listOf(UserDataError.Network, UserDataError.Timeout, UserDataError.Unauthorized, UserDataError.Forbidden, UserDataError.RateLimited, UserDataError.Server(503))) {
            val repository = Repository()
            val model = model(repository)
            fill(model)
            model.submitAddUser()
            repository.response.complete(CreateUserResult.Failure(error))
            runCurrent()
            assertTrue(model.addForm.value.isVisible)
            assertFalse(model.addForm.value.isSubmitting)
            assertEquals(" Ada ", model.addForm.value.name)
            assertEquals(" ada@example.com ", model.addForm.value.email)
            assertEquals(Gender.Female, model.addForm.value.gender)
            assertTrue(model.addForm.value.message!!.isNotBlank())
            assertNull(model.createdUserId.value)
        }
    }

    @Test
    fun confirmedCreationWithFailedSaveRetainsRecoveryAcrossDismissAndNeverPostsAgain() = runTest {
        val repository = Repository()
        val model = model(repository)
        fill(model)
        model.submitAddUser()
        repository.response.complete(CreateUserResult.NeedsLocalSave(created))
        runCurrent()
        assertTrue(model.addForm.value.needsLocalSave)
        assertFalse(model.addForm.value.canEdit)
        assertNull(model.createdUserId.value)
        model.dismissAddUser()
        model.openAddUser()
        model.editEmail("different@example.com")
        assertEquals(" ada@example.com ", model.addForm.value.email)
        model.submitAddUser()
        repeat(5) { model.submitAddUser() }
        assertEquals(1, repository.saves)
        assertEquals(1, repository.posts)
        repository.saveResponse.complete(CreateUserResult.Success(created))
        runCurrent()
        assertFalse(model.addForm.value.isVisible)
        assertEquals(42L, model.createdUserId.value)
    }

    @Test
    fun unreadable201ConfirmationPreventsRepeatSubmissionAcrossReopening() = runTest {
        val repository = Repository()
        val model = model(repository)
        fill(model)
        model.submitAddUser()
        repository.response.complete(CreateUserResult.Failure(UserDataError.CreateOutcomeUnknown))
        runCurrent()
        model.dismissAddUser()
        model.openAddUser()
        model.submitAddUser()
        assertEquals(1, repository.posts)
        assertTrue(model.addForm.value.outcomeUnknown)
        assertFalse(model.addForm.value.canEdit)
    }

    @Test
    fun cancelDoesNotSubmitAndOpeningAgainStartsClean() = runTest {
        val repository = Repository()
        val model = model(repository)
        fill(model)
        model.dismissAddUser()
        model.submitAddUser()
        model.openAddUser()
        assertEquals(0, repository.posts)
        assertEquals("", model.addForm.value.name)
        assertTrue(model.addForm.value.errors.isEmpty())
    }

    @Test
    fun unexpectedFailureReleasesSubmitGateWithoutExposingPrivateDetail() = runTest {
        val repository = Repository()
        val model = model(repository)
        fill(model)
        model.submitAddUser()
        repository.response.completeExceptionally(IllegalStateException("private detail"))
        runCurrent()
        assertFalse(model.addForm.value.isSubmitting)
        assertFalse(model.addForm.value.message!!.contains("private detail"))
        assertEquals(" Ada ", model.addForm.value.name)
    }

    @Test
    fun cancelledSubmissionReleasesGateWithoutSuccessOrError() = runTest {
        val repository = Repository()
        val model = model(repository)
        fill(model)
        model.submitAddUser()
        repository.response.completeExceptionally(CancellationException("Cancelled"))
        runCurrent()
        assertFalse(model.addForm.value.isSubmitting)
        assertNull(model.addForm.value.message)
        assertNull(model.createdUserId.value)
        assertEquals(" Ada ", model.addForm.value.name)
    }

    @Test
    fun repeatedSaveFailureKeepsRecoveryStateAndDoesNotCreateAgain() = runTest {
        val repository = Repository()
        val model = model(repository)
        fill(model)
        model.submitAddUser()
        repository.response.complete(CreateUserResult.NeedsLocalSave(created))
        runCurrent()
        model.submitAddUser()
        repository.saveResponse.complete(CreateUserResult.NeedsLocalSave(created))
        runCurrent()
        assertTrue(model.addForm.value.needsLocalSave)
        assertTrue(model.addForm.value.isVisible)
        assertFalse(model.addForm.value.isSubmitting)
        assertEquals(1, repository.posts)
        assertNull(model.createdUserId.value)
    }

    private fun fill(model: UsersViewModel) {
        model.openAddUser()
        model.editName(" Ada ")
        model.editEmail(" ada@example.com ")
        model.selectGender(Gender.Female)
    }

    private fun TestScope.model(repository: UserRepository): UsersViewModel {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val store = ViewModelStore().also { stores += it }
        return ViewModelProvider.create(store, viewModelFactory {
            initializer { UsersViewModel(repository, NoOpDeleteProcessor(), nowMillis = { 1_000 }, minimumShimmerMillis = 0) }
        })[UsersViewModel::class]
    }

    private class NoOpDeleteProcessor : DeleteProcessor {
        override val undoTarget: kotlinx.coroutines.flow.StateFlow<com.example.people.data.delete.DeleteUndoTarget?> = MutableStateFlow(null)
        override val failedDeletes: kotlinx.coroutines.flow.Flow<com.example.people.data.delete.DeleteFailure> =
            kotlinx.coroutines.flow.emptyFlow()
        override suspend fun accept(userId: Long): DeleteAcceptanceResult =
            DeleteAcceptanceResult.Rejected(UserDataError.Unexpected)
        override suspend fun undo(transactionId: String): Boolean = false
    }

    private class Repository : UserRepository {
        private val rows = MutableStateFlow(emptyList<User>())
        val response = CompletableDeferred<CreateUserResult>()
        val saveResponse = CompletableDeferred<CreateUserResult>()
        var posts = 0
        var saves = 0
        var refreshes = 0
        var request: NewUser? = null
        override fun observeUsers(): Flow<List<User>> = rows
        override suspend fun refresh(): RefreshResult { refreshes++; return RefreshResult.Success }
        override suspend fun createUser(user: NewUser): CreateUserResult {
            posts++
            request = user
            return response.await().also { if (it is CreateUserResult.Success) rows.value = listOf(it.user) }
        }
        override suspend fun saveCreatedUser(user: User): CreateUserResult {
            saves++
            return saveResponse.await().also { if (it is CreateUserResult.Success) rows.value = listOf(it.user) }
        }
    }
}
