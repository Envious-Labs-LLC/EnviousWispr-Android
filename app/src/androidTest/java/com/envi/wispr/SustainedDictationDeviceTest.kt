package com.envi.wispr

import android.app.ActivityManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.envi.wispr.asr.AsrService
import com.envi.wispr.asr.IAsrCallback
import com.envi.wispr.asr.IAsrService
import com.envi.wispr.debug.DebugLogger
import com.envi.wispr.models.DownloadState
import com.envi.wispr.models.FirebaseModelFixture
import com.envi.wispr.models.ModelBootstrapApplication
import com.envi.wispr.models.ModelDeliveryStore
import com.envi.wispr.models.ModelManifest
import com.envi.wispr.models.ModelStorage
import com.envi.wispr.polish.IPolishCallback
import com.envi.wispr.polish.IPolishService
import com.envi.wispr.polish.PolishOutcome
import com.envi.wispr.polish.PolishPolicy
import com.envi.wispr.polish.PolishReason
import com.envi.wispr.polish.PolishService
import com.envi.wispr.polish.S1ControlSettings
import com.envi.wispr.settings.SpeechModelUnload
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Product Outcome (#37): with "Unload model after" at its default (Never), the speech model stays loaded between
 * dictations while the local polish model loads for each one. This runs that shape back to back on a real Test Lab
 * phone through the REAL `:asr` and `:polish` services and the REAL application hold, and fails when any take loses
 * its transcript or its polish, or when either process dies. Memory is reported per take for the reader to judge.
 * Each take mirrors the session: its own speech bind, the hold told at start, at the transcript and at the end, a
 * polish bind for the take, then both unbound. No capture or insertion (audio comes from synthetic clips).
 * Arguments: `takes` (default 40), `firebaseS1Fixture=true`. Fixtures in /data/local/tmp as in [AsrSpeedDeviceTest].
 */
@RunWith(AndroidJUnit4::class)
class SustainedDictationDeviceTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val activityManager = context.getSystemService(ActivityManager::class.java)

    @Test
    fun backToBackDictationsWithTheSpeechModelKeptLoadedAndLocalPolish() {
        val takes = InstrumentationRegistry.getArguments().getString("takes")?.toInt() ?: 40
        val totalRam = ActivityManager.MemoryInfo().also { activityManager.getMemoryInfo(it) }.totalMem / MB
        report("device", "${Build.MANUFACTURER} ${Build.MODEL} api=${Build.VERSION.SDK_INT} soc=${Build.SOC_MANUFACTURER}/${Build.SOC_MODEL} ram=${totalRam}MB takes=$takes")
        // #464: the main process's runtime choice, to compare with `:polish`'s "Local S1 runtime" log line.
        report("runtime", "${com.envi.wispr.polish.S1RuntimeKind.current.identity} (${com.envi.wispr.polish.S1RuntimeKind.reason})")
        stageParakeet()
        FirebaseModelFixture.stageS1IfRequested(context)
        val clips = listOf("asr-short", "asr-medium", "asr-long").map { copyFromTmp("$it.pcm", context.cacheDir) }
        val hold = checkNotNull(ModelBootstrapApplication.speechKeepAlive(context)) { "no application hold in this process" }
        onMain { hold.settingChanged(SpeechModelUnload.NEVER) }

        var firstAsrPid: Int? = null
        val failures = mutableListOf<String>()
        try {
            for (n in 1..takes) {
                val takeId = "soak-$n"
                val clip = clips[(n - 1) % clips.size]
                val started = SystemClock.elapsedRealtime()
                val asr = Bound<IAsrService>(AsrService::class.java) { IAsrService.Stub.asInterface(it) }
                try {
                    onMain { hold.takeStarted(takeId) }
                    val transcript = transcribe(asr.service, clip, takeId)
                    val asrMs = SystemClock.elapsedRealtime() - started
                    if (transcript.isNullOrBlank()) failures += "take $n: no transcript" else onMain { hold.transcriptArrived(takeId) }
                    val polish = Bound<IPolishService>(PolishService::class.java) { IPolishService.Stub.asInterface(it) }
                    val outcome = try {
                        polish(polish.service, n.toLong(), transcript.orEmpty().ifBlank { "nothing was heard" })
                    } finally {
                        polish.close()
                    }
                    if (outcome?.reason != PolishReason.POLISHED) failures += "take $n: polish ${outcome?.reason}"
                    val asrPid = pidOf("$PACKAGE:asr")
                    if (firstAsrPid == null) firstAsrPid = asrPid
                    if (asrPid != firstAsrPid) failures += "take $n: speech process changed $firstAsrPid -> $asrPid"
                    val memory = ActivityManager.MemoryInfo().also { activityManager.getMemoryInfo(it) }
                    report(
                        "take$n",
                        "asrMs=$asrMs totalMs=${SystemClock.elapsedRealtime() - started} chars=${transcript?.length} " +
                            "polish=${outcome?.reason} polishMs=${outcome?.latencyMs} engine=${outcome?.engine} " +
                            "asrPid=$asrPid asrMB=${pss(asrPid)} polishMB=${pss(pidOf("$PACKAGE:polish"))} " +
                            "mainMB=${pss(android.os.Process.myPid())} availMB=${memory.availMem / MB} low=${memory.lowMemory}",
                    )
                } finally {
                    onMain { hold.takeEnded(takeId) }
                    asr.close()
                }
                report("held$n", "${hold.holding}")
                if (!hold.holding) failures += "take $n: the hold let go"
                SystemClock.sleep(GAP_MS)
            }
        } finally {
            onMain { hold.settingChanged(SpeechModelUnload.IMMEDIATELY); hold.settingChanged(SpeechModelUnload.DEFAULT) }
            clips.forEach { it.delete() }
        }
        report("failures", failures.joinToString("; ").ifEmpty { "none" })
        assertEquals("Sustained dictation failures", emptyList<String>(), failures)
    }

    /** One binding for one take, like the session's own. */
    private inner class Bound<T>(type: Class<*>, asInterface: (IBinder) -> T) : AutoCloseable {
        private val connected = CountDownLatch(1)
        private var value: T? = null
        private val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) { value = asInterface(checkNotNull(binder)); connected.countDown() }
            override fun onServiceDisconnected(name: ComponentName?) = Unit
        }

        init {
            assertTrue("bind ${type.simpleName} refused", context.bindService(Intent(context, type), connection, Context.BIND_AUTO_CREATE))
            assertTrue("${type.simpleName} did not connect", connected.await(30, TimeUnit.SECONDS))
        }

        val service: T get() = checkNotNull(value)
        override fun close() = context.unbindService(connection)
    }

    private fun transcribe(asr: IAsrService, clip: File, takeId: String): String? {
        val done = CountDownLatch(1)
        var text: String? = null
        asr.transcribeFileForTake(clip.path, takeId, object : IAsrCallback.Stub() {
            override fun onResult(result: String?) { text = result; done.countDown() }
            override fun onError(message: String?) = done.countDown()
            override fun onFailure(reason: Int, detail: String?) = done.countDown()
        })
        assertTrue("No transcript within 150 s for $takeId", done.await(150, TimeUnit.SECONDS))
        return text
    }

    private fun polish(service: IPolishService, requestId: Long, text: String): PolishOutcome? {
        val done = CountDownLatch(1)
        var outcome: PolishOutcome? = null
        val policy = PolishPolicy.LocalS1(S1ControlSettings.DEFAULT)
        // The session warms the engine at connect; the request then queues behind the load.
        service.warmUpWithPolicy(policy)
        service.polishRequest(requestId, text, true, true, false, policy, object : IPolishCallback.Stub() {
            override fun onOutcome(delivered: PolishOutcome?) { outcome = delivered; done.countDown() }
        })
        assertTrue("No polish outcome within 90 s for request $requestId", done.await(90, TimeUnit.SECONDS))
        return outcome
    }

    private fun pidOf(process: String): Int? =
        activityManager.runningAppProcesses?.firstOrNull { it.processName == process }?.pid

    private fun pss(pid: Int?): Long? = pid?.let { activityManager.getProcessMemoryInfo(intArrayOf(it)).single().totalPss / 1024L }

    private fun onMain(block: () -> Unit) = instrumentation.runOnMainSync(block)

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
        } finally {
            source.listFiles()?.forEach { it.delete() }
            source.delete()
        }
    }

    private fun copyFromTmp(name: String, into: File): File {
        val target = File(into, name)
        val descriptor = instrumentation.uiAutomation.executeShellCommand("cat /data/local/tmp/$name")
        ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { input -> target.outputStream().use { input.copyTo(it) } }
        assertTrue("Test Lab did not upload $name", target.length() > 0)
        return target
    }

    private fun report(key: String, value: String) {
        DebugLogger.log(TAG, "$key: $value")
        instrumentation.sendStatus(0, Bundle().apply { putString("soak.$key", value) })
    }

    private companion object {
        const val TAG = "SustainedDictation37"
        const val PACKAGE = "com.envi.wispr"
        const val MB = 1024L * 1024L
        const val GAP_MS = 2_000L
    }
}
