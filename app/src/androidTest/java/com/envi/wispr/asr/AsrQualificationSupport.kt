package com.envi.wispr.asr

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import com.envi.wispr.AsrSpeedDeviceTest
import com.envi.wispr.audio.PcmAudio
import com.envi.wispr.models.ModelManifest
import com.envi.wispr.models.ModelStorage
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONObject

/** Fixed synthetic inputs/frozen old-source outputs, never expectations manufactured by this candidate. */
internal class AsrQualificationInputs(context: Context, val directory: File) {
    data class Clip(val name: String, val file: File, val expected: String, val sha256: String) {
        fun samples(): FloatArray = PcmAudio.toFloatSamples(file.readBytes()) { }
        fun requireWords(text: String) { check(text == expected) { "Complete frozen transcript mismatch for $name; see owned report" } }
    }
    private val baseline = JSONObject(InstrumentationRegistry.getInstrumentation().context.assets.open("asr-cancellation-baseline.json").bufferedReader().use { it.readText() })
    val preprocessor: ByteArray = context.assets.open(ParakeetEngine.PREPROCESSOR_ASSET).use { it.readBytes() }
    val short: Clip
    val long: Clip
    init {
        check(directory.isDirectory) { "The caller must own an exclusive input directory before setup" }
        check(baseline.getBoolean("frozen") && baseline.getString("manual_semantic_review") == "ACCEPTED")
        check(hash(preprocessor) == baseline.getString("preprocessor_sha256")) { "Preprocessor differs from the frozen old APK" }
        val libraries = baseline.getJSONObject("packaged_runtime_libraries")
        libraries.keys().forEach { name ->
            val expected = libraries.getJSONObject(name)
            val file = File(context.applicationInfo.nativeLibraryDir, name)
            check(file.length() == expected.getLong("bytes") && hash(file) == expected.getString("sha256")) { "Runtime differs from the frozen old packaged APK" }
        }
        val harness = AsrSpeedDeviceTest()
        if (!ModelStorage.isReady(context, ModelManifest.parakeet)) harness.stageParakeet()
        check(ModelStorage.isReady(context, ModelManifest.parakeet)) { "Current pinned Parakeet was not verified" }
        val root = ModelStorage.directory(context, ModelManifest.parakeet)
        val models = baseline.getJSONArray("model_files")
        for (i in 0 until models.length()) {
            val expected = models.getJSONObject(i)
            val file = File(root, expected.getString("name"))
            check(file.length() == expected.getLong("bytes") && hash(file) == expected.getString("sha256")) { "Frozen model identity mismatch" }
        }
        val clips = baseline.getJSONArray("rows")
        fun clip(index: Int): Clip {
            val expected = clips.getJSONObject(index)
            val name = expected.getString("case")
            check(name == if (index == 0) "asr-short" else "asr-long")
            val file = harness.copyFromTmp("$name.pcm", directory)
            check(file.length() == expected.getLong("input_bytes") && hash(file) == expected.getString("input_sha256")) { "Frozen PCM identity mismatch" }
            val text = expected.getString("text")
            check(hash(text.toByteArray()) == expected.getString("text_utf8_sha256"))
            check(text.codePointCount(0, text.length) == expected.getInt("text_codepoints"))
            return Clip(name, file, text, expected.getString("input_sha256"))
        }
        short = clip(0)
        long = clip(1)
    }
    fun removeConsumedInputs() {
        removeOwnedInputs(directory)
    }
    companion object {
        fun removeOwnedInputs(directory: File) {
            listOf("asr-short.pcm", "asr-long.pcm").forEach { name ->
                val file = File(directory, name)
                check(file.delete() || !file.exists()) { "Owned PCM cleanup failed" }
            }
            check(directory.delete()) { "Owned input directory cleanup failed; unknown entries retained" }
        }
        fun hash(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        fun hash(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input -> val buffer = ByteArray(65_536); while (true) { val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) } }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
    }
}

