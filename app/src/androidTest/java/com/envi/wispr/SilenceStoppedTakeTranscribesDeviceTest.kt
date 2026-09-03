package com.envi.wispr

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import androidx.test.platform.app.InstrumentationRegistry
import com.envi.wispr.asr.AsrService
import com.envi.wispr.asr.IAsrCallback
import com.envi.wispr.asr.IAsrService
import com.envi.wispr.audio.AudioCaptureService
import com.envi.wispr.audio.IAudioCaptureService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The words survive a take that ended itself.
 *
 * The read-block split is on the heart path, so "it stopped at the right moment" is not enough: the audio
 * it wrote has to still be audio. This records real speech, lets the silence end the take, and hands the
 * resulting file to the real speech engine.
 *
 * It stops short of insertion, which needs an unlocked phone and a real editor. Nothing between the
 * transcript and the editor is touched by this change.
 */
class SilenceStoppedTakeTranscribesDeviceTest {

    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun <T> bind(intent: Intent, wrap: (IBinder?) -> T): Pair<T, ServiceConnection> {
        val latch = CountDownLatch(1)
        var value: T? = null
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                value = wrap(binder); latch.countDown()
            }
            override fun onServiceDisconnected(name: ComponentName?) {}
        }
        assertTrue(context.bindService(intent, connection, Context.BIND_AUTO_CREATE))
        assertTrue("service must connect", latch.await(20, TimeUnit.SECONDS))
        return value!! to connection
    }

    @Test
    fun aTakeThatEndedOnSilenceStillProducesWords() {
        val (capture, captureConnection) = bind(
            Intent(context, AudioCaptureService::class.java),
        ) { IAudioCaptureService.Stub.asInterface(it) }

        val filePath: String
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

            context.startActivity(
                Intent()
                    .setClassName("com.envi.wispr.test", "com.envi.wispr.SpeakerPlaybackActivity")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )

            var ended = false
            for (i in 0 until 250) {
                Thread.sleep(100)
                if (!capture.isCapturing) { ended = true; break }
            }
            assertTrue("the take must end on its own", ended)
            assertEquals(
                "and end because of the silence",
                AudioCaptureService.TERMINAL_REASON_SILENCE,
                capture.terminalReason,
            )
            assertTrue("the file must close", capture.waitForFileReady(5_000L))
            filePath = capture.audioFilePath.orEmpty()
            assertTrue("there must be a recording to transcribe", filePath.isNotBlank())
        } finally {
            runCatching { context.unbindService(captureConnection) }
        }

        val (asr, asrConnection) = bind(
            Intent(context, AsrService::class.java),
        ) { IAsrService.Stub.asInterface(it) }
        try {
            val done = CountDownLatch(1)
            var text = ""
            var error = ""
            asr.transcribeFile(filePath, object : IAsrCallback.Stub() {
                override fun onResult(result: String?) { text = result.orEmpty(); done.countDown() }
                override fun onError(message: String?) { error = message.orEmpty(); done.countDown() }
            })
            assumeTrue("the speech model must be installed on this phone", error.isBlank() || done.await(1, TimeUnit.SECONDS))
            assertTrue("the speech engine must answer", done.await(60, TimeUnit.SECONDS))
            assumeTrue("the speech model must be installed on this phone", !error.contains("not ready", true))

            assertTrue(
                "a take that ended on silence must still contain words. error was '$error', text was '$text'",
                text.isNotBlank(),
            )
            android.util.Log.i("SilenceUat", "Transcript of a silence-stopped take: '$text'")
        } finally {
            runCatching { context.unbindService(asrConnection) }
        }
    }
}
