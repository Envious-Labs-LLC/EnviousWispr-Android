package com.envi.wispr.polish

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.util.AtomicFile
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.envi.wispr.BuildConfig
import com.envi.wispr.llama.S1Native
import com.envi.wispr.llama.S1SamplingExperiment
import com.envi.wispr.models.FirebaseModelFixture
import com.envi.wispr.models.ModelManifest
import com.envi.wispr.models.ModelStorage
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Harness/Observability Contracts: same-APK sampler study, actual native controls and complete results.
 * Product Outcome, bounded: real-model output/token/end compatibility and independent critical intent.
 * This bypasses capture, ASR, the service watchdog and insertion. It establishes no whole-app speed gain.
 */
@RunWith(AndroidJUnit4::class)
class S1SamplerStudyDeviceTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val rows = JSONArray()
    private val loads = JSONArray()
    private val controlCalls = JSONArray()
    private lateinit var report: JSONObject
    private lateinit var resultFile: File
    private lateinit var modelPath: String
    private var safeToUnload = true

    private class Case(val json: JSONObject) {
        val id: String = json.getString("id")
        val text: String = json.getString("text")
        val control = json.getJSONObject("controls").let {
            S1ControlSettings(
                S1Styling.entries.single { value -> value.token == it.getString("styling") },
                S1Structure.entries.single { value -> value.token == it.getString("structure") },
                S1Context.entries.single { value -> value.token == it.getString("context") },
            )
        }
    }

    private class Sample(val output: String, val end: IntArray, val metrics: LongArray, val tokens: IntArray, val chain: String) {
        fun json(): JSONObject = JSONObject().put("output", output).put("output_sha256", sha(output.toByteArray()))
            .put("end", JSONArray(end.toList())).put("metrics", JSONArray(metrics.toList()))
            .put("tokens", JSONArray(tokens.toList())).put("chain", chain)
    }

    private class Environment(val thermal: Int, val plugged: Int, val status: Int, val temperature: Int) {
        fun json(): JSONObject = JSONObject().put("thermal", thermal).put("plugged", plugged).put("battery_status", status).put("temperature_tenths_c", temperature)
    }

    @Test
    fun pairedSamplerStudy() {
        assertTrue("Study builds must omit telemetry", BuildConfig.TELEMETRY_POSTHOG_KEY.isEmpty() && BuildConfig.TELEMETRY_SENTRY_DSN.isEmpty())
        val stage = InstrumentationRegistry.getArguments().getString("samplerStage")
        require(stage == "pilot" || stage == "confirm") { "samplerStage must be pilot or confirm" }
        val bytes = instrumentation.context.assets.open("sampler-study-v1.json").use { it.readBytes() }
        val corpus = JSONObject(bytes.toString(Charsets.UTF_8))
        assertEquals(1, corpus.getInt("version"))
        assertEquals(ModelManifest.s1.files.single().sha256, corpus.getString("model_sha256"))
        assertEquals("Frozen system prompt", corpus.getString("system_prompt_sha256"), sha(S1Config.SYSTEM_PROMPT.toByteArray()))
        val timing = corpus.getJSONArray("timing").objects().map(::Case).associateBy { it.id }
        assertEquals(setOf("S", "M", "L"), timing.keys)
        val quality = corpus.getJSONArray("quality").objects().map(::Case)
        for (item in timing.values + quality) {
            assertEquals("Frozen input hash ${item.id}", item.json.getString("sha256"), sha(item.text.toByteArray()))
            assertEquals("Frozen user prompt ${item.id}", item.json.getString("user_prompt_sha256"), sha(S1PromptBuilder.buildUserPrompt(item.text, item.control).toByteArray()))
            assertEquals("Frozen requested cap ${item.id}", item.json.getInt("requested_cap"), S1PromptBuilder.maxOutputTokens(item.text))
        }
        val root = checkNotNull(context.getExternalFilesDir("sampler-study"))
        val dir = File(root, UUID.randomUUID().toString())
        check(dir.mkdirs()) { "Could not create an exclusive study output directory" }
        resultFile = File(dir, "results.json")
        report = JSONObject().put("version", 1).put("stage", stage).put("complete", false)
            .put("corpus_sha256", sha(bytes)).put("model_sha256", corpus.getString("model_sha256"))
            .put("system_prompt_sha256", sha(S1Config.SYSTEM_PROMPT.toByteArray()))
            .put("settings", JSONObject().put("context", S1Config.CONTEXT_SIZE).put("threads", S1Config.THREAD_COUNT)
                .put("batch", S1Config.BATCH_SIZE).put("ubatch", S1Config.UBATCH_SIZE))
            .put("device", "${Build.MANUFACTURER}/${Build.MODEL}").put("api", Build.VERSION.SDK_INT)
            .put("rows", rows).put("loads", loads).put("control_calls", controlCalls)
        save()
        try {
            FirebaseModelFixture.stageS1IfRequested(context)
            assertTrue("Pinned S1 fixture must be admitted", ModelStorage.isReady(context, ModelManifest.s1))
            modelPath = File(File(ModelStorage.root(context), ModelManifest.s1.id), S1Config.MODEL_FILENAME).path
            val backend = S1Native.initialize(context.applicationInfo.nativeLibraryDir)
            assertTrue("Portable CPU backend did not initialize", backend.startsWith("libggml-cpu-android_"))
            report.put("backend", backend)
            load("controls", -1)
            nativeContracts()
            lifecycleControls(timing.getValue("S"), timing.getValue("L"))
            for (item in quality) {
                val a = sample(item, 0, "quality", -1)
                val b = sample(item, 1, "quality", -1)
                compatible(a, b, item.id)
                faithful(item, a.output)
                faithful(item, b.output)
            }
            timerTurnover(timing.getValue("S"), timing.getValue("L"))
            val power = context.getSystemService(PowerManager::class.java)
            val observedPeak = AtomicInteger(power.currentThermalStatus)
            val thermalListener = PowerManager.OnThermalStatusChangedListener { status ->
                observedPeak.accumulateAndGet(status) { old, value -> maxOf(old, value) }
            }
            power.addThermalStatusListener({ command -> command.run() }, thermalListener)
            val blocks = if (stage == "pilot") 3 else 10
            try {
                for (block in 1..blocks) {
                    val arms = if (block % 2 == 1) listOf(0, 1) else listOf(1, 0)
                    val ids = if (stage == "pilot") listOf("S", "M", "L") else buildList {
                        addAll(if (block % 2 == 1) listOf("M", "L") else listOf("L", "M"))
                        if (block in listOf(1, 5, 10)) add("S")
                    }
                    val paired = mutableMapOf<Pair<Int, String>, Sample>()
                    for (arm in arms) {
                        load("study", block, arm)
                        val warm = sample(timing.getValue("S"), arm, "warmup", block)
                        faithful(timing.getValue("S"), warm.output)
                        for (id in ids) {
                            val item = timing.getValue(id)
                            val result = sample(item, arm, "timing", block)
                            faithful(item, result.output)
                            paired[arm to id] = result
                        }
                    }
                    for (id in ids) compatible(paired.getValue(0 to id), paired.getValue(1 to id), "block$block/$id")
                }
            } finally {
                power.removeThermalStatusListener(thermalListener)
                report.put("observed_thermal_peak", observedPeak.get())
                save()
            }
            report.put("complete", true).put("status", "COMPLETED")
            save()
            assertTrue("Result snapshot did not complete", JSONObject(resultFile.readText()).getBoolean("complete"))
        } catch (error: Throwable) {
            report.put("status", "FAILED").put("failure_class", error.javaClass.simpleName)
            save()
            throw error
        } finally {
            S1Native.abort()
            if (safeToUnload) S1Native.unload()
        }
    }

    private fun nativeContracts() {
        val data = S1SamplingExperiment.contracts()
        report.put("native_contracts", JSONArray(data.toList()))
        save()
        assertTrue("Native contract schema is incomplete", data.size >= 2 && data[0] == 1 && (data.size - 2) % 4 == 0)
        val expected = buildSet {
            for (arm in 0..1) for (id in 0..12) if (!(arm == 0 && id in 5..7)) add(arm to id)
        }
        val seen = mutableSetOf<Pair<Int, Int>>()
        for (i in 2 until data.size step 4) {
            val arm = data[i]; val id = data[i + 1]; val actual = data[i + 2]; val auxiliary = data[i + 3]
            assertTrue("Duplicate native contract", seen.add(arm to id))
            val wanted = when (id) {
                0, 2, 3 -> 0
                1 -> 1
                4, 9 -> 2
                5, 6, 7, 8 -> -1
                10 -> if (data[1] == 0) -2 else 1
                11 -> 3
                12 -> 1
                else -> error("Unknown native contract")
            }
            assertEquals("Native arm$arm case$id", wanted, actual)
            val expectedAuxiliary = when (id) {
                5, 6, 7, 8 -> 0
                10 -> data[1]
                else -> 1
            }
            assertEquals("Native contract validity arm$arm case$id", expectedAuxiliary, auxiliary)
        }
        assertEquals("Native contracts missing or unexpected", expected, seen)
    }

    private fun lifecycleControls(short: Case, long: Case) {
        S1Native.resetAbort()
        val ordinary = nativeBound { S1Native.generate(S1Config.SYSTEM_PROMPT, S1PromptBuilder.buildUserPrompt(short.text, short.control), S1PromptBuilder.maxOutputTokens(short.text)) }
        val ordinaryEnd = S1Native.lastEnd()
        controlCall("ordinary_baseline", ordinary, ordinaryEnd)
        val baseline = sample(short, 0, "ordinary_compatibility", -1)
        assertTrue("Ordinary/debug baseline output differs", ordinary == baseline.output)
        assertTrue("Ordinary/debug baseline ending differs", ordinaryEnd.contentEquals(baseline.end))
        val ordinaryObserved = sample(short, 0, "ordinary_eos", -1, ordinaryEntry = true)
        compatible(baseline, ordinaryObserved, "ordinary EOS")
        for ((arm, ordinaryEntry) in listOf(0 to false, 1 to false, 0 to true)) {
            val prefix = if (ordinaryEntry) "ordinary_" else ""
            for (phase in 1..4) {
                val ended = sample(short, arm, "${prefix}abort$phase", -1, abortPhase = phase, ordinaryEntry = ordinaryEntry)
                assertEquals("Abort did not reach its intended phase", phase.toLong(), ended.metrics[5])
                assertEquals("Abort ending", 2, ended.end[0])
                assertEquals("Abort generated count", if (phase <= 2) 0 else 1, ended.end[1])
                val recovered = sample(short, arm, "${prefix}after_abort$phase", -1, ordinaryEntry = ordinaryEntry)
                compatible(baseline, recovered, "after-abort arm$arm phase$phase")
            }
            val capped = sample(short, arm, "${prefix}cap", -1, cap = 1, ordinaryEntry = ordinaryEntry)
            assertEquals("NOT_STAGED: fixture did not exhaust cap", 1, capped.end[0])
            assertEquals(1, capped.end[1])
            assertEquals(1L, capped.metrics[4])
            val huge = Case(JSONObject(short.json.toString()).put("text", "word ".repeat(3000)).put("id", "context_limit"))
            val refused = sample(huge, arm, "${prefix}context_limit", -1, cap = 1, ordinaryEntry = ordinaryEntry)
            assertTrue("Context boundary was not refused", refused.output == "ERROR: S1 prompt exceeds the mobile context limit")
            assertTrue("Refused context ending", refused.end.contentEquals(intArrayOf(-1, 0, 0)))
        }
        S1Native.unload()
        for ((arm, ordinaryEntry) in listOf(0 to false, 1 to false, 0 to true)) {
            val refused = sample(short, arm, if (ordinaryEntry) "ordinary_unloaded" else "unloaded", -1, ordinaryEntry = ordinaryEntry)
            assertTrue("Unloaded native call was not refused", refused.output == "ERROR: S1-mini is not loaded")
            assertTrue("Unloaded ending", refused.end.contentEquals(intArrayOf(-1, 0, 0)))
        }
        for ((id, arm, phase) in listOf(Triple("invalid_arm", 9, 0), Triple("invalid_phase", 0, 9))) {
            val refused = S1SamplingExperiment.generate(S1Config.SYSTEM_PROMPT, short.text, 128, arm, phase)
            controlCall(id, refused, S1Native.lastEnd(), nativeStarted = false)
            assertTrue("Invalid experiment request was not refused", refused == "ERROR: Invalid sampler study request")
            assertTrue("Invalid request retained an old ending", S1Native.lastEnd().contentEquals(intArrayOf(-1, 0, 0)))
            val metadata = S1SamplingExperiment.metrics()
            assertTrue("Invalid request retained old observations", metadata[0] == -1L && metadata[5] == 0L && metadata[6] == 0L)
        }
        load("reload", -1)
        compatible(baseline, sample(short, 0, "after_reload", -1), "reload")
        compatible(baseline, sample(short, 0, "ordinary_after_reload", -1, ordinaryEntry = true), "ordinary reload")
        val noSignal = S1SamplingExperiment.awaitNativePhase(Long.MAX_VALUE, 2, 25)
        report.put("phase_waiter_observation", JSONArray(noSignal.toList()))
        save()
        assertEquals("Never-signalled phase waiter must expire", 1L, noSignal[3])
        report.put("phase_waiter_control", "EXPIRED").put("long_fixture_id", long.id)
    }

    private fun timerTurnover(short: Case, long: Case) {
        val expected = sample(long, 0, "timer_reference", -1)
        val timer = RecordingTimer()
        val worker = Executors.newSingleThreadExecutor { Thread(it, "SamplerTimerControl").apply { isDaemon = true } }
        val runtime = S1PortableRuntime(context, timer)
        try {
            runtime.load(modelPath, listOf("cpu"))
            val first = timer.boundedInvocation { runtime.generate(S1Config.SYSTEM_PROMPT, S1PromptBuilder.buildUserPrompt(short.text, short.control), S1PromptBuilder.maxOutputTokens(short.text), 30_000) }
            val firstEnd = S1Native.lastEnd()
            controlCall("timer_initial", first, firstEnd)
            assertTrue("Timer control initial call failed", !first.isNullOrBlank() && !first.startsWith("ERROR:"))
            assertEquals("Timer initial call ending", 0, firstEnd[0])
            faithful(short, checkNotNull(first))
            val old = timer.tasks.single()
            assertTrue("Completed generation must cancel its timer", old.isCancelled)
            assertTrue("Timer fixture must expose actual scheduled task", old is Runnable)
            val after = S1SamplingExperiment.serial()
            val future = worker.submit<String?> {
                timer.boundedInvocation { runtime.generate(S1Config.SYSTEM_PROMPT, S1PromptBuilder.buildUserPrompt(long.text, long.control), S1PromptBuilder.maxOutputTokens(long.text), 30_000) }
            }
            val phase = S1SamplingExperiment.awaitNativePhase(after, 2, 5000)
            report.put("timer_phase_observation", JSONArray(phase.toList()))
            save()
            assertTrue("NOT_STAGED: successor did not enter native work", phase[3] == 0L && phase[0] > after && phase[2] == phase[0] && !future.isDone)
            (old as Runnable).run()
            val actual = future.get(30, TimeUnit.SECONDS)
            controlCall("timer_successor", actual, S1Native.lastEnd())
            assertEquals("Phase belongs to the actual successor", phase[0], S1SamplingExperiment.serial())
            assertTrue("Successor phase must follow initial call", phase[0] > after)
            assertTrue("Old timer aborted or altered its successor", actual == expected.output)
            assertEquals("Successor native ending", 0, S1Native.lastEnd()[0])
            report.put("timer_turnover_control", "PASSED")
        } finally {
            S1Native.abort()
            worker.shutdown()
            safeToUnload = worker.awaitTermination(5, TimeUnit.SECONDS)
            timer.shutdownNow()
            if (safeToUnload) runtime.close()
        }
        assertTrue("Native timer-control worker did not retire", safeToUnload)
    }

    private class RecordingTimer : ScheduledThreadPoolExecutor(1, ThreadFactory { Thread(it, "SamplerStudyTimer").apply { isDaemon = true } }) {
        val tasks = CopyOnWriteArrayList<ScheduledFuture<*>>()
        private val bounds = CopyOnWriteArrayList<AbortBound>()
        override fun schedule(command: Runnable, delay: Long, unit: TimeUnit): ScheduledFuture<*> =
            protect { super.schedule(command, 1, TimeUnit.HOURS) } // test-fixture-timer: fire the real task explicitly.
        override fun <V> schedule(callable: Callable<V>, delay: Long, unit: TimeUnit): ScheduledFuture<V> =
            protect { super.schedule(callable, 1, TimeUnit.HOURS) } // test-fixture-timer: controlled clock.
        private fun <V> protect(schedule: () -> ScheduledFuture<V>): ScheduledFuture<V> {
            val bound = AbortBound()
            return try { schedule().also { tasks += it; bounds += bound } }
            catch (error: Throwable) { bound.close(); throw error }
        }
        fun <T> boundedInvocation(work: () -> T): T = try { work() } finally {
            bounds.forEach { it.close() }
            bounds.clear()
        }
        override fun shutdownNow(): MutableList<Runnable> {
            bounds.forEach { it.close() }
            bounds.clear()
            return super.shutdownNow()
        }
    }

    private class AbortBound : AutoCloseable {
        private val lock = Any()
        private var armed = true
        private val scheduler = Executors.newSingleThreadScheduledExecutor { Thread(it, "SamplerCallDeadline").apply { isDaemon = true } }
        private val task = scheduler.schedule({ synchronized(lock) { if (armed) S1Native.abort() } }, 30, TimeUnit.SECONDS)
        override fun close() {
            synchronized(lock) { armed = false }
            task.cancel(false)
            scheduler.shutdown()
        }
    }

    private fun <T> nativeBound(work: () -> T): T {
        val bound = AbortBound()
        return try { work() } finally { bound.close() }
    }

    private fun sample(item: Case, arm: Int, role: String, block: Int, abortPhase: Int = 0, cap: Int = S1PromptBuilder.maxOutputTokens(item.text), ordinaryEntry: Boolean = false): Sample {
        val before = environment()
        val userPrompt = S1PromptBuilder.buildUserPrompt(item.text, item.control)
        if (ordinaryEntry) assertTrue("Ordinary control must arm once", arm == 0 && S1SamplingExperiment.prepareOrdinary(abortPhase))
        S1Native.resetAbort()
        val output = nativeBound {
            if (ordinaryEntry) S1Native.generate(S1Config.SYSTEM_PROMPT, userPrompt, cap)
            else S1SamplingExperiment.generate(S1Config.SYSTEM_PROMPT, userPrompt, cap, arm, abortPhase)
        }
        val sample = Sample(output, S1Native.lastEnd(), S1SamplingExperiment.metrics(), S1SamplingExperiment.tokens(), S1SamplingExperiment.chain().trim())
        val after = environment()
        rows.put(JSONObject().put("role", role).put("block", block).put("case", item.id).put("arm", arm)
            .put("entry", if (ordinaryEntry) "ordinary" else "debug")
            .put("input_sha256", sha(item.text.toByteArray())).put("user_prompt_sha256", sha(userPrompt.toByteArray())).put("requested_cap", cap)
            .put("before", before.json()).put("after", after.json()).put("sample", sample.json()))
        save()
        assertEquals("Native ending schema", 3, sample.end.size)
        assertEquals("Native metric schema", 10, sample.metrics.size)
        assertEquals("Actual JNI entry", if (ordinaryEntry) 0L else 1L, sample.metrics[9])
        assertEquals("Requested study arm", arm.toLong(), sample.metrics[0])
        assertTrue("Native duration missing", sample.metrics[1] > 0)
        if (sample.chain.isNotEmpty()) {
            assertTrue("Actual chain differs from requested arm", if (arm == 0) sample.chain.endsWith("dist") else sample.chain.endsWith("greedy") && !sample.chain.contains("dist"))
            assertEquals("Accepted history count differs from selected trace", sample.tokens.size.toLong(), sample.metrics[3])
            if (sample.tokens.isNotEmpty()) assertEquals("Last accepted token", sample.tokens.last().toLong(), sample.metrics[8])
        }
        if (role == "timing" || role == "warmup" || role == "quality") {
            assertEquals("Healthy study input must end at EOS", 0, sample.end[0])
            assertEquals("EOS trace count", sample.end[1] + 1, sample.tokens.size)
            assertTrue("Native generation error", !sample.output.startsWith("ERROR:") && sample.output.isNotBlank())
        }
        return sample
    }

    private fun load(role: String, block: Int, arm: Int = -1) {
        S1Native.resetAbort()
        val started = SystemClock.elapsedRealtime()
        val status = S1Native.loadModel(modelPath, S1Config.CONTEXT_SIZE, S1Config.THREAD_COUNT)
        assertFalse("Pinned model could not load", status.startsWith("ERROR:"))
        loads.put(JSONObject().put("role", role).put("block", block).put("arm", arm)
            .put("status", status).put("load_ms", SystemClock.elapsedRealtime() - started))
        save()
    }

    private fun environment(): Environment {
        val power = context.getSystemService(PowerManager::class.java)
        val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        return Environment(power.currentThermalStatus, battery?.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1) ?: -1,
            battery?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1,
            battery?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1) ?: -1)
    }

    private fun compatible(a: Sample, b: Sample, label: String) {
        assertTrue("Output compatibility $label", a.output == b.output)
        assertTrue("Token compatibility $label", a.tokens.contentEquals(b.tokens))
        assertTrue("Ending compatibility $label", a.end.contentEquals(b.end))
        assertEquals("Cap compatibility $label", a.metrics[4], b.metrics[4])
    }

    private fun controlCall(id: String, output: String?, end: IntArray, nativeStarted: Boolean = true) {
        controlCalls.put(JSONObject().put("id", id).put("output", output ?: JSONObject.NULL)
            .put("output_sha256", output?.let { sha(it.toByteArray()) } ?: JSONObject.NULL)
            .put("end", JSONArray(end.toList())).put("serial", S1SamplingExperiment.serial()).put("native_started", nativeStarted))
        save()
    }

    private fun faithful(item: Case, text: String) {
        for (pattern in item.json.getJSONArray("require").strings()) assertTrue("Independent intent ${item.id}", Regex(pattern, setOf(RegexOption.IGNORE_CASE, RegexOption.MULTILINE)).containsMatchIn(text))
        for (pattern in item.json.getJSONArray("forbid").strings()) assertFalse("Independent format ${item.id}", Regex(pattern, setOf(RegexOption.IGNORE_CASE, RegexOption.MULTILINE)).containsMatchIn(text))
    }

    private fun save() {
        val atomic = AtomicFile(resultFile)
        val stream = atomic.startWrite()
        try {
            stream.write(report.toString(2).toByteArray())
            atomic.finishWrite(stream)
        } catch (error: Throwable) {
            atomic.failWrite(stream)
            throw error
        }
    }

    private fun JSONArray.objects(): List<JSONObject> = (0 until length()).map { getJSONObject(it) }
    private fun JSONArray.strings(): List<String> = (0 until length()).map { getString(it) }

    companion object {
        private fun sha(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }
}
