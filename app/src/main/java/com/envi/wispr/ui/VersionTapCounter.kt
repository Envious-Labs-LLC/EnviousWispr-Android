package com.envi.wispr.ui

/**
 * The hidden Developer unlock (#378 D1): [tap] answers true on the seventh tap within [windowMs] of the
 * first, and starts over after that or after the window lapses. Pure, so a JVM test pins the count.
 */
internal class VersionTapCounter(private val clock: () -> Long, private val windowMs: Long = 3_000L) {
    private var first = 0L
    private var count = 0

    fun tap(): Boolean {
        val now = clock()
        if (count == 0 || now - first > windowMs) {
            first = now
            count = 0
        }
        count++
        if (count >= TAPS) {
            count = 0
            return true
        }
        return false
    }

    companion object {
        const val TAPS = 7
    }
}
