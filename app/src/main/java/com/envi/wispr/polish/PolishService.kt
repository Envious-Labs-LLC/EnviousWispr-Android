package com.envi.wispr.polish

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.SystemClock
import com.envi.wispr.debug.DebugLogger
import com.envi.wispr.cleanup.PolishPipeline
import com.envi.wispr.cleanup.CleanupOptions
import com.envi.wispr.providers.ProviderCancellation
import com.envi.wispr.providers.ProviderConfigurationRepository
import com.envi.wispr.providers.ProviderFailureKind
import com.envi.wispr.providers.PolishMode
import com.envi.wispr.providers.ProviderPolishClient
import com.envi.wispr.providers.ProviderPolishResult
import com.envi.wispr.providers.capabilities
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference

/** Keeps S1-mini loaded in a separate process so ASR memory can be reclaimed independently. */
class PolishService : Service() {
    companion object {
        private const val TAG = "PolishService"
    }

    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "S1PolishThread")
    }
    private lateinit var providerRepository: ProviderConfigurationRepository
    private val providerClient = ProviderPolishClient()
    private val activeCancellation = AtomicReference<ProviderCancellation?>(null)
    private val s1Runtime by lazy { S1GenieXRuntime(applicationContext) }

    @Volatile
    private var modelReady = false

    @Volatile
    private var modelStatus = "Waiting to load ${S1Config.MODEL_NAME}"

    @Volatile
    private var modelLoading = false

    private val binder = object : IPolishService.Stub() {
        override fun polish(
            rawText: String?,
            removeFillers: Boolean,
            spokenEmoji: Boolean,
            spokenPunctuation: Boolean,
            callback: IPolishCallback?,
        ) {
            val raw = rawText.orEmpty().trim()
            val cleanupOptions = CleanupOptions(
                removeFillers = removeFillers,
                spokenEmoji = spokenEmoji,
                spokenPunctuation = spokenPunctuation,
            )
            if (raw.isBlank()) {
                callback?.onResult(raw, "No speech", 0)
                return
            }

            val cancellation = ProviderCancellation()
            activeCancellation.set(cancellation)
            executor.execute {
                val started = SystemClock.elapsedRealtime()
                val preparedRaw = raw
                try {
                    val mode = providerRepository.loadMode()
                    val selectedProvider = if (mode == PolishMode.PROVIDER) providerRepository.load() else null
                    var providerFailure: ProviderFailureKind? = null
                    val pipeline = when (mode) {
                        PolishMode.OFF -> PolishPipeline.run(preparedRaw, cleanupOptions)
                        PolishMode.OFFLINE_S1 -> PolishPipeline.run(preparedRaw, cleanupOptions) { cleaned ->
                            if (modelReady) polishWithS1(cleaned) else null
                        }
                        PolishMode.PROVIDER -> if (selectedProvider == null) {
                            providerFailure = ProviderFailureKind.INVALID_CONFIGURATION
                            PolishPipeline.run(preparedRaw, cleanupOptions)
                        } else {
                            PolishPipeline.run(preparedRaw, cleanupOptions) { cleaned ->
                                when (val result = providerClient.polish(selectedProvider.request(cleaned), cancellation)) {
                                    is ProviderPolishResult.Success -> result.text
                                    is ProviderPolishResult.Failure -> {
                                        providerFailure = result.kind
                                        null
                                    }
                                }
                            }
                        }
                    }
                    providerFailure?.let { failure ->
                        DebugLogger.warn(TAG, "Provider polish unavailable: ${failure.name}")
                    }
                    val engine = when {
                        mode == PolishMode.OFF -> "Polish off"
                        pipeline.usedModel && mode == PolishMode.PROVIDER ->
                            selectedProvider!!.provider.capabilities().displayName
                        pipeline.usedModel ->
                            "${S1Config.MODEL_NAME} by ${S1Config.MODEL_CREATOR} (${s1Runtime.activeComputeUnit.uppercase()})"
                        else -> "Deterministic fallback"
                    }
                    val latency = SystemClock.elapsedRealtime() - started
                    DebugLogger.log(TAG, "polish_done")
                    DebugLogger.log(TAG, "$engine polished text in ${latency}ms")
                    callback?.onResult(pipeline.text, engine, latency)
                } catch (exception: Exception) {
                    DebugLogger.error(TAG, "Polish failed")
                    callback?.onResult(
                        PolishPipeline.run(preparedRaw, cleanupOptions).text,
                        "Deterministic fallback",
                        SystemClock.elapsedRealtime() - started
                    )
                } finally {
                    activeCancellation.compareAndSet(cancellation, null)
                }
            }
        }

        override fun isReady(): Boolean = when (providerRepository.loadMode()) {
            PolishMode.OFF -> true
            PolishMode.OFFLINE_S1 -> modelReady
            PolishMode.PROVIDER -> providerRepository.load()?.let { selected ->
                !selected.provider.capabilities().requiresApiKey || !selected.apiKey.isNullOrBlank()
            } == true
        }

        override fun getStatus(): String = when (providerRepository.loadMode()) {
            PolishMode.OFF -> "Polish off; deterministic cleanup active"
            PolishMode.OFFLINE_S1 -> modelStatus
            PolishMode.PROVIDER -> providerRepository.load()?.let { selected ->
                if (isReady) "${selected.provider.capabilities().displayName} provider ready"
                else "Provider configuration missing; deterministic fallback active"
            } ?: "Provider configuration missing; deterministic fallback active"
        }

        override fun warmUp() {
            if (providerRepository.loadMode() == PolishMode.OFFLINE_S1) ensureModelLoaded()
        }
    }

    override fun onCreate() {
        super.onCreate()
        providerRepository = ProviderConfigurationRepository(this)
        if (providerRepository.loadMode() == PolishMode.OFFLINE_S1) ensureModelLoaded()
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        activeCancellation.getAndSet(null)?.cancel()
        executor.execute {
            s1Runtime.close()
            modelReady = false
        }
        executor.shutdown()
        super.onDestroy()
    }

    @Synchronized
    private fun ensureModelLoaded() {
        if (modelReady || modelLoading) return
        modelLoading = true
        executor.execute {
            val selection = S1ModelSelector.resolve(this)
            if (selection == null) {
                modelStatus = "${S1Config.MODEL_NAME} is not verified in app-private storage"
                DebugLogger.warn(TAG, modelStatus)
                modelLoading = false
                return@execute
            }

            modelStatus = "Loading ${S1Config.MODEL_NAME}"
            val started = SystemClock.elapsedRealtime()
            try {
                val result = s1Runtime.load(selection.file.path, selection.computeUnits)
                modelReady = true
                val elapsed = SystemClock.elapsedRealtime() - started
                val modelKind = if (selection.npuOptimized) "NPU model" else "compatibility model"
                modelStatus = "Ready on ${s1Runtime.activeComputeUnit.uppercase()} in ${elapsed}ms ($modelKind)"
                DebugLogger.log(TAG, "${S1Config.MODEL_NAME} loaded: $result; $modelStatus")
            } catch (exception: Throwable) {
                modelReady = false
                modelStatus = "S1 unavailable; deterministic fallback active"
                DebugLogger.error(TAG, modelStatus)
            } finally {
                modelLoading = false
            }
        }
    }

    private fun polishWithS1(rawText: String): String? {
        val output = s1Runtime.generate(
            S1Config.SYSTEM_PROMPT,
            S1PromptBuilder.buildUserPrompt(rawText),
            S1PromptBuilder.maxOutputTokens(rawText)
        ).trim()

        if (output.startsWith("ERROR:")) {
            DebugLogger.warn(TAG, "S1 generation failed")
            return null
        }

        val cleaned = output.substringAfterLast("</think>").trim()
        if (cleaned.isBlank()) return null
        if (!com.envi.wispr.cleanup.TextSafety.isSafe(rawText, cleaned)) {
            DebugLogger.warn(TAG, "Rejected unsafe S1 output")
            return null
        }
        return cleaned
    }

}
