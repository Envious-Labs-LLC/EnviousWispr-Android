package com.envi.wispr.history

import com.envi.wispr.debug.DebugLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/**
 * Every per-take History write of this process, applied in the order it was ENQUEUED by one worker (#115).
 *
 * Application-owned, one per process: the session owner and the paste service both write a take's row,
 * and before #115 each launched its writes on its own scope, so two writes to one row could land in either
 * order (a `processing` status after the `asr_error` that followed it) and a teardown had to `runBlocking`
 * on the main thread to make sure its own write was the last word. Here the order IS the enqueue order and
 * nothing waits: [enqueue] never suspends and never blocks, and a write that throws is logged by its
 * content-free label while the drain continues. A write left in the channel at process death is lost, as a
 * launched coroutine was; the start-up recovery owns those rows.
 *
 * Per-take writers (insert, status, discard, finalize, the insertion outcome) go through here. The History
 * screen's and the start-up recovery's writers (`setKept`, `delete`, `deleteAll`, `pruneWordlessRows`,
 * `recoverStaleOpenRows`) stay on their own scopes: they never write a live take's row.
 */
internal class HistoryWriteQueue(
    private val repository: TranscriptRepository,
    /** An application scope: `SupervisorJob() + Dispatchers.IO`, never a Service's, so a write outlives the Service that issued it. */
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val warn: (String) -> Unit = { DebugLogger.warn(TAG, it) },
) {
    companion object {
        private const val TAG = "HistoryWriteQueue"
    }

    private class Write(val label: String, val body: suspend (TranscriptRepository) -> Unit)

    private val writes = Channel<Write>(Channel.UNLIMITED)

    init {
        scope.launch {
            for (write in writes) {
                runCatching { write.body(repository) }
                    .onFailure { warn("History write failed (${write.label}): ${it.javaClass.simpleName}") }
            }
        }
    }

    /**
     * Queue one write. Returns at once; `true` unless the queue is closed, which it never is in production.
     * [label] names the write for the log and carries no content.
     */
    fun enqueue(label: String, body: suspend (TranscriptRepository) -> Unit): Boolean =
        writes.trySend(Write(label, body)).isSuccess
}
