package com.dailyplan.shared.sync

import kotlin.test.Test
import kotlin.test.assertEquals

class SyncBackoffTest {
    @Test
    fun retryDelayDoublesAndStopsAtFiveMinutes() {
        assertEquals(15_000L, syncRetryDelayMillis(0))
        assertEquals(30_000L, syncRetryDelayMillis(1))
        assertEquals(60_000L, syncRetryDelayMillis(2))
        assertEquals(120_000L, syncRetryDelayMillis(3))
        assertEquals(240_000L, syncRetryDelayMillis(4))
        assertEquals(300_000L, syncRetryDelayMillis(5))
        assertEquals(300_000L, syncRetryDelayMillis(9))
    }
}
