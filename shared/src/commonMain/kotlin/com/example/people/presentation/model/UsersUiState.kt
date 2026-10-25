package com.example.people.presentation.model

/**
 * Immutable feed state. Pull-to-refresh keeps visible cached users on screen.
 */
sealed interface UsersUiState {
    val isRefreshing: Boolean get() = false

    data object Loading : UsersUiState
    data class Success(
        val users: List<UserItemUiState>,
        override val isRefreshing: Boolean = false,
        val refreshError: Error? = null,
    ) : UsersUiState
    data class Empty(override val isRefreshing: Boolean = false) : UsersUiState
    data class Error(
        val message: String,
        val isNetworkError: Boolean = false,
        val title: String = "Unable to load users",
        override val isRefreshing: Boolean = false,
    ) : UsersUiState
}
