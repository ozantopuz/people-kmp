package com.example.people.presentation

import com.example.people.domain.model.User
import kotlin.time.Clock

/** Local observation/creation time only; GoRest does not supply a creation timestamp. */
class RelativeTimeFormatter(
    private val nowMillis: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) {
    fun format(user: User): String = format(user, nowMillis())

    internal fun format(user: User, now: Long): String {
        val timestamp = user.addedAt ?: user.firstSeenAt
        // Clamp clock skew to now, and saturate subtraction rather than overflowing.
        val elapsed = if (timestamp >= now) 0L else
            (now - timestamp).takeIf { it >= 0 } ?: Long.MAX_VALUE
        val prefix = if (user.addedAt != null) "Added" else "Seen"
        val (count, unit) = when {
            elapsed < 60_000 -> return "$prefix just now"
            elapsed < 3_600_000 -> elapsed / 60_000 to "minute"
            elapsed < 86_400_000 -> elapsed / 3_600_000 to "hour"
            else -> elapsed / 86_400_000 to "day"
        }
        return "$prefix $count $unit${if (count == 1L) "" else "s"} ago"
    }
}
