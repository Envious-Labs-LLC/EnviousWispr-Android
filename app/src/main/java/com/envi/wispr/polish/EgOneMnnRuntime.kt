package com.envi.wispr.polish

import android.content.Context
import android.os.StatFs
import com.envi.wispr.mnn.MnnNative
import com.envi.wispr.models.EngineCache
import com.envi.wispr.models.ModelManifest
import java.io.File
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * EG-1 Micro on Alibaba MNN (#531), on the processor ([S1RuntimeKind.MNN_CPU]) or the graphics processor
 * ([S1RuntimeKind.MNN_OPENCL]). Weights are memory-mapped from a cache folder outside the model folder
 * ([EngineCache]), so most of the model's memory is file-backed and the phone can drop it under pressure.
 * If the cache cannot be built (no room, folder unusable) the load fails and the deterministic text is used;
 * there is deliberately no fallback to loading without the cache, which would hold the whole model in memory the
 * phone cannot free (#531 plan §3.4).
 *
 * [abortTimer] is `PolishService`'s deadline thread, which fires the cooperative abort; the native loop checks it
 * between tokens.
 */
internal class EgOneMnnRuntime(
    private val context: Context,
    private val abortTimer: ScheduledExecutorService,
    private val kind: S1RuntimeKind,
) : S1Runtime {
    init {
        require(kind == S1RuntimeKind.MNN_CPU || kind == S1RuntimeKind.MNN_OPENCL) { "not an MNN runtime: $kind" }
    }

    override var failedComputeUnits: String = ""
        private set
    override var initializationFailed: Boolean = false
        private set
    override var activeComputeUnit: String = UNLOADED
        private set
    @Volatile
    override var lastGeneration: GenerationEnd? = null
        private set

    private val unit: String = if (kind == S1RuntimeKind.MNN_OPENCL) GPU else CPU

    override fun load(modelPath: String, computeUnits: List<String>): String {
        failedComputeUnits = ""; initializationFailed = false
        close()
        val model = File(modelPath)
        val cache = EngineCache.folder(context, ModelManifest.eg1Micro, MNN_VERSION)
        EngineCache.sweepExcept(context, ModelManifest.eg1Micro, keep = cache)
        val prefix = File(cache, EngineCache.PREFIX_FOLDER)
        // KV scratch files a killed process left behind; MNN names new ones per run and never reuses these.
        EngineCache.sweepScratch(cache)
        val threads = if (unit == GPU) OPENCL_MODE else MnnCpuThreads.forThisProcess().also {
            com.envi.wispr.debug.DebugLogger.log("EgOneMnnRuntime", it.logLine())
        }.threads
        val loaded = try {
            loadFirstS1Backend(computeUnits) { requested ->
                check(requested == unit) { "${kind.identity} runs on $unit only" }
                check(model.name == CONFIG_FILE) { "not an MNN model folder" }
                cache.mkdirs()
                check(cache.isDirectory) { "cache folder unavailable" }
                check(cache.canWrite()) { "cache folder not writable" }
                prefix.mkdirs()
                check(prefix.isDirectory && prefix.canWrite()) { "prefix folder unavailable" }
                // Room for whatever this load and later prefix entries still have to write: the whole engine cache when
                // the weights are not built yet, otherwise one prefix entry plus KV scratch (the native side re-checks
                // before each new entry).
                val needed = requiredEngineCacheBytes(cache)
                check(StatFs(cache.path).availableBytes >= needed + MARGIN_BYTES) { "not enough storage for the engine cache" }
                val status = try {
                    MnnNative.loadModel(model.path, if (unit == GPU) "opencl" else "cpu", threads, cache.path, prefix.path)
                } catch (error: Throwable) {
                    initializationFailed = true
                    throw S1RuntimeInitializationException(error)
                }
                check(status.isEmpty()) { status }
            }
        } catch (error: S1BackendLoadException) { failedComputeUnits = error.failedCodes; throw error }
        activeComputeUnit = loaded.backend; failedComputeUnits = loaded.failedCodes
        return "MNN $MNN_VERSION on ${loaded.backend}"
    }

    /** Builds the saved system-prompt state before the first request (#531 plan §3.6), bounded by [timeoutMs]. */
    override fun warmPrefix(systemPrompt: String, timeoutMs: Long): Boolean {
        if (activeComputeUnit != unit) return false
        MnnNative.resetAbort()
        val timer = abortTimer.schedule({ MnnNative.abort() }, timeoutMs, TimeUnit.MILLISECONDS)
        return try {
            MnnNative.warmPrefix(systemPrompt)
        } finally {
            timer.cancel(false)
        }
    }

    override fun generate(systemPrompt: String, userPrompt: String, maxTokens: Int, timeoutMs: Long): String? {
        check(activeComputeUnit == unit) { "EG-1 runtime is not loaded" }
        lastGeneration = null
        // Clear before arming, so an abort can never be lost to a reset that runs after it.
        MnnNative.resetAbort()
        val timer = abortTimer.schedule({ MnnNative.abort() }, timeoutMs, TimeUnit.MILLISECONDS)
        val output = try {
            MnnNative.generate(systemPrompt, userPrompt, maxTokens, S1Config.CONTEXT_SIZE)
        } finally {
            timer.cancel(false)
        }
        val end = MnnNative.lastEnd()
        return when (end.getOrNull(0)) {
            END_ABORTED -> null
            END_STOP, END_CAP -> {
                lastGeneration = GenerationEnd(
                    stopReason = if (end[0] == END_STOP) "eos" else "length",
                    generatedTokens = end[1].toLong(),
                    promptTokens = end[2].toLong(),
                    cap = maxTokens,
                )
                output
            }
            // Engine error or refused input: `output` is the native "ERROR: ..." string PolishService rejects.
            else -> output
        }
    }

    override fun close() {
        if (activeComputeUnit != UNLOADED) {
            try { MnnNative.unload() } catch (error: Throwable) { throw S1RuntimeReleaseException(error) }
        }
        activeComputeUnit = UNLOADED
    }

    internal companion object {
        const val CPU = "cpu"
        const val GPU = "gpu"
        const val UNLOADED = "unloaded"
        const val CONFIG_FILE = "config.json"
        /** MNN's OpenCL mode word passed as `thread_num` (buffer memory, tuned kernels), as the 2026-11-15 study ran it. */
        private const val OPENCL_MODE = 68
        private const val MARGIN_BYTES = 128L * 1024L * 1024L
        // One saved prefix entry and the KV scratch at one thread, the larger case (`mnn_jni.cpp` ENTRY_BYTES, SCRATCH_BYTES).
        internal const val PREFIX_AND_SCRATCH_BYTES = 24_750_854L + 16_777_216L
        // `mnn_jni.cpp` END_* codes.
        const val END_STOP = 0
        const val END_CAP = 1
        const val END_ABORTED = 2

        /** MNN marks a complete cache with a `*sync.static` file it writes once the cache is fully built. */
        fun cacheBuilt(cache: File): Boolean = cache.listFiles().orEmpty().any { it.isFile && it.name.endsWith("sync.static") }

        /** Bytes the EG-1 Micro engine still needs free (above [MARGIN_BYTES]) before loading. */
        fun requiredEngineCacheBytes(cache: File): Long =
            if (cacheBuilt(cache)) PREFIX_AND_SCRATCH_BYTES else ModelManifest.eg1Micro.engineCacheBytes
    }
}
