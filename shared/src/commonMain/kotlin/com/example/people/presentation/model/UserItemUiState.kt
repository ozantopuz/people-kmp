package com.example.people.presentation.model

/**
 * UI representation of a single user item in the list.
 */
data class UserItemUiState(
    val id: Long,
    val name: String,
    val email: String,
    val relativeTimestamp: String,
    val initials: String = getInitials(name),
) {
    companion object {
        fun getInitials(name: String): String {
            val trimmed = name.trim()
            if (trimmed.isEmpty()) return "?"
            val parts = trimmed.split("\\s+".toRegex()).filter { it.isNotEmpty() }
            return when {
                parts.size >= 2 -> "${parts[0].first().uppercaseChar()}${parts[1].first().uppercaseChar()}"
                parts.size == 1 -> parts[0].first().uppercaseChar().toString()
                else -> "?"
            }
        }
    }
}
