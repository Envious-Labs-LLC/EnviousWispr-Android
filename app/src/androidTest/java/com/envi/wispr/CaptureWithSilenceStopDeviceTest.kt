package com.envi.wispr

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import androidx.test.platform.app.InstrumentationRegistry
import com.envi.wispr.audio.AudioCaptureService
import com.envi.wispr.audio.IAudioCaptureService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The REAL capture path with the detector attached, on the phone, across the real process boundary.
 *
 * The other device test proves the detector works. This proves the WIRING: that `:audio` binds `:vad`,
 * feeds it, and reports a status, using the same binder the app uses. It needs no screen, so it can run
 * while the phone is locked; what it cannot do is play audio, because that needs a visible activity.
 *
 * It therefore asserts the half that does not need sound: the negative control, that a room with nobody
 * speaking in it never ends a take by itself.
 */
class CaptureWithSilenceStopDeviceTest {

    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun bindCapture(): Pair<IAudioCaptureService, ServiceConnection> {
        val latch = CountDownLatch(1)
        var service: IAudioCaptureService? = null
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                service = IAudioCaptureService.Stub.asInterface(binder)
                latch.countDown()
            }
            override fun onServiceDisconnected(name: ComponentName?) { service = null }
        }
        assertTrue(
            "the capture service must bind",
            context.bindService(
                Intent(context, AudioCaptureService::class.java),
                connection,
                Context.BIND_AUTO_CREATE,
            ),
        )
        assertTrue("it must connect within ten seconds", latch.await(10, TimeUnit.SECONDS))
        return service!! to connection
    }

    /** The speaker finishes itself when the fixture ends. Overlapping runs measure the wrong audio. */
    private fun waitForTheSpeakerToFinish() {
        Thread.sleep(20_000)
    }

    /** Plays whatever fixture is in this package's cache, through the speaker, over the lock screen. */
    private fun playFixture() {
        context.startActivity(
            Intent()
                .setClassName("com.envi.wispr.test", "com.envi.wispr.SpeakerPlaybackActivity")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    @Test
    fun realSpeechThroughTheMicrophoneEndsTheTakeWhenItStops() {
        // The one that matters: real sound, the real microphone, the real detector, the real ending.
        // The fixture is the founder's own recorded speech followed by eight seconds of silence, so a
        // take that does NOT end here is a take that would never end.
        val (capture, connection) = bindCapture()
        try {
            assumeTrue(
                "the microphone must be available to this test",
                capture.startCaptureWithSilenceStop(true, 1.5f),
            )

            var ready = false
            for (i in 0 until 40) {
                if (capture.silenceStopStatus == AudioCaptureService.SILENCE_STATUS_READY) { ready = true; break }
                Thread.sleep(100)
            }
            assumeTrue("the detector must be ready before the audio starts", ready)

            playFixture()

            var endedAfterMs = -1L
            val startedAt = System.currentTimeMillis()
            for (i in 0 until 250) {
                Thread.sleep(100)
                if (!capture.isCapturing) { endedAfterMs = System.currentTimeMillis() - startedAt; break }
            }

            assertTrue(
                "speech then silence must end the take by itself; it did not in 25 seconds",
                endedAfterMs > 0,
            )
            assertEquals(
                "and it must end BECAUSE of the silence, not for any other reason",
                AudioCaptureService.TERMINAL_REASON_SILENCE,
                capture.terminalReason,
            )
            assertTrue("the recording must have closed", capture.waitForFileReady(3_000L))
            // The fixture speaks for about 3.7 s before its silence begins, so an ending sooner than
            // that would mean the take ended before the speech, which is the failure this test hides
            // behind its own success if nobody checks.
            assertTrue(
                "it must not have ended before the speech finished, ended after ${endedAfterMs}ms",
                endedAfterMs > 3_000,
            )
        } finally {
            runCatching { context.unbindService(connection) }
        }
    }

    /**
     * The slider has to be worth having. Same audio, two settings, two outcomes.
     *
     * The fixture speaks, goes quiet for 2.5 seconds, speaks again, then goes quiet for good. At the
     * default the take must end IN that gap. At the longest setting it must survive it, hear the second
     * half, and only then end. If both behaved the same the control would be decoration.
     *
     * Run as one test rather than two because the two halves are only meaningful against each other.
     */
    @Test
    fun theWaitSettingDecidesWhetherAThinkingPauseEndsTheTake() {
        val gapEndings = mutableMapOf<Float, Long>()

        listOf(1.5f, 3.0f).forEach { pause ->
            val (capture, connection) = bindCapture()
            try {
                assumeTrue(
                    "the microphone must be available to this test",
                    capture.startCaptureWithSilenceStop(true, pause),
                )
                var ready = false
                for (i in 0 until 40) {
                    if (capture.silenceStopStatus == AudioCaptureService.SILENCE_STATUS_READY) { ready = true; break }
                    Thread.sleep(100)
                }
                assumeTrue("the detector must be ready before the audio starts", ready)

                playFixture()
                val startedAt = System.currentTimeMillis()
                var endedAfterMs = -1L
                for (i in 0 until 300) {
                    Thread.sleep(100)
                    if (!capture.isCapturing) { endedAfterMs = System.currentTimeMillis() - startedAt; break }
                }
                assertTrue("the take must end at $pause seconds", endedAfterMs > 0)
                assertEquals(
                    "and end because of the silence",
                    AudioCaptureService.TERMINAL_REASON_SILENCE,
                    capture.terminalReason,
                )
                capture.waitForFileReady(3_000L)
                gapEndings[pause] = endedAfterMs
            } finally {
                runCatching { context.unbindService(connection) }
            }

            // The fixture is 18 seconds long and the speaker finishes itself only when it ends. The
            // first attempt at this test measured the SECOND run against audio the FIRST run was still
            // playing, which made the two settings look identical when they were not. Wait it out.
            waitForTheSpeakerToFinish()
        }

        val short = gapEndings.getValue(1.5f)
        val long = gapEndings.getValue(3.0f)
        assertTrue(
            "the longer setting must survive the 2.5 second pause the shorter one ends in: " +
                "1.5s ended after ${short}ms, 3.0s ended after ${long}ms",
            long > short + 2_000,
        )
    }

    @Test
    fun aQuietRoomNeverEndsATakeByItselfThroughTheWholeRealPath() {
        val (capture, connection) = bindCapture()
        try {
            val started = capture.startCaptureWithSilenceStop(true, 1.5f)
            // The microphone may be refused to a background caller on this Android version. That is a
            // property of the harness, not of the change, so it is a SKIP rather than a red row.
            assumeTrue("the microphone must be available to this test", started)

            // Long enough that a detector willing to stop on silence alone would have done so many times
            // over: the nominal wait at 1.5 seconds is under two.
            var stillCapturing = true
            for (i in 0 until 60) {
                Thread.sleep(200)
                if (!capture.isCapturing) { stillCapturing = false; break }
            }

            assertTrue(
                "a room with nobody speaking must never end a take: the machine cannot reach the " +
                    "countdown without passing through speech first",
                stillCapturing,
            )

            capture.stopCapture()
            assertTrue("the file must close", capture.waitForFileReady(3_000L))
            assertEquals(
                "and the ending must be the manual one, because nothing else ended it",
                AudioCaptureService.TERMINAL_REASON_MANUAL,
                capture.terminalReason,
            )
        } finally {
            runCatching { context.unbindService(connection) }
        }
    }

    @Test
    fun theDetectorBecomesReadyForARealTakeAndTheStatusSaysSo() {
        val (capture, connection) = bindCapture()
        try {
            val started = capture.startCaptureWithSilenceStop(true, 1.5f)
            assumeTrue("the microphone must be available to this test", started)

            var status = capture.silenceStopStatus
            for (i in 0 until 50) {
                if (status == AudioCaptureService.SILENCE_STATUS_READY) break
                Thread.sleep(200)
                status = capture.silenceStopStatus
            }
            capture.stopCapture()
            capture.waitForFileReady(3_000L)

            assertEquals(
                "the detector must reach ready on a real take, not merely be asked for",
                AudioCaptureService.SILENCE_STATUS_READY,
                status,
            )
        } finally {
            runCatching { context.unbindService(connection) }
        }
    }

    @Test
    fun withTheSwitchOffNoDetectorIsAskedForAtAll() {
        val (capture, connection) = bindCapture()
        try {
            val started = capture.startCaptureWithSilenceStop(false, 0f)
            assumeTrue("the microphone must be available to this test", started)
            Thread.sleep(1_500)
            assertEquals(
                "off must mean off: no detector, no status to report",
                AudioCaptureService.SILENCE_STATUS_DISABLED,
                capture.silenceStopStatus,
            )
            capture.stopCapture()
            capture.waitForFileReady(3_000L)
        } finally {
            runCatching { context.unbindService(connection) }
        }
    }

    @Test
    fun anOutOfRangePauseRefusesAutoStopButStillRecords() {
        val (capture, connection) = bindCapture()
        try {
            val started = capture.startCaptureWithSilenceStop(true, 99f)
            assumeTrue("the microphone must be available to this test", started)
            Thread.sleep(500)
            assertEquals(
                "a value nobody could have chosen refuses auto-stop rather than substituting one",
                AudioCaptureService.SILENCE_STATUS_UNAVAILABLE,
                capture.silenceStopStatus,
            )
            assertTrue("and ordinary recording is untouched", capture.isCapturing)
            capture.stopCapture()
            assertTrue(capture.waitForFileReady(3_000L))
        } finally {
            runCatching { context.unbindService(connection) }
        }
    }
}
