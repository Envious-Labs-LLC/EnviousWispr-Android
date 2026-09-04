package com.envi.wispr.shortcuts

import android.os.Handler
import android.os.Looper

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
    )

    fun interface Listener {
        fun onChanged(snapshot: Snapshot)
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val lock = Any()
    private var snapshot = Snapshot()
    private var listener: Listener? = null

    fun attach(listener: Listener) {
        val current = synchronized(lock) {
            this.listener = listener
            snapshot
        }
        mainHandler.post { listener.onChanged(current) }
    }

    fun detach(listener: Listener) {
        synchronized(lock) {
            if (this.listener === listener) this.listener = null
        }
    }

    fun show() = publish(Snapshot(visible = true, elapsedSeconds = 0))

    /** Show a line under the timer. It survives the once-a-second tick until the recorder is hidden. */
    fun showNotice(text: String) {
        val next = synchronized(lock) {
            val current = snapshot
            if (!current.visible || current.notice == text) return
            current.copy(notice = text)
        }
        publish(next)
    }

    fun updateElapsed(seconds: Int) {
        val next = synchronized(lock) {
            if (!snapshot.visible || snapshot.elapsedSeconds == seconds) return
            snapshot.copy(elapsedSeconds = seconds.coerceAtLeast(0))
        }
        publish(next)
    }

    fun hide() = publish(Snapshot())

    private fun publish(next: Snapshot) {
        val target = synchronized(lock) {
            snapshot = next
            listener
        }
        if (target != null) mainHandler.post { target.onChanged(next) }
    }
}