/** 260 seconds active + 30 cleanup, leaving runner overhead inside the physical matrix's five-minute cap. */
internal class AsrQualificationBudget {
    private val start = System.nanoTime()
    private val activeUntil = start + TimeUnit.SECONDS.toNanos(260)
    private val cleanupUntil = start + TimeUnit.SECONDS.toNanos(290)
    class Step(private val until: Long) {
        fun left(): Long {
            val nanos = until - System.nanoTime()
            check(nanos > 0) { "Qualification step exhausted its declared budget" }
            return TimeUnit.NANOSECONDS.toMillis(nanos).coerceAtLeast(1)
        }
        fun await(signal: CountDownLatch, what: String) { check(signal.await(left(), TimeUnit.MILLISECONDS)) { "No subject signal: $what" } }
    }
    fun step(seconds: Long): Step = Step(minOf(activeUntil, System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds)))
    fun cleanup(): Step = Step(cleanupUntil)
}

/** A Future is never cancelled. Actual body exit is independently signalled and retained on observation failure. */
internal class AsrQualificationWorker {
    class Operation<T>(val future: Future<T>, val exited: CountDownLatch, val retiredNanos: AtomicLong)
    private val executor = Executors.newSingleThreadExecutor { Thread(it, "AsrQualificationNative").apply { isDaemon = true } }
    val thread = AtomicReference<Thread?>()
    private var lastExit: CountDownLatch? = null
    @Volatile var unconfirmed = false
        private set
    fun <T> start(step: AsrQualificationBudget.Step, onExit: () -> Unit = {}, work: () -> T): Operation<T> {
        step.left() // Expired observation/admission budgets cannot enqueue fresh native work.
        check(!unconfirmed) { "Native retirement/cleanup was not confirmed; no further model operation is permitted" }
        check(lastExit?.count != 1L) { "Previous native operation has not actually exited" }
        val exited = CountDownLatch(1)
        val retired = AtomicLong()
        lastExit = exited
        val future = executor.submit(Callable {
            thread.set(Thread.currentThread())
            try { work() } catch (failure: Throwable) {
                if (failure is AsrCleanupFailure || failure is Error) unconfirmed = true
                throw failure
            }
            finally { retired.set(System.nanoTime()); exited.countDown(); onExit() }
        })
        return Operation(future, exited, retired)
    }
    fun <T> await(operation: Operation<T>, step: AsrQualificationBudget.Step): T = try {
        operation.future.get(step.left(), TimeUnit.MILLISECONDS)
    } catch (failure: Throwable) {
        // Even an already-exited body may have returned an owned model the caller never received.
        // Only an observed work exception establishes that no successful result escaped this await.
        if (failure !is ExecutionException || operation.exited.count != 0L) unconfirmed = true
        val cause = if (failure is ExecutionException) failure.cause ?: failure else failure
        if (cause is AsrCleanupFailure) unconfirmed = true
        throw cause
    }
    fun <T> call(step: AsrQualificationBudget.Step, work: () -> T): T = await(start(step, work = work), step)
    fun shutdown() { executor.shutdown() }
}

/** Full synthetic words stay in this owned result, never assertion messages or instrumentation status. */
internal class AsrQualificationReport(context: Context, target: String) {
    private val directory = File(checkNotNull(context.getExternalFilesDir("asr-cancellation")), java.util.UUID.randomUUID().toString()).also { check(it.mkdirs()) }
    val data = JSONObject().put("version", 1).put("target", target).put("complete", false).put("status", "STARTED")
    fun save() {
        val bytes = data.toString(2).toByteArray()
        val pending = File(directory, "results.json.tmp")
        FileOutputStream(pending).use { it.write(bytes); it.fd.sync() }
        val committed = File(directory, "results.json")
        check(pending.renameTo(committed)) { "Qualification result publication failed" }
        check(committed.readBytes().contentEquals(bytes)) { "Qualification result readback failed" }
    }
}
