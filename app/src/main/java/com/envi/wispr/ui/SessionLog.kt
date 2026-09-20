package com.envi.wispr.ui

import com.envi.wispr.debug.DebugLogger

/**
 * The session owner's diagnostics, shape only, never content (`kotlin-patterns.md` RULE:
 * no-content-in-diagnostics). [DebugLogger] calls `android.util.Log` unguarded, so a JVM test fakes this
 * (#186); the same seam `providers/ProviderPolishClient.kt` uses through its `logInfo`/`logWarn` lambdas.
 */
internal interface SessionLog {
    fun log(message: String)
    fun warn(message: String)
    fun error(message: String, throwable: Throwable? = null)
    fun mark(event: String)
    fun pipelineSummary(): String
}

/** Production: [DebugLogger] under the session owner's tag, which lives here and nowhere else. */
internal object DebugSessionLog : SessionLog {
    private const val TAG = "DictationSession"

    override fun log(message: String) = DebugLogger.log(TAG, message)
    override fun warn(message: String) = DebugLogger.warn(TAG, message)
    override fun error(message: String, throwable: Throwable?) = DebugLogger.error(TAG, message, throwable)
    override fun mark(event: String) = DebugLogger.mark(TAG, event)
    override fun pipelineSummary(): String = DebugLogger.pipelineSummary()
}
