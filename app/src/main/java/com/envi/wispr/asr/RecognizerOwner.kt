package com.envi.wispr.asr

import java.util.concurrent.Executor

/** Service-owned model, with a borrowed process lane and one watchdog authority across generations. */
internal class RecognizerOwner<R : Any>(
    lane: RecognizerLane,
    private val watchdog: AsrWatchdog,
    private val control: Executor,
    private val free: (R) -> Unit,
    private val discarded: () -> Unit,
    private val releaseBoundMs: Long,
) {
    private class Close(val grace: AsrWatchdog.Scope?, val after: () -> Unit)
    private sealed interface Life {
        data object Open : Life
        data class Closing(val close: Close) : Life
        data class FreedAwaitingRequests(val close: Close) : Life
        data object Retired : Life
        data object Incomplete : Life
    }
    private val lock = Any()
    private val generation = lane.generation()
    private val requests = mutableSetOf<AsrRequest>()
    @Volatile private var life: Life = Life.Open
    @Volatile private var ready = false
    /** Native worker only. No global model singleton and no owner-owned executor shutdown. */
    private var recognizer: R? = null

    val isReady: Boolean get() = life == Life.Open && ready && !watchdog.wedged
    val generationIdentity: Long get() = generation.identity

    fun request(
        cleanup: () -> Unit,
        retired: (AsrRequest.Retirement) -> Unit,
        controlFailed: (Throwable) -> Unit,
    ): AsrRequest {
        lateinit var entry: AsrRequest
        val accepted = synchronized(lock) {
            entry = AsrRequest(watchdog, control, { life == Life.Open }, cleanup, { outcome ->
                synchronized(lock) { requests.remove(entry) }
                if (outcome == AsrRequest.Retirement.INCOMPLETE) watchdog.poison("Speech request lifetime")
                finishCloseIfReady()
                retired(outcome)
            }, controlFailed)
            if (life == Life.Open) { requests += entry; true } else false
        }
        // A late old-generation Binder call still completes its own registration/cleanup handshake.
        if (!accepted) entry.cancel()
        return entry
    }

    fun load(boundMs: Long, open: () -> R?) {
        generation.enqueue {
            if (life != Life.Open) return@enqueue
            try {
                watchdog.guard(boundMs, "the model load") {
                    if (life == Life.Open) {
                        recognizer = open()
                        ready = recognizer != null
                    }
                }
            } catch (failure: AsrCleanupFailure) {
                watchdog.poison("Speech model construction")
            }
        }
    }

    fun use(boundMs: Long, request: AsrRequest, work: (R?) -> () -> Unit) {
        val admitted = generation.enqueue {
            if (!request.claim(boundMs)) return@enqueue
            try {
                request.check()
                val deliver = work(recognizer)
                if (!request.deliver(deliver)) discarded()
            } catch (_: AsrCancelled) {
                discarded()
            } catch (failure: AsrCleanupFailure) {
                watchdog.poison("Speech invocation")
            } finally {
                request.finish()
            }
        }
        if (!admitted) request.cancel()
    }

    /** Returns immediately; actual free and every registered request must retire before the lane advances. */
    fun close(after: () -> Unit = {}) {
        val pending = synchronized(lock) {
            if (life != Life.Open) return
            val close = Close(watchdog.begin(AsrBounds.RETIREMENT_GRACE_MS, "speech owner close"), after)
            life = Life.Closing(close)
            ready = false
            generation.seal { release(close) }
            requests.toList()
        }
        pending.forEach { it.cancel() }
    }

    private fun release(close: Close) {
        val released = try {
            watchdog.guard(releaseBoundMs, "the recognizer release") {
                val current = recognizer
                if (current != null) free(current)
                recognizer = null
                ready = false
                true
            } == true
        } catch (failure: Throwable) {
            watchdog.poison("Speech model release")
            false
        }
        synchronized(lock) {
            life = if (released) Life.FreedAwaitingRequests(close) else Life.Incomplete
        }
        finishCloseIfReady()
    }

    private fun finishCloseIfReady() {
        val completed = synchronized(lock) {
            val freed = life as? Life.FreedAwaitingRequests ?: return
            if (requests.isNotEmpty()) return
            if (freed.close.grace?.complete() == true && !watchdog.wedged) {
                life = Life.Retired
                freed.close
            } else {
                life = Life.Incomplete
                null
            }
        }
        if (completed != null && generation.completeRetirement()) completed.after()
    }
}
