package com.example.people.domain.model

enum class Gender(val apiValue: String) { Male("male"), Female("female") }

enum class UserField { Name, Email, Gender }

/** Values are trimmed by shared validation before crossing the repository boundary. */
data class NewUser(val name: String, val email: String, val gender: Gender)
