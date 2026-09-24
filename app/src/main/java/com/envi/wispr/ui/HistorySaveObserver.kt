package com.envi.wispr.ui

import com.envi.wispr.telemetry.AppDefect
import com.envi.wispr.telemetry.TelemetryChannels
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * The History save's diagnostics (#277, #304): one per process, owned by the application beside the History write
 * queue, on a scope nothing cancels. The Service stops right after an ordinary take hands its words over, so a watch
 * on the Service's scope died before a slow or failed save could be reported; this one lives as long as the save.
 *
 * [watchSaveBound] decides "late" exactly, from the enqueue and the save's own stamp, and reports it at most once; a
 * failure, early or late, raises its warning, breadcrumb and (when [TelemetryChannels.historySaveDefect] names one)
 * its defect once. Each sink is guarded on its own (#252): one that throws never stops the reports after it. Nothing
 * here routes, inserts or changes the take's facts, and nothing waits on it.
 */
internal class HistorySaveObserver(
    private val scope: CoroutineScope,
    /** The same time base as the slot's answer stamp: the owner's `elapsedRealtime`. */
    private val clock: () -> Long,
    private val warn: (String) -> Unit,
    /** Named as the owner's sink is, so `SentrySchemaTest`'s key scan reads every map this sends. */
    private val defectSink: (AppDefect, Map<String, Any?>) -> Unit,
    /** (category, message, data): the call below names both literally, as `SentrySchemaTest` requires. */
    private val breadcrumb: (String, String, Map<String, Any?>) -> Unit,
    /** A save slower than this raises one defect (#235). The words never wait for it; a product ceiling, not a Room time. */
    private val boundMs: Long = HISTORY_SAVE_BOUND_MS,
) {
    companion object {
        const val HISTORY_SAVE_BOUND_MS = 1_000L
    }

    /** Watches one save from [enqueuedAtMs], taken immediately before its enqueue. Returns at once. */
    fun observe(slot: SaveSlot, enqueuedAtMs: Long, takeId: String) {
        scope.launch {
            val answer = watchSaveBound(slot, enqueuedAtMs, boundMs, clock) {
                guarded { warn("History save did not answer in $boundMs ms; the words did not wait for it") }
                guarded { defectSink(AppDefect.HistorySaveTimedOut, mapOf("take_id" to takeId)) }
            }
            val outcome = answer.outcome
            if (outcome is SaveOutcome.Failed) {
                val error = outcome.cause
                guarded { warn("Unable to save transcript history: ${error.javaClass.simpleName}") }
                // Storage being full or locked is the world (a breadcrumb); a constraint or an illegal statement is
                // our schema contract (a defect). The message never leaves either way.
                guarded { breadcrumb("take", "history_save_failed", mapOf("take_id" to takeId, "error_type" to error.javaClass.simpleName)) }
                guarded { TelemetryChannels.historySaveDefect(error)?.let { defectSink(it, mapOf("take_id" to takeId)) } }
            }
        }
    }

    private inline fun guarded(report: () -> Unit) {
        try {
            report()
        } catch (error: Exception) {
            // A diagnostics sink that throws costs its own report and nothing else (#252).
            runCatching { warn("History save diagnostic not reported: ${error.javaClass.simpleName}") }
        }
    }
}
