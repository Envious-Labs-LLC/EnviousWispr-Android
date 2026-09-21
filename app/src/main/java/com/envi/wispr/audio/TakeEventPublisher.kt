package com.envi.wispr.audio

import com.envi.wispr.debug.DebugLogger
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.LockSupport

/**
 * Pushes the take's events to the one registered [ITakeListener] (#115): live, a heartbeat, the silence
 * status and the ending. Service-scoped like `WarmHoldOwner`: one publisher for the service's lifetime, one
 * worker thread, one queue.
 *
 * The capture thread only ever does a primitive clock comparison and a lock-free offer here: one small
 * event object and one queue node are allocated per event, no lock is taken (`ConcurrentLinkedQueue`), and
 * the worker is unparked, which is a permit write. A `oneway` binder transaction still allocates a Parcel
 * and can back-pressure its caller, so the worker makes every call, under `runCatching`: a dead or
 * unresponsive owner costs the event and nothing else, and never the capture loop. Delivery order is the
 * queue's, which is the order the events happened.
 *
 * Every event names its take ([beginTake]): the listener slot is the binding's and a new owner can register
 * while the previous take's ending is still queued, so the owner discards events that are not its take's.
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
        val takeId: String

        data class Live(override val takeId: String, val forced: Boolean, val routeKind: Int, val routeReason: Int, val liveAfterMs: Long) : Event
        data class Tick(override val takeId: String, val elapsedMs: Long) : Event
        data class SilenceStatus(override val takeId: String, val status: Int) : Event
        data class Ended(
            override val takeId: String,
            val terminalReason: Int,
            val startFailure: Int,
            val audioFilePath: String,
            val silenceStatus: Int,
            val takePeakAmplitude: Float,
            val effectiveInputDevice: String,
        ) : Event
    }

    private val queue = ConcurrentLinkedQueue<Event>()
    private val closed = AtomicBoolean(false)
    @Volatile private var lastTickNanos = Long.MIN_VALUE
    /** The take whose events the capture thread and the detector publish; set under the service's session lock at start. */
    @Volatile private var currentTakeId = ""
    private val worker = Thread({ drain() }, "TakeEventPublisher").apply { isDaemon = true }

    /** Starts the worker; called once by the service's `onCreate`, before any event can be offered. */
    fun start() {
        runCatching { worker.start() }.onFailure { DebugLogger.warn(tag, "Take events unavailable: ${it.javaClass.simpleName}") }
    }

    /** A new take: its id on every event that follows, and the next positive read sends a heartbeat at once. */
    fun beginTake(takeId: String) {
        currentTakeId = takeId
        lastTickNanos = Long.MIN_VALUE
    }

    /**
     * Capture thread only, after each positive read. One primitive comparison; at most one event a second.
     * **Nothing here logs, locks, waits or calls across a process**; the allocation is the event and its node.
     */
    fun offerTick(elapsedMs: Long) {
        val now = nowNanos()
        val last = lastTickNanos
        // The sentinel is tested by identity: `now - Long.MIN_VALUE` overflows negative and would swallow
        // the first heartbeat of every take (found by TakeEventPublisherTest).
        if (last != Long.MIN_VALUE && now - last < TICK_INTERVAL_NANOS) return
        lastTickNanos = now
        offer(Event.Tick(currentTakeId, elapsedMs))
    }

    fun publishLive(forced: Boolean, routeKind: Int, routeReason: Int, liveAfterMs: Long) {
        offer(Event.Live(currentTakeId, forced, routeKind, routeReason, liveAfterMs))
    }

    fun publishSilenceStatus(status: Int) {
        offer(Event.SilenceStatus(currentTakeId, status))
    }

    /**
     * The last thing the capture process does for a take. Exactly once per take is the CALLER's contract;
     * [takeId] is passed explicitly because a refused start ends a take that never became [currentTakeId].
     */
    fun publishEnded(
        takeId: String,
        terminalReason: Int,
        startFailure: Int,
        audioFilePath: String?,
        silenceStatus: Int,
        takePeakAmplitude: Float,
        effectiveInputDevice: String?,
    ) {
        offer(Event.Ended(takeId, terminalReason, startFailure, audioFilePath.orEmpty(), silenceStatus, takePeakAmplitude, effectiveInputDevice.orEmpty()))
    }

    /** Stops the worker after it has drained what is queued. Idempotent; never joined. */
    fun close() {
        if (!closed.compareAndSet(false, true)) return
        LockSupport.unpark(worker)
    }

    private fun offer(event: Event) {
        queue.offer(event)
        // A permit, never a lock: an unpark before the worker parks makes its next park return at once,
        // so an offer racing the worker's empty check cannot be lost.
        LockSupport.unpark(worker)
    }

    /**
     * Parked between events: no timer, no wake at idle (`architecture-rules.md` RULE: no-idle-cost). The
     * unpark from an offer or from [close] is the only thing that wakes it; after close, what is still
     * queued is delivered and the worker leaves.
     */
    private fun drain() {
        while (true) {
            val event = queue.poll()
            if (event == null) {
                if (closed.get()) return
                LockSupport.park(this)
                continue
            }
            val target = listener.get() ?: continue
            runCatching {
                when (event) {
                    is Event.Live -> target.onLive(event.takeId, event.forced, event.routeKind, event.routeReason, event.liveAfterMs)
                    is Event.Tick -> target.onTick(event.takeId, event.elapsedMs)
                    is Event.SilenceStatus -> target.onSilenceStatus(event.takeId, event.status)
                    is Event.Ended -> target.onEnded(event.takeId, event.terminalReason, event.startFailure, event.audioFilePath, event.silenceStatus, event.takePeakAmplitude, event.effectiveInputDevice)
                }
            }.onFailure { DebugLogger.warn(tag, "Take event not delivered: ${event.javaClass.simpleName} ${it.javaClass.simpleName}") }
        }
    }
}
