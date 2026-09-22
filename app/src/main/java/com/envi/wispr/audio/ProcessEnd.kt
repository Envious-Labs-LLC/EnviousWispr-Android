package com.envi.wispr.audio

import java.util.concurrent.atomic.AtomicBoolean

/**
 * Ends this process after a bounded attempt to leave a note (#213), in the shape of
 * `SilenceVadService.terminateDetectorProcess`: a SIGKILL leaves no crash report, so the pending defect is
 * written first, on its own thread, and joined for at most [noteBoundMs]; a note that cannot be written in
 * time is lost, the end is never delayed past the bound. First wins: a second caller only parks.
 *
 * [park] never returns in production (the process is going away); a test injects one that throws.
 */
internal class ProcessEnd(
    private val writeNote: () -> Unit,
    private val kill: () -> Unit,
    private val noteBoundMs: Long,
    private val park: () -> Nothing,
) {
    private val started = AtomicBoolean(false)

    fun end(): Nothing {
        if (started.compareAndSet(false, true)) {
            val note = Thread(writeNote, "CaptureReleasePendingDefect")
            runCatching {
                note.start()
                note.join(noteBoundMs)
            }
            kill()
        }
        park()
    }

    companion object {
        /** The same bound the detector process gives its note. */
        const val NOTE_BOUND_MS = 500L

        fun parkUntilKilled(): Nothing {
            while (true) {
                try {
                    Thread.sleep(Long.MAX_VALUE)
                } catch (_: InterruptedException) {
                    // Keep parking: the kill is already on its way.
                }
            }
        }
    }
}
