package com.envi.wispr.shortcuts

import android.os.Handler
import android.os.Looper
import kotlin.math.roundToInt

/** Process-local state bridge between the dictation session and accessibility overlay. */
object RecordingOverlayState {
    data class Snapshot(
        val visible: Boolean = false,
        val elapsedSeconds: Int = 0,
        /**
         * A short line to show under the timer, or null.
         *
         * null is a SENTINEL meaning "nothing to say", never "not loaded yet". The recorder hides the
         * line entirely when it is null.
         */
        val notice: String? = null,
        /**
         * How much of the microphone meter is lit, 0 for nothing and 1 for full.
         *
         * Already scaled for display by `AudioLevelScale`, never a raw amplitude. 0 is a real reading
         * meaning silence, not a "not measured yet" sentinel; the recorder draws its resting bars for it.
         */
        val level: Float = 0f,
    )

    fun interface Listener {
        fun onChanged(snapshot: Snapshot)
    }

    /** How many distinct meter positions exist. The recorder cannot show more than this many. */
    private const val LEVEL_STEPS = 32f

    private val mainHandler = Handler(Looper.getMainLooper())
    private val lock = Any()
    private var snapshot = Snapshot()
    private var listener: Listener? = null

    fun attach(listener: Listener) {
        synchronized(lock) { this.listener = listener }
        notifyListener()
    }

    fun detach(listener: Listener) {
        synchronized(lock) {
            if (this.listener === listener) this.listener = null
        }
    }

    fun show() = change { Snapshot(visible = true, elapsedSeconds = 0) }

    /** Show a line under the timer. It survives every later tick until the recorder is hidden. */
    fun showNotice(text: String) = change {
        if (!it.visible || it.notice == text) it else it.copy(notice = text)
    }

    /**
     * Publish a new microphone level, already scaled for display.
     *
     * Quantised to [LEVEL_STEPS] before the comparison. The session owner ticks about ten times a second
     * and smooths, so consecutive floats are almost never equal; without the quantisation every tick
     * would wake the recorder to move a bar by a fraction of a pixel.
     */
    fun updateLevel(level: Float) {
        val safe = if (level.isFinite()) level.coerceIn(0f, 1f) else 0f
        val quantised = (safe * LEVEL_STEPS).roundToInt().toFloat() / LEVEL_STEPS
        change { if (!it.visible || it.level == quantised) it else it.copy(level = quantised) }
    }

    fun updateElapsed(seconds: Int) {
        val safe = seconds.coerceAtLeast(0)
        change { if (!it.visible || it.elapsedSeconds == safe) it else it.copy(elapsedSeconds = safe) }
    }

    fun hide() = change { Snapshot() }

    /**
     * Read the current state, decide the next one, and commit it WITHOUT letting go of the lock
     * in between.
     *
     * Splitting that into two lock acquisitions leaves the recorder able to come back from the dead.
     * The session owner's polling thread reads a visible snapshot, the user presses Stop, `hide` writes
     * the invisible one, and then the polling thread commits the visible copy it prepared before the
     * stop. Nothing hides it again, because the take is already over. The level moves about ten times a
     * second against a stop that can land on any of them, which is the pairing that makes the window
     * worth closing rather than documenting.
     *
     * The transform must be pure: it runs under the lock, so it may not call back into a listener,
     * block, or touch anything that takes another lock.
     */
    private inline fun change(transform: (Snapshot) -> Snapshot) {
        synchronized(lock) {
            val next = transform(snapshot)
            if (next == snapshot) return
            snapshot = next
        }
        notifyListener()
    }

    /**
     * Deliver the state as it stands WHEN THE MAIN THREAD GETS THERE, never the state that was current
     * when the change was made.
     *
     * Posting a captured snapshot reopens the same defect from the delivery side: two changes can commit
     * in the right order and still be posted in the wrong one, because a thread can be descheduled
     * between releasing the lock and posting. Reading at delivery time has no such ordering to get
     * wrong, and it coalesces a burst of level changes into one redraw for free.
     */
    private fun notifyListener() {
        val target = synchronized(lock) { listener } ?: return
        mainHandler.post {
            val current = synchronized(lock) {
                // The listener that was attached when this was posted may be gone, and a NEW listener
                // must not be handed a delivery it did not ask for; `attach` posts its own.
                if (listener !== target) return@post
                snapshot
            }
            target.onChanged(current)
        }
    }
}
