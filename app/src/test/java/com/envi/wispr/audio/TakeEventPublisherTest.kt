package com.envi.wispr.audio

import android.os.IBinder
import android.os.RemoteException
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Harness and Drift rows on the take-event publisher (#115): what leaves the audio process, in what order,
 * under which take's id, throttled by which clock, and what a dead owner costs. The listener is a JVM fake
 * of the AIDL interface (`android.jar` returns defaults, so `asBinder` may answer null here).
 */
class TakeEventPublisherTest {
    private class Recorder : ITakeListener {
        val events = CopyOnWriteArrayList<String>()
        @Volatile var throwOnce = false
        private var latch = CountDownLatch(0)

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
        override fun onTick(takeId: String?, elapsedMs: Long) = record("$takeId:tick($elapsedMs)")
        override fun onSilenceStatus(takeId: String?, status: Int) = record("$takeId:silence($status)")
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
        recorder.expect(2)
        publisher.beginTake("t1")
        publisher.offerTick(0L)
        now.set(500_000_000L)
        publisher.offerTick(0L)
        now.set(TakeEventPublisher.TICK_INTERVAL_NANOS)
        publisher.offerTick(0L)
        recorder.await()
        assertEquals(listOf("t1:tick(0)", "t1:tick(0)"), recorder.events.toList())
    }

    @Test
    fun eventsArriveInTheOrderTheyHappenedUnderTheirTakesId() {
        recorder.expect(4)
        publisher.beginTake("t1")
        publisher.publishLive(false, 1, 2, 120L)
        publisher.offerTick(1_000L)
        publisher.publishSilenceStatus(AudioCaptureService.SILENCE_STATUS_READY)
        publisher.publishEnded("t1", AudioCaptureService.TERMINAL_REASON_MANUAL, AudioCaptureService.START_FAILURE_NONE, "/tmp/take.pcm", AudioCaptureService.SILENCE_STATUS_READY, 0.5f, "Phone microphone")
        recorder.await()
        assertEquals(
            listOf("t1:live(false,1,2,120)", "t1:tick(1000)", "t1:silence(2)", "t1:ended(${AudioCaptureService.TERMINAL_REASON_MANUAL},0,/tmp/take.pcm,2,0.5,Phone microphone)"),
            recorder.events.toList(),
        )
    }

    @Test
    fun aRefusedStartEndsUnderTheRequestedIdWhileThePreviousTakeKeepsItsOwn() {
        // Review round 1, F2 and F6: the previous take is still open (its id is current) when the next
        // owner's start is refused as busy. The refusal is published under the REQUESTED id with nothing of
        // the previous take, and the previous take's ending, later, still carries ITS id.
        recorder.expect(2)
        publisher.beginTake("t1")
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
        publisher.beginTake("t1")
        publisher.publishSilenceStatus(AudioCaptureService.SILENCE_STATUS_READY)
        publisher.publishEnded("t1", AudioCaptureService.TERMINAL_REASON_MANUAL, AudioCaptureService.START_FAILURE_NONE, "/tmp/take.pcm", AudioCaptureService.SILENCE_STATUS_READY, 0.5f, "Phone microphone")
        publisher.close()
        publisher.close()
        recorder.await()
        assertEquals(listOf("t1:silence(2)", "t1:ended(${AudioCaptureService.TERMINAL_REASON_MANUAL},0,/tmp/take.pcm,2,0.5,Phone microphone)"), recorder.events.toList())
    }

    @Test
    fun aDeadOwnerCostsTheEventAndNothingElse() {
        recorder.throwOnce = true
        recorder.expect(2)
        publisher.beginTake("t1")
        publisher.publishSilenceStatus(AudioCaptureService.SILENCE_STATUS_READY)
        publisher.publishSilenceStatus(AudioCaptureService.SILENCE_STATUS_LOST_AFTER_READY)
        recorder.await()
        assertEquals("the second event was delivered after the first threw", listOf("t1:silence(4)"), recorder.events.toList())
    }
}
