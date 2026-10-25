package com.example.people.data.remote

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RetryAfterTest {
    @Test fun parsesDelaySecondsAndHttpDate() {
        assertEquals(130_000L, parseRetryAfter("120", 10_000L))
        assertEquals(784_111_777_000L, parseRetryAfter("Sun, 06 Nov 1994 08:49:37 GMT", 0L))
    }

    @Test fun rejectsInvalidRetryTimes() {
        assertNull(parseRetryAfter("-5", 10_000L))
        assertNull(parseRetryAfter("nonsense", 10_000L))
        assertNull(parseRetryAfter("Sun, 32 Nov 1994 08:49:37 GMT", 0L))
    }
}
