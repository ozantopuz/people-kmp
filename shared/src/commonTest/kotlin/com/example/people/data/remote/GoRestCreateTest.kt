package com.example.people.data.remote

import com.example.people.domain.model.Gender
import com.example.people.domain.model.NewUser
import com.example.people.domain.model.UserField
import com.example.people.domain.repository.CreateFieldError
import com.example.people.domain.repository.UserDataError
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.network.sockets.ConnectTimeoutException
import io.ktor.client.network.sockets.SocketTimeoutException
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.utils.io.errors.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull

class GoRestCreateTest {
    private val draft = NewUser(" Ada ", " ada@example.com ", Gender.Female)
    private val response = """{"id":42,"name":"Ada","email":"ada@example.com","gender":"female","status":"active"}"""
    private val headers = Headers.build { append(HttpHeaders.ContentType, "application/json") }

    @Test
    fun postsActiveUserWithConfiguredAuthAndRequires201() = runTest {
        withApi(MockEngine { request ->
            assertEquals(HttpMethod.Post, request.method)
            assertEquals("/public/v2/users", request.url.encodedPath)
            // Only presence is inspected; credentials never appear in assertion output.
            assertNotNull(request.headers[HttpHeaders.Authorization])
            val body = Json.parseToJsonElement((request.body as TextContent).text).jsonObject
            assertEquals(setOf("name", "email", "gender", "status"), body.keys)
            assertEquals("Ada", body.getValue("name").jsonPrimitive.content)
            assertEquals("ada@example.com", body.getValue("email").jsonPrimitive.content)
            assertEquals("female", body.getValue("gender").jsonPrimitive.content)
            assertEquals("active", body.getValue("status").jsonPrimitive.content)
            respond(response, HttpStatusCode.Created, headers)
        }) { api -> assertEquals(42L, assertIs<ApiResult.Success<UserDto>>(api.createUser(draft)).value.id) }
    }

    @Test
    fun missingTokenBlocksPostBeforeEngineIsCalled() = runTest {
        withApi(MockEngine { error("Must not send") }, enabled = false) { api ->
            assertEquals(ApiResult.Failure(UserDataError.MissingToken), api.createUser(draft))
        }
    }

    @Test
    fun mapsKnown422FieldsAndNeverReturnsRawServerText() = runTest {
        val body = """[{"field":"email","message":"has already been taken"},{"field":"name","message":"private server detail"},{"field":"gender","message":"invalid"},{"field":"status","message":"invalid"}]"""
        withApi(MockEngine { respond(body, HttpStatusCode.UnprocessableEntity, headers) }) { api ->
            assertEquals(ApiResult.Failure(UserDataError.Validation, mapOf(
                UserField.Email to CreateFieldError.AlreadyTaken,
                UserField.Name to CreateFieldError.Invalid,
                UserField.Gender to CreateFieldError.Invalid,
            )), api.createUser(draft))
        }
    }

    @Test
    fun malformed422StillReportsValidationFailure() = runTest {
        withApi(MockEngine { respond("not json", HttpStatusCode.UnprocessableEntity, headers) }) { api ->
            assertEquals(ApiResult.Failure(UserDataError.Validation), api.createUser(draft))
        }
    }

    @Test
    fun malformedOrInvalidCreatedConfirmationCannotEncourageAnotherPost() = runTest {
        for (body in listOf("not json", response.replace("42", "0"), response.replace("42", "-1"), "{}")) {
            withApi(MockEngine { respond(body, HttpStatusCode.Created, headers) }) { api ->
                assertEquals(ApiResult.Failure(UserDataError.CreateOutcomeUnknown), api.createUser(draft))
            }
        }
    }

    @Test
    fun handlesExactErrorStatusesAndRejectsOtherSuccessCodes() = runTest {
        for ((status, error) in mapOf(
            200 to UserDataError.Http(200), 204 to UserDataError.Http(204),
            401 to UserDataError.Unauthorized, 403 to UserDataError.Forbidden,
            404 to UserDataError.NotFound, 429 to UserDataError.RateLimited, 503 to UserDataError.Server(503),
        )) {
            withApi(MockEngine { respond(response, HttpStatusCode.fromValue(status), headers) }) { api ->
                assertEquals(ApiResult.Failure(error), api.createUser(draft))
            }
        }
    }

    @Test
    fun mapsOfflineAndAllTimeoutTypes() = runTest {
        for (kind in 0..3) {
            withApi(MockEngine { request ->
                throw when (kind) {
                    0 -> IOException("Offline")
                    1 -> HttpRequestTimeoutException(request)
                    2 -> ConnectTimeoutException("Timeout")
                    else -> SocketTimeoutException("Timeout")
                }
            }) { api ->
                assertEquals(ApiResult.Failure(if (kind == 0) UserDataError.Network else UserDataError.Timeout), api.createUser(draft))
            }
        }
    }

    @Test
    fun cancellationPropagates() = runTest {
        withApi(MockEngine { throw CancellationException() }) { api ->
            assertFailsWith<CancellationException> { api.createUser(draft) }
        }
    }

    private suspend fun withApi(engine: MockEngine, enabled: Boolean = true, block: suspend (GoRestApi) -> Unit) {
        val client = createGoRestHttpClient(engine, token = if (enabled) "test-only-credential" else "")
        try { block(GoRestApi(client, writesEnabled = enabled)) }
        finally { client.close(); engine.close() }
    }
}
