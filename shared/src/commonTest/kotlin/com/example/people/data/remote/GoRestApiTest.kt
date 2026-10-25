package com.example.people.data.remote

import com.example.people.domain.repository.UserDataError
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.network.sockets.ConnectTimeoutException
import io.ktor.client.network.sockets.SocketTimeoutException
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.utils.io.errors.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull

class GoRestApiTest {
    private val users = """[{"id":42,"name":"Ada Lovelace","email":"ada@example.com","gender":"female","status":"active"}]"""

    @Test
    fun discoversLastPageAndAllowsUnauthenticatedReads() = runTest {
        val pages = mutableListOf<String?>()
        val engine = MockEngine { request ->
            assertEquals("https", request.url.protocol.name)
            assertEquals("gorest.co.in", request.url.host)
            assertEquals("/public/v2/users", request.url.encodedPath)
            assertNull(request.headers[HttpHeaders.Authorization])
            val page = request.url.parameters["page"]
            pages += page
            respond(if (page == "9") users else "[]", headers = metadata("9", page))
        }
        withApi(engine) { api ->
            val result = assertIs<ApiResult.Success<List<UserDto>>>(api.fetchLastPage())
            assertEquals(listOf(42L), result.value.map { it.id })
        }
        assertEquals(listOf<String?>("1", "9"), pages)
    }

    @Test
    fun singlePageReusesFirstResponseAndSupportsEmptySnapshot() = runTest {
        var requests = 0
        val engine = MockEngine {
            requests++
            respond("[]", headers = metadata("1"))
        }
        withApi(engine) { api ->
            assertEquals(ApiResult.Success(emptyList()), api.fetchLastPage())
        }
        assertEquals(1, requests)
    }

    @Test
    fun missingOrInvalidPageCountsFailWithoutGuessing() = runTest {
        for (count in listOf(null, "", "bad", "0", "-2", "1.5", "2147483648")) {
            var requests = 0
            val engine = MockEngine {
                requests++
                respond(users, headers = metadata(count))
            }
            withApi(engine) { api ->
                assertEquals(ApiResult.Failure(UserDataError.InvalidPagination), api.fetchLastPage())
            }
            assertEquals(1, requests)
        }
    }

    @Test
    fun malformedOrMismatchedCurrentPageFails() = runTest {
        for (current in listOf("bad", "0", "2")) {
            withApi(MockEngine { respond(users, headers = metadata("5", current)) }) { api ->
                assertEquals(ApiResult.Failure(UserDataError.InvalidPagination), api.fetchLastPage())
            }
        }
    }

    @Test
    fun missingMetadataOnLastResponseDoesNotReturnFirstPage() = runTest {
        val engine = MockEngine { request ->
            respond(users, headers = metadata(if (request.url.parameters["page"] == "1") "4" else null))
        }
        withApi(engine) { api ->
            assertEquals(ApiResult.Failure(UserDataError.InvalidPagination), api.fetchLastPage())
        }
    }

    @Test
    fun lastPageHttpFailureDoesNotFallBackToFirstPageData() = runTest {
        val engine = MockEngine { request ->
            if (request.url.parameters["page"] == "1") respond(users, headers = metadata("4"))
            else respond("", status = HttpStatusCode.Forbidden)
        }
        withApi(engine) { api ->
            assertEquals(ApiResult.Failure(UserDataError.Forbidden), api.fetchLastPage())
        }
    }

    @Test
    fun pageCountChangeTriggersBoundedRediscovery() = runTest {
        val pages = mutableListOf<String?>()
        val engine = MockEngine { request ->
            pages += request.url.parameters["page"]
            val count = if (pages.size == 1) 5 else 4
            val current = minOf(request.url.parameters["page"]!!.toInt(), count)
            respond(users, headers = metadata(count.toString(), current.toString()))
        }
        withApi(engine) { api ->
            assertIs<ApiResult.Success<*>>(api.fetchLastPage())
        }
        assertEquals(listOf<String?>("1", "5", "1", "4"), pages)
    }

    @Test
    fun successiveReadsUseCurrentLastPageMembershipAfterCreateAndDelete() = runTest {
        var phase = 0
        val requestedPages = mutableListOf<String?>()
        val pageCounts = listOf(2, 3, 2)
        val lastPageIds = listOf(20, 30, 40)
        val engine = MockEngine { request ->
            val page = request.url.parameters["page"]
            requestedPages += page
            val lastPage = pageCounts[phase]
            val payload = if (page == lastPage.toString()) {
                """[{"id":${lastPageIds[phase]},"name":"User","email":"user@example.com"}]"""
            } else "[]"
            respond(payload, headers = metadata(lastPage.toString(), page))
        }
        withApi(engine) { api ->
            for (step in pageCounts.indices) {
                phase = step
                val result = assertIs<ApiResult.Success<List<UserDto>>>(api.fetchLastPage())
                assertEquals(listOf(lastPageIds[step].toLong()), result.value.map { it.id })
            }
        }
        assertEquals(listOf<String?>("1", "2", "1", "3", "1", "2"), requestedPages)
    }

