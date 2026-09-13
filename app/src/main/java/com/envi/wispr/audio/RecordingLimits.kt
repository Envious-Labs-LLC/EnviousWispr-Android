package com.envi.wispr.audio

/**
 * How long one take may run, and everything derived from that.
 *
 * ONE owner, because the number is read in two processes that cannot see each other. `:audio` stops the
 * recording at it and `:asr` refuses a file longer than it. Held separately, raising one either truncates
 * a take the other would have accepted, or refuses a legal one while quoting a stale number at the user.
 *
 * ## Why ten minutes rather than the sixty macOS allows
 *
 * The binding constraint is the Java heap in `:asr`, not the disk. Measured on the founder's S26 Ultra on
 * 2026-09-05: `dalvik.vm.heapsize` is 512m and `dalvik.vm.heapgrowthlimit` is 256m, and
 * `AndroidManifest.xml` declares no `android:largeHeap`, so every process is held to the growth limit.
 *
 * `AsrService.transcribeFile` holds two whole-take copies at once, the bytes it reads and the floats it
 * converts them to, which is 96,000 bytes of Java heap per second of audio:
 *
 * | Cap | Java heap for the two copies |
 * |---|---|
 * | 2 minutes, the old value | 11.5 MB |
 * | 10 minutes, this value | 57.6 MB |
 * | 60 minutes, the macOS cap | 345.6 MB, over the growth limit on its own |
 *
 * Transcription is also one-shot rather than streaming, so decode time grows with the take and a much
 * longer cap would mean minutes of silent waiting as well.
 *
 * **Ten minutes is NOT the answer the founder asked for, and this is not the place that settles it.**
 * The decision on #41, 2026-09-03, is an hour where an hour is safely supportable, and explicitly says
 * to name the measurement rather than take the number from macOS parity. An hour needs the double copy
 * in `AsrService` removed and a real measurement on the phone, which is issue #117.
 *
 * **Ten minutes is PROVISIONAL and it is not a proven maximum.** The table above is the arithmetic for
 * two arrays. It says nothing about what else `:asr` holds while the speech model is loaded, and a
 * number that clears the two-array budget is not therefore the largest that clears the real one. The
 * maximum safe duration is a measurement nobody has taken; #117 takes it.
 *
 * **These are DISPLAY and POLICY numbers.** Nothing about the audio itself changes.
 */
object RecordingLimits {

    /** How long one take may run before it is stopped and transcribed. */
    const val MAX_DURATION_MS = 600_000L

    /** How long before the cap the user is told, so they can finish the sentence they are in. */
    const val WARNING_LEAD_MS = 60_000L

    /** The cap in whole minutes, for the sentences the user reads. */
    const val MAX_DURATION_MINUTES = (MAX_DURATION_MS / 60_000L).toInt()

    /**
     * How far into a take the user is warned, computed here rather than at the call site.
     *
     * A caller subtracting the lead from the cap itself can produce zero or a negative number, which
     * announces the deadline as a greeting on the first tick of every take. Doing the subtraction once
     * leaves no arithmetic for a caller to get wrong.
     *
     * **The relationships between these values are checked by `RecordingLimitsTest`, not at runtime.**
     * An earlier revision put `check` calls in an `init` block here. Every value in this object is a
     * `const val`, which the compiler inlines at the call site, so none of the reads this app makes
     * triggers that block. Something referencing the object itself would, and nothing does. A guard
     * whose arming depends on a reference nobody writes reads as protection and is not.
     */
    const val WARNING_AT_MS = MAX_DURATION_MS - WARNING_LEAD_MS

    /**
     * The largest audio file the speech engine will accept, derived from [MAX_DURATION_MS].
     *
     * It is not an independent limit and must never become one. A file longer than this is a file the
     * capture side should not have been able to produce.
     */
    const val MAX_AUDIO_BYTES: Long =
        MAX_DURATION_MS / 1000L * PcmAudio.SAMPLE_RATE * PcmAudio.BYTES_PER_SAMPLE
}
