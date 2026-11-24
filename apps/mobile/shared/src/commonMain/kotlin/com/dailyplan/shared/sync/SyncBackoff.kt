package com.dailyplan.shared.sync

const val FOREGROUND_SYNC_INTERVAL_MILLIS = 15_000L
const val MAX_SYNC_RETRY_DELAY_MILLIS = 300_000L

fun syncRetryDelayMillis(failureCount: Int): Long {
    require(failureCount >= 0) { "failureCount must not be negative" }
    val exponent = failureCount.coerceAtMost(5)
    return (FOREGROUND_SYNC_INTERVAL_MILLIS shl exponent)
        .coerceAtMost(MAX_SYNC_RETRY_DELAY_MILLIS)
}
