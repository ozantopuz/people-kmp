package com.example.people.presentation.model

data class DeleteUiState(
    val selectedUser: UserItemUiState? = null,
    val undoTransactionId: String? = null,
    val activeUndoUserId: Long? = null,
    val acceptingUserIds: Set<Long> = emptySet(),
    val errorMessage: String? = null,
    val errorVersion: Long = 0,
)
