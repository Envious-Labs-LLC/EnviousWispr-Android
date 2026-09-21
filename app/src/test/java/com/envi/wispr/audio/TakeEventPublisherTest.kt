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
 * throttled by which clock, and what a dead owner costs. The listener is a JVM fake of the AIDL interface
 * (`android.jar` returns defaults, so `asBinder` may answer null here).
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
        override fun onLive(forced: Boolean, routeKind: Int, routeReason: Int, liveAfterMs: Long) = record("live($forced,$routeKind,$routeReason,$liveAfterMs)")
        override fun onTick(elapsedMs: Long) = record("tick($elapsedMs)")
        override fun onSilenceStatus(status: Int) = record("silence($status)")
        override fun onEnded(terminalReason: Int, startFailure: Int, audioFilePath: String?, silenceStatus: Int, takePeakAmplitude: Float, effectiveInputDevice: String?) =
            record("ended($terminalReason,$startFailure,$audioFilePath,$silenceStatus,$takePeakAmplitude,$effectiveInputDevice)")
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
        publisher.resetTicks()
        publisher.offerTick(0L)
        now.set(500_000_000L)
        publisher.offerTick(0L)
        now.set(TakeEventPublisher.TICK_INTERVAL_NANOS)
        publisher.offerTick(0L)
        recorder.await()
        assertEquals(listOf("tick(0)", "tick(0)"), recorder.events.toList())
    }

    @Test
    fun eventsArriveInTheOrderTheyHappened() {
        recorder.expect(4)
        publisher.publishLive(false, 1, 2, 120L)
        publisher.resetTicks()
        publisher.offerTick(1_000L)
        publisher.publishSilenceStatus(AudioCaptureService.SILENCE_STATUS_READY)
        publisher.publishEnded(AudioCaptureService.TERMINAL_REASON_MANUAL, AudioCaptureService.START_FAILURE_NONE, "/tmp/take.pcm", AudioCaptureService.SILENCE_STATUS_READY, 0.5f, "Phone microphone")
        recorder.await()
        assertEquals(
            listOf("live(false,1,2,120)", "tick(1000)", "silence(2)", "ended(${AudioCaptureService.TERMINAL_REASON_MANUAL},0,/tmp/take.pcm,2,0.5,Phone microphone)"),
            recorder.events.toList(),
        )
    }

    @Test
    fun closeDeliversWhatWasQueuedBeforeTheWorkerLeaves() {
        // The service closes the publisher at the end of onDestroy, after the take's ending was queued:
        // that ending is the owner's signal and must not die with the worker. Idempotent close.
        recorder.expect(2)
        publisher.publishSilenceStatus(AudioCaptureService.SILENCE_STATUS_READY)
        publisher.publishEnded(AudioCaptureService.TERMINAL_REASON_MANUAL, AudioCaptureService.START_FAILURE_NONE, "/tmp/take.pcm", AudioCaptureService.SILENCE_STATUS_READY, 0.5f, "Phone microphone")
        publisher.close()
        publisher.close()
        recorder.await()
        assertEquals(listOf("silence(2)", "ended(${AudioCaptureService.TERMINAL_REASON_MANUAL},0,/tmp/take.pcm,2,0.5,Phone microphone)"), recorder.events.toList())
    }

    @Test
    fun aDeadOwnerCostsTheEventAndNothingElse() {
        recorder.throwOnce = true
        recorder.expect(2)
        publisher.publishSilenceStatus(AudioCaptureService.SILENCE_STATUS_READY)
        publisher.publishSilenceStatus(AudioCaptureService.SILENCE_STATUS_LOST_AFTER_READY)
        recorder.await()
        assertEquals("the second event was delivered after the first threw", listOf("silence(4)"), recorder.events.toList())
    }

}
