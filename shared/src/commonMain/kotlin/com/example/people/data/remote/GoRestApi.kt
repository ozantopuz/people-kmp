package com.example.people.data.remote

import com.example.people.domain.repository.UserDataError
import com.example.people.domain.repository.CreateFieldError
import com.example.people.domain.model.NewUser
import com.example.people.domain.model.UserField
import io.ktor.client.HttpClient
import io.ktor.client.call.NoTransformationFoundException
import io.ktor.client.call.body
import io.ktor.client.network.sockets.ConnectTimeoutException
import io.ktor.client.network.sockets.SocketTimeoutException
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.request.get
import io.ktor.client.request.delete
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.client.statement.HttpResponse
import io.ktor.serialization.ContentConvertException
import io.ktor.utils.io.errors.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerializationException
import kotlin.time.Clock

internal class GoRestApi(
    private val client: HttpClient,
    private val writesEnabled: Boolean,
    private val nowMillis: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) : UsersRemoteDataSource {
    override suspend fun deleteUser(id: Long): ApiResult<Unit> {
        if (!writesEnabled) return ApiResult.Failure(UserDataError.MissingToken)
        return safely {
            val response = client.delete("users/$id")
            if (response.status.value == 204) ApiResult.Success(Unit) else failure(response)
        }
    }

    override suspend fun createUser(user: NewUser): ApiResult<UserDto> {
        if (!writesEnabled) return ApiResult.Failure(UserDataError.MissingToken)
        var receivedCreated = false
        val result = safely {
            val response = client.post("users") {
                contentType(ContentType.Application.Json)
                setBody(CreateUserDto(user.name.trim(), user.email.trim(), user.gender.apiValue, "active"))
            }
            if (response.status.value == 422) {
                // Never show arbitrary response text. Only recognize fields and a known duplicate error.
                val errors = try {
                    response.body<List<FieldErrorDto>>().mapNotNull { error ->
                        val field = when (error.field) {
                            "name" -> UserField.Name
                            "email" -> UserField.Email
                            "gender" -> UserField.Gender
                            else -> null
                        }
                        field?.let {
                            it to if (error.message == "has already been taken") CreateFieldError.AlreadyTaken
                            else CreateFieldError.Invalid
                        }
                    }.toMap()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) { emptyMap() }
                return@safely ApiResult.Failure(UserDataError.Validation, errors)
            }
            if (response.status.value != 201) return@safely failure(response)
            receivedCreated = true
            val created = response.body<UserDto>()
            if (created.id <= 0 || created.name.isBlank() || created.email.isBlank()) {
                ApiResult.Failure(UserDataError.CreateOutcomeUnknown)
            } else ApiResult.Success(created)
        }
        return if (receivedCreated && result is ApiResult.Failure) {
            ApiResult.Failure(UserDataError.CreateOutcomeUnknown)
        } else result
    }

    override suspend fun fetchLastPage(): ApiResult<List<UserDto>> = safely {
        // One rediscovery handles a page-count change during the two requests.
        // Repeated changes fail safely rather than accepting a guessed snapshot.
        repeat(2) {
            val first = client.get("users") { parameter("page", 1) }
            if (first.status.value != 200) return@safely failure(first)
            val pages = first.pageCount()
                ?: return@safely ApiResult.Failure(UserDataError.InvalidPagination)
            if (!first.hasExpectedPage(1)) return@safely ApiResult.Failure(UserDataError.InvalidPagination)
            if (pages == 1) {
                return@safely ApiResult.Success(first.validUsers())
            }

            val last = client.get("users") { parameter("page", pages) }
            if (last.status.value == 404) return@repeat // Snapshot may have shrunk.
            if (last.status.value != 200) return@safely failure(last)
            val latestPages = last.pageCount()
                ?: return@safely ApiResult.Failure(UserDataError.InvalidPagination)
            if (latestPages != pages) return@repeat
            if (!last.hasExpectedPage(pages)) return@safely ApiResult.Failure(UserDataError.InvalidPagination)
            return@safely ApiResult.Success(last.validUsers())
        }
        ApiResult.Failure(UserDataError.InvalidPagination)
    }

    override suspend fun findUser(id: Long): ApiResult<UserDto> = safely {
        val response = client.get("users/$id")
        if (response.status.value != 200) return@safely failure(response)
        val user = response.body<UserDto>()
        if (user.id != id || user.id <= 0) ApiResult.Failure(UserDataError.InvalidResponse)
        else ApiResult.Success(user)
    }

    private fun HttpResponse.pageCount(): Int? =
        headers["X-Pagination-Pages"]?.trim()?.toIntOrNull()?.takeIf { it > 0 }

    // Current-page metadata is optional, but must agree when supplied.
    private fun HttpResponse.hasExpectedPage(requestedPage: Int): Boolean =
        headers["X-Pagination-Page"]?.let { it.trim().toIntOrNull() == requestedPage } ?: true

    private suspend fun HttpResponse.validUsers(): List<UserDto> = body<List<UserDto>>().also {
        if (it.any { user -> user.id <= 0 }) throw SerializationException("Invalid user ID")
    }

    private fun failure(response: HttpResponse): ApiResult.Failure {
        val status = response.status.value
        val error = when (status) {
            401 -> UserDataError.Unauthorized
            403 -> UserDataError.Forbidden
            404 -> UserDataError.NotFound
            422 -> UserDataError.Validation
            429 -> UserDataError.RateLimited
            in 500..599 -> UserDataError.Server(status)
            else -> UserDataError.Http(status)
        }
        return ApiResult.Failure(
            error,
            retryAfterEpochMs = if (status == 429 || status == 503) {
                parseRetryAfter(response.headers["Retry-After"], nowMillis())
            } else null,
        )
    }

    private suspend fun <T> safely(block: suspend () -> ApiResult<T>): ApiResult<T> = try {
        block()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: HttpRequestTimeoutException) {
        ApiResult.Failure(UserDataError.Timeout)
    } catch (_: ConnectTimeoutException) {
        ApiResult.Failure(UserDataError.Timeout)
    } catch (_: SocketTimeoutException) {
        ApiResult.Failure(UserDataError.Timeout)
    } catch (_: SerializationException) {
        ApiResult.Failure(UserDataError.InvalidResponse)
    } catch (_: ContentConvertException) {
        ApiResult.Failure(UserDataError.InvalidResponse)
    } catch (_: NoTransformationFoundException) {
        ApiResult.Failure(UserDataError.InvalidResponse)
    } catch (_: IOException) {
        ApiResult.Failure(UserDataError.Network)
    } catch (_: Exception) {
        ApiResult.Failure(UserDataError.Unexpected)
    }
}

