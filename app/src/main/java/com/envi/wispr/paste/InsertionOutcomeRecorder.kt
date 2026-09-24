package com.envi.wispr.paste

import android.content.Context
import com.envi.wispr.debug.DebugLogger
import com.envi.wispr.history.Enqueued
import com.envi.wispr.history.HistoryRow
import com.envi.wispr.history.TranscriptRepository
import com.envi.wispr.history.WriteKind
import com.envi.wispr.insertion.ClipboardOutcome
import com.envi.wispr.models.ModelBootstrapApplication
import com.envi.wispr.telemetry.AnalyticsEvent
import com.envi.wispr.telemetry.InsertionResultKind
import com.envi.wispr.telemetry.InsertionRouteKind
import com.envi.wispr.telemetry.Telemetry

/**
 * One accepted insertion's ending, frozen when its attempt ends (#359): everything the History row and the
 * `insertion.terminal` event need, and nothing about the editor. [targetPackage] is a package name, never a label.
 */
internal data class InsertionEnding(
    val row: HistoryRow,
    val takeId: String?,
    val targetPackage: String?,
    val status: String,
    val result: String,
    val interrupted: Boolean,
    val clipboard: ClipboardOutcome?,
    val latencyMs: Long,
)

/**
 * Records an accepted insertion's ending (#359, issue #176 G1 D3): the History row's outcome and the one
 * `insertion.terminal` event with the same value. `AccessibilityInsertionRunner` decides what the editor did and
 * hands over one [InsertionEnding]; nothing here touches an editor, the clipboard or a haptic.
 */
internal class InsertionOutcomeRecorder(
    private val enqueue: (label: String, kind: WriteKind, body: suspend (TranscriptRepository) -> Unit) -> Enqueued,
    private val capture: (AnalyticsEvent) -> Unit,
    /** The `insertion`/`outcome` breadcrumb with its data; the literal pair is written where the sink is built. */
    private val outcomeTrail: (Map<String, String?>) -> Unit,
    private val warn: (String) -> Unit,
) {
    fun record(ending: InsertionEnding) {
        val kind = InsertionResultKind.fromStored(ending.result)
        fun emit() {
            outcomeTrail(mapOf("take_id" to ending.takeId, "result" to ending.result, "target_app" to ending.targetPackage))
            if (ending.takeId == null) return
            capture(
                AnalyticsEvent.InsertionTerminal(
                    takeId = ending.takeId,
                    handoff = InsertionHandoff.SCHEDULED,
                    result = kind,
                    route = InsertionRouteKind.of(kind),
                    targetApp = ending.targetPackage,
                    latencyMs = ending.latencyMs,
                    clipboard = ending.clipboard?.name?.lowercase(),
                    recovered = false,
                ),
            )
        }
        // On the application's History queue (#115), behind the owner's writes of the same row, so the
        // outcome cannot land before the finalization it belongs to, and the row is resolved THERE (#277): the
        // words were handed over before the save answered. No saved row (the save failed, or a debug probe)
        // emits once without a write. Otherwise the update is first-wins; the row leaves only when THIS writer
        // won it, so a recovery or a second finalizer that got there first is the one that reports (round 1, F5).
        val admitted = enqueue("insertion outcome", WriteKind.TERMINAL) { repository ->
            recordInsertionOutcome(
                id = ending.row.resolveOnQueue(),
                write = { id ->
                    runCatching { repository.finalizeInsertionOutcome(id, ending.status, ending.result, ending.interrupted) }
                        .onFailure { error -> warn("Unable to update transcript insertion result: ${error.javaClass.simpleName}") }
                        .getOrNull()
                },
                emit = ::emit,
            )
        }
        // A refused outcome write still reports the insertion once (#292), as a take with no saved row does.
        if (admitted == Enqueued.REJECTED) emit()
    }

    companion object {
        private const val TAG = "PasteService"

        /** The process's recorder: the application's History queue and the telemetry sinks. */
        fun forProcess(context: Context) = InsertionOutcomeRecorder(
            enqueue = { label, kind, body -> ModelBootstrapApplication.historyWrites(context.applicationContext).enqueue(label, kind, body) },
            capture = Telemetry::capture,
            outcomeTrail = { data -> Telemetry.breadcrumb("insertion", "outcome", data) },
            warn = { DebugLogger.warn(TAG, it) },
        )
    }
}

/**
 * The insertion outcome's write and its one terminal event, on the History queue (#277). [id] is the take's saved
 * row resolved there: 0 (the save failed, or a debug probe) emits once without a write; otherwise the write is
 * first-wins and the event is emitted only when THIS writer won the row, so a recovery that got there first is
 * the one that reports.
 */
internal suspend fun recordInsertionOutcome(id: Long, write: suspend (Long) -> Int?, emit: () -> Unit) {
    if (id <= 0L) {
        emit()
        return
    }
    if (write(id) == 1) emit()
}
