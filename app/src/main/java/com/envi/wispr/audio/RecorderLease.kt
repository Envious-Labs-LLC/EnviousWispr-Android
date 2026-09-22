package com.envi.wispr.audio

import java.util.concurrent.atomic.AtomicBoolean

/**
 * The one `AudioRecord` this process may hold, as a lease that outlives any Service instance (#115).
 *
 * `AudioCaptureService.onDestroy` no longer joins the capture thread, and that thread is the sole owner of
 * the recorder: a thread parked inside a read keeps the recorder until process termination. `session` is
 * instance-local, so a NEW Service instance Android creates in the same cached `:audio` process could
 * otherwise open a second recorder while the old thread still holds the first. The lease is acquired before
 * the recorder is created and released only after the owning thread has released it, on every path.
 */
internal class RecorderLease {
    private val held = AtomicBoolean(false)

    /** True when this caller now holds the lease; false when another recorder is still held in this process. */
    fun acquire(): Boolean = held.compareAndSet(false, true)

    fun release() {
        held.set(false)
    }

    val isHeld: Boolean get() = held.get()

    companion object {
        /** The process's lease. */
        val PROCESS = RecorderLease()
    }
}
