package com.example.people.data.remote

import com.example.people.data.config.GoRestConfig
import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.accept
import io.ktor.client.request.bearerAuth
import io.ktor.http.ContentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json

internal fun createGoRestHttpClient(
    engine: HttpClientEngine,
    token: String = GoRestConfig.token,
): HttpClient = HttpClient(engine) {
    expectSuccess = false // Check exact status codes before decoding any payload.
    followRedirects = false
    install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
    install(HttpTimeout) {
        requestTimeoutMillis = 20_000
        connectTimeoutMillis = 10_000
        socketTimeoutMillis = 20_000
    }
    defaultRequest {
        url("https://gorest.co.in/public/v2/")
        accept(ContentType.Application.Json)
        token.trim().takeIf { it.isNotEmpty() }?.let { bearerAuth(it) }
    }
    // Deliberately no request/response logging plugin.
}
