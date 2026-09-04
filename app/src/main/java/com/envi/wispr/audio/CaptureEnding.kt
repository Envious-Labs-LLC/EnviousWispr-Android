package com.envi.wispr.audio

import java.util.concurrent.atomic.AtomicInteger

/**
 * How a take ended, and the one place the integers behind that answer are defined.
 *
 * The integers cross a process boundary as an AIDL `int`, so they are append-only in practice: a value
 * already installed on a phone cannot be renumbered. `AudioCaptureService` re-exports them under their
 * existing names so no caller has to change.
 */
internal sealed interface CaptureEnding {

    /** The take has not ended. Not a terminal value, and never a reason to stop transcribing. */
    data object StillRunning : CaptureEnding

    /** The duration ceiling fired. */
    data object MaxDuration : CaptureEnding

    /** The user pressed stop. */
    data object Manual : CaptureEnding

    /** The user stopped speaking and the detector said so. A SUCCESS, in the same class as [Manual]. */
    data object Silence : CaptureEnding

    /**
     * Capture failed, **or the ending is a value this build does not recognise.**
     *
     * Declining to classify is an action, and the safe action is to refuse to transcribe audio whose
     * provenance is unknown rather than to guess that it was fine.
     */
    data object Failure : CaptureEnding

    /** Whether a take that ended this way should go on to be transcribed and inserted. */
    val transcribes: Boolean
        get() = when (this) {
            MaxDuration, Manual, Silence -> true
            StillRunning, Failure -> false
        }

    companion object {
        const val NONE = 0
        const val MAX_DURATION = 1
        const val MANUAL = 2
        const val ERROR = 3
        const val SILENCE = 4

        /**
         * Exhaustive by construction: every integer maps to a member, and an unknown one maps to
         * [Failure]. There is deliberately no branch that treats an unrecognised value as ordinary.
         */
        fun fromAidl(reason: Int): CaptureEnding = when (reason) {
            NONE -> StillRunning
            MAX_DURATION -> MaxDuration
            MANUAL -> Manual
            SILENCE -> Silence
            ERROR -> Failure
            else -> Failure
        }
    }
}

/**
 * The single owner of how one take ended. **The first claim wins; every later one is a no-op.**
 *
 * A take can be ended by the user, by the duration ceiling, by a capture failure, by service teardown,
 * and by the silence detector, and several of those arrive on different threads. Without one atomic
 * claim the reason a user sees depends on which thread wrote last, so a successful silence stop could be
 * reported as a failure, or a genuine failure could be reported as a clean ending and its empty audio
 * sent on to be transcribed.
 *
 * This is a separate class from the capture session so that its atomicity can be RACED in a unit test. A
 * single-threaded test cannot tell an atomic claim from a check-then-act that reads and then writes.
 */
internal class CaptureEndingClaim {

    private val value = AtomicInteger(CaptureEnding.NONE)

    /** The claimed reason, or [CaptureEnding.NONE] while the take is still running. */
    val reason: Int get() = value.get()

    val ended: Boolean get() = value.get() != CaptureEnding.NONE

    val ending: CaptureEnding get() = CaptureEnding.fromAidl(value.get())

    /**
     * @return true only for the caller that decided how this take ended. A [CaptureEnding.NONE] claim is
     *   refused outright, because "it ended, with no reason" is not a state any consumer can act on.
     */
    fun claim(reason: Int): Boolean =
        reason != CaptureEnding.NONE && value.compareAndSet(CaptureEnding.NONE, reason)
}
