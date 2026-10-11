package com.envi.wispr.speedbench433

import android.app.Activity
import android.os.Bundle
import android.os.Debug
import android.os.PowerManager
import android.os.SystemClock
import android.view.WindowManager
import com.envi.wispr.asr.*
import com.envi.wispr.polish.*
import org.json.JSONObject
import org.json.JSONArray
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.Timer
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.schedule

/** Isolated native bench. It never opens a microphone, speaker, editor or network connection. */
class BenchActivity : Activity() {
    private val timer = Timer("bench-native-bound", true)
    private val terminal = AtomicBoolean(false)
    private val poisoned = AtomicBoolean(false)
    private lateinit var runId: String
    private lateinit var arm: String
    private lateinit var corpusHash: String
    private lateinit var status: File
    private var warmupCount = 0
    private var rowIndex = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        runId = java.util.UUID.fromString(checkNotNull(intent.getStringExtra("run_id"))).toString()
        arm = checkNotNull(intent.getStringExtra("arm"))
        corpusHash = checkNotNull(intent.getStringExtra("corpus_sha256"))
        require(corpusHash.matches(Regex("[0-9a-f]{64}")))
        val corpus = checkNotNull(intent.getStringExtra("corpus"))
        require(corpus.matches(Regex("[A-Za-z0-9_-]+\\.jsonl")))
        warmupCount = intent.getIntExtra("warmups", 1)
        require(warmupCount == 1)
        status = File(filesDir, "status-$runId.txt")
        check(!status.exists())
        status.writeText("RUNNING:$runId:$arm:$corpusHash")
        val blockExpiry = timer.schedule(300_000) {
            if (!terminal.get()) {
                terminate("FAILED:$runId:$arm:block-deadline")
            }
        }
        Thread({
            try {
                val file = File(filesDir, corpus)
                check(sha(file) == corpusHash)
                val rows = file.readLines().filter(String::isNotBlank).map(::JSONObject)
                require(rows.isNotEmpty() && rows.map { it.getString("id") }.distinct().size == rows.size)
                val output = File(filesDir, "results/$runId.jsonl")
                output.parentFile!!.mkdirs()
                check(output.createNewFile())
                when (arm) {
                    "A0", "A1", "A3", "A4", "A5" -> asr(rows, output)
                    "B0", "B1", "B2" -> s1(rows, output)
                    else -> error("unknown or excluded arm")
                }
                finishStatus("COMPLETED:$runId:$arm:$corpusHash")
            } catch (failure: Throwable) {
                finishStatus("FAILED:$runId:$arm:${failure.javaClass.simpleName}")
            } finally {
                blockExpiry.cancel()
                timer.cancel()
                runOnUiThread { finishAndRemoveTask() }
            }
        }, "bench-native-worker").start()
    }

    private fun finishStatus(value: String) {
        if (terminal.compareAndSet(false, true)) {
            val staging = File(filesDir, "status-$runId.tmp")
            staging.writeText(value)
            check(staging.renameTo(status))
        }
    }

    private fun terminate(value: String) {
        poisoned.set(true)
        try {
            finishStatus(value)
        } finally {
            android.os.Process.killProcess(android.os.Process.myPid())
        }
    }

    private fun <T> bounded(limitMs: Long = 60_000, work: () -> T): T {
        val settled = AtomicBoolean(false)
        val expiry = timer.schedule(limitMs) {
            if (settled.compareAndSet(false, true)) {
                terminate("FAILED:$runId:$arm:native-deadline")
            }
        }
        try {
            val result = work()
            check(settled.compareAndSet(false, true)) { "native deadline already won" }
            return result
        } finally {
            settled.set(true)
            expiry.cancel()
        }
    }

    private fun sha(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { stream ->
            val buffer = ByteArray(1024 * 1024)
            while (true) { val n = stream.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private val pins = mapOf(
        "encoder-model.int8.onnx" to (649524002L to "019f798a42be5eee029d8591116308df8e8adf1f55a6292c15f1bd5583f04af4"),
        "decoder_joint-model.int8.onnx" to (18203490L to "63a6cd892244e5dbdd8b41541514f2643c7d3c7c454f9adcdf99ca31acb802d0"),
        "vocab.txt" to (93939L to "d58544679ea4bc6ac563d1f545eb7d474bd6cfa467f0a6e2c1dc1c7d37e3c35d"),
        "s1-mini-q4_k_m.gguf" to (484219808L to "3b41ebe2502cbd03e811d5d16b022f5ab551eda58d62597d152f89535003c634"),
    )

    private fun model(name: String): File = File(filesDir, "models/$name").also {
        val pin = pins.getValue(name)
        check(it.length() == pin.first && sha(it) == pin.second)
    }

    private fun thermal() = getSystemService(PowerManager::class.java).currentThermalStatus
    private fun base(row: JSONObject, ms: Double, before: Int, loadMs: Double) = JSONObject()
        .put("schema", 1).put("runId", runId).put("arm", arm).put("corpusSha256", corpusHash)
        .put("id", row.getString("id")).put("rowIndex", rowIndex++)
        .put("ms", ms).put("loadMs", loadMs).put("loadMetric", "native-load-after-integrity-checks")
        .put("pid", android.os.Process.myPid()).put("thermalBefore", before).put("thermalAfter", thermal())
        .put("sampledPssKbAfter", Debug.getPss()).put("warmups", warmupCount)
        .put("nativeState", "resident-inference-after-one-excluded-warmup")

    private fun asr(rows: List<JSONObject>, output: File) {
        val directory = File(filesDir, "models")
        listOf(TdtModel.ENCODER, TdtModel.DECODER_JOINT, TdtModel.VOCAB).forEach(::model)
        val front = assets.open("nemo128.onnx").use { it.readBytes() }
        check(front.size == 138824)
        val frontHash = MessageDigest.getInstance("SHA-256").digest(front).joinToString("") { "%02x".format(it) }
        check(frontHash == "5b4a84c52eeaa615dc46d781cc7e4598f9b432184831d57493c48adcd01371a9")
        val started = SystemClock.elapsedRealtimeNanos()
        val owned = bounded { if (arm == "A1") TdtNoSpinModel.open(directory, front, 4) else TdtModel.open(directory, front, 4) }
        val loadMs = (SystemClock.elapsedRealtimeNanos() - started) / 1e6
        try {
            val profile = Profile(owned)
            val baseline = TdtRecipe(profile)
            val pad12 = TdtPad12Recipe(profile)
            val pad8 = TdtPad8Recipe(profile)
            val tail1 = TdtTail1Recipe(profile)
            fun decode(samples: FloatArray): Pair<String, Int> = when (arm) {
                "A0", "A1" -> baseline.transcribe(samples).let { it.text to it.repairs }
                "A3" -> pad12.transcribe(samples).let { it.text to it.repairs }
                "A4" -> pad8.transcribe(samples).let { it.text to it.repairs }
                "A5" -> tail1.transcribe(samples).let { it.text to it.repairs }
                else -> error("not an ASR arm")
            }
            for ((index, row) in rows.withIndex()) {
                val id = row.getString("id")
                require(id.matches(Regex("[A-Za-z0-9_-]+")))
                val file = File(filesDir, "audio/$id.pcm")
                check(sha(file) == row.getString("pcmSha256"))
                val bytes = file.readBytes()
                require(bytes.isNotEmpty() && bytes.size % 2 == 0)
                val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
                val samples = FloatArray(bytes.size / 2) { buffer.short / 32768f }
                if (index == 0) {
                    val w = SystemClock.elapsedRealtimeNanos()
                    bounded { decode(samples) }
                    File(filesDir, "results/$runId.warmup.json").writeText(JSONObject().put("ms", (SystemClock.elapsedRealtimeNanos()-w)/1e6).put("excluded", true).toString())
                }
                profile.reset()
                val before = thermal()
                val t = SystemClock.elapsedRealtimeNanos()
                val result = bounded { decode(samples) }
                val ms = (SystemClock.elapsedRealtimeNanos() - t) / 1e6
                output.appendText(base(row, ms, before, loadMs)
                    .put("text", result.first).put("repairs", result.second).put("pcmSha256", row.getString("pcmSha256"))
                    .put("observedInputs", JSONArray(profile.inputs)).put("encodeMs", profile.encodeNs / 1e6).put("stepMs", profile.stepNs / 1e6).put("steps", profile.steps)
                    .put("backend", "CPU").put("modelClass", owned.javaClass.name).put("threads", 4).put("spinning", if (arm == "A1") "0" else "runtime-default")
                    .put("paddingArm", arm).put("durationMs", bytes.size / 32.0).toString() + "\n")
            }
        } finally { if (!poisoned.get()) bounded { owned.close() } }
    }

    private class S1Handle(
        val load: (String, List<String>) -> String,
        val generate: (String, String, Int, Long) -> String?,
        val close: () -> Unit,
        val backend: () -> String,
        val end: () -> GenerationEnd?,
    )

    private fun s1(rows: List<JSONObject>, output: File) {
        val file = model("s1-mini-q4_k_m.gguf")
        val batch = when (arm) { "B0" -> 512; "B1" -> 256; "B2" -> 128; else -> error("not S1 arm") }
        val handle = if (arm == "B0") {
            val r = S1GenieXRuntime(applicationContext)
            S1Handle(r::load, r::generate, r::close, { r.activeComputeUnit }, { r.lastGeneration })
        } else {
            val r = S1BenchRuntime(applicationContext, batch, batch)
            S1Handle(r::load, r::generate, r::close, { r.activeComputeUnit }, { r.lastGeneration })
        }
        try {
            val started = SystemClock.elapsedRealtimeNanos()
            val loaded = bounded { handle.load(file.path, listOf("gpu")) }
            val loadMs = (SystemClock.elapsedRealtimeNanos() - started) / 1e6
            check(handle.backend() == "gpu")
            fun generate(row: JSONObject): String {
                val input = row.getString("input")
                val settings = S1ControlSettings(
                    S1Styling.entries.single { it.token == row.getString("styling") },
                    S1Structure.entries.single { it.token == row.getString("structure") },
                    S1Context.entries.single { it.token == row.getString("context") },
                )
                val answer = try {
                    handle.generate(S1Config.SYSTEM_PROMPT, S1PromptBuilder.buildUserPrompt(input, settings), S1PromptBuilder.maxOutputTokens(input), LocalPolishBudget.SHIPPED.cooperativeMs)
                } catch (failure: Throwable) {
                    terminate("FAILED:$runId:$arm:native-generation-${failure.javaClass.simpleName}")
                    throw failure
                }
                if (answer == null) {
                    // Cancelled collection is not proof that native generation stopped. Never close/reuse it.
                    terminate("FAILED:$runId:$arm:cooperative-timeout")
                    error("poisoned cooperative timeout")
                }
                return answer
            }
            for ((index, row) in rows.withIndex()) {
                if (index == 0) {
                    val w = SystemClock.elapsedRealtimeNanos()
                    bounded(LocalPolishBudget.SHIPPED.hardMs) { generate(row) }
                    File(filesDir, "results/$runId.warmup.json").writeText(JSONObject().put("ms", (SystemClock.elapsedRealtimeNanos()-w)/1e6).put("excluded", true).toString())
                }
                val before = thermal()
                val started = SystemClock.elapsedRealtimeNanos()
                val text = bounded(LocalPolishBudget.SHIPPED.hardMs) { generate(row) }
                val ms = (SystemClock.elapsedRealtimeNanos() - started) / 1e6
                check(text.isNotBlank() && !text.startsWith("ERROR:"))
                val end = checkNotNull(handle.end())
                check(handle.backend() == "gpu" && end.stopReason == "eos" && !end.reachedCap)
                output.appendText(base(row, ms, before, loadMs).put("text", text)
                    .put("backend", handle.backend()).put("loadStatus", loaded).put("batch", batch).put("ubatch", batch)
                    .put("modelSha256", pins.getValue("s1-mini-q4_k_m.gguf").second)
                    .put("stop", end.stopReason).put("generated", end.generatedTokens).put("promptTokens", end.promptTokens).put("cap", end.cap)
                    .put("styling", row.getString("styling")).put("structure", row.getString("structure")).put("context", row.getString("context"))
                    .toString() + "\n")
            }
        } finally {
            if (!poisoned.get()) {
                try { bounded { handle.close() } } catch (failure: Throwable) {
                    terminate("FAILED:$runId:$arm:native-release-${failure.javaClass.simpleName}")
                    throw failure
                }
            }
        }
    }
}

private class Profile(private val runner: TdtRunner) : TdtRunner {
    override val vocab get() = runner.vocab
    var encodeNs = 0L
    var stepNs = 0L
    var steps = 0
    val inputs = ArrayList<JSONObject>()
    fun reset() { encodeNs = 0; stepNs = 0; steps = 0; inputs.clear() }
    override fun encode(samples: FloatArray, length: Int): Encoded {
        inputs += JSONObject().put("tensorSamples", samples.size).put("realSamples", length)
        val started = SystemClock.elapsedRealtimeNanos()
        return runner.encode(samples, length).also { encodeNs += SystemClock.elapsedRealtimeNanos() - started }
    }
    override fun initialState() = runner.initialState()
    override fun step(encoded: Encoded, frame: Int, previousToken: Int, state: DecoderState): Step {
        val started = SystemClock.elapsedRealtimeNanos()
        return runner.step(encoded, frame, previousToken, state).also { stepNs += SystemClock.elapsedRealtimeNanos() - started; steps++ }
    }
    override fun close() = runner.close()
}
