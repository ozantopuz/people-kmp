package com.example.people.data.remote

import kotlinx.serialization.Serializable

// GoRest also returns gender/status. They are not needed for cached directory reads.
@Serializable
internal data class UserDto(val id: Long, val name: String, val email: String)

@Serializable
internal data class CreateUserDto(
    val name: String,
    val email: String,
    val gender: String,
    val status: String,
)

@Serializable
internal data class FieldErrorDto(val field: String, val message: String)
