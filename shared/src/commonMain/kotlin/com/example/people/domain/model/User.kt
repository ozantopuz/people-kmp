package com.example.people.domain.model

data class User(
    val id: Long,
    val name: String,
    val email: String,
    /** Local first observation in epoch milliseconds, never a server creation timestamp. */
    val firstSeenAt: Long,
    /** Local creation in epoch milliseconds; non-null only after a successful local create. */
    val addedAt: Long? = null,
)
