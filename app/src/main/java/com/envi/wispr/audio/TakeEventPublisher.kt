package com.envi.wispr.audio

import com.envi.wispr.debug.DebugLogger
import java.util.Queue
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicLong
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
 * queue's: the order the producers' offers linearised in, which for one producer is the order it offered.
 *
 * Every event names its take, passed by the caller at each publish (never read from a shared field, which a
 * detector callback outliving its take would read as the NEXT take's): the listener slot is the binding's
 * and a new owner can register while the previous take's ending is still queued, so the owner discards
 * events that are not its take's.
 *
 * [close] is the end of delivery, decided by ONE atomic word ([lifecycle]: a closed bit and a count of
 * offers between entry and enqueue): an offer enters only by a compare-and-set that fails once the closed
 * bit is set, an entered offer is always enqueued and delivered, and one that finds the bit set is dropped
 * by contract, never lost by a race (the worker leaves only once the bit is set, no offer is between entry
 * and enqueue, and the queue is empty). After the service's destroy nothing about any take can change, and
 * the owner's silence bound covers a take whose ending was never published.
 *
 * Every event is a limb. The take does not know this class exists.
 */
internal class TakeEventPublisher(
    private val listener: AtomicReference<ITakeListener?>,
    private val tag: String,
    private val nowNanos: () -> Long = System::nanoTime,
    /** The event queue; a test hands in one whose enqueue it can hold, to stage close against an entered offer. */
    private val queue: Queue<Event> = ConcurrentLinkedQueue(),
) {
    companion object {
        /** Heartbeats are throttled by WALL-CLOCK second: elapsed is 0 before live and would send one. */
        const val TICK_INTERVAL_NANOS = 1_000_000_000L

        /** The closed bit of [lifecycle]; the low bits count offers between entry and enqueue. */
        private const val CLOSED = 1L shl 62
        private const val ENTERED_MASK = CLOSED - 1
    }

    internal sealed interface Event {
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

    /** One word: the [CLOSED] bit and the count of offers that entered and have not enqueued yet. */
    private val lifecycle = AtomicLong(0L)
    @Volatile private var lastTickNanos = Long.MIN_VALUE
    private val worker = Thread({ drain() }, "TakeEventPublisher").apply { isDaemon = true }

    /** Starts the worker; called once by the service's `onCreate`, before any event can be offered. */
    fun start() {
        runCatching { worker.start() }.onFailure { DebugLogger.warn(tag, "Take events unavailable: ${it.javaClass.simpleName}") }
    }

    /** A new take: the next positive read sends a heartbeat at once. */
    fun resetTicks() {
        lastTickNanos = Long.MIN_VALUE
    }

    /**
     * Capture thread only, after each positive read. One primitive comparison; at most one event a second.
     * **Nothing here logs, locks, waits or calls across a process**; the allocation is the event and its node.
     */
    fun offerTick(takeId: String, elapsedMs: Long) {
        val now = nowNanos()
        val last = lastTickNanos
        // The sentinel is tested by identity: `now - Long.MIN_VALUE` overflows negative and would swallow
        // the first heartbeat of every take (found by TakeEventPublisherTest).
        if (last != Long.MIN_VALUE && now - last < TICK_INTERVAL_NANOS) return
        lastTickNanos = now
        offer(Event.Tick(takeId, elapsedMs))
    }

    fun publishLive(takeId: String, forced: Boolean, routeKind: Int, routeReason: Int, liveAfterMs: Long) {
        offer(Event.Live(takeId, forced, routeKind, routeReason, liveAfterMs))
    }

    fun publishSilenceStatus(takeId: String, status: Int) {
        offer(Event.SilenceStatus(takeId, status))
    }

    /** The last thing the capture process does for a take. Exactly once per take is the CALLER's contract. */
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

    /** Stops the worker once every offer that entered before this is delivered. Idempotent; never joined. */
    fun close() {
        val before = lifecycle.getAndUpdate { it or CLOSED }
        if (before and CLOSED != 0L) return
        LockSupport.unpark(worker)
    }

    /** Enter: one compare-and-set that fails once the closed bit is set. True means this offer WILL enqueue. */
    private fun enter(): Boolean {
        while (true) {
            val current = lifecycle.get()
            if (current and CLOSED != 0L) return false
            if (lifecycle.compareAndSet(current, current + 1)) return true
        }
    }

    private fun offer(event: Event) {
        // Entered by the CAS, or dropped by contract (see the class KDoc); no check-then-act in between.
        if (!enter()) return
        try {
            queue.offer(event)
        } finally {
            lifecycle.decrementAndGet()
            // A permit, never a lock: an unpark before the worker parks makes its next park return at
            // once, so an offer racing the worker's empty check cannot be lost.
            LockSupport.unpark(worker)
        }
    }

    /**
     * Parked between events: no timer, no wake at idle (`architecture-rules.md` RULE: no-idle-cost). The
     * unpark from an offer or from [close] is the only thing that wakes it; after close, what is queued
     * and what is still being offered is delivered, then the worker leaves.
     */
    private fun drain() {
        while (true) {
            val event = queue.poll()
            if (event == null) {
                val state = lifecycle.get()
                if (state and CLOSED != 0L && state and ENTERED_MASK == 0L && queue.isEmpty()) return
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
