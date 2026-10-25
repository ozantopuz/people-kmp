package com.example.people.presentation

import com.example.people.domain.model.User
import kotlin.test.Test
import kotlin.test.assertEquals

class RelativeTimeFormatterTest {
    @Test
    fun handlesJustNowMinuteHourAndDayBoundariesWithPluralization() {
        val now = 1_000_000_000L
        val formatter = RelativeTimeFormatter { now }
        val cases = listOf(
            0L to "just now",
            59_999L to "just now",
            60_000L to "1 minute ago",
            119_999L to "1 minute ago",
            120_000L to "2 minutes ago",
            300_000L to "5 minutes ago",
            3_599_999L to "59 minutes ago",
            3_600_000L to "1 hour ago",
            7_200_000L to "2 hours ago",
            86_399_999L to "23 hours ago",
            86_400_000L to "1 day ago",
            172_800_000L to "2 days ago",
        )
        for ((elapsed, label) in cases) {
            val remote = user(firstSeenAt = now - elapsed)
            assertEquals("Seen $label", formatter.format(remote))
            assertEquals("Added $label", formatter.format(remote.copy(addedAt = now - elapsed)))
        }
    }

    @Test
    fun addedAtTakesPrecedenceIncludingEpochZero() {
        val formatter = RelativeTimeFormatter { 300_000 }
        assertEquals("Added 5 minutes ago", formatter.format(user(299_000).copy(addedAt = 0)))
    }

    @Test
    fun futureTimesAreJustNowForBothOrigins() {
        val formatter = RelativeTimeFormatter { 1_000 }
        assertEquals("Seen just now", formatter.format(user(Long.MAX_VALUE)))
        assertEquals("Added just now", formatter.format(user(0).copy(addedAt = 2_000)))
    }

    @Test
    fun subtractionDoesNotOverflowAcrossExtremeEpochValues() {
        val formatter = RelativeTimeFormatter { Long.MAX_VALUE }
        assertEquals("Seen ${Long.MAX_VALUE / 86_400_000} days ago", formatter.format(user(Long.MIN_VALUE)))
        assertEquals("Seen just now", RelativeTimeFormatter { Long.MIN_VALUE }.format(user(Long.MAX_VALUE)))
    }

    @Test
    fun readsInjectedClockEachTimeWithoutMutatingPersistedTime() {
        var now = 59_999L
        val formatter = RelativeTimeFormatter { now }
        val user = user(0)
        assertEquals("Seen just now", formatter.format(user))
        now = 60_000
        assertEquals("Seen 1 minute ago", formatter.format(user))
        assertEquals(0L, user.firstSeenAt)
    }

    private fun user(firstSeenAt: Long) = User(1, "A Person", "a@example.com", firstSeenAt)
}
