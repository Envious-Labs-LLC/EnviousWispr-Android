package com.envi.wispr

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.IBinder
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.envi.wispr.asr.AsrService
import com.envi.wispr.asr.IAsrCallback
import com.envi.wispr.asr.IAsrService
import com.envi.wispr.polish.IPolishCallback
import com.envi.wispr.polish.IPolishService
import com.envi.wispr.polish.PolishService
import com.envi.wispr.vocabulary.BuiltinVocabulary
import com.envi.wispr.vocabulary.CustomTermRecord
import com.envi.wispr.vocabulary.CustomTermRepository
import com.envi.wispr.vocabulary.StructuredTermRestorer
import java.io.File
import java.io.FileInputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Physical-phone UAT. Place a 16 kHz mono s16le fixture in the app cache before running. */
@RunWith(AndroidJUnit4::class)
class VoicePipelineDeviceTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val fixturePath = File(context.cacheDir, "enviouswispr-uat.pcm").path
    private val asrConnected = CountDownLatch(1)
    private val polishConnected = CountDownLatch(1)
    private var asrService: IAsrService? = null
    private var polishService: IPolishService? = null
    private var asrBound = false
    private var polishBound = false

    private val asrConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            asrService = IAsrService.Stub.asInterface(binder)
            asrBound = true
            asrConnected.countDown()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            asrService = null
            asrBound = false
        }
    }

    private val polishConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            polishService = IPolishService.Stub.asInterface(binder)
            polishBound = true
            polishConnected.countDown()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            polishService = null
            polishBound = false
        }
    }

    @Before
    fun bindServices() {
        assumeTrue("Physical-phone fixture is missing", File(fixturePath).isFile)
        context.bindService(Intent(context, AsrService::class.java), asrConnection, Context.BIND_AUTO_CREATE)
        context.bindService(Intent(context, PolishService::class.java), polishConnection, Context.BIND_AUTO_CREATE)
        assertTrue("ASR service did not connect", asrConnected.await(15, TimeUnit.SECONDS))
        assertTrue("Polish service did not connect", polishConnected.await(15, TimeUnit.SECONDS))

        polishService?.warmUp()
        val deadline = System.currentTimeMillis() + 30_000
        while (polishService?.isReady != true && System.currentTimeMillis() < deadline) {
            Thread.sleep(250)
        }
        assertTrue("S1-mini did not become ready: ${polishService?.status}", polishService?.isReady == true)
    }

    @After
    fun unbindServices() {
        if (asrBound) context.unbindService(asrConnection)
        if (polishBound) context.unbindService(polishConnection)
    }

    @Test
    fun transcribesThenPolishesWithSavedCustomWords() {
        val terms = BuiltinVocabulary.withUserTerms(
            runBlocking { CustomTermRepository(context).list() }.map(CustomTermRecord::term),
        )
        val matcher = StructuredTermRestorer.compile(terms)

        val asrFinished = CountDownLatch(1)
        var rawText = ""
        var asrError = ""
        asrService?.transcribeFile(fixturePath, object : IAsrCallback.Stub() {
            override fun onResult(text: String?) {
                rawText = text.orEmpty()
                asrFinished.countDown()
            }

            override fun onError(message: String?) {
                asrError = message.orEmpty()
                asrFinished.countDown()
            }
        })
        assertTrue("ASR callback timed out", asrFinished.await(30, TimeUnit.SECONDS))
        assertTrue("ASR returned no text: $asrError", rawText.isNotBlank())

        val polishFinished = CountDownLatch(1)
        var polishedText = ""
        var engine = ""
        var latencyMs = -1L
        polishService?.polish(matcher.restore(rawText), true, true, false, object : IPolishCallback.Stub() {
            override fun onResult(text: String?, usedEngine: String?, measuredLatencyMs: Long) {
                polishedText = matcher.restore(text.orEmpty())
                engine = usedEngine.orEmpty()
                latencyMs = measuredLatencyMs
                polishFinished.countDown()
            }

            override fun onError(message: String?) {
                polishFinished.countDown()
            }
        })
        assertTrue("Polish callback timed out", polishFinished.await(30, TimeUnit.SECONDS))
        assertTrue("Unexpected engine: $engine", engine.startsWith("S1-mini by Superwhisper"))
        assertTrue("Saved product spelling was not applied: $polishedText", polishedText.contains("EnviousWispr"))
        assertTrue("Saved name spelling was not applied: $polishedText", polishedText.contains("Saurabh"))
        Log.i(
            "VoicePipelineDeviceTest",
            "rawChars=${rawText.length} engine=$engine latencyMs=$latencyMs polishedChars=${polishedText.length}"
        )
    }

    @Test
    fun launcherRecordsPhonePlaybackAndReachesClipboardStep() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        shell("logcat -c")

        val launchIntent = Intent(context, com.envi.wispr.ui.VoiceInputActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(launchIntent)
        Thread.sleep(2_000)

        val pcm = File(fixturePath).readBytes()
        val audioManager = context.getSystemService(AudioManager::class.java)
        val originalVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        val testVolume = minOf(5, audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC))
        val audioTrack = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(16_000)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(pcm.size)
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build()

        try {
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, testVolume, 0)
            assertTrue("Phone playback could not be queued", audioTrack.write(pcm, 0, pcm.size) > 0)
            audioTrack.play()
            Thread.sleep(pcm.size / 32L + 750)
        } finally {
            audioTrack.stop()
            audioTrack.release()
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, originalVolume, 0)
        }

        context.startActivity(
            Intent(context, com.envi.wispr.ui.VoiceInputActivity::class.java)
                .putExtra(com.envi.wispr.ui.VoiceInputActivity.EXTRA_STOP, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        )
        val deadline = System.currentTimeMillis() + 30_000
        var logs = ""
        while (System.currentTimeMillis() < deadline) {
            logs = shell("logcat -d -v brief -s DictationSession:I AsrService:I PolishService:I AudioCapture:I '*:S'")
            if (logs.contains("Auto-insert handed") || logs.contains("transcript kept on clipboard")) break
            Thread.sleep(500)
        }

        assertTrue("Launcher path did not reach S1: $logs", logs.contains("Polish result received (S1-mini by Superwhisper (NPU)"))
        assertTrue(
            "Launcher path did not reach the clipboard/paste step: $logs",
            logs.contains("Auto-insert handed") || logs.contains("transcript kept on clipboard"),
        )
        Log.i("VoiceLauncherDeviceTest", logs.lines().filter { it.contains("DictationSession") }.joinToString(" | "))
    }

    private fun shell(command: String): String {
        val descriptor = InstrumentationRegistry.getInstrumentation().uiAutomation
            .executeShellCommand(command)
        return FileInputStream(descriptor.fileDescriptor).bufferedReader().use { it.readText() }
            .also { descriptor.close() }
    }
}
