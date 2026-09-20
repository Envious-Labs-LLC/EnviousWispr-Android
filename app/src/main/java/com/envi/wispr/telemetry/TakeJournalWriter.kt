package com.envi.wispr.telemetry

import com.envi.wispr.debug.DebugLogger
import com.envi.wispr.ui.TerminalReason
import com.envi.wispr.ui.TriggerSource
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/**
 * The ONE ordered writer of the take journal (issue #176, plan §3.3). Every mutation for every take is
 * queued here and applied one at a time, in arrival order, on one background worker, so:
 *
 * - admission always lands before any stage or ending of the same take, even when the owner stopped
 *   waiting for it (a deadline expiry proves nothing about the database; the queued write still runs);
 * - a late admission can never recreate an open row after an ending, because it was queued first;
 * - the `dictation.terminal` row is captured ONLY after the ending's Room transaction changed the row,
 *   so the journal and the wire agree: a take the journal never admitted sends nothing.
 *
 * Nothing here blocks a take. The queue is unbounded and every call returns at once; the caller that
 * wants to know admission landed waits on the returned [Deferred] under its own deadline.
 */
class TakeJournalWriter(
    private val dao: TakeJournalDao,
    private val processRunId: String,
    private val capture: (AnalyticsEvent) -> Unit,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    private val queue = Channel<suspend () -> Unit>(Channel.UNLIMITED)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        scope.launch {
            for (work in queue) {
                runCatching { work() }.onFailure { DebugLogger.warn(TAG, "Journal write failed: ${it.javaClass.simpleName}") }
            }
        }
    }

    /** Queues the admission; completes true once the row is in the journal (or already was). */
    fun admit(takeId: String, trigger: TriggerSource): Deferred<Boolean> {
        val landed = CompletableDeferred<Boolean>()
        post(landed) {
            dao.admit(
                TakeJournalEntry(
                    takeId = takeId,
                    processRunId = processRunId,
                    admittedAtMs = nowMs(),
                    stage = TakeStage.ADMITTED.name,
                    stageSeq = TakeStage.ADMITTED.seq,
                    transcriptId = null,
                    terminalResult = null,
                    terminalReason = null,
                    terminalAtMs = null,
                    triggerSource = trigger.name,
                ),
            )
            landed.complete(true)
        }
        return landed
    }

    fun advance(takeId: String, stage: TakeStage) {
        post { dao.advance(takeId, stage.name, stage.seq) }
    }

    fun associate(takeId: String, transcriptId: Long) {
        if (transcriptId <= 0L) return
        post { dao.associateTranscript(takeId, transcriptId) }
    }

    /**
     * The ending: committed in Room first, then, and only when that commit changed the row, captured.
     * A second ending for the same take (impossible through the arbiter, possible through a bug) changes
     * nothing and sends nothing.
     */
    fun terminal(event: AnalyticsEvent.DictationTerminal) {
        post {
            val changed = dao.commitTerminal(event.takeId, event.reason.result.wire, event.reason.name, nowMs())
            if (changed == 1) capture(event) else DebugLogger.warn(TAG, "Ending for an unadmitted or already ended take; nothing sent")
        }
    }

    /**
     * Main bootstrap only: every take an EARLIER run admitted and never ended becomes
     * `dictation.interrupted{stage}`, closed and reported in one transaction per take, then endings
     * older than [RETENTION_MS] are pruned. Current-run takes are never touched.
     */
    fun recoverAndPrune() {
        post {
            for (open in dao.openFromOtherRuns(processRunId)) {
                val stage = TakeStage.entries.firstOrNull { it.name == open.stage } ?: TakeStage.ADMITTED
                val reason = when (stage) {
                    TakeStage.ADMITTED -> TerminalReason.INTERRUPTED_STARTING
                    TakeStage.RECORDING -> TerminalReason.INTERRUPTED_RECORDING
                    TakeStage.PROCESSING, TakeStage.INSERTING -> TerminalReason.INTERRUPTED_PROCESSING
                }
                if (dao.closeInterrupted(open.takeId, reason.name, nowMs())) {
                    capture(AnalyticsEvent.DictationInterrupted(open.takeId, stage, TriggerSource.entries.firstOrNull { it.name == open.triggerSource }?.wire ?: TriggerSource.UNKNOWN.wire))
                }
            }
            val cutoffMs = nowMs() - RETENTION_MS
            dao.prune(cutoffMs, keep = dao.insertionTakeIdsToKeep(cutoffMs))
        }
    }

    /** The take a History row belongs to, for the insertion writers that hold only the row id. */
    suspend fun takeIdForTranscript(transcriptId: Long): String? =
        runCatching { dao.takeIdForTranscript(transcriptId) }.getOrNull()

    private fun post(landed: CompletableDeferred<Boolean>? = null, work: suspend () -> Unit) {
        val queued = queue.trySend {
            try {
                work()
            } finally {
                landed?.complete(false)
            }
        }.isSuccess
        if (!queued) landed?.complete(false)
    }

    companion object {
        private const val TAG = "TakeJournal"
        const val RETENTION_MS = 7L * 24 * 60 * 60 * 1000
    }
}