    @Test
    fun disappearingLastPageIsRediscoveredAfter404() = runTest {
        val pages = mutableListOf<String?>()
        val engine = MockEngine { request ->
            pages += request.url.parameters["page"]
            if (pages.size == 2) respond("", status = HttpStatusCode.NotFound)
            else respond(users, headers = metadata(if (pages.size == 1) "5" else "4"))
        }
        withApi(engine) { api ->
            assertIs<ApiResult.Success<*>>(api.fetchLastPage())
        }
        assertEquals(listOf<String?>("1", "5", "1", "4"), pages)
    }

    @Test
    fun repeatedlyChangingPaginationFailsAfterFourRequests() = runTest {
        var requests = 0
        val engine = MockEngine {
            requests++
            respond(users, headers = metadata(if (requests % 2 == 1) "5" else "6"))
        }
        withApi(engine) { api ->
            assertEquals(ApiResult.Failure(UserDataError.InvalidPagination), api.fetchLastPage())
        }
        assertEquals(4, requests)
    }

    @Test
    fun mapsExactHttpStatusesWithoutExposingResponseBody() = runTest {
        val cases = mapOf(
            201 to UserDataError.Http(201),
            204 to UserDataError.Http(204),
            401 to UserDataError.Unauthorized,
            403 to UserDataError.Forbidden,
            404 to UserDataError.NotFound,
            422 to UserDataError.Validation,
            429 to UserDataError.RateLimited,
            503 to UserDataError.Server(503),
        )
        for ((status, error) in cases) {
            withApi(MockEngine { respond("Untrusted server body", status = HttpStatusCode.fromValue(status)) }) { api ->
                assertEquals(ApiResult.Failure(error), api.fetchLastPage())
            }
        }
    }

    @Test
    fun mapsTransportFailure() = runTest {
        withApi(MockEngine { throw IOException("Offline") }) { api ->
            assertEquals(ApiResult.Failure(UserDataError.Network), api.fetchLastPage())
        }
    }

    @Test
    fun mapsRequestConnectAndSocketTimeouts() = runTest {
        for (kind in 0..2) {
            val engine = MockEngine { request ->
                throw when (kind) {
                    0 -> HttpRequestTimeoutException(request)
                    1 -> ConnectTimeoutException("Timeout")
                    else -> SocketTimeoutException("Timeout")
                }
            }
            withApi(engine) { api ->
                assertEquals(ApiResult.Failure(UserDataError.Timeout), api.fetchLastPage())
            }
        }
    }

    @Test
    fun unexpectedContentTypeIsInvalidResponse() = runTest {
        val headers = Headers.build {
            append(HttpHeaders.ContentType, "text/html")
            append("X-Pagination-Pages", "1")
        }
        withApi(MockEngine { respond("<html>Unexpected</html>", headers = headers) }) { api ->
            assertEquals(ApiResult.Failure(UserDataError.InvalidResponse), api.fetchLastPage())
        }
    }

    @Test
    fun malformedPayloadAndNonPositiveIdsAreRejected() = runTest {
        for (payload in listOf("not json", "{}", """[{"id":0,"name":"Ada","email":"ada@example.com"}]""")) {
            withApi(MockEngine { respond(payload, headers = metadata("1")) }) { api ->
                assertEquals(ApiResult.Failure(UserDataError.InvalidResponse), api.fetchLastPage())
            }
        }
    }

    @Test
    fun byIdUsesExactUserAndChecksStatus() = runTest {
        val engine = MockEngine { request ->
            assertEquals("/public/v2/users/42", request.url.encodedPath)
            respond(users.removePrefix("[").removeSuffix("]"), headers = metadata(null))
        }
        withApi(engine) { api ->
            assertEquals(42L, assertIs<ApiResult.Success<UserDto>>(api.findUser(42)).value.id)
        }
        withApi(MockEngine { respond("", status = HttpStatusCode.NotFound) }) { api ->
            assertEquals(ApiResult.Failure(UserDataError.NotFound), api.findUser(42))
        }
    }

    @Test
    fun byIdRejectsDifferentUser() = runTest {
        withApi(MockEngine { respond(users.removePrefix("[").removeSuffix("]"), headers = metadata(null)) }) { api ->
            assertEquals(ApiResult.Failure(UserDataError.InvalidResponse), api.findUser(7))
        }
    }

    @Test
    fun cancellationPropagates() = runTest {
        withApi(MockEngine { throw CancellationException("Cancelled") }) { api ->
            assertFailsWith<CancellationException> { api.fetchLastPage() }
        }
    }

    private fun metadata(pages: String?, current: String? = null): Headers = Headers.build {
        append(HttpHeaders.ContentType, "application/json")
        pages?.let { append("X-Pagination-Pages", it) }
        current?.let { append("X-Pagination-Page", it) }
    }

    private suspend fun withApi(engine: MockEngine, block: suspend (GoRestApi) -> Unit) {
        val client = createGoRestHttpClient(engine, token = "")
        try {
            block(GoRestApi(client, writesEnabled = false))
        } finally {
            client.close()
            engine.close()
        }
    }
}
