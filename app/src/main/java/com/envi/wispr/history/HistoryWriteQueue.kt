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
 * Bounded (#292): at most [capacity] writes outstanding, and an ORDINARY write only up to [ordinaryLimit], so a
 * take's terminal writes keep room in a deep stall. A refusal is [Enqueued.REJECTED], never a wait; the caller
 * answers it (a limb write lost, which start-up recovery owns), and [onOverload] runs once per overload episode,
 * which lasts until the worker has finished the whole accepted backlog.
 *
 * Per-take writers (insert, status, discard, finalize, the insertion outcome, the teardown's interrupted
 * mark) go through here and nothing else does: the History screen's writers (`setKept`, `delete`,
 * `deleteAll`, `pruneWordlessRows`) and the start-up recovery (`recoverStaleOpenRows`) stay on their own
 * scopes, so a write of theirs stalled on the disk never sits ahead of a live take's writes.
 */
internal class HistoryWriteQueue(
    private val repository: TranscriptRepository,
    /** An application scope: `SupervisorJob() + Dispatchers.IO`, never a Service's, so a write outlives the Service that issued it. */
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val warn: (String) -> Unit = { DebugLogger.warn(TAG, it) },
    private val capacity: Int = HISTORY_QUEUE_CAPACITY,
    private val ordinaryLimit: Int = HISTORY_QUEUE_ORDINARY_LIMIT,
    /** Once per overload episode (#292); production raises `AppDefect.HistoryQueueOverloaded`. */
    private val onOverload: () -> Unit = {},
) {
    companion object {
        private const val TAG = "HistoryWriteQueue"

        /** Writes outstanding at most (#292): a take enqueues about eight, so this is eight takes of backlog. */
        const val HISTORY_QUEUE_CAPACITY = 64

        /** Where an ORDINARY write is refused (#292), leaving the rest for a take's terminal writes. */
        const val HISTORY_QUEUE_ORDINARY_LIMIT = 48
    }

    init {
        require(ordinaryLimit in 1 until capacity) { "0 < ordinaryLimit < capacity" }
    }

    private class Write(val label: String, val body: suspend (TranscriptRepository) -> Unit)

    private val writes = Channel<Write>(capacity)

    private val lock = Any()

    /** Accepted and not yet finished, the running write included. Guarded by [lock]. */
    private var pending = 0

    /** Guarded by [lock]: set by the episode's first refusal, cleared when the backlog is finished. */
    private var overloaded = false

    init {
        scope.launch {
            for (write in writes) {
                // The count always comes down, whatever the write or its log line throws (#292 review): a stuck count
                // would refuse every later write for good.
                try {
                    runCatching { write.body(repository) }
                        .onFailure { error -> runCatching { warn("History write failed (${write.label}): ${error.javaClass.simpleName}") } }
                } finally {
                    synchronized(lock) {
                        pending -= 1
                        if (pending == 0) overloaded = false
                    }
                }
            }
        }
    }

    /** Accepted writes not yet finished (for the tests' drain). */
    internal fun pendingForTest(): Int = synchronized(lock) { pending }

    /**
     * Queue one write. Returns at once, never suspending and never blocking. [label] names the write for the log
     * and carries no content. [kind] is TERMINAL for a take's last word on its row (finalize, an insertion outcome,
     * discard, interrupted, an error status), which keeps room when ordinary writes are refused.
     */
    fun enqueue(label: String, kind: WriteKind = WriteKind.ORDINARY, body: suspend (TranscriptRepository) -> Unit): Enqueued {
        var episodeStarted = false
        val result = synchronized(lock) {
            val limit = when (kind) {
                WriteKind.ORDINARY -> ordinaryLimit
                WriteKind.TERMINAL -> capacity
            }
            if (pending < limit && writes.trySend(Write(label, body)).isSuccess) {
                pending += 1
                Enqueued.ACCEPTED
            } else {
                if (!overloaded) {
                    overloaded = true
                    episodeStarted = true
                }
                Enqueued.REJECTED
            }
        }
        if (result == Enqueued.REJECTED) {
            // Best effort: a log line that throws must never keep a caller from answering its refusal (#292 review).
            runCatching { warn("History write refused, the queue is full ($label)") }
            if (episodeStarted) runCatching { onOverload() }
        }
        return result
    }
}

/** Whether [HistoryWriteQueue.enqueue] took the write (#292). */
internal enum class Enqueued { ACCEPTED, REJECTED }

/** An ORDINARY write is refused first; a TERMINAL one (a take's last word on its row) keeps the reserve (#292). */
internal enum class WriteKind { ORDINARY, TERMINAL }

/** A History write refused because the queue was full (#292): the cause a refused save or draft carries. */
internal class HistoryQueueFullException : IllegalStateException("History write queue full")
