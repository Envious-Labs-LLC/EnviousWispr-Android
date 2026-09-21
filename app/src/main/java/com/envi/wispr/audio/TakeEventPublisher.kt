package com.envi.wispr.audio

import com.envi.wispr.debug.DebugLogger
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Pushes the take's events to the one registered [ITakeListener] (#115): live, a heartbeat, the silence
 * status and the ending. Service-scoped like `WarmHoldOwner`: one publisher for the service's lifetime, one
 * worker thread, one queue.
 *
 * The capture thread only ever does a primitive clock comparison and a non-blocking offer here. A `oneway`
 * binder transaction still allocates a Parcel and can back-pressure its caller, so the worker makes every
 * call, under `runCatching`: a dead or unresponsive owner costs the event and nothing else, and never the
 * capture loop. Delivery order is the queue's, which is the order the events happened.
 *
 * Every event is a limb. The take does not know this class exists.
 */
internal class TakeEventPublisher(
    private val listener: AtomicReference<ITakeListener?>,
    private val tag: String,
    private val nowNanos: () -> Long = System::nanoTime,
) {
    companion object {
        /** Heartbeats are throttled by WALL-CLOCK second: elapsed is 0 before live and would send one. */
        const val TICK_INTERVAL_NANOS = 1_000_000_000L
    }

    private sealed interface Event {
        data class Live(val forced: Boolean, val routeKind: Int, val routeReason: Int, val liveAfterMs: Long) : Event
        data class Tick(val elapsedMs: Long) : Event
        data class SilenceStatus(val status: Int) : Event
        data class Ended(
            val terminalReason: Int,
            val startFailure: Int,
            val audioFilePath: String,
            val silenceStatus: Int,
            val takePeakAmplitude: Float,
            val effectiveInputDevice: String,
        ) : Event
    }

    private val queue = LinkedBlockingQueue<Event>()
    private val closed = AtomicBoolean(false)
    @Volatile private var lastTickNanos = Long.MIN_VALUE
    private val worker = Thread({ drain() }, "TakeEventPublisher").apply { isDaemon = true }

    /** Starts the worker; called once by the service's `onCreate`. */
    fun start() {
        runCatching { worker.start() }.onFailure { DebugLogger.warn(tag, "Take events unavailable: ${it.javaClass.simpleName}") }
    }

    /**
     * Capture thread only, after each positive read. One primitive comparison; at most one event a second.
     * **Nothing here logs, allocates beyond the queued event, locks or calls across a process.**
     */
    fun offerTick(elapsedMs: Long) {
        val now = nowNanos()
        val last = lastTickNanos
        // The sentinel is tested by identity: `now - Long.MIN_VALUE` overflows negative and would swallow
        // the first heartbeat of every take (found by TakeEventPublisherTest).
        if (last != Long.MIN_VALUE && now - last < TICK_INTERVAL_NANOS) return
        lastTickNanos = now
        queue.offer(Event.Tick(elapsedMs))
    }

    /** A new take: the next positive read sends a heartbeat at once. */
    fun resetTicks() {
        lastTickNanos = Long.MIN_VALUE
    }

    fun publishLive(forced: Boolean, routeKind: Int, routeReason: Int, liveAfterMs: Long) {
        queue.offer(Event.Live(forced, routeKind, routeReason, liveAfterMs))
    }

    fun publishSilenceStatus(status: Int) {
        queue.offer(Event.SilenceStatus(status))
    }

    /** The last thing the capture process does for a take. Exactly once per take is the CALLER's contract. */
    fun publishEnded(
        terminalReason: Int,
        startFailure: Int,
        audioFilePath: String?,
        silenceStatus: Int,
        takePeakAmplitude: Float,
        effectiveInputDevice: String?,
    ) {
        queue.offer(Event.Ended(terminalReason, startFailure, audioFilePath.orEmpty(), silenceStatus, takePeakAmplitude, effectiveInputDevice.orEmpty()))
    }

    /** Stops the worker after it has drained what is queued. Idempotent; never joined. */
    fun close() {
        if (!closed.compareAndSet(false, true)) return
        worker.interrupt()
    }

    /**
     * Parked on the queue between events: no timer, no wake at idle (`architecture-rules.md` RULE:
     * no-idle-cost). The interrupt from [close] is the only other thing that wakes it; after it, what is
     * still queued is delivered without waiting and the worker leaves.
     */
    private fun drain() {
        var closing = false
        while (true) {
            val event = if (closing) {
                queue.poll() ?: return
            } else {
                try {
                    queue.take()
                } catch (_: InterruptedException) {
                    closing = true
                    continue
                }
            }
            val target = listener.get() ?: continue
            runCatching {
                when (event) {
                    is Event.Live -> target.onLive(event.forced, event.routeKind, event.routeReason, event.liveAfterMs)
                    is Event.Tick -> target.onTick(event.elapsedMs)
                    is Event.SilenceStatus -> target.onSilenceStatus(event.status)
                    is Event.Ended -> target.onEnded(event.terminalReason, event.startFailure, event.audioFilePath, event.silenceStatus, event.takePeakAmplitude, event.effectiveInputDevice)
                }
            }.onFailure { DebugLogger.warn(tag, "Take event not delivered: ${event.javaClass.simpleName} ${it.javaClass.simpleName}") }
        }
    }
}
