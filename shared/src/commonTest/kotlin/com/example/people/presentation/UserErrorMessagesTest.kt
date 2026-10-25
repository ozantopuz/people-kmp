package com.example.people.presentation

import com.example.people.domain.repository.UserDataError
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UserErrorMessagesTest {
    @Test
    fun distinguishesConnectivityTimeoutAccessRateLimitAndServerFailures() {
        val cases = listOf(
            UserDataError.Network to "No internet connection",
            UserDataError.Timeout to "Request timed out",
            UserDataError.Unauthorized to "Access unavailable",
            UserDataError.Forbidden to "Access denied",
            UserDataError.RateLimited to "Too many requests",
            UserDataError.Server(503) to "Service unavailable",
            UserDataError.Cache to "Saved users unavailable",
        )
        for ((error, title) in cases) {
            val uiError = error.toUiError()
            assertEquals(title, uiError.title)
            assertEquals(error == UserDataError.Network, uiError.isNetworkError)
            assertTrue(uiError.message.isNotBlank())
            assertFalse(uiError.isRefreshing)
        }
    }

    @Test
    fun remainingServiceErrorsHaveSafeActionableMessages() {
        for (error in listOf(
            UserDataError.NotFound, UserDataError.Validation, UserDataError.Http(418),
            UserDataError.InvalidPagination, UserDataError.InvalidResponse, UserDataError.Unexpected,
        )) {
            val uiError = error.toUiError()
            assertTrue(uiError.message.contains("try again"))
            assertFalse(uiError.isNetworkError)
        }
    }
}
