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
    private class Loader<T>(val boundMs: Long, val open: () -> T?)
    /** The open the service handed [load]; a [use] that finds no model retries through it (#562). */
    @Volatile private var loader: Loader<R>? = null
    /** Finished load attempts. Written by the worker alone; read at demand time to coalesce attempts. */
    @Volatile private var loadsFinished = 0L

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

    /**
     * Opens the model on the worker and remembers how, so a [use] that finds no model asks again (#562). Before, one
     * failed open (no verified model yet, a throw, a null) left every take on `MODEL_NOT_LOADED` for the life of the
     * process, and the keep-alive (#37) can hold that process for good.
     */
    fun load(boundMs: Long, open: () -> R?) {
        val loader = Loader(boundMs, open)
        this.loader = loader
        attempt(loader)
    }

    /**
     * Single-flight on the one worker: an attempt opens nothing while a model is present, and nothing when another
     * attempt FINISHED after this one was asked for. That attempt already answered this demand, so a burst of takes
     * queued behind a failed open costs one open, never one each, and a missing model is retried once per later take,
     * never in a loop. Admission refusal after close and the in-worker [life] checks keep a load out of a closed owner.
     * [mayOpen] is read on the worker immediately before the open, so a request cancelled while queued never parks the
     * worker on a model open; a skipped attempt is not counted, so it cannot use up a live request's retry.
     */
    private fun attempt(loader: Loader<R>, mayOpen: () -> Boolean = { true }) {
        val asked = loadsFinished
        generation.enqueue {
            if (life != Life.Open || recognizer != null || loadsFinished != asked) return@enqueue
            var opened = false
            try {
                watchdog.guard(loader.boundMs, "the model load") {
                    if (life == Life.Open && mayOpen()) {
                        opened = true
                        recognizer = loader.open()
                        // Under the close lock: once close() has cleared readiness, a late open cannot set it again.
                        synchronized(lock) { ready = life == Life.Open && recognizer != null }
                    }
                }
            } catch (failure: AsrCleanupFailure) {
                watchdog.poison("Speech model construction")
            } catch (_: Exception) {
                // A throwing open is a failed attempt like a null one: nothing was handed over, so nothing to free.
            } finally {
                if (opened) loadsFinished++
            }
        }
    }

    /** [allowed] is the caller's own admission (a revoked recovery), read with the request's before any model open. */
    fun use(boundMs: Long, request: AsrRequest, allowed: () -> Boolean = { true }, work: (R?) -> () -> Unit) {
        // Queued AHEAD of the work on the same FIFO worker, so the work reads the model this attempt opened.
        if (!ready) loader?.let { attempt(it) { request.canStartModelLoad() && allowed() } }
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
