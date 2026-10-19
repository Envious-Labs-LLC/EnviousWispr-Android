package com.envi.wispr.polish

import android.content.Context
import com.envi.wispr.llama.S1Native
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * S1 on the repository's own llama.cpp build (#449), for phones whose processor or vendor libraries cannot run
 * GenieX. Same model file, system prompt, control line and greedy sampling; processor only. The native side picks
 * the processor variant (`s1_jni.cpp` `load_cpu_backend`) and keeps every vendor log line silent.
 */
internal class S1PortableRuntime(private val context: Context) : S1Runtime {
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

    /** Fires the cooperative abort from its own thread: the worker is inside native code while it waits. */
    private val abortTimer = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "S1PortableAbort").apply { isDaemon = true }
    }

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
            initialized = true
        }
        val loaded = try {
            loadFirstS1Backend(computeUnits) { unit ->
                check(unit == CPU) { "The portable runtime runs on the processor only" }
                val status = S1Native.loadModel(modelPath, S1Config.CONTEXT_SIZE, S1Config.THREAD_COUNT)
                check(!status.startsWith("ERROR:")) { status }
            }
        } catch (error: S1BackendLoadException) { failedComputeUnits = error.failedCodes; throw error }
        activeComputeUnit = loaded.backend; failedComputeUnits = loaded.failedCodes
        return "llama.cpp portable on ${loaded.backend}"
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
        // `s1_jni.cpp` END_* codes.
        const val END_EOS = 0
        const val END_LENGTH = 1
        const val END_ABORTED = 2
    }
}
