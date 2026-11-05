package com.envi.wispr.llama

/** Debug-only native study surface; the ordinary production entry remains unchanged. */
object S1SamplingExperiment {
    init { System.loadLibrary("s1-polish") }

    external fun generate(system: String, user: String, cap: Int, arm: Int, abortPhase: Int): String
    /** Observe/target the next ordinary JNI call without changing its baseline sampler. */
    external fun prepareOrdinary(abortPhase: Int): Boolean
    /** Mode, elapsed ns, sampling ns, accepted count, cap, observed abort, serial, seed, last token, entry (ordinary0/debug1). */
    external fun metrics(): LongArray
    external fun tokens(): IntArray
    external fun chain(): String
    /** Version, suppression population count, then arm/case/actual/auxiliary quadruples. */
    external fun contracts(): IntArray
    external fun serial(): Long
    /** Observed serial, native phase, active serial, timed-out flag. Does not take the model mutex. */
    external fun awaitNativePhase(afterSerial: Long, phase: Int, timeoutMs: Long): LongArray
}
