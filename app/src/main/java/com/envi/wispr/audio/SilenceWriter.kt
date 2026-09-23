package com.envi.wispr.audio

import java.util.concurrent.atomic.AtomicReference

/**
 * The warm hold's write loop (#241), pure so the JVM drives the loop the silent track actually runs: write
 * silence until stopped, and report a failed write once. A negative result or a thrown write is a failure; a
 * single zero is not, since Android documents zero as a possible return of this blocking write.
 *
 * One state decides every race: only `RUNNING` to `FAILED` reports, and `stop()` moves `RUNNING` to
 * `STOPPED`, so a stop that wins silences the error its own `AudioTrack.stop()` can cause in the blocked
 * write. A failure that won first may still be reported after a stop; the hold owner's identity check makes
 * that harmless.
 */
internal class SilenceWriter(
    private val write: () -> Int,
    private val onFailed: () -> Unit,
) {
    private enum class State { RUNNING, STOPPED, FAILED }

    private val state = AtomicReference(State.RUNNING)

    /** The loop, on the caller's thread; returns when stopped or failed. */
    fun run() {
        try {
            while (state.get() == State.RUNNING) {
                if (write() < 0) {
                    fail()
                    return
                }
            }
        } catch (error: Exception) {
            fail()
        }
    }

    /** Called before the platform track is stopped, so the stop's own write error is not a failure. */
    fun stop() {
        state.compareAndSet(State.RUNNING, State.STOPPED)
    }

    private fun fail() {
        if (state.compareAndSet(State.RUNNING, State.FAILED)) onFailed()
    }
}
