package com.envi.wispr.debug

/**
 * A take's logger (#378 D4): built from the take's id where a process first meets the take (admission in
 * main, `startCaptureForTake` in `:audio`, `startForTake` in `:vad`, `transcribeFileForTake` in `:asr`,
 * `polishRequestForTake` in `:polish`), and the only way a take-scoped owner logs. The id has no null: a
 * take-scoped line that cannot name its take cannot be written. Logcat output is the plain `DebugLogger`
 * line; the id reaches only the local log file, as a field.
 */
internal class TakeLog(val takeId: String, private val tag: String) {
    fun debug(message: String) = DebugLogger.debug(tag, message, takeId)
    fun log(message: String) = DebugLogger.log(tag, message, takeId)
    fun warn(message: String) = DebugLogger.warn(tag, message, takeId)
    fun error(message: String, throwable: Throwable? = null) = DebugLogger.error(tag, message, throwable, takeId)
    fun mark(event: String) = DebugLogger.mark(tag, event, takeId)

    /** This process's start of the take for [mark]'s timings. */
    fun startPipeline() = DebugLogger.startPipeline(takeId)

    /** The take's words at [stage], into the local log file only (never logcat, never telemetry). */
    fun words(stage: String, text: () -> String) = LocalLog.words(tag, takeId, stage, text)

    /** The same take under another tag, for a helper class the owner hands its logger to. */
    fun withTag(other: String) = TakeLog(takeId, other)
}
