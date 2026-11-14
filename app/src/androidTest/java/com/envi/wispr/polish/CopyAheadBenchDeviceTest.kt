package com.envi.wispr.polish

import android.content.Context
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.system.Os
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.envi.wispr.cleanup.CleanupLanguage
import com.envi.wispr.llama.S1Native
import com.envi.wispr.models.FirebaseModelFixture
import com.envi.wispr.models.ModelDeliveryStore
import com.envi.wispr.models.ModelManifest
import com.envi.wispr.models.ModelStorage
import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * EXPERIMENT harness (exp/polish-copy-ahead), Harness Contract class: it measures, it protects nothing.
 *
 * Loads one local polish model on the portable llama.cpp runtime (the path mid-tier phones use), warms the prompt
 * prefix exactly as the service does, and runs every case under each copy-ahead arm (`arms`, e.g. `0,8`; 0 is the
 * shipped one-token loop) in alternating order on the same loaded model. Writes texts, times and draft statistics to
 * `files/bench/<runTag>.json`. Cases come from `/data/local/tmp/<cases>`.
 */
@RunWith(AndroidJUnit4::class)
class CopyAheadBenchDeviceTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun benchCopyAhead() {
        val args = InstrumentationRegistry.getArguments()
        val model = when (val name = args.getString("model") ?: "s1") {
            "s1" -> LocalPolishModel.S1_MINI
            "eg1" -> LocalPolishModel.EG1_MICRO
            else -> error("model must be s1 or eg1, got $name")
        }
        val runTag = requireNotNull(args.getString("runTag")) { "pass -e runTag <name>" }
        val arms = (args.getString("arms") ?: "0,8").split(',', ':').map { it.trim().toInt() }
        val threads = args.getString("threads")?.toInt() ?: S1Config.THREAD_COUNT
        val rounds = args.getString("rounds")?.toInt() ?: 1
        val threadArms = (args.getString("threadArms") ?: threads.toString()).split(',', ':').map { it.trim().toInt() }
        val cases = JSONArray(runShell("cat /data/local/tmp/" + requireNotNull(args.getString("cases"))))

        FirebaseModelFixture.stageS1IfRequested(context)
        FirebaseModelFixture.stageEg1IfRequested(context)
        val descriptor = model.descriptor()
        assertTrue("${descriptor.displayName} not verified", ModelDeliveryStore(ModelStorage.root(context)).isVerified(descriptor))
        val selection = checkNotNull(S1ModelSelector.resolve(context, descriptor))

        context.startForegroundService(android.content.Intent(context, com.envi.wispr.debug.BenchForegroundService::class.java))
        com.envi.wispr.debug.BenchForegroundService.started.get(10, java.util.concurrent.TimeUnit.SECONDS)
        val cpuset = runShell("cat /proc/${android.os.Process.myPid()}/cpuset").trim()
        val init = S1Native.initialize(context.applicationInfo.nativeLibraryDir)
        assertFalse(init, init.startsWith("ERROR:"))
        val loadSnapshots = args.getString("loadSnapshots")?.toInt() ?: 0
        if (loadSnapshots > 0) Os.setenv("EW_COPY_AHEAD_LOAD", loadSnapshots.toString(), true) else Os.unsetenv("EW_COPY_AHEAD_LOAD")
        val loadStarted = SystemClock.elapsedRealtime()
        val load = S1Native.loadModel(selection.file.path, S1Config.CONTEXT_SIZE, threads)
        assertFalse(load, load.startsWith("ERROR:"))
        val systemPrompt = when (model) {
            LocalPolishModel.S1_MINI -> S1Config.SYSTEM_PROMPT
            LocalPolishModel.EG1_MICRO -> EGOneNamedLanguagePromptBuilder.systemPrompt(CleanupLanguage.Unknown)
        }
        val warmSystem = when (model) {
            LocalPolishModel.S1_MINI -> S1Config.SYSTEM_PROMPT
            LocalPolishModel.EG1_MICRO -> EGOneNamedLanguagePromptBuilder.SYSTEM_PROMPT
        }
        assertTrue("prefix warm failed", S1Native.warmPrefix(warmSystem))
        val result = JSONObject().put("model", model.name).put("threads", threads).put("load", load)
            .put("loadMs", SystemClock.elapsedRealtime() - loadStarted).put("thermalStart", thermal())
        val rows = JSONArray()
        try {
            for (round in 0 until rounds) for (index in 0 until cases.length()) {
                val case = cases.getJSONObject(index)
                val raw = case.getString("raw")
                val (userPrompt, maxTokens) = when (model) {
                    LocalPolishModel.S1_MINI ->
                        S1PromptBuilder.buildUserPrompt(raw, S1ControlSettings.DEFAULT) to S1PromptBuilder.maxOutputTokens(raw)
                    LocalPolishModel.EG1_MICRO ->
                        EGOneNamedLanguagePromptBuilder.buildUserPrompt(raw) to EGOneNamedLanguagePromptBuilder.maxOutputTokens(raw)
                }
                val combos = threadArms.flatMap { t -> arms.map { a -> t to a } }
                val order = if ((index + round) % 2 == 0) combos else combos.reversed()
                for ((armThreads, arm) in order) {
                    Os.setenv("EW_THREADS", armThreads.toString(), true)
                    if (arm > 0) Os.setenv("EW_COPY_AHEAD", arm.toString(), true) else Os.unsetenv("EW_COPY_AHEAD")
                    S1Native.resetAbort()
                    val started = SystemClock.elapsedRealtime()
                    val text = S1Native.generate(systemPrompt, userPrompt, maxTokens)
                    val ms = SystemClock.elapsedRealtime() - started
                    val end = S1Native.lastEnd()
                    val stats = S1Native.lastCopyAheadStats()
                    rows.put(
                        JSONObject().put("round", round).put("id", case.getString("id")).put("bucket", case.optString("bucket"))
                            .put("arm", arm).put("threads", armThreads).put("ms", ms).put("end", end[0]).put("generated", end[1]).put("promptTokens", end[2])
                            .put("cache", end[3]).put("decodes", stats[0]).put("drafted", stats[1]).put("accepted", stats[2])
                            .put("rawChars", raw.length).put("text", text),
                    )
                }
            }
        } finally {
            Os.unsetenv("EW_COPY_AHEAD")
            Os.unsetenv("EW_THREADS")
            S1Native.unload()
        }
        context.stopService(android.content.Intent(context, com.envi.wispr.debug.BenchForegroundService::class.java))
        result.put("rows", rows).put("thermalEnd", thermal()).put("cpusetStart", cpuset)
            .put("cpusetEnd", runShell("cat /proc/${android.os.Process.myPid()}/cpuset").trim())
        val out = File(context.filesDir, "bench").apply { mkdirs() }
        File(out, "$runTag.json").writeText(result.toString(1))
        // Test Lab pulls only shared storage; the texts are synthetic fixtures, never user content.
        context.getExternalFilesDir("bench")?.let { File(it, "$runTag.json").writeText(result.toString(1)) }
    }

    private fun thermal(): JSONObject {
        val thermal = runShell("dumpsys thermalservice")
        val battery = runShell("dumpsys battery")
        return JSONObject()
            .put("status", Regex("Thermal Status: (\\d+)").find(thermal)?.groupValues?.get(1))
            .put("batteryTenthsC", Regex("temperature: (\\d+)").find(battery)?.groupValues?.get(1))
    }

    private fun runShell(command: String): String {
        val pfd = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
        return ParcelFileDescriptor.AutoCloseInputStream(pfd).bufferedReader().use { it.readText() }
    }
}
