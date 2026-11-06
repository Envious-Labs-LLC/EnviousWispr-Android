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
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.envi.wispr.asr.AsrService
import com.envi.wispr.asr.IAsrCallback
import com.envi.wispr.asr.IAsrService
import com.envi.wispr.debug.DebugLogger
import com.envi.wispr.models.DownloadState
import com.envi.wispr.models.ModelDeliveryStore
import com.envi.wispr.models.ModelManifest
import com.envi.wispr.models.ModelStorage
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Harness Contract, #449 research: wall time from request to transcript through the REAL `:asr` service on a
 * remote Test Lab phone. Test Lab uploads the pinned Parakeet files and six synthetic speech clips (3 to 60 s) to
 * /data/local/tmp; production admission verifies the model. The first request includes the model load.
 * Synthetic speech only; nothing personal.
 *
 * #43 C1 Product Outcome (bounded): complete words through the real ASR model/Binder.
 * Harness Contracts: baseline identity, capture, transport bounds and input cleanup.
 * It bypasses recording, polish and insertion. Full synthetic words stay in its owned result file;
 * capture completion is distinct from the required manual semantic review and later frozen reference.
 */
@RunWith(AndroidJUnit4::class)
class AsrSpeedDeviceTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun captureCompleteShortAndLongBaseline() {
        assertTrue("Baseline builds must omit telemetry", BuildConfig.TELEMETRY_POSTHOG_KEY.isEmpty() && BuildConfig.TELEMETRY_SENTRY_DSN.isEmpty())
        val root = checkNotNull(context.getExternalFilesDir("asr-baseline"))
        val output = File(root, UUID.randomUUID().toString()).also { check(it.mkdirs()) }
        val resultFile = File(output, "results.json")
        var inputs: File? = null
        val requests = mutableListOf<Pair<BaselineClip, BaselineRequest>>()
        val clips = listOf(
            BaselineClip("asr-short", 68298, "f3dd840e4521de1cc6f0b3ac947f052cfe8fe618016eaa9f9291ed3005141077", 90),
            BaselineClip("asr-long", 1062142, "216852be522ca3ea03bf5eb8ccc3b58976e5d164290b00759e688f0972e52953", 45),
        )
        val report = JSONObject().put("version", 1).put("capture_complete", false)
            .put("status", "STARTED").put("semantic_review", "PENDING_MANUAL").put("frozen", false)
            .put("device", "${Build.MANUFACTURER}/${Build.MODEL}").put("api", Build.VERSION.SDK_INT)
            .put("app_apk_sha256", baselineHash(File(context.applicationInfo.sourceDir)))
            .put("test_apk_sha256", baselineHash(File(InstrumentationRegistry.getInstrumentation().context.applicationInfo.sourceDir)))
            .put("model_files", JSONArray(ModelManifest.parakeet.files.map { entry ->
                JSONObject().put("name", entry.name).put("bytes", entry.expectedBytes).put("sha256", entry.sha256)
            }))
        fun save(rows: JSONArray = JSONArray(requests.map { (clip, request) -> request.json(clip) })) {
            report.put("rows", rows)
            val bytes = report.toString(2).toByteArray(Charsets.UTF_8)
            val pending = File(output, "results.json.tmp")
            // Same synced-temp/checked-rename pattern as RescuedWords; no power-loss claim here.
            FileOutputStream(pending).use { stream -> stream.write(bytes); stream.fd.sync() }
            check(pending.renameTo(resultFile)) { "Baseline result publication failed" }
            check(resultFile.readBytes().contentEquals(bytes)) { "Baseline result readback failed" }
        }
        save()
        val connected = CountDownLatch(1)
        val link = AtomicReference<BaselineLink>(BaselineLink.Waiting)
        val waiting = AtomicReference<BaselineRequest?>()
        fun lost() {
            link.set(BaselineLink.Lost)
            waiting.get()?.finish(BaselineAnswer.Lost)
            connected.countDown()
        }
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                val asr = IAsrService.Stub.asInterface(binder)
                if (asr == null) lost() else {
                    link.compareAndSet(BaselineLink.Waiting, BaselineLink.Ready(asr))
                    connected.countDown()
                }
            }
            override fun onServiceDisconnected(name: ComponentName?) = lost()
            override fun onBindingDied(name: ComponentName?) = lost()
            override fun onNullBinding(name: ComponentName?) = lost()
        }
        val submitter = Executors.newSingleThreadExecutor { Thread(it, "AsrBaselineSubmit").apply { isDaemon = true } }
        var binding = BaselineBinding.NOT_ATTEMPTED
        var problem: Throwable? = null
        try {
            val stagedInputs = Files.createTempDirectory(context.cacheDir.toPath(), "asr-baseline-").toFile()
            inputs = stagedInputs
            report.put("input_directory", stagedInputs.path)
            save()
            if (!ModelStorage.isReady(context, ModelManifest.parakeet)) stageParakeet()
            assertTrue("Pinned Parakeet must be verified", ModelStorage.isReady(context, ModelManifest.parakeet))
            for (clip in clips) {
                val file = copyFromTmp("${clip.name}.pcm", stagedInputs)
                assertEquals("Frozen PCM byte count", clip.bytes, file.length())
                assertEquals("Frozen PCM hash", clip.sha256, baselineHash(file))
            }
            binding = BaselineBinding.ATTEMPTED
            val accepted = context.bindService(Intent(context, AsrService::class.java), connection, Context.BIND_AUTO_CREATE)
            if (accepted) binding = BaselineBinding.CONFIRMED
            assertTrue("ASR bind refused", accepted)
            assertTrue("ASR connection did not answer", connected.await(15, TimeUnit.SECONDS))
            val asr = (link.get() as? BaselineLink.Ready)?.service ?: error("ASR connection was lost")
            for (clip in clips) {
                val request = BaselineRequest()
                requests += clip to request
                waiting.set(request)
                if (link.get() !is BaselineLink.Ready) request.finish(BaselineAnswer.Lost)
                save()
                if (request.answer.get() == null) submitter.execute {
                    try {
                        asr.transcribeFileForTake(File(stagedInputs, "${clip.name}.pcm").path, request.takeId, object : IAsrCallback.Stub() {
                            override fun onResult(text: String?) = request.callback(BaselineAnswer.Words(text))
                            override fun onError(message: String?) = request.callback(BaselineAnswer.Failure(null))
                            override fun onFailure(reason: Int, detail: String?) = request.callback(BaselineAnswer.Failure(reason))
                        })
                    } catch (error: Throwable) {
                        request.submissionError.set(error.javaClass.simpleName)
                        request.finish(BaselineAnswer.TransportFailure)
                    } finally { request.submissionReturned.countDown() }
                }
                val answered = request.done.await(clip.boundSeconds, TimeUnit.SECONDS)
                save() // Retain the complete callback (or explicit incomplete state) before any assertion.
                assertTrue("No terminal ASR signal for ${clip.name}", answered)
                val words = (request.answer.get() as? BaselineAnswer.Words)?.text
                assertTrue("ASR did not return nonblank words", !words.isNullOrBlank())
                val returned = request.submissionReturned.await(5, TimeUnit.SECONDS)
                save()
                assertTrue("Synchronous ASR submission did not return", returned)
                assertTrue("ASR submission threw", request.submissionError.get() == null)
                assertTrue("Expected one native callback", request.callbackCount.get() == 1)
                if (clip.name == "asr-short") {
                    assertTrue("Complete short oracle failed; see private result", words == "Please send the report tomorrow morning.")
                } else assertBaselineLong(checkNotNull(words))
            }
            assertTrue("ASR service was lost during baseline capture", link.get() is BaselineLink.Ready)
            assertTrue("Duplicate callback observed", requests.all { it.second.callbackCount.get() == 1 })
        } catch (error: Throwable) {
            problem = error
            report.put("status", "FAILED").put("failure_class", error.javaClass.simpleName)
            runCatching { save() }.exceptionOrNull()?.let(error::addSuppressed)
        } finally {
            fun attempt(action: () -> Unit) {
                try { action() } catch (error: Throwable) {
                    val previous = problem
                    if (previous == null) problem = error else previous.addSuppressed(error)
                }
            }
            waiting.set(null)
            attempt {
                when (binding) {
                    BaselineBinding.NOT_ATTEMPTED -> Unit
                    BaselineBinding.ATTEMPTED -> try { context.unbindService(connection) }
                        catch (_: IllegalArgumentException) { /* The refused/throwing bind registered no connection. */ }
                    BaselineBinding.CONFIRMED -> context.unbindService(connection)
                }
            }
            attempt { submitter.shutdown() }
            // A callback is input-consumption evidence for this unchanged service, not model/task retirement.
            val readComplete = requests.all { it.second.answer.get() is BaselineAnswer.Words && it.second.submissionReturned.count == 0L }
            val ownedInputs = inputs
            val removed = mutableListOf<Boolean>()
            if (readComplete && ownedInputs != null) {
                clips.forEach { clip -> attempt {
                    val file = File(ownedInputs, "${clip.name}.pcm")
                    val gone = file.delete() || !file.exists()
                    removed += gone
                    check(gone) { "Owned PCM cleanup failed" }
                } }
                attempt { check(ownedInputs.delete()) { "Owned input-directory cleanup failed" } }
            }
            report.put("input_cleanup", when {
                ownedInputs == null -> "NOT_CREATED"
                !readComplete -> "RETAINED_UNCONFIRMED"
                removed.size == clips.size && removed.all { it } && !ownedInputs.exists() -> "READ_COMPLETE_INPUTS_REMOVED"
                else -> "CLEANUP_FAILED"
            })
            // Validate and publish the same immutable observed rows, including late duplicate callbacks.
            val finalRows = JSONArray(requests.map { (clip, request) -> request.json(clip) })
            val lossObserved = link.get() is BaselineLink.Lost
            if (problem == null && (lossObserved || finalRows.length() != clips.size ||
                    (0 until finalRows.length()).any { finalRows.getJSONObject(it).getInt("observed_callback_count") != 1 })) {
                problem = AssertionError("Baseline terminal snapshot failed")
            }
            report.put("unexpected_service_loss_observed", lossObserved)
            val failed = problem
            report.put("capture_complete", failed == null)
                .put("status", if (failed == null) "CAPTURED_PENDING_MANUAL_REVIEW" else "FAILED")
            if (failed != null) report.put("failure_class", failed.javaClass.simpleName)
            attempt { save(finalRows) } // The first complete snapshot follows all required work.
            if (problem != null) {
                report.put("capture_complete", false).put("status", "FAILED")
                    .put("failure_class", checkNotNull(problem).javaClass.simpleName)
                attempt { save(finalRows) } // Best effort: retain an incomplete receipt after publication failure.
            }
        }
        problem?.let { throw it }
    }

    private data class BaselineClip(val name: String, val bytes: Long, val sha256: String, val boundSeconds: Long)
    private enum class BaselineBinding { NOT_ATTEMPTED, ATTEMPTED, CONFIRMED }
    private sealed interface BaselineLink {
        data object Waiting : BaselineLink
        data class Ready(val service: IAsrService) : BaselineLink
        data object Lost : BaselineLink
    }
    private sealed interface BaselineAnswer {
        data class Words(val text: String?) : BaselineAnswer
        data class Failure(val code: Int?) : BaselineAnswer
        data object Lost : BaselineAnswer
        data object TransportFailure : BaselineAnswer
    }
    private class BaselineRequest {
        val takeId: String = UUID.randomUUID().toString()
        val answer = AtomicReference<BaselineAnswer?>()
        val callbackCount = AtomicInteger()
        val done = CountDownLatch(1)
        val submissionReturned = CountDownLatch(1)
        val submissionError = AtomicReference<String?>()
        fun callback(value: BaselineAnswer) { callbackCount.incrementAndGet(); finish(value) }
        fun finish(value: BaselineAnswer) { if (answer.compareAndSet(null, value)) done.countDown() }
        fun json(clip: BaselineClip): JSONObject {
            val result = JSONObject().put("case", clip.name).put("take_id", takeId)
                .put("input_bytes", clip.bytes).put("input_sha256", clip.sha256)
                .put("observed_callback_count", callbackCount.get()).put("submission_returned", submissionReturned.count == 0L)
                .put("submission_failure_class", submissionError.get() ?: JSONObject.NULL)
            when (val value = answer.get()) {
                null -> result.put("outcome", "PENDING")
                is BaselineAnswer.Words -> {
                    result.put("outcome", "WORDS").put("text", value.text ?: JSONObject.NULL)
                    value.text?.let { text ->
                        val hash = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
                        result.put("text_utf8_sha256", hash.joinToString("") { "%02x".format(it) })
                            .put("text_codepoints", text.codePointCount(0, text.length))
                    }
                }
                is BaselineAnswer.Failure -> result.put("outcome", "FAILURE").put("failure_code", value.code ?: JSONObject.NULL)
                BaselineAnswer.Lost -> result.put("outcome", "SERVICE_LOST")
                BaselineAnswer.TransportFailure -> result.put("outcome", "TRANSPORT_FAILURE")
            }
            return result
        }
    }

    private fun baselineHash(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input -> val buffer = ByteArray(65536); while (true) { val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) } }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    @Test
    fun parakeetTranscriptionSpeedOnThisPhone() {
        report("device", "${Build.MANUFACTURER} ${Build.MODEL} api=${Build.VERSION.SDK_INT} soc=${Build.SOC_MANUFACTURER}/${Build.SOC_MODEL}")
        report("cpuFeatures", File("/proc/cpuinfo").readLines().filter { it.startsWith("Features") }.distinct().joinToString(" || "))
        stageParakeet()
        val names = listOf("asr-short", "asr-medium", "asr-long", "asr-xlong", "asr-30s", "asr-60s")
        val clips = names.associateWith { copyFromTmp("$it.pcm", context.cacheDir) }

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
            // Three rounds back to back, each in a different order, so long and short requests follow each other (#453).
            repeat(3) { run ->
                for (name in names.drop(run) + names.take(run)) report("$name#$run", "${transcribe(asr, clips.getValue(name))} asrMB=${asrPss()}")
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

    /** The speech process's memory after a request, in MB; null when it is not running. */
    private fun asrPss(): Long? {
        val am = context.getSystemService(android.app.ActivityManager::class.java)
        val pid = am.runningAppProcesses?.firstOrNull { it.processName == "com.envi.wispr:asr" }?.pid ?: return null
        return am.getProcessMemoryInfo(intArrayOf(pid)).single().totalPss / 1024L
    }

    internal fun stageParakeet() {
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

    internal fun copyFromTmp(name: String, into: File): File {
        val target = File(into, name)
        val descriptor = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("cat /data/local/tmp/$name")
        ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { input -> target.outputStream().use { input.copyTo(it) } }
        assertTrue("Test Lab did not upload $name", target.length() > 0)
        return target
    }

    private fun report(key: String, value: String) {
        DebugLogger.log(TAG, "$key: $value")
        InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply { putString("asr.$key", value) })
    }

    private companion object { const val TAG = "AsrSpeed449" }
}

private fun assertBaselineLong(text: String) {
    val clauses = listOf(
        "send the report tomorrow morning", "meeting starts at (?:nine|9(?::00)?)(?![\\d:]|\\.\\d)(?=\\s*(?:[,.;]|and\\b|$))", "Maya.*revised budget",
        "we need (?:three|3) copies for the design team", "keep the original schedule", "confirm the delivery address before sending the package",
        "after lunch.*review the customer notes.*write down the (?:two|2) remaining questions", "purple lantern.*beside the quiet garden",
        "Jordan.*Thursday.*replacement parts", "blue folder on the (?:second|2nd) shelf",
        "invoice lists (?:\\$\\s*45(?:\\.00)?|(?:forty[- ]five|45(?:\\.00)?)\\s+dollars)(?!\\d|\\.\\d)(?=\\s*(?:and\\b|[,.;]|$))",
        "appointment.*still.*Friday afternoon",
    )
    clauses.forEachIndexed { index, pattern ->
        assertTrue("Long clause oracle $index failed; see private result", Regex(pattern, setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)).containsMatchIn(text))
    }
    assertTrue("Introduced negation in baseline", !Regex("\\b(?:not|never|no|cannot)\\b|n['’]t\\b", RegexOption.IGNORE_CASE).containsMatchIn(text))
}
