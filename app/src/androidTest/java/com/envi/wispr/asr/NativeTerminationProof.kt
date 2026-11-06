package com.envi.wispr.asr

/** Qualification oracle only. JNI residency is not graph/kernel-progress proof. */
internal object NativeTerminationProof {
    const val DIAGNOSTIC = "Exiting due to terminate flag being set to true."
    data class Raw(val call: AsrObserver.Call, val returned: Boolean, val code: String?, val message: String?, val atNanos: Long)
    data class Sample(val call: AsrObserver.Call, val nativeFrame: Boolean, val uncancelled: Boolean,
        val atNanos: Long, val cancellationNanos: Long, val controlReturned: Boolean)

    fun terminated(call: AsrObserver.Call, raw: Raw?): Boolean = raw != null && raw.call == call &&
        !raw.returned && raw.code == "ORT_FAIL" && raw.message?.contains(DIAGNOSTIC) == true

    fun activeInvocation(sample: Sample?, raw: Raw?): Boolean = sample != null && sample.nativeFrame &&
        sample.uncancelled && sample.controlReturned && sample.cancellationNanos >= sample.atNanos &&
        raw != null && raw.atNanos >= sample.cancellationNanos && terminated(sample.call, raw)
}
