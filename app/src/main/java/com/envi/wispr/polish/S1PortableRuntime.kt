package com.envi.wispr.polish

import android.content.Context
import com.envi.wispr.llama.S1Native
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * S1 on the repository's own llama.cpp build (#449), for phones whose processor or vendor libraries cannot run
 * GenieX. Same model file, system prompt, control line and greedy sampling; processor only. The native side picks
 * the processor variant (`s1_jni.cpp` `load_cpu_backend`) and keeps every vendor log line silent.
 *
 * [abortTimer] fires the cooperative abort from its own thread (the worker is inside native code while it waits). It
 * is `PolishService`'s deadline thread, which the service shuts down in `onDestroy`, so this runtime starts none.
 */
internal class S1PortableRuntime(
    private val context: Context,
    private val abortTimer: ScheduledExecutorService,
) : S1Runtime {
    override var failedComputeUnits: String = ""
        private set
    override var initializationFailed: Boolean = false
        private set
    override var activeComputeUnit: String = "unloaded"
        private set
    @Volatile
    override var lastGeneration: GenerationEnd? = null
        private set

    private var initialized = false

    override fun load(modelPath: String, computeUnits: List<String>): String {
        failedComputeUnits = ""; initializationFailed = false
        close()
        if (!initialized) {
            val status = try { S1Native.initialize(context.applicationInfo.nativeLibraryDir) } catch (error: Throwable) {
                initializationFailed = true
                throw S1RuntimeInitializationException(error)
            }
            if (status.startsWith("ERROR:")) {
                initializationFailed = true
                throw S1RuntimeInitializationException(IllegalStateException(status))
            }
            android.util.Log.i(BENCH_TAG, "init $status")
            initialized = true
        }
        val loaded = try {
            loadFirstS1Backend(computeUnits) { unit ->
                check(unit == CPU) { "The portable runtime runs on the processor only" }
                loadWithExperimentalRoute(modelPath)
            }
        } catch (error: S1BackendLoadException) { failedComputeUnits = error.failedCodes; throw error }
        activeComputeUnit = loaded.backend; failedComputeUnits = loaded.failedCodes
        return "llama.cpp portable on ${loaded.backend}"
    }

    /**
     * EXPERIMENT (exp/eg1-micro-opencl-bench, debuggable builds only): `files/debug/eg1-backend` containing `opencl`
     * loads the same model fully offloaded to the OpenCL GPU and falls back to the processor on any failure. The
     * compute unit stays `cpu` for the policy machinery; the real route is [lastRoute] and the `EgBench` log line.
     */
    private fun loadWithExperimentalRoute(modelPath: String) {
        val debuggable = context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0
        val wantsOpenCl = debuggable && runCatching {
            java.io.File(context.filesDir, "debug/eg1-backend").takeIf { it.isFile }?.readText()?.trim()
        }.getOrNull() == "opencl"
        val flashAttention = if (debuggable) runCatching {
            java.io.File(context.filesDir, "debug/eg1-fa").takeIf { it.isFile }?.readText()?.trim()
        }.getOrNull() else null
        if (flashAttention == "on" || flashAttention == "off") android.system.Os.setenv("EW_FA", flashAttention, true)
        else android.system.Os.unsetenv("EW_FA")
        android.util.Log.i(BENCH_TAG, "flashAttention=${flashAttention ?: "auto"}")
        if (wantsOpenCl) {
            val started = android.os.SystemClock.elapsedRealtime()
            val status = S1Native.loadModel(modelPath, S1Config.CONTEXT_SIZE, S1Config.THREAD_COUNT, 99)
            val elapsed = android.os.SystemClock.elapsedRealtime() - started
            if (!status.startsWith("ERROR:")) {
                lastRoute = "opencl"
                android.util.Log.i(BENCH_TAG, "route=opencl nativeLoadMs=$elapsed status=$status")
                return
            }
            android.util.Log.w(BENCH_TAG, "route=opencl-failed nativeLoadMs=$elapsed status=$status; falling back to cpu")
        }
        val started = android.os.SystemClock.elapsedRealtime()
        val status = S1Native.loadModel(modelPath, S1Config.CONTEXT_SIZE, S1Config.THREAD_COUNT, 0)
        check(!status.startsWith("ERROR:")) { status }
        lastRoute = if (wantsOpenCl) "cpu-fallback" else "cpu"
        android.util.Log.i(
            BENCH_TAG,
            "route=$lastRoute nativeLoadMs=${android.os.SystemClock.elapsedRealtime() - started} status=$status",
        )
    }

    @Volatile
    var lastRoute: String = "unloaded"
        private set

    override fun warmPrefix(systemPrompt: String, timeoutMs: Long): Boolean {
        if (activeComputeUnit != CPU) return false
        S1Native.resetAbort()
        val timer = abortTimer.schedule({ S1Native.abort() }, timeoutMs, TimeUnit.MILLISECONDS)
        return try {
            S1Native.warmPrefix(systemPrompt)
        } finally {
            timer.cancel(false)
        }
    }

    override fun generate(systemPrompt: String, userPrompt: String, maxTokens: Int, timeoutMs: Long): String? {
        check(activeComputeUnit == CPU) { "S1 runtime is not loaded" }
        lastGeneration = null
        // Clear before arming, so an abort can never be lost to a reset that runs after it.
        S1Native.resetAbort()
        val timer = abortTimer.schedule({ S1Native.abort() }, timeoutMs, TimeUnit.MILLISECONDS)
        val output = try { S1Native.generate(systemPrompt, userPrompt, maxTokens) } finally { timer.cancel(false) }
        val end = S1Native.lastEnd()
        return when (end.getOrNull(0)) {
            END_ABORTED -> null
            END_EOS, END_LENGTH -> {
                lastGeneration = GenerationEnd(
                    stopReason = if (end[0] == END_EOS) "eos" else "length",
                    generatedTokens = end[1].toLong(),
                    promptTokens = end[2].toLong(),
                    cap = maxTokens,
                )
                output
            }
            // END_NONE: the call failed before it could end; `output` is the native "ERROR: ..." string.
            else -> output
        }
    }

    override fun close() {
        if (activeComputeUnit != "unloaded") {
            try { S1Native.unload() } catch (error: Throwable) { throw S1RuntimeReleaseException(error) }
        }
        activeComputeUnit = "unloaded"
    }

    private companion object {
        const val CPU = "cpu"
        const val BENCH_TAG = "EgBench"
        // `s1_jni.cpp` END_* codes.
        const val END_EOS = 0
        const val END_LENGTH = 1
        const val END_ABORTED = 2
    }
}
