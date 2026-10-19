package com.envi.wispr.benchmark

import android.os.Build
import android.os.Bundle
import android.os.Debug
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * #449 research: the pinned S1 q4_k_m model through the repository's own llama.cpp build (runtime CPU variant
 * selection) on a remote physical phone. Synthetic text only. Reports the phone's CPU features, the variant
 * llama.cpp chose, and per-fixture wall time; fails on any error so a crash or fallback is never a pass.
 */
@RunWith(AndroidJUnit4::class)
class PortableCpuDeviceTest {
    @Test
    fun s1GeneratesOnThePortableCpuBuild() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        report("device", "${Build.MANUFACTURER} ${Build.MODEL} api=${Build.VERSION.SDK_INT} soc=${Build.SOC_MANUFACTURER}/${Build.SOC_MODEL}")
        report("cpuFeatures", File("/proc/cpuinfo").readLines().filter { it.startsWith("Features") }.distinct().joinToString(" || "))
        report("cpuParts", File("/proc/cpuinfo").readLines().filter { it.startsWith("CPU part") }.map { it.substringAfter(":").trim() }.distinct().joinToString(","))
        report("memTotal", File("/proc/meminfo").readLines().first { it.startsWith("MemTotal") })

        val model = File(FIXTURE)
        assertTrue("Model fixture missing at $FIXTURE", model.isFile)
        assertEquals("Model fixture size", MODEL_BYTES, model.length())
        assertEquals("Model fixture SHA-256", MODEL_SHA256, sha256(model))

        val engine = BenchmarkEngineFactory.create(context)
        try {
            val loadStarted = SystemClock.elapsedRealtime()
            val status = engine.load(model.absolutePath)
            report("load", "${SystemClock.elapsedRealtime() - loadStarted}ms $status")

            val warm = BenchmarkFixtures.all.first()
            engine.generate(BenchmarkFixtures.SYSTEM_PROMPT, BenchmarkFixtures.userPrompt(warm), warm.maxTokens)

            BenchmarkFixtures.all.forEach { fixture ->
                repeat(RUNS) { run ->
                    val started = SystemClock.elapsedRealtime()
                    val output = engine.generate(BenchmarkFixtures.SYSTEM_PROMPT, BenchmarkFixtures.userPrompt(fixture), fixture.maxTokens).output
                    val elapsed = SystemClock.elapsedRealtime() - started
                    report("${fixture.id}#$run", "${elapsed}ms pssKb=${Debug.getPss()} chars=${output.length} text=${output.replace('\n', ' ')}")
                    assertTrue("Blank output for ${fixture.id}", output.isNotBlank())
                }
            }
            // Repeated close and reload: the lifecycle the app drives between sessions.
            engine.close()
            val reloadStarted = SystemClock.elapsedRealtime()
            engine.load(model.absolutePath)
            val again = engine.generate(BenchmarkFixtures.SYSTEM_PROMPT, BenchmarkFixtures.userPrompt(warm), warm.maxTokens).output
            report("reload", "${SystemClock.elapsedRealtime() - reloadStarted}ms chars=${again.length}")
            assertTrue("Blank output after reload", again.isNotBlank())
        } finally {
            engine.close()
        }
    }

    /** Thread-count probe: the same fixtures straight through S1Native at the `threads` runner argument. */
    @Test
    fun s1ThreadCountProbe() {
        val args = InstrumentationRegistry.getArguments()
        val threads = args.getString("threads")?.toIntOrNull() ?: 2
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        com.envi.wispr.llama.S1Native.initialize(context.applicationInfo.nativeLibraryDir)
        val status = com.envi.wispr.llama.S1Native.loadModel(FIXTURE, 2048, threads)
        report("threads$threads.load", status)
        assertTrue(status, !status.startsWith("ERROR:"))
        try {
            BenchmarkFixtures.all.forEach { fixture ->
                repeat(RUNS + 1) { run ->
                    val started = SystemClock.elapsedRealtime()
                    val output = com.envi.wispr.llama.S1Native.generate(BenchmarkFixtures.SYSTEM_PROMPT, BenchmarkFixtures.userPrompt(fixture), fixture.maxTokens)
                    report("threads$threads.${fixture.id}#$run", "${SystemClock.elapsedRealtime() - started}ms chars=${output.length}")
                    assertTrue(output, !output.startsWith("ERROR:"))
                }
            }
        } finally { com.envi.wispr.llama.S1Native.unload() }
    }

    private fun report(key: String, value: String) {
        Log.i(TAG, "$key: $value")
        InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply { putString("s1.$key", value) })
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(1 shl 16)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private companion object {
        const val TAG = "S1Portable"
        const val FIXTURE = "/data/local/tmp/s1-mini-q4_k_m.gguf"
        const val MODEL_BYTES = 484219808L
        const val MODEL_SHA256 = "3b41ebe2502cbd03e811d5d16b022f5ab551eda58d62597d152f89535003c634"
        const val RUNS = 2
    }
}
