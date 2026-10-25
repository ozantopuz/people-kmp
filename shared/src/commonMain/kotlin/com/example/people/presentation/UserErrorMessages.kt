package com.example.people.presentation

import com.example.people.data.local.DeleteFailureKind
import com.example.people.domain.repository.UserDataError
import com.example.people.presentation.model.UsersUiState

internal fun UserDataError.toUiError(): UsersUiState.Error = when (this) {
    UserDataError.MissingToken -> UsersUiState.Error(message = "Adding users requires a GoRest token in the local build configuration. Configure it and rebuild the app.")
    UserDataError.CreateOutcomeUnknown -> UsersUiState.Error(message = "The service accepted the user but returned an unreadable confirmation. Check the directory before adding this user again.")
    UserDataError.Network -> UsersUiState.Error(
        title = "No internet connection",
        message = "Check your connection and try again.",
        isNetworkError = true,
    )
    UserDataError.Timeout -> UsersUiState.Error(
        title = "Request timed out", message = "The server took too long to respond. Please try again.",
    )
    UserDataError.Unauthorized -> UsersUiState.Error(
        title = "Access unavailable", message = "The service could not authorize this request. Please try again later.",
    )
    UserDataError.Forbidden -> UsersUiState.Error(
        title = "Access denied", message = "The service did not allow this request.",
    )
    UserDataError.RateLimited -> UsersUiState.Error(
        title = "Too many requests", message = "Please wait a moment before trying again.",
    )
    is UserDataError.Server -> UsersUiState.Error(
        title = "Service unavailable", message = "The service is having trouble. Please try again later.",
    )
    UserDataError.Cache -> UsersUiState.Error(
        title = "Saved users unavailable", message = "Unable to read or update saved users. Please try again.",
    )
    UserDataError.InvalidPagination, UserDataError.InvalidResponse -> UsersUiState.Error(
        message = "The service returned an unexpected response. Please try again.",
    )
    UserDataError.NotFound, UserDataError.Validation, is UserDataError.Http,
    UserDataError.Unexpected -> UsersUiState.Error(
        message = "Unable to load users right now. Please try again.",
    )
}

internal fun UserDataError.toCreateMessage(): String = when (this) {
    UserDataError.Validation -> "Please check your details and try again."
    UserDataError.Network ->
        "Check your internet connection. We couldn't confirm the request; check the directory before trying again."
    UserDataError.Timeout ->
        "The request timed out. We couldn't confirm whether the user was added; check the directory before trying again."
    UserDataError.Unauthorized -> "Your GoRest token could not be authorized. Check the local build configuration."
    UserDataError.Forbidden -> "This token does not have permission to add users."
    UserDataError.NotFound, is UserDataError.Http, UserDataError.Unexpected,
    UserDataError.InvalidPagination, UserDataError.InvalidResponse -> "Unable to add this user right now. Please try again later."
    else -> toUiError().message
}

internal fun UserDataError.toDeleteMessage(): String = when (this) {
    UserDataError.MissingToken -> "Deleting users requires a GoRest token in the local build configuration."
    UserDataError.Unauthorized -> "Your GoRest token could not be authorized to delete this user."
    UserDataError.Forbidden -> "This token does not have permission to delete this user."
    UserDataError.NotFound -> "This user was not found on the server. Refresh the list and try again."
    UserDataError.Network -> "Couldn't delete this user. Check your connection and try again."
    UserDataError.Timeout -> "The delete request timed out. Refresh to check whether the user still exists."
    UserDataError.RateLimited -> "Too many requests. Please wait before deleting this user."
    is UserDataError.Server -> "The service couldn't delete this user right now. Try again later."
    UserDataError.Cache -> "Couldn't save this delete request on this device. Please try again."
    else -> "Couldn't delete this user. Please try again."
}

internal fun DeleteFailureKind.toDeleteMessage(): String = when (this) {
    DeleteFailureKind.Unauthorized -> UserDataError.Unauthorized.toDeleteMessage()
    DeleteFailureKind.Forbidden -> UserDataError.Forbidden.toDeleteMessage()
    DeleteFailureKind.NotFound -> UserDataError.NotFound.toDeleteMessage()
    DeleteFailureKind.RateLimited -> UserDataError.RateLimited.toDeleteMessage()
    DeleteFailureKind.Network -> UserDataError.Network.toDeleteMessage()
    DeleteFailureKind.Timeout -> UserDataError.Timeout.toDeleteMessage()
    DeleteFailureKind.Server -> UserDataError.Server(500).toDeleteMessage()
    DeleteFailureKind.Cache -> "Couldn't update saved users after deleting this user. Refresh the directory."
    DeleteFailureKind.Unknown -> "Couldn't delete this user. Please try again."
}
