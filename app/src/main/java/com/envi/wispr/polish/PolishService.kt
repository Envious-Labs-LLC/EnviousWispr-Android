package com.envi.wispr.polish

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.SystemClock
import com.envi.wispr.cleanup.CleanupOptions
import com.envi.wispr.cleanup.PolishPipeline
import com.envi.wispr.cleanup.TextSafety
import com.envi.wispr.debug.DebugLogger
import com.envi.wispr.providers.AndroidKeystoreSecretStore
import com.envi.wispr.providers.ProviderPolishClient
import com.envi.wispr.providers.ProviderPolishRequest
import com.envi.wispr.providers.ProviderPolishResult
import com.envi.wispr.providers.SecretStore
import com.envi.wispr.providers.capabilities
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Keeps S1-mini loaded in a separate process so ASR memory can be reclaimed independently.
 *
 * The engine holds no settings. Every request carries its own [PolishPolicy] snapshot and every
 * answer is one [PolishOutcome] with the request's id (issue #69): a live `:polish` process used to
 * keep the preference values it read when it was created, so a mode change on the screen never
 * reached it. The only state the engine owns is whether its local model is loaded.
 */
class PolishService : Service() {
    companion object {
        private const val TAG = "PolishService"
        private const val EXIT_GRACE_MS = 300L
    }

    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "S1PolishThread")
    }
    private lateinit var secrets: SecretStore
    private val providerClient = ProviderPolishClient()
    private val registry = PolishRequestRegistry()
    private val s1Runtime by lazy { S1GenieXRuntime(applicationContext) }

    // The hard deadline on local generation runs on its own thread because the worker it watches may be
    // wedged inside native code (#75). Expiry delivers, poisons, and ends this process.
    private val deadlineScheduler = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "S1DeadlineThread").apply { isDaemon = true }
    }
    private val deadline = EngineDeadline(deadlineScheduler)

    /** Once a local generation has timed out the runtime is never reused; this process is ending. */
    private val poisoned = AtomicBoolean(false)

    /** Local requests accepted and not yet finished, queued or running; counted before they reach the worker. */
    private val activeLocalRequests = AtomicInteger()

    @Volatile
    private var modelReady = false

    @Volatile
    private var modelStatus = "Waiting to load ${S1Config.MODEL_NAME}"

    @Volatile
    private var modelLoading = false

    private val binder = object : IPolishService.Stub() {
        // ---- v1, kept declared for the separately installed instrumentation APK. No caller here.

        override fun polish(
            rawText: String?,
            removeFillers: Boolean,
            spokenEmoji: Boolean,
            spokenPunctuation: Boolean,
            callback: IPolishCallback?,
        ) {
            val raw = rawText.orEmpty().trim()
            val options = CleanupOptions(removeFillers, spokenEmoji, spokenPunctuation)
            if (raw.isBlank()) {
                runCatching { callback?.onResult(raw, PolishEngineLabels.NO_SPEECH, 0) }
                return
            }
            executor.execute {
                val started = SystemClock.elapsedRealtime()
                val text = PolishFallback.deterministic(raw, options)
                runCatching {
                    callback?.onResult(text, PolishEngineLabels.DETERMINISTIC, SystemClock.elapsedRealtime() - started)
                }
            }
        }

        override fun isReady(): Boolean = modelReady

        override fun getStatus(): String = modelStatus

        override fun warmUp() = Unit

        // ---- v2

        override fun polishRequest(
            requestId: Long,
            rawText: String?,
            removeFillers: Boolean,
            spokenEmoji: Boolean,
            spokenPunctuation: Boolean,
            policy: PolishPolicy?,
            callback: IPolishCallback?,
        ) {
            val raw = rawText.orEmpty().trim()
            val options = CleanupOptions(removeFillers, spokenEmoji, spokenPunctuation)
            val effectivePolicy = policy ?: PolishPolicy.Off
            if (raw.isBlank()) {
                deliver(callback, PolishOutcome(requestId, raw, PolishEngineLabels.NO_SPEECH, PolishReason.NO_SPEECH, 0, 0))
                return
            }
            if (poisoned.get()) {
                // This process is ending after a timeout; a request queued behind the wedged worker would only
                // learn that when the process died. Answer now.
                deliver(
                    callback,
                    PolishOutcome(requestId, PolishFallback.deterministic(raw, options), PolishEngineLabels.DETERMINISTIC, PolishReason.LOCAL_FAILED, 0, 0),
                )
                return
            }
            val entry = registry.register(requestId)
            if (entry == null) {
                DebugLogger.warn(TAG, "Refusing polish request $requestId: id already registered")
                deliver(
                    callback,
                    PolishOutcome(requestId, PolishFallback.deterministic(raw, options), PolishEngineLabels.DETERMINISTIC, PolishReason.UNEXPECTED, 0, 0),
                )
                return
            }
            val budget = localBudget()
            val tracksLocal = effectivePolicy == PolishPolicy.LocalS1
            if (tracksLocal) activeLocalRequests.incrementAndGet()
            try {
                executor.execute { work(entry, callback, requestId, raw, options, effectivePolicy, budget, tracksLocal) }
            } catch (failure: RuntimeException) {
                if (tracksLocal) activeLocalRequests.decrementAndGet()
                registry.release(entry)
                throw failure
            }
        }

        override fun warmUpWithPolicy(policy: PolishPolicy?) {
            if (policy == PolishPolicy.LocalS1) ensureModelLoaded()
        }

        override fun cancel(requestId: Long) {
            registry.cancel(requestId)
        }

        override fun isLocalModelReady(): Boolean = modelReady

        override fun localModelStatus(): String = modelStatus
    }

    /** One request on the single worker. */
    private fun work(
        entry: PolishRequestRegistry.Entry,
        callback: IPolishCallback?,
        requestId: Long,
        raw: String,
        options: CleanupOptions,
        effectivePolicy: PolishPolicy,
        budget: LocalPolishBudget,
        tracksLocal: Boolean,
    ) {
        val started = SystemClock.elapsedRealtime()
        // Armed only for a local generation: the cloud client bounds itself and honours cancel.
        val armed = if (effectivePolicy == PolishPolicy.LocalS1 && !poisoned.get()) {
            deadline.arm(budget.hardMs) { expireLocal(entry, callback, requestId, raw, options, started) }
        } else null
        // The count guards a WEDGED generation. It is released before a healthy delivery: the client may
        // publish and unbind before this worker's finally, and destroy must not read that as work in flight.
        var localReleased = !tracksLocal
        fun releaseLocal() {
            if (localReleased) return
            localReleased = true
            activeLocalRequests.decrementAndGet()
        }
        try {
            val outcome = if (entry.cancellation.isCancelled) {
                PolishOutcome(requestId, PolishFallback.deterministic(raw, options), PolishEngineLabels.DETERMINISTIC, PolishReason.CANCELLED, 0, 0)
            } else if (poisoned.get()) {
                PolishOutcome(requestId, PolishFallback.deterministic(raw, options), PolishEngineLabels.DETERMINISTIC, PolishReason.LOCAL_FAILED, 0, 0)
            } else {
                run(requestId, raw, options, effectivePolicy, entry, started, budget)
            }
            if (outcome.reason == PolishReason.LOCAL_TIMEOUT) {
                // The cooperative timeout returned: same winning path as the hard timer.
                armed?.cancel()
                expireLocal(entry, callback, requestId, raw, options, started)
            } else if (armed == null || armed.cancel()) {
                releaseLocal()
                entry.deliverOnce { deliver(callback, outcome) }
            }
            // else: the hard deadline already expired and owns the delivery and the exit.
        } catch (exception: Exception) {
            DebugLogger.error(TAG, "Polish failed", exception)
            val fallback = PolishOutcome(
                requestId,
                PolishFallback.deterministic(raw, options),
                PolishEngineLabels.DETERMINISTIC,
                PolishReason.UNEXPECTED,
                0,
                SystemClock.elapsedRealtime() - started,
            )
            if (armed == null || armed.cancel()) {
                releaseLocal()
                entry.deliverOnce { deliver(callback, fallback) }
            }
        } finally {
            releaseLocal()
            registry.release(entry)
        }
    }

    /**
     * The winning expiry path for a local generation, cooperative or hard (#75): poison first, so a request
     * entering during delivery already sees it; deliver the deterministic text synchronously; then end this
     * process after the reply has returned, because a wedged native generation cannot be interrupted and
     * the runtime is never reused after a coroutine cancellation either. Runs on the worker for the
     * cooperative case and on the deadline thread for the hard case; `deliverOnce` picks one.
     */
    private fun expireLocal(
        entry: PolishRequestRegistry.Entry,
        callback: IPolishCallback?,
        requestId: Long,
        raw: String,
        options: CleanupOptions,
        started: Long,
    ) {
        expireOnce(
            entry,
            poison = {
                poisoned.set(true)
                DebugLogger.warn(TAG, "Local polish deadline expired for request $requestId; engine process will end")
            },
            deliver = {
                deliver(
                    callback,
                    PolishOutcome(
                        requestId,
                        PolishFallback.deterministic(raw, options),
                        PolishEngineLabels.DETERMINISTIC,
                        PolishReason.LOCAL_TIMEOUT,
                        0,
                        SystemClock.elapsedRealtime() - started,
                    ),
                )
            },
            scheduleExit = { deadline.after(EXIT_GRACE_MS) { endProcess("local timeout") } },
        )
    }

    private fun endProcess(why: String) {
        DebugLogger.warn(TAG, "Ending the polish engine process: $why")
        android.os.Process.killProcess(android.os.Process.myPid())
    }

    /**
     * Debug builds only: `files/debug/polish-deadline-ms` stages the timeout that a real wedge would
     * produce (`device-testing.md` FACT: the-staged-polish-timeout). A release build never reads it.
     */
    private fun localBudget(): LocalPolishBudget {
        val debuggable = applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0
        if (!debuggable) return LocalPolishBudget.SHIPPED
        val file = File(filesDir, "debug/polish-deadline-ms")
        return LocalPolishBudget.fromOverride(runCatching { file.takeIf(File::isFile)?.readText() }.getOrNull())
    }

    /**
     * Debug builds only: `files/debug/polish-stall-ms` holds the worker here, outside the cooperative
     * timeout, for that many milliseconds, which is the only way to stage a WEDGED generation on a phone: the
     * hard timer, the poison, the exit, and the session watchdog above them all fire against a real stall
     * (`device-testing.md` FACT: the-staged-polish-timeout). A release build never reads it. Bounded like the
     * deadline override, and a value outside the bound is ignored.
     */
    private fun debugStall() {
        val debuggable = applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0
        if (!debuggable) return
        val file = File(filesDir, "debug/polish-stall-ms")
        val stallMs = runCatching { file.takeIf(File::isFile)?.readText()?.trim()?.toLong() }.getOrNull() ?: return
        if (stallMs < 1L || stallMs > LocalPolishBudget.MAX_OVERRIDE_MS) return
        DebugLogger.warn(TAG, "Debug stall of ${stallMs}ms before local generation")
        Thread.sleep(stallMs)
    }

    /** The single delivery site. A dead client throws here; the throw is logged and goes no further. */
    private fun deliver(callback: IPolishCallback?, outcome: PolishOutcome) {
        DebugLogger.log(TAG, "polish_done")
        DebugLogger.log(TAG, "$outcome")
        runCatching { callback?.onOutcome(outcome) }
            .onFailure { error -> DebugLogger.warn(TAG, "Outcome for request ${outcome.requestId} not delivered: ${error.javaClass.simpleName}") }
    }

    private fun run(
        requestId: Long,
        raw: String,
        options: CleanupOptions,
        policy: PolishPolicy,
        entry: PolishRequestRegistry.Entry,
        started: Long,
        budget: LocalPolishBudget,
    ): PolishOutcome {
        // What the model adapter learned about its own failure, recorded before it hands null back
        // to the pipeline, which cannot tell a thrown adapter from a blank answer.
        var attempt: PolishReason? = null
        var statusCode = 0
        val pipeline = when (policy) {
            PolishPolicy.Off, PolishPolicy.CloudUnconfigured -> PolishPipeline.run(raw, options)
            PolishPolicy.LocalS1 -> PolishPipeline.run(raw, options) { cleaned ->
                if (!modelReady) {
                    attempt = PolishReason.LOCAL_NOT_READY
                    null
                } else {
                    polishWithS1(cleaned, budget.cooperativeMs) { reason -> attempt = reason }
                }
            }
            is PolishPolicy.Cloud -> PolishPipeline.run(raw, options) { cleaned ->
                val request = ProviderPolishRequest(
                    provider = policy.provider,
                    model = policy.model,
                    prompt = cleaned,
                    apiKey = runCatching { secrets.get(policy.provider) }.getOrNull(),
                    endpoint = policy.endpoint,
                    selfHostedProtocol = policy.protocol,
                )
                when (val result = providerClient.polish(request, entry.cancellation)) {
                    is ProviderPolishResult.Success -> result.text
                    is ProviderPolishResult.Failure -> {
                        attempt = PolishReason.from(result.kind)
                        statusCode = result.statusCode ?: 0
                        null
                    }
                }
            }
        }
        val reason = PolishReason.resolve(policy, pipeline.outcome, attempt)
        val engine = when {
            policy == PolishPolicy.Off -> PolishEngineLabels.OFF
            pipeline.usedModel && policy is PolishPolicy.Cloud -> policy.provider.capabilities().displayName
            pipeline.usedModel -> "${S1Config.MODEL_NAME} by ${S1Config.MODEL_CREATOR} (${s1Runtime.activeComputeUnit.uppercase()})"
            else -> PolishEngineLabels.DETERMINISTIC
        }
        if (reason != PolishReason.POLISHED && reason != PolishReason.OFF) {
            DebugLogger.warn(TAG, "Polish fell back: reason=$reason status=$statusCode")
        }
        return PolishOutcome(requestId, pipeline.text, engine, reason, statusCode, SystemClock.elapsedRealtime() - started)
    }

    override fun onCreate() {
        super.onCreate()
        secrets = AndroidKeystoreSecretStore(this)
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        if (mustKillEngineOnDestroy(poisoned.get(), activeLocalRequests.get())) {
            // Orderly destruction would cancel the deadline timer and queue the runtime close behind a
            // worker that may be wedged (#75). The client has already unbound; nothing is owed to it.
            val why = if (poisoned.get()) "destroyed after a local timeout" else "destroyed with ${activeLocalRequests.get()} local request(s) in flight"
            poisoned.set(true)
            super.onDestroy()
            endProcess(why)
            return
        }
        registry.cancelAll()
        deadlineScheduler.shutdownNow()
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
                DebugLogger.error(TAG, modelStatus, exception)
            } finally {
                modelLoading = false
            }
        }
    }

    /**
     * @return the accepted model text, or null with the reason [record]ed: a thrown or `ERROR:`
     * generation is [PolishReason.LOCAL_FAILED]; a blank or unsafe answer is
     * [PolishReason.OUTPUT_REJECTED].
     */
    private fun polishWithS1(rawText: String, cooperativeMs: Long, record: (PolishReason) -> Unit): String? {
        debugStall()
        val output = try {
            val generated = s1Runtime.generate(
                S1Config.SYSTEM_PROMPT,
                S1PromptBuilder.buildUserPrompt(rawText),
                S1PromptBuilder.maxOutputTokens(rawText),
                cooperativeMs,
            )
            if (generated == null) {
                record(PolishReason.LOCAL_TIMEOUT)
                return null
            }
            generated.trim()
        } catch (exception: Exception) {
            DebugLogger.error(TAG, "S1 generation threw", exception)
            record(PolishReason.LOCAL_FAILED)
            return null
        }

        if (output.startsWith("ERROR:")) {
            DebugLogger.warn(TAG, "S1 generation failed")
            record(PolishReason.LOCAL_FAILED)
            return null
        }

        val cleaned = output.substringAfterLast("</think>").trim()
        if (cleaned.isBlank()) {
            record(PolishReason.OUTPUT_REJECTED)
            return null
        }
        if (!TextSafety.isSafe(rawText, cleaned)) {
            DebugLogger.warn(TAG, "Rejected unsafe S1 output")
            record(PolishReason.OUTPUT_REJECTED)
            return null
        }
        return cleaned
    }
}
