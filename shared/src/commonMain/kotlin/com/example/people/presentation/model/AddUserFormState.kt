package com.example.people.presentation.model

import com.example.people.domain.model.Gender
import com.example.people.domain.model.NewUser
import com.example.people.domain.model.UserField

/** Shared, deliberately permissive validation; the server remains authoritative. */
internal object AddUserValidation {
    fun name(value: String): String? = if (value.trim().isEmpty()) "Enter a name." else null

    fun email(value: String): String? {
        val email = value.trim()
        if (email.isEmpty()) return "Enter an email address."
        val parts = email.split('@')
        val local = parts.first()
        val domain = parts.getOrNull(1).orEmpty()
        val valid = parts.size == 2 && local.isNotEmpty() &&
            !local.startsWith('.') && !local.endsWith('.') && !local.contains("..") &&
            domain.contains('.') && domain.split('.').all { label ->
                label.isNotEmpty() && !label.startsWith('-') && !label.endsWith('-') &&
                    label.all { it.isLetterOrDigit() || it == '-' }
            } &&
            email.none { it.isWhitespace() || it.isISOControl() || it in "<>(),;:\"\\" }
        return if (valid) null else "Enter a valid email address."
    }

    fun gender(value: Gender?): String? = if (value == null) "Select a gender." else null
}

data class AddUserFormState(
    val isVisible: Boolean = false,
    val name: String = "",
    val email: String = "",
    val gender: Gender? = null,
    val interacted: Set<UserField> = emptySet(),
    val errors: Map<UserField, String> = emptyMap(),
    val isSubmitting: Boolean = false,
    val message: String? = null,
    val needsLocalSave: Boolean = false,
    val outcomeUnknown: Boolean = false,
) {
    val canEdit: Boolean get() = !isSubmitting && !needsLocalSave && !outcomeUnknown

    fun validate(field: UserField): AddUserFormState {
        val error = when (field) {
            UserField.Name -> AddUserValidation.name(name)
            UserField.Email -> AddUserValidation.email(email)
            UserField.Gender -> AddUserValidation.gender(gender)
        }
        return copy(
            interacted = interacted + field,
            errors = if (error == null) errors - field else errors + (field to error),
        )
    }

    fun validateAll(): AddUserFormState = UserField.entries.fold(this) { state, field -> state.validate(field) }

    fun validatedUser(): NewUser? = if (validateAll().errors.isEmpty()) {
        gender?.let { NewUser(name.trim(), email.trim(), it) }
    } else null
}
