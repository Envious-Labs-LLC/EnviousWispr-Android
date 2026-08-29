package com.envi.wispr.history

object HistoryRecovery {
    const val STALE_OPEN_ROW_AGE_MS = 30_000L

    fun isStale(stateChangedAtMs: Long, nowMs: Long, cutoffMs: Long = nowMs - STALE_OPEN_ROW_AGE_MS): Boolean =
        stateChangedAtMs <= cutoffMs
}
