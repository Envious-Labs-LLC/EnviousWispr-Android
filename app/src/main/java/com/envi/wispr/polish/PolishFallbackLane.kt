package com.envi.wispr.polish

import com.envi.wispr.cleanup.CleanupOptions
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The `:polish` process's failure answers, off the binder thread (#291): a request with no policy, a runtime ending
 * after a local timeout, a duplicate id. Each is the deterministic text (cleanup plus language detection), which must
 * never run on the AIDL call's thread (`kotlin-patterns.md` RULE: never-block-a-binder-or-ui-thread), and never on the
 * main polish worker, which may be the very thing that is wedged (#75).
 *
 * Every answer holds a token until it is delivered or cancelled; its deliverer and [cancel] compete once for it, so a
 * cancelled answer is never delivered and a delivered one is never cancelled halfway. A lane that is closed,
 * or whose worker refuses, still answers: the RAW text, from a short-lived daemon thread, never inline.
 */
internal class PolishFallbackLane(
    private val worker: ExecutorService,
    private val prepare: (String, CleanupOptions) -> String,
    /** Starts the refusal's deliverer; a daemon thread in production, a held thread in a test. */
    private val startRefusal: (Runnable) -> Unit = { runnable -> Thread(runnable, "PolishFallbackRefusal").apply { isDaemon = true }.start() },
) {
    /** One answer. [settled] is won ONCE, by its deliverer or by [cancel], so a cancelled answer is never delivered. */
    private class Token(val requestId: Long) {
        val settled = AtomicBoolean(false)
    }

    private val lock = Any()

    /** Guarded by [lock]. */
    private var closed = false
    private val tokens = ConcurrentHashMap.newKeySet<Token>()

    /** Queues the deterministic answer for [requestId]; returns at once. [sink] receives it at most once. */
    fun answer(requestId: Long, raw: String, options: CleanupOptions, reason: PolishReason, sink: (PolishOutcome) -> Unit) {
        val token = Token(requestId).also(tokens::add)
        val admitted = synchronized(lock) {
            !closed && try {
                // A cleanup that throws answers the raw words: an answer with less cleanup beats none.
                worker.execute { deliver(token, sink) { PolishOutcome(requestId, runCatching { prepare(raw, options) }.getOrDefault(raw), PolishEngineLabels.DETERMINISTIC, reason, 0, 0) } }
                true
            } catch (refused: RejectedExecutionException) {
                false
            }
        }
        if (!admitted) {
            // No cleanup runs here: the raw words, now, from their own thread.
            startRefusal { deliver(token, sink) { PolishOutcome(requestId, raw, PolishEngineLabels.DETERMINISTIC, reason, 0, 0) } }
        }
    }

    /**
     * Marks every answer for [requestId] cancelled and forgets it at once (#291 review): one not yet delivered is never
     * delivered, and a cleanup stalled behind it cannot make cancelled answers pile up.
     */
    fun cancel(requestId: Long) {
        tokens.filter { it.requestId == requestId }.forEach {
            it.settled.set(true)
            tokens.remove(it)
        }
    }

    /** Answers admitted and neither delivered nor cancelled yet (for the leak row). */
    internal fun pending(): Int = tokens.size

    /**
     * Stops admission, queues [then] behind every admitted answer and shuts the worker down, without waiting. A later
     * [answer] is refused (the raw text, off-thread).
     */
    fun close(then: () -> Unit) {
        synchronized(lock) {
            if (closed) return
            closed = true
            // A worker that refuses the final step must not leave it undone (#291 review): it runs on its own thread.
            try {
                worker.execute(then)
            } catch (refused: RejectedExecutionException) {
                startRefusal(Runnable(then))
            }
            worker.shutdown()
        }
    }

    /**
     * Waits up to [timeoutMs] for the lane's worker to finish after [close] (#344 review round 3): the engine's close
     * watch bounds this worker too, since a fallback answer can stall in the detector's acquisition with the detector
     * close queued behind it. True when the worker finished.
     */
    fun awaitTermination(timeoutMs: Long): Boolean =
        worker.awaitTermination(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)

    private fun deliver(token: Token, sink: (PolishOutcome) -> Unit, outcome: () -> PolishOutcome) {
        try {
            if (token.settled.get()) return
            val answer = outcome()
            if (!token.settled.compareAndSet(false, true)) return
            sink(answer)
        } finally {
            tokens.remove(token)
        }
    }
}
