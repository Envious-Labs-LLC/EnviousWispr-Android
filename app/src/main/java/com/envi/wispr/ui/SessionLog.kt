package com.envi.wispr.ui

import com.envi.wispr.debug.DebugLogger
import com.envi.wispr.debug.LocalLog
import com.envi.wispr.debug.TakeLog

/**
 * The session owner's diagnostics, shape only, never content in logcat (`kotlin-patterns.md` RULE:
 * no-content-in-diagnostics). [DebugLogger] calls `android.util.Log` unguarded, so a JVM test fakes this
 * (#186); the same seam `providers/ProviderPolishClient.kt` uses through its `logInfo`/`logWarn` lambdas.
 *
 * #378: a take's lines go through the take-bound log [admitTake] returns, which carries the take id to the
 * local log file as a field; [words] is the only way the session owner hands a take's words to that file.
 */
internal interface SessionLog {
    fun log(message: String)
    fun warn(message: String)
    fun error(message: String, throwable: Throwable? = null)
    fun mark(event: String)
    fun pipelineSummary(): String

    /** The take's words at [stage], into the local log file only; the owner-level log has no take and drops them. */
    fun words(stage: String, text: () -> String) {}

    /** A log bound to [takeId], built at the take's admission; also starts this process's timing of the take. */
    fun admitTake(takeId: String): SessionLog = this
}

/** Production: [DebugLogger] under the session owner's tag, which lives here and nowhere else. */
internal object DebugSessionLog : SessionLog {
    private const val TAG = "DictationSession"

    override fun log(message: String) = DebugLogger.log(TAG, message)
    override fun warn(message: String) = DebugLogger.warn(TAG, message)
    override fun error(message: String, throwable: Throwable?) = DebugLogger.error(TAG, message, throwable)
    override fun mark(event: String) = DebugLogger.mark(TAG, event)
    override fun pipelineSummary(): String = DebugLogger.pipelineSummary()

    override fun admitTake(takeId: String): SessionLog {
        LocalLog.takeAdmitted()
        return TakeSessionLog(TakeLog(takeId, TAG)).also { it.take.startPipeline() }
    }
}

/** One take's session log: every line carries [take]'s id to the local log file. */
internal class TakeSessionLog(val take: TakeLog) : SessionLog {
    override fun log(message: String) = take.log(message)
    override fun warn(message: String) = take.warn(message)
    override fun error(message: String, throwable: Throwable?) = take.error(message, throwable)
    override fun mark(event: String) = take.mark(event)
    override fun pipelineSummary(): String = DebugLogger.pipelineSummary()
    override fun words(stage: String, text: () -> String) = take.words(stage, text)
    override fun admitTake(takeId: String): SessionLog = this
}
