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
