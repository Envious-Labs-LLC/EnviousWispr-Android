package com.envi.wispr.audio

import java.util.concurrent.atomic.AtomicReference

/**
 * Keeps the earbud link open after a take by playing silence, so the next take starts live at once.
 *
 * Android honours an app's communication-device request only while that app has an active recording or
 * an active playback (`AudioDeviceBroker.updateCommunicationRouteClientsActivity`, Android 16); about
 * 6 s after the recorder stops the route is dropped (V9). Measured 2026-09-18 (V13): a silent
 * `VOICE_COMMUNICATION` track keeps the route and the link up for the whole window with no recording
 * running, and the take after it is live at about 120 ms instead of 0.5 to 2.3 s.
 *
 * Pure policy: the platform track and the timer are injected, so the start and every end path are
 * unit-tested. Exactly one end wins; every later end is a no-op.
 */
internal class WarmHold(
    /** The communication-device ownership carried over from the take. Released when the hold ends. */
    private val route: RouteHold,
    private val track: SilentTrack,
    private val onEnded: (reason: String) -> Unit,
    /**
     * The track's playback failed after it started (#241). Called on the track's own thread; the owner posts
     * it to the route thread and ends this exact hold as `track-failed` under the session lock.
     */
    private val onPlaybackFailed: () -> Unit,
) {
    /**
     * The platform side of the hold, injectable. `play` may throw; the hold then ends as `track-failed`. A
     * failure after it started is reported once through [play]'s `onFailed` (#241).
     */
    interface SilentTrack {
        fun play(onFailed: () -> Unit)
        fun stop()
    }

    private val ended = AtomicReference<String?>(null)

    val isActive: Boolean get() = ended.get() == null

    /** Start playing. Returns false, ended, when the track cannot play. */
    fun start(): Boolean {
        val played = runCatching { track.play { onPlaybackFailed() } }.isSuccess
        if (!played) {
            end(END_TRACK_FAILED)
            return false
        }
        return true
    }

    /**
     * Give the route to the next take instead of releasing it: the track stops, the hold is over, and
     * [route] is handed back untouched. Null when the hold already ended (the caller then resolves a
     * fresh route).
     */
    fun handOver(): RouteHold? {
        if (!ended.compareAndSet(null, END_NEW_TAKE)) return null
        runCatching { track.stop() }
        onEnded(END_NEW_TAKE)
        return route
    }

    /** End for any reason but a new take: stop the track and release the route. */
    fun end(reason: String) {
        if (!ended.compareAndSet(null, reason)) return
        runCatching { track.stop() }
        route.release()
        onEnded(reason)
    }

    companion object {
        const val HOLD_MS = 30_000L

        const val END_EXPIRED = "expired"
        const val END_DEVICE_CHANGED = "device-changed"
        const val END_DEVICE_REMOVED = "device-removed"
        const val END_NEW_TAKE = "new-take"
        const val END_DESTROYED = "destroyed"
        const val END_TRACK_FAILED = "track-failed"
    }
}
