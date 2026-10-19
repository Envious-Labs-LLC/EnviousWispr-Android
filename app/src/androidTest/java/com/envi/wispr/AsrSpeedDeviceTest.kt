package com.envi.wispr

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.envi.wispr.asr.AsrService
import com.envi.wispr.asr.IAsrCallback
import com.envi.wispr.asr.IAsrService
import com.envi.wispr.models.DownloadState
import com.envi.wispr.models.ModelDeliveryStore
import com.envi.wispr.models.ModelManifest
import com.envi.wispr.models.ModelStorage
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Harness Contract, #449 research: wall time from request to transcript through the REAL `:asr` service on a
 * remote Test Lab phone. Test Lab uploads the pinned Parakeet files and three synthetic speech clips to
 * /data/local/tmp; production admission verifies the model. The first request includes the model load.
 * Synthetic speech only; nothing personal.
 */
@RunWith(AndroidJUnit4::class)
class AsrSpeedDeviceTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun parakeetTranscriptionSpeedOnThisPhone() {
        report("device", "${Build.MANUFACTURER} ${Build.MODEL} api=${Build.VERSION.SDK_INT} soc=${Build.SOC_MANUFACTURER}/${Build.SOC_MODEL}")
        report("cpuFeatures", File("/proc/cpuinfo").readLines().filter { it.startsWith("Features") }.distinct().joinToString(" || "))
        stageParakeet()
        val clips = listOf("asr-short", "asr-medium", "asr-long").associateWith { copyFromTmp("$it.pcm", context.cacheDir) }

        val connected = CountDownLatch(1)
        var service: IAsrService? = null
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) { service = IAsrService.Stub.asInterface(binder); connected.countDown() }
            override fun onServiceDisconnected(name: ComponentName?) = Unit
        }
        val bindStarted = SystemClock.elapsedRealtime()
        context.bindService(Intent(context, AsrService::class.java), connection, Context.BIND_AUTO_CREATE)
        try {
            assertTrue("AsrService did not connect", connected.await(30, TimeUnit.SECONDS))
            val asr = checkNotNull(service)
            // Cold: the first request queues behind the model load started in onCreate.
            report("cold.short", "${transcribe(asr, clips.getValue("asr-short")) { SystemClock.elapsedRealtime() - bindStarted }}")
            for (name in listOf("asr-short", "asr-medium", "asr-long")) repeat(2) { run ->
                report("$name#$run", transcribe(asr, clips.getValue(name)))
            }
        } finally {
            context.unbindService(connection)
            clips.values.forEach { it.delete() }
        }
    }

    private fun transcribe(asr: IAsrService, clip: File, elapsed: ((Long) -> Long)? = null): String {
        val done = CountDownLatch(1)
        var text: String? = null
        var failure: String? = null
        val started = SystemClock.elapsedRealtime()
        asr.transcribeFileForTake(clip.path, "asr-speed-449", object : IAsrCallback.Stub() {
            override fun onResult(result: String?) { text = result; done.countDown() }
            override fun onError(message: String?) { failure = "error"; done.countDown() }
            override fun onFailure(reason: Int, detail: String?) { failure = "failure $reason"; done.countDown() }
        })
        assertTrue("No transcript within 150 s for ${clip.name}", done.await(150, TimeUnit.SECONDS))
        val wall = SystemClock.elapsedRealtime() - started
        assertEquals("Transcription failed for ${clip.name}", null, failure)
        val words = assertNotNull(text).let { text!! }
        val audioSec = clip.length() / 32_000.0
        return "${elapsed?.invoke(wall) ?: wall}ms audio=${"%.1f".format(audioSec)}s rtf=${"%.2f".format(wall / 1000.0 / audioSec)} chars=${words.length} text=${words.take(160)}"
    }

    private fun stageParakeet() {
        val model = ModelManifest.parakeet
        val source = Files.createTempDirectory(context.cacheDir.toPath(), "firebase-parakeet-").toFile()
        try {
            model.files.forEach { entry ->
                val local = copyFromTmp(entry.name, source)
                assertEquals("Test Lab fixture ${entry.name} is incomplete", entry.expectedBytes, local.length())
            }
            val store = ModelDeliveryStore(ModelStorage.root(context))
            assertEquals("Pinned Parakeet admission failed", DownloadState.READY, store.adoptExisting(model, source).state)
            assertTrue("Admitted Parakeet did not verify", store.isVerified(model))
        } finally {
            source.listFiles()?.forEach { it.delete() }
            source.delete()
        }
    }

    private fun copyFromTmp(name: String, into: File): File {
        val target = File(into, name)
        val descriptor = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("cat /data/local/tmp/$name")
        ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { input -> target.outputStream().use { input.copyTo(it) } }
        assertTrue("Test Lab did not upload $name", target.length() > 0)
        return target
    }

    private fun report(key: String, value: String) {
        Log.i(TAG, "$key: $value")
        InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply { putString("asr.$key", value) })
    }

    private companion object { const val TAG = "AsrSpeed449" }
}
