package com.envi.wispr.asr

import ai.onnxruntime.OrtException

/** Observation callbacks have no routing return value and receive no model/options or guard-setting handle. */
internal interface AsrObserver {
    enum class Phase { CONVERSION, WINDOW, SPEECH_END, FRAME, FINALIZATION, MERGE, REPAIR, TEXT }
    enum class Stage { PREPROCESSOR, ENCODER, DECODER }
    data class Call(val model: Long, val invocation: Long, val serial: Long, val stage: Stage, val session: Int, val options: Long, val thread: Long)

    fun phase(phase: Phase, index: Int) { }
    fun entered(call: Call) { }
    fun returned(call: Call) { }
    /** Raw status at the SAME run boundary, before Kotlin cancellation mapping. Never a production logger. */
    fun failed(call: Call, failure: OrtException) { }

    companion object { val NONE: AsrObserver = object : AsrObserver { } }
}