/** RFC 9110 Retry-After: nonnegative delay-seconds or an IMF-fixdate in GMT. */
internal fun parseRetryAfter(value: String?, nowEpochMs: Long): Long? {
    val text = value?.trim() ?: return null
    text.toLongOrNull()?.takeIf { it >= 0 }?.let { seconds ->
        val millis = if (seconds > Long.MAX_VALUE / 1_000) Long.MAX_VALUE else seconds * 1_000
        return if (millis > Long.MAX_VALUE - nowEpochMs) Long.MAX_VALUE else nowEpochMs + millis
    }
    val match = Regex("""(?:Mon|Tue|Wed|Thu|Fri|Sat|Sun), (\d{2}) (Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec) (\d{4}) (\d{2}):(\d{2}):(\d{2}) GMT""").matchEntire(text)
        ?: return null
    val day = match.groupValues[1].toInt()
    val month = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")
        .indexOf(match.groupValues[2]) + 1
    val year = match.groupValues[3].toInt()
    val hour = match.groupValues[4].toInt()
    val minute = match.groupValues[5].toInt()
    val second = match.groupValues[6].toInt()
    if (year !in 1970..9999 || hour > 23 || minute > 59 || second > 59) return null
    val leap = year % 4 == 0 && (year % 100 != 0 || year % 400 == 0)
    val monthDays = intArrayOf(31, if (leap) 29 else 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31)
    if (day !in 1..monthDays[month - 1]) return null
    var days = 0L
    for (y in 1970 until year) days += if (y % 4 == 0 && (y % 100 != 0 || y % 400 == 0)) 366 else 365
    for (m in 0 until month - 1) days += monthDays[m]
    days += day - 1
    return (((days * 24 + hour) * 60 + minute) * 60 + second) * 1_000
}
