package com.envi.wispr.benchmark

import android.app.Activity
import android.os.Bundle
import android.os.Debug
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import android.view.WindowManager
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class BenchmarkActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val fixtureId = intent.getStringExtra(EXTRA_FIXTURE) ?: "short"
        val warmups = intent.getIntExtra(EXTRA_WARMUPS, 1).coerceIn(0, 5)
        val runs = intent.getIntExtra(EXTRA_RUNS, 5).coerceIn(1, 30)
        val cooldownMs = intent.getIntExtra(EXTRA_COOLDOWN_MS, 0).coerceIn(0, 60_000)
        val model = intent.getStringExtra(EXTRA_MODEL) ?: DEFAULT_MODEL
        val modelSha256 = intent.getStringExtra(EXTRA_MODEL_SHA256) ?: DEFAULT_MODEL_SHA256
        scope.launch {
            runBenchmark(fixtureId, warmups, runs, cooldownMs, model, modelSha256)
            finishAndRemoveTask()
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private suspend fun runBenchmark(
        fixtureId: String,
        warmups: Int,
        runs: Int,
        cooldownMs: Int,
        model: String,
        modelSha256: String
    ) =
        withContext(Dispatchers.IO) {
            val statusFile = File(filesDir, "benchmark-status.txt")
            val resultsDirectory = File(filesDir, "results").apply { mkdirs() }
            val resultFile = File(resultsDirectory, "${BuildConfig.BENCHMARK_ENGINE}.jsonl")
            resultFile.writeText("")
            statusFile.writeText("RUNNING")

            val modelFile = File(File(filesDir, "models"), model)
            if (!modelFile.isFile || sha256(modelFile) != modelSha256) {
                statusFile.writeText("FAILED: model missing or checksum mismatch")
                Log.e(TAG, statusFile.readText())
                return@withContext
            }

            val fixtures = runCatching { BenchmarkFixtures.select(fixtureId) }.getOrElse { error ->
                statusFile.writeText("FAILED: ${error.message}")
                Log.e(TAG, "Invalid benchmark fixture")
                return@withContext
            }

            val engine = BenchmarkEngineFactory.create(applicationContext)
            try {
                val loadStarted = SystemClock.elapsedRealtimeNanos()
                val loadStatus = engine.load(modelFile.absolutePath)
                val coldLoadMs = elapsedMs(loadStarted)
                Log.i(TAG, "${engine.backend} loaded in ${coldLoadMs}ms: $loadStatus")

                repeat(warmups) {
                    val fixture = fixtures.first()
                    engine.generate(
                        BenchmarkFixtures.SYSTEM_PROMPT,
                        BenchmarkFixtures.userPrompt(fixture),
                        fixture.maxTokens
                    )
                }

                fixtures.forEach { fixture ->
                    repeat(runs) { index ->
                        val result = measure(
                            engine,
                            fixture,
                            index,
                            model,
                            modelSha256,
                            cooldownMs,
                            coldLoadMs.takeIf { fixture == fixtures.first() && index == 0 }
                        )
                        resultFile.appendText(result.toJsonLine() + "\n")
                        Log.i(
                            TAG,
                            "${result.engine}/${result.fixture} run ${index + 1}: " +
                                "${result.totalMs}ms, chars=${result.outputChars}"
                        )
                        if (cooldownMs > 0 && index + 1 < runs) {
                            delay(cooldownMs.toLong())
                        }
                    }
                }

                statusFile.writeText("COMPLETED: ${resultFile.absolutePath}")
            } catch (error: Throwable) {
                statusFile.writeText("FAILED: ${error.javaClass.simpleName}: ${error.message}")
                Log.e(TAG, "Benchmark failed", error)
            } finally {
                runCatching { engine.close() }
            }
        }

    private suspend fun measure(
        engine: BenchmarkEngine,
        fixture: BenchmarkFixture,
        iteration: Int,
        model: String,
        modelSha256: String,
        cooldownMs: Int,
        coldLoadMs: Double?
    ): BenchmarkResult {
        val power = getSystemService(PowerManager::class.java)
        val thermalBefore = power.currentThermalStatus
        val pssBefore = Debug.getPss()
        val started = SystemClock.elapsedRealtimeNanos()
        return try {
            val generation = engine.generate(
                BenchmarkFixtures.SYSTEM_PROMPT,
                BenchmarkFixtures.userPrompt(fixture),
                fixture.maxTokens
            )
            BenchmarkResult(
                engine = BuildConfig.BENCHMARK_ENGINE,
                backend = engine.backend,
                computeUnit = engine.computeUnit,
                model = model,
                modelSha256 = modelSha256,
                cooldownMs = cooldownMs,
                fixture = fixture.id,
                iteration = iteration,
                coldLoadMs = coldLoadMs,
                totalMs = elapsedMs(started),
                firstTokenMs = generation.firstTokenMs,
                promptMs = generation.promptMs,
                decodeMs = generation.decodeMs,
                promptTokens = generation.promptTokens,
                outputTokens = generation.outputTokens,
                prefillTokensPerSecond = generation.prefillTokensPerSecond,
                decodeTokensPerSecond = generation.decodeTokensPerSecond,
                outputSha256 = sha256(generation.output),
                outputChars = generation.output.length,
                syntheticOutput = "",
                stopReason = generation.stopReason,
                pssBeforeKb = pssBefore,
                pssAfterKb = Debug.getPss(),
                thermalBefore = thermalBefore,
                thermalAfter = power.currentThermalStatus,
                error = null
            )
        } catch (error: Throwable) {
            BenchmarkResult(
                engine = BuildConfig.BENCHMARK_ENGINE,
                backend = engine.backend,
                computeUnit = engine.computeUnit,
                model = model,
                modelSha256 = modelSha256,
                cooldownMs = cooldownMs,
                fixture = fixture.id,
                iteration = iteration,
                coldLoadMs = coldLoadMs,
                totalMs = elapsedMs(started),
                firstTokenMs = null,
                promptMs = null,
                decodeMs = null,
                promptTokens = null,
                outputTokens = null,
                prefillTokensPerSecond = null,
                decodeTokensPerSecond = null,
                outputSha256 = sha256(""),
                outputChars = 0,
                syntheticOutput = "",
                stopReason = null,
                pssBeforeKb = pssBefore,
                pssAfterKb = Debug.getPss(),
                thermalBefore = thermalBefore,
                thermalAfter = power.currentThermalStatus,
                error = "${error.javaClass.simpleName}: ${error.message}"
            )
        }
    }

    private fun elapsedMs(startedNanos: Long): Double =
        (SystemClock.elapsedRealtimeNanos() - startedNanos) / 1_000_000.0

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte) }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
    }

    companion object {
        private const val TAG = "S1Benchmark"
        private const val EXTRA_FIXTURE = "fixture"
        private const val EXTRA_WARMUPS = "warmups"
        private const val EXTRA_RUNS = "runs"
        private const val EXTRA_COOLDOWN_MS = "cooldown_ms"
        private const val EXTRA_MODEL = "model"
        private const val EXTRA_MODEL_SHA256 = "model_sha256"
        private const val DEFAULT_MODEL = "s1-mini-q4_k_m.gguf"
        private const val DEFAULT_MODEL_SHA256 =
            "3b41ebe2502cbd03e811d5d16b022f5ab551eda58d62597d152f89535003c634"
    }
}
