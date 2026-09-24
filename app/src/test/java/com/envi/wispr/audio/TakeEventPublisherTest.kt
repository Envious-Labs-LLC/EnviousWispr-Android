package com.envi.wispr.audio

import android.os.IBinder
import android.os.RemoteException
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Harness and Drift rows on the take-event publisher (#115): what leaves the audio process, in what order,
 * under which take's id (passed at every publish), throttled by which clock, what close still delivers, and
 * what a dead owner costs. The listener is a JVM fake
 * of the AIDL interface (`android.jar` returns defaults, so `asBinder` may answer null here).
 */
class TakeEventPublisherTest {
    private class Recorder : ITakeListener {
        val events = CopyOnWriteArrayList<String>()
        @Volatile var throwOnce = false
        private var latch = CountDownLatch(0)

        /** When set, the next silence status blocks inside its delivery until [release] (#280: the worker held). */
        @Volatile var holdNextSilence = false
        val holding = CountDownLatch(1)
        val release = CountDownLatch(1)

        /** Called inside a heartbeat's or a silence status's delivery, on the worker (#280 fairness rows). */
        @Volatile var onTickHook: (() -> Unit)? = null
        @Volatile var onSilenceHook: (() -> Unit)? = null

        fun expect(count: Int) { latch = CountDownLatch(count) }
        fun await() { check(latch.await(10, TimeUnit.SECONDS)) { "the publisher never delivered; events: $events" } }

        private fun record(line: String) {
            if (throwOnce) {
                throwOnce = false
                latch.countDown()
                throw RemoteException("owner gone")
            }
            events += line
            latch.countDown()
        }
        override fun onLive(takeId: String?, forced: Boolean, routeKind: Int, routeReason: Int, liveAfterMs: Long) = record("$takeId:live($forced,$routeKind,$routeReason,$liveAfterMs)")
        override fun onTick(takeId: String?, elapsedMs: Long) {
            record("$takeId:tick($elapsedMs)")
            onTickHook?.invoke()
        }
        override fun onSilenceStatus(takeId: String?, status: Int) {
            if (holdNextSilence) {
                holdNextSilence = false
                holding.countDown()
                check(release.await(10, TimeUnit.SECONDS)) { "the held delivery was never released" }
            }
            record("$takeId:silence($status)")
            onSilenceHook?.invoke()
        }
        override fun onEnded(takeId: String?, terminalReason: Int, startFailure: Int, audioFilePath: String?, silenceStatus: Int, takePeakAmplitude: Float, effectiveInputDevice: String?) =
            record("$takeId:ended($terminalReason,$startFailure,$audioFilePath,$silenceStatus,$takePeakAmplitude,$effectiveInputDevice)")
        override fun asBinder(): IBinder? = null
    }

    private val now = AtomicLong(0L)
    private val recorder = Recorder()
    private val slot = AtomicReference<ITakeListener?>(recorder)
    private val publisher = TakeEventPublisher(slot, "test", nowNanos = { now.get() }).also { it.start() }

    @After
    fun tearDown() = publisher.close()

    @Test
    fun heartbeatsAreThrottledByWallClockNotByElapsed() {
        // Elapsed is 0 before live, so a throttle by elapsed second would send exactly one heartbeat and
        // then starve the owner's bound (#115 plan review, round 2).
        // Each heartbeat is awaited before the clock moves: a heartbeat still in the slot would make the next one
        // a legitimate skip (#280), which is not what this row is about.
        publisher.resetTicks()
        recorder.expect(1)
        publisher.offerTick("t1", 0L)
        recorder.await()
        now.set(500_000_000L)
        publisher.offerTick("t1", 0L)
        now.set(TakeEventPublisher.TICK_INTERVAL_NANOS)
        recorder.expect(1)
        publisher.offerTick("t1", 0L)
        recorder.await()
        assertEquals(listOf("t1:tick(0)", "t1:tick(0)"), recorder.events.toList())
    }

    @Test
    fun eventsArriveInTheOrderTheyHappenedUnderTheirTakesId() {
        recorder.expect(4)
        publisher.resetTicks()
        publisher.publishLive("t1", false, 1, 2, 120L)
        publisher.offerTick("t1", 1_000L)
        publisher.publishSilenceStatus("t1", AudioCaptureService.SILENCE_STATUS_READY)
        publisher.publishEnded("t1", AudioCaptureService.TERMINAL_REASON_MANUAL, AudioCaptureService.START_FAILURE_NONE, "/tmp/take.pcm", AudioCaptureService.SILENCE_STATUS_READY, 0.5f, "Phone microphone")
        recorder.await()
        // The queued events keep their order; a heartbeat is delivered but not ordered against them (#280).
        assertEquals(
            listOf("t1:live(false,1,2,120)", "t1:silence(2)", "t1:ended(${AudioCaptureService.TERMINAL_REASON_MANUAL},0,/tmp/take.pcm,2,0.5,Phone microphone)"),
            recorder.events.filterNot { it.contains(":tick(") },
        )
        assertEquals(listOf("t1:tick(1000)"), recorder.events.filter { it.contains(":tick(") })
    }

    /**
     * #327, staged: the worker has already found the Live slot empty and is inside its poll when the take's Live
     * (slot) and ending (queue) are offered; the polled ending must still wait for the Live. The queue holds the
     * worker's first poll until both are offered. MUTATION m3: the worker delivers a polled event before a
     * waiting Live.
     */
    @Test
    fun aLiveOfferedDuringThePollStillPrecedesTheEndingPolled() {
        val polling = CountDownLatch(1)
        val release = CountDownLatch(1)
        val held = object : java.util.concurrent.ConcurrentLinkedQueue<TakeEventPublisher.Event>() {
            @Volatile var holdNext = true
            override fun poll(): TakeEventPublisher.Event? {
                if (holdNext) {
                    holdNext = false
                    polling.countDown()
                    release.await(10, TimeUnit.SECONDS)
                }
                return super.poll()
            }
        }
        val staged = TakeEventPublisher(slot, "test", nowNanos = { now.get() }, queue = held)
        try {
            staged.start()
            assertTrue("the worker reached its poll", polling.await(10, TimeUnit.SECONDS))
            recorder.expect(2)
            staged.publishLive("t1", false, 1, 2, 7L)
            staged.publishEnded("t1", AudioCaptureService.TERMINAL_REASON_MANUAL, AudioCaptureService.START_FAILURE_NONE, "/tmp/take.pcm", AudioCaptureService.SILENCE_STATUS_READY, 0.5f, "Phone microphone")
            release.countDown()
            recorder.await()
            assertEquals("t1:live(false,1,2,7)", recorder.events.first())
        } finally {
            release.countDown()
            staged.close()
        }
    }

    /** #327: many takes back to back while the worker runs; each take's Live still arrives before its ending. */
    @Test
    fun aTakesLiveAlwaysArrivesBeforeItsEnding() {
        val takes = 500
        recorder.expect(takes * 2)
        repeat(takes) { n ->
            publisher.publishLive("t$n", false, 1, 2, n.toLong())
            publisher.publishEnded("t$n", AudioCaptureService.TERMINAL_REASON_MANUAL, AudioCaptureService.START_FAILURE_NONE, "/tmp/take.pcm", AudioCaptureService.SILENCE_STATUS_READY, 0.5f, "Phone microphone")
        }
        recorder.await()
        val events = recorder.events.toList()
        repeat(takes) { n ->
            val live = events.indexOf("t$n:live(false,1,2,$n)")
            val ended = events.indexOfFirst { it.startsWith("t$n:ended(") }
            assertTrue("take $n: live $live before ended $ended", live in 0 until ended)
        }
    }

    @Test
    fun aRefusedStartEndsUnderTheRequestedIdWhileThePreviousTakeKeepsItsOwn() {
        // Review round 1, F2 and F6: the previous take is still open (its id is current) when the next
        // owner's start is refused as busy. The refusal is published under the REQUESTED id with nothing of
        // the previous take, and the previous take's ending, later, still carries ITS id.
        recorder.expect(2)
        publisher.publishEnded("t2", AudioCaptureService.TERMINAL_REASON_NONE, AudioCaptureService.START_FAILURE_OTHER, null, AudioCaptureService.SILENCE_STATUS_DISABLED, 0f, null)
        publisher.publishEnded("t1", AudioCaptureService.TERMINAL_REASON_MANUAL, AudioCaptureService.START_FAILURE_NONE, "/tmp/take.pcm", AudioCaptureService.SILENCE_STATUS_READY, 0.5f, "Phone microphone")
        recorder.await()
        assertEquals(
            listOf(
                "t2:ended(${AudioCaptureService.TERMINAL_REASON_NONE},${AudioCaptureService.START_FAILURE_OTHER},,0,0.0,)",
                "t1:ended(${AudioCaptureService.TERMINAL_REASON_MANUAL},0,/tmp/take.pcm,2,0.5,Phone microphone)",
            ),
            recorder.events.toList(),
        )
    }

    @Test
    fun closeDeliversWhatWasQueuedBeforeTheWorkerLeaves() {
        // The service closes the publisher at the end of onDestroy, after the take's ending was queued:
        // that ending is the owner's signal and must not die with the worker. Idempotent close.
        recorder.expect(2)
        publisher.publishSilenceStatus("t1", AudioCaptureService.SILENCE_STATUS_READY)
        publisher.publishEnded("t1", AudioCaptureService.TERMINAL_REASON_MANUAL, AudioCaptureService.START_FAILURE_NONE, "/tmp/take.pcm", AudioCaptureService.SILENCE_STATUS_READY, 0.5f, "Phone microphone")
        publisher.close()
        publisher.close()
        recorder.await()
        assertEquals(listOf("t1:silence(2)", "t1:ended(${AudioCaptureService.TERMINAL_REASON_MANUAL},0,/tmp/take.pcm,2,0.5,Phone microphone)"), recorder.events.toList())
    }

    /** A queue whose enqueue can be HELD, so a real offer can be caught between its entry and its enqueue. */
    private class GatedQueue : java.util.concurrent.ConcurrentLinkedQueue<TakeEventPublisher.Event>() {
        val atTheGate = CountDownLatch(1)
        val gate = CountDownLatch(1)
        @Volatile var hold = false
        override fun offer(e: TakeEventPublisher.Event): Boolean {
            if (hold) {
                hold = false
                atTheGate.countDown()
                check(gate.await(10, TimeUnit.SECONDS)) { "the gate was never opened" }
            }
            return super.offer(e)
        }
    }

    @Test
    fun closeWaitsForAnOfferThatEnteredBeforeItAndDropsOneThatEntersAfter() {
        // Review rounds 2 and 3 (F3, then F1/F2): the REAL offer is driven from a producer thread through
        // a queue whose enqueue is held, so the offer has ENTERED (the lifecycle CAS) when close lands and
        // enqueues only afterwards. It must still be delivered, and only then does the worker leave; an
        // offer that enters after close is dropped by contract. Every wait is on a signal the subject or
        // the gate fires; nothing here waits for time to pass.
        publisher.close()
        val gated = GatedQueue()
        val recorder = Recorder()
        val subject = TakeEventPublisher(AtomicReference<ITakeListener?>(recorder), "test", queue = gated).also { it.start() }
        val worker = TakeEventPublisher::class.java.getDeclaredField("worker").apply { isAccessible = true }.get(subject) as Thread
        try {
            gated.hold = true
            recorder.expect(1)
            val producer = Thread({ subject.publishSilenceStatus("t1", AudioCaptureService.SILENCE_STATUS_READY) }, "producer")
            producer.start()
            check(gated.atTheGate.await(10, TimeUnit.SECONDS)) { "the producer never reached the enqueue" }
            subject.close()
            gated.gate.countDown()
            recorder.await()
            producer.join(10_000L)
            worker.join(10_000L)
            check(!worker.isAlive) { "the worker did not leave once the entered offer was delivered" }
            subject.publishSilenceStatus("t1", AudioCaptureService.SILENCE_STATUS_LOST_AFTER_READY)
            assertEquals("the entered offer was delivered and the later one dropped", listOf("t1:silence(2)"), recorder.events.toList())
        } finally {
            subject.close()
        }
    }

    @Test
    fun aDeadOwnerCostsTheEventAndNothingElse() {
        recorder.throwOnce = true
        recorder.expect(2)
        publisher.publishSilenceStatus("t1", AudioCaptureService.SILENCE_STATUS_READY)
        publisher.publishSilenceStatus("t1", AudioCaptureService.SILENCE_STATUS_LOST_AFTER_READY)
        recorder.await()
        assertEquals("the second event was delivered after the first threw", listOf("t1:silence(4)"), recorder.events.toList())
    }

    // ---- #280: the heartbeat's slot ----

    private fun workerOf(subject: TakeEventPublisher): Thread =
        TakeEventPublisher::class.java.getDeclaredField("worker").apply { isAccessible = true }.get(subject) as Thread

    /** Holds the worker inside a silence status's delivery; returns once it is held. */
    private fun holdTheWorker(recorder: Recorder, subject: TakeEventPublisher) {
        recorder.holdNextSilence = true
        subject.publishSilenceStatus("t0", AudioCaptureService.SILENCE_STATUS_READY)
        check(recorder.holding.await(10, TimeUnit.SECONDS)) { "the worker never reached the held delivery" }
    }

    /** A queue that counts offers, and whose poll on an empty queue can be held once (the worker's exit decision). */
    private class CountingQueue : java.util.concurrent.ConcurrentLinkedQueue<TakeEventPublisher.Event>() {
        val offers = AtomicInteger()
        @Volatile var holdEmptyPoll = false
        val atEmptyPoll = CountDownLatch(1)
        val releaseEmptyPoll = CountDownLatch(1)
        override fun offer(e: TakeEventPublisher.Event): Boolean {
            offers.incrementAndGet()
            return super.offer(e)
        }
        override fun poll(): TakeEventPublisher.Event? {
            val head = super.poll()
            if (head == null && holdEmptyPoll) {
                holdEmptyPoll = false
                atEmptyPoll.countDown()
                check(releaseEmptyPoll.await(10, TimeUnit.SECONDS)) { "the empty poll was never released" }
            }
            return head
        }
    }

    /**
     * Row 2b (#280 review): the clock read on every positive read returns a primitive. A `() -> Long` answers
     * through `Function0.invoke`, which returns an Object, so each read would box a Long. MUTATION m10.
     */
    @Test
    fun theHeartbeatClockReturnsAPrimitive() {
        val clock = TakeEventPublisher::class.java.getDeclaredField("nowNanos")
        assertEquals(NanoClock::class.java, clock.type)
        assertEquals(java.lang.Long.TYPE, NanoClock::class.java.getMethod("now").returnType)
    }

    /** Row 2: a heartbeat never touches the queue, and each accepted one is delivered. MUTATION m1. */
    @Test
    fun aHeartbeatNeverTouchesTheQueue() {
        val queue = CountingQueue()
        val recorder = Recorder()
        val subject = TakeEventPublisher(AtomicReference<ITakeListener?>(recorder), "test", nowNanos = { now.get() }, queue = queue).also { it.start() }
        try {
            subject.resetTicks()
            recorder.expect(1)
            subject.offerTick("t1", 0L)
            recorder.await()
            now.set(TakeEventPublisher.TICK_INTERVAL_NANOS)
            recorder.expect(1)
            subject.offerTick("t1", 1_000L)
            recorder.await()
            assertEquals(listOf("t1:tick(0)", "t1:tick(1000)"), recorder.events.toList())
            assertEquals("no heartbeat reached the queue", 0, queue.offers.get())
        } finally {
            subject.close()
        }
    }

    /**
     * Row 3: a heartbeat in the slot is not overwritten, a skipped one retries on the next read without waiting a
     * second, and the skip leaves nothing entered (the worker still leaves after close). MUTATIONS m2, m5, m8.
     */
    @Test
    fun aWaitingHeartbeatIsKeptAndASkippedOneRetries() {
        publisher.resetTicks()
        holdTheWorker(recorder, publisher)
        publisher.offerTick("t1", 100L)
        now.set(TakeEventPublisher.TICK_INTERVAL_NANOS)
        publisher.offerTick("t2", 200L)
        recorder.expect(2)
        recorder.release.countDown()
        recorder.await()
        recorder.expect(1)
        publisher.offerTick("t1", 300L)
        recorder.await()
        assertEquals(listOf("t0:silence(2)", "t1:tick(100)", "t1:tick(300)"), recorder.events.toList())
        publisher.close()
        val worker = workerOf(publisher)
        worker.join(10_000L)
        assertFalse("a skipped heartbeat left the worker waiting for an entered offer", worker.isAlive)
    }

    /** Row 4: a new take while the previous take's heartbeat waits: the new take's is sent as soon as the slot frees. */
    @Test
    fun aNewTakesFirstHeartbeatFollowsThePreviousTakesWaitingOne() {
        publisher.resetTicks()
        holdTheWorker(recorder, publisher)
        publisher.offerTick("t1", 5_000L)
        publisher.resetTicks()
        publisher.offerTick("t2", 0L)
        recorder.expect(2)
        recorder.release.countDown()
        recorder.await()
        recorder.expect(1)
        publisher.offerTick("t2", 0L)
        recorder.await()
        assertEquals(listOf("t0:silence(2)", "t1:tick(5000)", "t2:tick(0)"), recorder.events.toList())
    }

    /** Row 5: close at the worker's exit decision with a heartbeat just accepted: it is delivered, then the worker leaves. MUTATION m3. */
    @Test
    fun closeDeliversAHeartbeatAcceptedAtTheExitDecision() {
        publisher.close()
        val queue = CountingQueue().apply { holdEmptyPoll = true }
        val recorder = Recorder()
        val subject = TakeEventPublisher(AtomicReference<ITakeListener?>(recorder), "test", nowNanos = { now.get() }, queue = queue).also { it.start() }
        val worker = workerOf(subject)
        try {
            check(queue.atEmptyPoll.await(10, TimeUnit.SECONDS)) { "the worker never reached its empty poll" }
            subject.resetTicks()
            recorder.expect(1)
            subject.offerTick("t1", 700L)
            subject.close()
            queue.releaseEmptyPoll.countDown()
            recorder.await()
            worker.join(10_000L)
            assertFalse("the worker left", worker.isAlive)
            assertEquals(listOf("t1:tick(700)"), recorder.events.toList())
        } finally {
            subject.close()
        }
    }

    /** Row 6: a heartbeat offered after close is refused. MUTATION m4. */
    @Test
    fun aHeartbeatAfterCloseIsRefused() {
        publisher.resetTicks()
        holdTheWorker(recorder, publisher)
        publisher.close()
        publisher.offerTick("t1", 100L)
        recorder.expect(1)
        recorder.release.countDown()
        recorder.await()
        val worker = workerOf(publisher)
        worker.join(10_000L)
        assertFalse("the worker left", worker.isAlive)
        assertEquals(listOf("t0:silence(2)"), recorder.events.toList())
    }

    /**
     * Row 8, first half: continuing status offers do not delay a waiting heartbeat past the statuses queued
     * before it (MUTATION m7). The second half, [queuedEventsAreNotHeldBackByContinuingHeartbeats], is the
     * reverse (MUTATION m9).
     */
    @Test
    fun aHeartbeatIsNotStarvedByContinuingStatuses() {
        val statuses = AtomicInteger()
        recorder.onSilenceHook = { if (statuses.incrementAndGet() < 200) publisher.publishSilenceStatus("t1", AudioCaptureService.SILENCE_STATUS_READY) }
        publisher.resetTicks()
        holdTheWorker(recorder, publisher)
        publisher.publishSilenceStatus("t1", AudioCaptureService.SILENCE_STATUS_READY)
        recorder.expect(4)
        publisher.offerTick("t1", 100L)
        recorder.release.countDown()
        recorder.await()
        val tickAt = recorder.events.indexOf("t1:tick(100)")
        assertTrue("the heartbeat came within the first deliveries: ${recorder.events.take(5)}", tickAt in 0..3)
    }

    @Test
    fun queuedEventsAreNotHeldBackByContinuingHeartbeats() {
        val ticks = AtomicInteger()
        recorder.onTickHook = {
            if (ticks.incrementAndGet() < 50) {
                now.addAndGet(TakeEventPublisher.TICK_INTERVAL_NANOS)
                publisher.offerTick("t1", ticks.get() * 1_000L)
            }
        }
        publisher.resetTicks()
        holdTheWorker(recorder, publisher)
        repeat(3) { publisher.publishSilenceStatus("t1", AudioCaptureService.SILENCE_STATUS_LOST_AFTER_READY) }
        publisher.offerTick("t1", 0L)
        recorder.expect(1 + 3 + 50)
        recorder.release.countDown()
        recorder.await()
        val firstStatus = recorder.events.indexOf("t1:silence(4)")
        val lastTick = recorder.events.indexOfLast { it.contains(":tick(") }
        assertEquals("all three statuses arrived", 3, recorder.events.count { it == "t1:silence(4)" })
        assertTrue("a status arrived between heartbeats: ${recorder.events.take(8)}", firstStatus in 0 until lastTick)
    }
}
