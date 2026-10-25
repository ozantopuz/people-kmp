package com.example.people.lifecycle

import com.example.people.data.delete.DeleteTransactionProcessor
import com.example.people.data.local.PeopleDatabase
import io.ktor.client.HttpClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Manages app-level lifecycle events and coordinates explicit ordered shutdown
 * for background processors, databases, and network clients.
 */
class AppLifecycleManager internal constructor(
    internal val processor: DeleteTransactionProcessor,
    internal val database: PeopleDatabase,
    internal val httpClient: HttpClient,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {
    private val shutdownMutex = Mutex()
    private var isShutdown = false

    /**
     * Signaled when the app enters the foreground or becomes active.
     * Invokes `processor.resume()` to start/resume recovery idempotently.
     */
    fun onForeground() {
        if (isShutdown) return
        processor.resume()
    }

    /**
     * Signaled when the app moves to the background.
     * Note: Background transition does NOT shut down or close Room/processor,
     * so in-flight work and pending deadlines continue naturally.
     */
    fun onBackground() {
        if (isShutdown) return
        // No-op for backgrounding: preserve durable processor instance and database connection.
    }

    /**
     * Performs explicit ordered shutdown when a controlled terminal shutdown is requested:
     * 1. First, gracefully joins and stops all DeleteTransactionProcessor worker jobs.
     * 2. Only after processor jobs complete successfully, safely closes the PeopleDatabase.
     * 3. Closes the Ktor HttpClient.
     * 4. Cancels internal lifecycle scope.
     *
     * Exception policy:
     * - Does NOT proceed to close Room if processor.shutdownAndJoin() fails or is cancelled.
     * - CancellationException is rethrown to preserve coroutine cancellation hierarchy.
     * - Idempotent and safe across multiple concurrent calls.
     */
    suspend fun shutdown() {
        shutdownMutex.withLock {
            if (isShutdown) return

            // Step 1: Wait for processor jobs to finish completely before touching Room.
            // If shutdownAndJoin() throws or is cancelled, do NOT proceed to close the database.
            processor.shutdownAndJoin()

            // Step 2: Safely close database ONLY after processor work has joined.
            database.close()

            // Step 3: Close HTTP client.
            httpClient.close()

            isShutdown = true
            scope.cancel()
        }
    }
}
