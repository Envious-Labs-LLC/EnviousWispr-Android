package com.envi.wispr.audio

/**
 * Decides when a take is LIVE: when the chosen route is delivering sound, so the pill, the timer and the
 * file can all start at the same moment. Capture thread only; no locks, no allocation after construction.
 *
 * Measured on the S26 with AirPods Pro 3 (`bluetooth-capture-android.md`, 2026-09-18): before the link
 * opens the recorder reads pure zeros for 0.5 to 1.0 s; for about 1.2 s after that the earbuds' own
 * microphone is still muted (peaks 2 to 6); then a live, quiet microphone reads 12 to 22. So the gate
 * opens on the second consecutive 100 ms window whose peak reaches [LIVE_PEAK], which the muted phase and
 * a single click block (155 for one window) never satisfy. The gate promises SOUND, not speech: a deaf
 * link that delivers 20 to 110 opens it, by design (founder rule 2026-09-18: a quiet user is never a fault).
 *
 * A non-Bluetooth route is live on its first read: nothing changes for the phone or a wired headset.
 */
class LiveGate(private val gated: Boolean) {
    enum class State { WAITING, READY, FORCED }

    @Volatile var state: State = if (gated) State.WAITING else State.READY
        private set

    /** How many deadline misses have been spent; the service performs the reset on the first. */
    var resetsUsed: Int = 0
        private set

    private var windowPeak = 0
    private var windowSamples = 0
    private var liveWindows = 0

    /**
     * Offer one read. [admissible] is whether the OBSERVED route may count (an earbud target that Android
     * is routing to the phone must not open the gate onto the phone). Returns true exactly on the read
     * that opens the gate.
     */
    fun offer(buffer: ByteArray, bytesRead: Int, admissible: Boolean): Boolean {
        if (state != State.WAITING) return false
        var i = 0
        while (i + 1 < bytesRead) {
            val s = (buffer[i].toInt() and 0xFF) or (buffer[i + 1].toInt() shl 8)
            val a = if (s < 0) -s else s
            if (a > windowPeak) windowPeak = a
            windowSamples++
            i += PcmAudio.BYTES_PER_SAMPLE
            if (windowSamples == WINDOW_SAMPLES) {
                val live = admissible && windowPeak >= LIVE_PEAK
                liveWindows = if (live) liveWindows + 1 else 0
                windowPeak = 0
                windowSamples = 0
                if (liveWindows >= WINDOWS_REQUIRED) {
                    state = State.READY
                    return true
                }
            }
        }
        return false
    }

    /**
     * A deadline passed with the gate still waiting. Returns what the service must do: RESET once, then
     * FORCE. The gate does not force itself: the service decides whether the observed route may be
     * forced ([force]) or the take must fail instead.
     */
    fun deadlinePassed(): DeadlineAction {
        if (state != State.WAITING) return DeadlineAction.NONE
        return if (resetsUsed < MAX_RESETS) {
            resetsUsed++
            DeadlineAction.RESET
        } else {
            DeadlineAction.FORCE
        }
    }

    /** Proceed without sound. Only from WAITING. */
    fun force() {
        if (state == State.WAITING) state = State.FORCED
    }

    enum class DeadlineAction { NONE, RESET, FORCE }

    companion object {
        /** 10 of 32,767: above the muted phase (2 to 6), below a live quiet room (12 to 22). */
        const val LIVE_PEAK = 10

        /** 100 ms at 16 kHz. */
        const val WINDOW_SAMPLES = PcmAudio.SAMPLE_RATE / 10

        const val WINDOWS_REQUIRED = 2

        /** From the recorder's start, and again after the one reset. */
        const val DEADLINE_MS = 3_500L

        const val MAX_RESETS = 1
    }
}
