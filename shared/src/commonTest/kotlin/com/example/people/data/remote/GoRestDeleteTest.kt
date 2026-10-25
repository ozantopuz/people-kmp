package com.example.people.data.remote

import com.example.people.domain.repository.UserDataError
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.network.sockets.ConnectTimeoutException
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.errors.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull

class GoRestDeleteTest {
    @Test
    fun delete404AndGet404RemainTwoObservedFailures() = runTest {
        val calls = mutableListOf<HttpMethod>()
        withApi(MockEngine { request ->
            calls += request.method
            assertEquals("/public/v2/users/42", request.url.encodedPath)
            respond("", HttpStatusCode.NotFound)
        }) { api ->
            assertEquals(ApiResult.Failure(UserDataError.NotFound), api.deleteUser(42))
            assertEquals(ApiResult.Failure(UserDataError.NotFound), api.findUser(42))
        }
        assertEquals(listOf(HttpMethod.Delete, HttpMethod.Get), calls)
    }

    @Test
    fun delete404FollowedByGet200CannotBeTreatedAsDeleted() = runTest {
        val calls = mutableListOf<HttpMethod>()
        withApi(MockEngine { request ->
            calls += request.method
            assertEquals("/public/v2/users/42", request.url.encodedPath)
            if (request.method == HttpMethod.Delete) respond("", HttpStatusCode.NotFound)
            else respond(
                """{"id":42,"name":"User 42","email":"42@example.com"}""",
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }) { api ->
            assertEquals(ApiResult.Failure(UserDataError.NotFound), api.deleteUser(42))
            assertEquals(ApiResult.Success(UserDto(42, "User 42", "42@example.com")), api.findUser(42))
        }
        assertEquals(listOf(HttpMethod.Delete, HttpMethod.Get), calls)
    }

    @Test
    fun deletesByIdWithAuthorizationAndRequiresExact204() = runTest {
        withApi(MockEngine { request ->
            assertEquals(HttpMethod.Delete, request.method)
            assertEquals("/public/v2/users/42", request.url.encodedPath)
            assertNotNull(request.headers[HttpHeaders.Authorization])
            respond("", HttpStatusCode.NoContent)
        }) { api -> assertEquals(ApiResult.Success(Unit), api.deleteUser(42)) }

        for ((status, error) in mapOf(
            200 to UserDataError.Http(200),
            201 to UserDataError.Http(201),
            401 to UserDataError.Unauthorized,
            403 to UserDataError.Forbidden,
            404 to UserDataError.NotFound,
            429 to UserDataError.RateLimited,
            503 to UserDataError.Server(503),
        )) {
            withApi(MockEngine { respond("", HttpStatusCode.fromValue(status)) }) { api ->
                assertEquals(ApiResult.Failure(error), api.deleteUser(42))
            }
        }
    }

    @Test
    fun missingTokenNeverCallsEngine() = runTest {
        withApi(MockEngine { error("No request expected") }, enabled = false) { api ->
            assertEquals(ApiResult.Failure(UserDataError.MissingToken), api.deleteUser(42))
        }
    }

    @Test
    fun delete429CarriesRetryAfterWithoutAcceptingItAsSuccess() = runTest {
        val engine = MockEngine {
            respond("", HttpStatusCode.TooManyRequests, headersOf("Retry-After", "120"))
        }
        val client = createGoRestHttpClient(engine, token = "test-only-credential")
        try {
            val api = GoRestApi(client, writesEnabled = true, nowMillis = { 10_000L })
            assertEquals(
                ApiResult.Failure(UserDataError.RateLimited, retryAfterEpochMs = 130_000L),
                api.deleteUser(42),
            )
        } finally { client.close(); engine.close() }
    }

    @Test
    fun offlineAndTimeoutAreMapped() = runTest {
        for (kind in 0..2) {
            withApi(MockEngine { request ->
                throw when (kind) {
                    0 -> IOException("Offline")
                    1 -> HttpRequestTimeoutException(request)
                    else -> ConnectTimeoutException("Timeout")
                }
            }) { api ->
                assertEquals(
                    ApiResult.Failure(if (kind == 0) UserDataError.Network else UserDataError.Timeout),
                    api.deleteUser(42),
                )
            }
        }
    }

    @Test
    fun cancellationIsNotReportedAsNetworkFailure() = runTest {
        withApi(MockEngine { throw CancellationException("Cancelled") }) { api ->
            assertFailsWith<CancellationException> { api.deleteUser(42) }
        }
    }

    private suspend fun withApi(engine: MockEngine, enabled: Boolean = true, block: suspend (GoRestApi) -> Unit) {
        val client = createGoRestHttpClient(engine, token = if (enabled) "test-only-credential" else "")
        try { block(GoRestApi(client, writesEnabled = enabled)) }
        finally { client.close(); engine.close() }
    }
}
