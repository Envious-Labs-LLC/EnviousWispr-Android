package com.envi.wispr.polish

import com.envi.wispr.process.EngineDeadline
import com.envi.wispr.processing.ProcessingCheckRegistry
import com.envi.wispr.processing.ProcessingBackend
import com.envi.wispr.processing.ProcessingEnvironment
import com.envi.wispr.processing.ProcessingEvidenceStamp
import com.envi.wispr.processing.ProcessingUsage
import com.envi.wispr.processing.ProcessingCheckResult
import com.envi.wispr.processing.ProcessingCheckStatus
import com.envi.wispr.processing.IProcessingCheckCallback
import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.SystemClock
import com.envi.wispr.cleanup.CleanupLanguage
import com.envi.wispr.cleanup.CleanupLanguagePolicy
import com.envi.wispr.cleanup.CleanupOptions
import com.envi.wispr.cleanup.LanguageDetector
import com.envi.wispr.cleanup.PolishPipeline
import com.envi.wispr.cleanup.TextSafety
import com.envi.wispr.debug.DebugLogger
import com.envi.wispr.providers.AndroidKeystoreSecretStore
import com.envi.wispr.providers.ProviderPolishClient
import com.envi.wispr.providers.ProviderPolishRequest
import com.envi.wispr.providers.ProviderPolishResult
import com.envi.wispr.providers.SecretStore
import com.envi.wispr.providers.capabilities
import com.envi.wispr.debug.TakeLog
import java.io.Closeable
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
        /**
         * A model load's hard deadline (#344): about six times the slowest load measured on the S26, 10.63 s for the
         * first GPU load while its kernels compile (`polish-engines.md`).
         */
        private const val MODEL_LOAD_DEADLINE_MS = 60_000L

        /** How long the orderly close may wait behind the worker before the process ends instead (#344). */
        private const val ORDERLY_CLOSE_BOUND_MS = 5_000L
        private const val TAG = "PolishService"

        /** The take id a request from the legacy, take-less `polishRequest` carries in the local log (#378). */
        private const val LEGACY_TAKE = "untracked"
        private const val EXIT_GRACE_MS = 300L
    }

    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "S1PolishThread")
    }

    /**
     * The failure answers' own worker (#291): never the binder thread, never [executor], which may be wedged (#75).
     * A daemon, so it never holds the process open.
     */
    private val fallbackLane = PolishFallbackLane(
        worker = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "PolishFallbackThread").apply { isDaemon = true } },
        prepare = { raw, options, log -> fallbackText(raw, options, log) },
    )
    private lateinit var secrets: SecretStore
    private val providerClient = ProviderPolishClient()
    private val registry = PolishRequestRegistry()
    private val qualificationRegistry = ProcessingCheckRegistry()
    private val activeQualifications = AtomicInteger()
    private val resident = S1LoadedConfiguration()
    private var lastLoadStamp: ProcessingEvidenceStamp? = null
    private var lastLoadObservation: Pair<S1LoadConfiguration, Pair<String, Boolean>>? = null
    private val s1Runtime by lazy { S1GenieXRuntime(applicationContext) }

    /**
     * Reads the dictation's language off the finished transcript so deterministic cleanup does not apply
     * English number, money and date rules to the other 24 languages Parakeet v3 decodes (#107).
     * Built in `onCreate`, not as a field initializer and NOT lazily. A field initializer runs before the
     * service's context is attached, and the detector needs an application context to initialize ML Kit
     * in THIS process — `:polish`, where ML Kit's own `ContentProvider` initializer never runs. A `Lazy`
     * would fix that and reopen the close-versus-first-use race one level ABOVE the detector's own lock:
     * `onDestroy` could observe it uninitialised, return, and an in-flight binder callback could then
     * construct it after the only close opportunity had passed. Constructing eagerly in `onCreate` loads
     * no model, so the detector's lock owns the whole race (review round 5).
     */
    private lateinit var languageDetector: MlKitLanguageDetector

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
    private val activeModelLoads = AtomicInteger()

    @Volatile
    private var modelReady = false

    @Volatile
    private var modelStatus = "Waiting to load ${S1Config.MODEL_NAME}"

    @Volatile
    private var modelLoading = false

    /**
     * Guards a warm-up's admission against destruction (#344): `ensureModelLoaded` queues a load only while not
     * [destroyed], and `onDestroy` reads [modelLoading] under the same lock, so no load can be queued behind the
     * orderly close after destruction decided there was none.
     */
    private val loadLock = Any()
    @Volatile private var destroyed = false

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
                val text = fallbackText(raw, options, null)
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
        ) = accept(requestId, rawText, CleanupOptions(removeFillers, spokenEmoji, spokenPunctuation), policy, takeId = "", callback)

        /** The versioned request (issue #176): identical, plus the take's id bound to the request entry. */
        override fun polishRequestForTake(
            requestId: Long,
            rawText: String?,
            removeFillers: Boolean,
            spokenEmoji: Boolean,
            spokenPunctuation: Boolean,
            policy: PolishPolicy?,
            takeId: String?,
            callback: IPolishCallback?,
        ) = accept(requestId, rawText, CleanupOptions(removeFillers, spokenEmoji, spokenPunctuation), policy, takeId.orEmpty(), callback)

        override fun polishRequestWithCleanupForTake(
            requestId: Long,
            rawText: String?,
            cleanup: CleanupRequestOptions?,
            policy: PolishPolicy?,
            takeId: String?,
            callback: IPolishCallback?,
        ) = accept(requestId, rawText, cleanup?.options ?: CleanupOptions(), if (cleanup == null) null else policy, takeId.orEmpty(), callback)

        private fun accept(
            requestId: Long,
            rawText: String?,
            options: CleanupOptions,
            policy: PolishPolicy?,
            takeId: String,
            callback: IPolishCallback?,
        ) {
            val raw = rawText.orEmpty().trim()
            // The request's own take id (#378), never only the registry's: polish can answer before registration.
            val log = TakeLog(takeId.ifEmpty { LEGACY_TAKE }, TAG).also { it.startPipeline() }
            log.words("polish_input") { raw }
            if (raw.isBlank()) {
                deliver(callback, PolishOutcome(requestId, raw, PolishEngineLabels.NO_SPEECH, PolishReason.NO_SPEECH, 0, 0), log)
                return
            }
            // Our own client always sends the take's frozen policy; a null one is a protocol fault, never the
            // user's Off (#278). Answer the deterministic text as UNEXPECTED, which raises its defect.
            if (policy == null) {
                log.warn("Polish request $requestId carried no policy")
                fallbackLane.answer(requestId, raw, options, PolishReason.UNEXPECTED, log) { deliver(callback, it, log) }
                return
            }
            val effectivePolicy: PolishPolicy = policy
            if (poisoned.get()) {
                // This process is ending after a timeout; a request queued behind the wedged worker would only
                // learn that when the process died. Answer now, from the fallback lane (#291).
                fallbackLane.answer(requestId, raw, options, PolishReason.LOCAL_FAILED, log) { deliver(callback, it, log) }
                return
            }
            val entry = registry.register(requestId)?.also { it.takeId = takeId }
            if (entry == null) {
                log.warn("Refusing polish request $requestId: id already registered")
                fallbackLane.answer(requestId, raw, options, PolishReason.UNEXPECTED, log) { deliver(callback, it, log) }
                return
            }
            val tracksLocal = effectivePolicy is PolishPolicy.LocalS1
            if (tracksLocal) activeLocalRequests.incrementAndGet()
            try {
                // The budget's debug override is a file read (#291): it happens on the worker, never on this binder thread.
                executor.execute { work(entry, callback, requestId, raw, options, effectivePolicy, localBudget(), tracksLocal, log) }
            } catch (failure: RuntimeException) {
                if (tracksLocal) activeLocalRequests.decrementAndGet()
                registry.release(entry)
                throw failure
            }
        }

        override fun warmUpWithPolicy(policy: PolishPolicy?) {
            // Logged here too (#236): the caller only learns that its send failed, never why the load did not start.
            runCatching { if (policy is PolishPolicy.LocalS1) ensureModelLoaded(policy) }
                .onFailure { error -> DebugLogger.warn(TAG, "Polish warm-up failed: ${error.javaClass.simpleName}") }
        }

        override fun cancel(requestId: Long) {
            registry.cancel(requestId)
            fallbackLane.cancel(requestId)
        }

        override fun qualifyProcessing(operationId: Long, backend: String?, callback: IProcessingCheckCallback?) {
            val selected = runCatching { ProcessingBackend.fromWire(backend.orEmpty()) }.getOrNull() ?: return
            synchronized(loadLock) {
                if (destroyed || poisoned.get()) {
                    callback?.onChecked(ProcessingCheckResult(operationId, selected, ProcessingEnvironment.s1ContextId(), ProcessingCheckStatus.RUNTIME_FAILED))
                    return
                }
                val entry = qualificationRegistry.register(operationId) ?: run {
                    callback?.onChecked(ProcessingCheckResult(operationId, selected, ProcessingEnvironment.s1ContextId(), ProcessingCheckStatus.CANCELLED))
                    return
                }
                activeQualifications.incrementAndGet()
                try { executor.execute { qualify(entry, selected, callback) } }
                catch (error: RuntimeException) {
                    activeQualifications.decrementAndGet(); qualificationRegistry.release(entry)
                    throw error
                }
            }
        }
        override fun cancelQualification(operationId: Long) = qualificationRegistry.cancel(operationId)

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
        log: TakeLog,
    ) {
        val started = SystemClock.elapsedRealtime()
        val timeoutUsage = if (effectivePolicy is PolishPolicy.LocalS1) {
            val captured = configuration(effectivePolicy)
            ProcessingUsage(resident.loaded?.takeIf { it.requested == captured }?.backend, captured.contextId, effectivePolicy.processing,
                false, runtimeFailed = true, artifactStamp = captured.artifactStamp, artifactSha256 = checkNotNull(com.envi.wispr.models.ModelManifest.s1.files.single().sha256))
        } else null
        // Armed only for a local generation: the cloud client bounds itself and honours cancel.
        val armed = if (effectivePolicy is PolishPolicy.LocalS1 && !poisoned.get()) {
            deadline.arm(budget.hardMs) { expireLocal(entry, callback, requestId, raw, options, started, log, timeoutUsage) }
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
                fallbackOutcome(requestId, raw, options, log, PolishReason.CANCELLED)
            } else if (poisoned.get()) {
                fallbackOutcome(requestId, raw, options, log, PolishReason.LOCAL_FAILED)
            } else {
                run(requestId, raw, options, effectivePolicy, entry, started, budget, log)
            }
            if (outcome.reason == PolishReason.LOCAL_TIMEOUT) {
                // The cooperative timeout returned: same winning path as the hard timer.
                armed?.cancel()
                expireLocal(entry, callback, requestId, raw, options, started, log, timeoutUsage)
            } else if (armed == null || armed.cancel()) {
                releaseLocal()
                entry.deliverOnce { deliver(callback, outcome, log) }
            }
            // else: the hard deadline already expired and owns the delivery and the exit.
        } catch (exception: Exception) {
            log.error("Polish failed", exception)
            val fallback = fallbackOutcome(requestId, raw, options, log, PolishReason.UNEXPECTED, SystemClock.elapsedRealtime() - started)
            if (armed == null || armed.cancel()) {
                releaseLocal()
                entry.deliverOnce { deliver(callback, fallback, log) }
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
        log: TakeLog,
        processing: ProcessingUsage?,
    ) {
        expireOnce(
            entry,
            poison = {
                poisoned.set(true)
                log.warn("Local polish deadline expired for request $requestId; engine process will end")
            },
            deliver = {
                deliver(
                    callback,
                    fallbackOutcome(requestId, raw, options, log, PolishReason.LOCAL_TIMEOUT, SystemClock.elapsedRealtime() - started).copy(processing = processing?.copy(generationStamp = ProcessingEvidenceStamp.now(this))),
                    log,
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

    /**
     * The language answer for one request, resolved through the policy's confidence floor. Every exit
     * from this engine goes through [fallbackText] or through `run`, and both call this, so two exits
     * on one request cannot clean the same words under different language answers.
     */
    private fun detectLanguage(text: String): CleanupLanguage =
        CleanupLanguagePolicy.resolve(languageDetector.detect(text))

    /**
     * The deterministic text for every failure exit. Detection lives here rather than at each of the
     * seven call sites it replaced, so a new failure exit cannot forget the language.
     */
    /** [log] is the request's take log when there is one (#378); the take-less lane and legacy path pass null. */
    private fun fallbackText(raw: String, options: CleanupOptions, log: TakeLog?): String =
        PolishFallback.deterministicOrWords(raw, options, languageDetector, warn = { message ->
            if (log != null) log.warn(message) else DebugLogger.warn(TAG, message)
        })

    private fun fallbackOutcome(requestId: Long, raw: String, options: CleanupOptions, log: TakeLog?, reason: PolishReason, latencyMs: Long = 0): PolishOutcome {
        val cleaned = fallbackText(raw, options, log)
        return PolishOutcome(requestId, cleaned, PolishEngineLabels.DETERMINISTIC, reason, 0, latencyMs)
    }

    /** The single delivery site. A dead client throws here; the throw is logged and goes no further. */
    private fun deliver(callback: IPolishCallback?, outcome: PolishOutcome, log: TakeLog) {
        log.log("polish_done")
        log.log("$outcome")
        log.words("polish_answer") { outcome.text }
        runCatching { callback?.onOutcome(outcome) }
            .onFailure { error -> log.warn("Outcome for request ${outcome.requestId} not delivered: ${error.javaClass.simpleName}") }
    }

    private fun run(
        requestId: Long,
        raw: String,
        options: CleanupOptions,
        policy: PolishPolicy,
        entry: PolishRequestRegistry.Entry,
        started: Long,
        budget: LocalPolishBudget,
        log: TakeLog,
    ): PolishOutcome {
        // What the model adapter learned about its own failure, recorded before it hands null back
        // to the pipeline, which cannot tell a thrown adapter from a blank answer.
        val requestedLocal = (policy as? PolishPolicy.LocalS1)?.let(::configuration)
        var attempt: PolishReason? = null
        var statusCode = 0
        var attemptedBackend: ProcessingBackend? = null
        var generationFailed = false
        val language = detectLanguage(raw)
        // The words after each cleanup family (#378), into the local log file only.
        val trace: (String, String) -> Unit = { family, text -> log.words("cleanup_$family") { text } }
        val pipeline = when (policy) {
            PolishPolicy.Off, PolishPolicy.CloudUnconfigured -> PolishPipeline.run(raw, options, language, trace = trace)
            is PolishPolicy.LocalS1 -> PolishPipeline.run(raw, options, language, trace = trace, restoreLocalEmoji = true) { cleaned ->
                if (!modelReady || checkNotNull(requestedLocal).artifactStamp.isEmpty() || !resident.matches(requestedLocal)) {
                    attempt = PolishReason.LOCAL_NOT_READY
                    null
                } else {
                    attemptedBackend = resident.loaded?.backend
                    polishWithS1(cleaned, policy.control, budget.cooperativeMs, log) { reason ->
                        attempt = reason; generationFailed = reason == PolishReason.LOCAL_FAILED
                    }
                }
            }
            is PolishPolicy.Cloud -> PolishPipeline.run(raw, options, language, trace = trace) { cleaned ->
                log.words("cloud_prompt") { cleaned }
                val request = ProviderPolishRequest(
                    provider = policy.provider,
                    model = policy.model,
                    prompt = cleaned,
                    apiKey = runCatching { secrets.get(policy.provider) }.getOrNull(),
                    endpoint = policy.endpoint,
                    selfHostedProtocol = policy.protocol,
                )
                when (val result = providerClient.polish(request, entry.cancellation)) {
                    is ProviderPolishResult.Success -> result.text.also { answer -> log.words("cloud_answer") { answer } }
                    is ProviderPolishResult.Failure -> {
                        attempt = PolishReason.from(result.kind, result.signal)
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
        if (pipeline.refusal != null) log.warn("Polish guard refused: ${pipeline.refusal}")
        if (reason == PolishReason.TOO_SHORT) {
            log.log("Polish bypassed: too short")
        } else if (reason != PolishReason.POLISHED && reason != PolishReason.OFF) {
            log.warn("Polish fell back: reason=$reason status=$statusCode")
        }
        log.words("pipeline_result") { pipeline.text }
        val usage = if (policy is PolishPolicy.LocalS1) {
            val requested = checkNotNull(requestedLocal)
            val observation = lastLoadObservation?.takeIf { it.first.contextId == requested.contextId && it.first.artifactStamp == requested.artifactStamp && it.first.preference == requested.preference }?.second
            val failures = (resident.loaded?.takeIf { resident.matches(requested) }?.failedBackendCodes ?: observation?.first).orEmpty().split(',').filter(String::isNotEmpty).toMutableSet()
            if (generationFailed && attemptedBackend != null) failures += attemptedBackend!!.wire
            ProcessingUsage(
                attemptedBackend, requested.contextId, policy.processing, pipeline.usedModel,
                failures.joinToString(","), observation?.second == true, artifactStamp = requested.artifactStamp, artifactSha256 = checkNotNull(com.envi.wispr.models.ModelManifest.s1.files.single().sha256),
                loadStamp = resident.loaded?.failureStamp ?: lastLoadStamp, generationStamp = if (attemptedBackend != null) ProcessingEvidenceStamp.now(this) else null, generationFailed = generationFailed,
            )
        } else null
        return PolishOutcome(requestId, pipeline.text, engine, reason, statusCode, SystemClock.elapsedRealtime() - started, usage)
    }

    override fun onCreate() {
        super.onCreate()
        secrets = AndroidKeystoreSecretStore(this)
        languageDetector = MlKitLanguageDetector(applicationContext)
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        // The kill condition FIRST (#291): that branch ends the process without closing the detector or waiting for
        // any fallback answer, so nothing a fallback task holds can delay it.
        // Under the load lock (#344): after this no warm-up can queue a load, and a load already queued or running
        // is known here, so the orderly close is never queued behind it.
        val loading = synchronized(loadLock) {
            destroyed = true
            modelLoading
        }
        if (mustKillEngineOnDestroy(poisoned.get(), activeLocalRequests.get() + activeQualifications.get(), loading)) {
            // Orderly destruction would cancel the deadline timer and queue the runtime close behind a
            // worker that may be wedged (#75). The client has already unbound; nothing is owed to it.
            val why = when {
                poisoned.get() -> "destroyed after a local timeout"
                activeLocalRequests.get() > 0 -> "destroyed with ${activeLocalRequests.get()} local request(s) in flight"
                else -> "destroyed while the model was still loading"
            }
            poisoned.set(true)
            super.onDestroy()
            endProcess(why)
            return
        }
        // Orderly: the detector closes behind every admitted fallback answer, so each detects with a live detector
        // (#291); `close` is a no-op when no detection ever loaded a model.
        fallbackLane.close { if (::languageDetector.isInitialized) languageDetector.close() }
        registry.cancelAll()
        qualificationRegistry.cancelAll()
        deadlineScheduler.shutdownNow()
        executor.execute {
            s1Runtime.close()
            modelReady = false
        }
        executor.shutdown()
        // The orderly close waits behind whatever the workers are running (#344 review rounds 2 and 3): a vendor load,
        // a language-detector acquisition or any other task with no deadline of its own, on the main worker or the
        // fallback lane's (whose detector close is queued behind its answers). Rather than list each, the close itself
        // is bounded: BOTH workers must finish within one ORDERLY_CLOSE_BOUND_MS, or one holds something that never
        // returns and the process ends so nothing stays resident. The client has unbound; nothing is owed to it.
        Thread({
            val end = SystemClock.elapsedRealtime() + ORDERLY_CLOSE_BOUND_MS
            val mainDone = runCatching { executor.awaitTermination(ORDERLY_CLOSE_BOUND_MS, java.util.concurrent.TimeUnit.MILLISECONDS) }.getOrDefault(false)
            val left = (end - SystemClock.elapsedRealtime()).coerceAtLeast(0L)
            val fallbackDone = runCatching { fallbackLane.awaitTermination(left) }.getOrDefault(false)
            if (!mainDone || !fallbackDone) endProcess("a worker did not finish within $ORDERLY_CLOSE_BOUND_MS ms of destruction")
        }, "PolishCloseWatch").apply { isDaemon = true }.start()
        super.onDestroy()
    }

    /**
     * Queues the model load once. `modelReady`, `modelLoading` and `modelStatus` are `@Volatile`: written here
     * under this method's lock or on the single worker, and read from binder threads (#236).
     */
    @Synchronized
    private fun ensureModelLoaded(policy: PolishPolicy.LocalS1) = synchronized(loadLock) {
        if (destroyed || poisoned.get()) return@synchronized
        activeModelLoads.incrementAndGet()
        modelLoading = true
        // A queue that refuses the load (the executor is shut down in onDestroy) must not leave loading set,
        // or no later warm-up could ever start one (#236).
        try {
            executor.execute { loadModel(policy) }
        } catch (refused: java.util.concurrent.RejectedExecutionException) {
            finishWarm()
            DebugLogger.warn(TAG, "Polish model load refused: the worker is shut down")
        }
    }

    /**
     * The single worker's model load, queued by [ensureModelLoaded]. Its own hard deadline ([MODEL_LOAD_DEADLINE_MS],
     * #344) ends the process if the vendor load never returns: a request queued behind it on this worker never
     * starts, so never arms its own deadline, and the owner's fail-open handles the lost process. Every exit,
     * including a throw from model selection, clears [modelLoading] and the deadline.
     */
    private fun finishWarm() = synchronized(loadLock) {
        modelLoading = activeModelLoads.decrementAndGet() > 0
    }

    private fun configuration(policy: PolishPolicy.LocalS1) = S1LoadConfiguration(
        ProcessingEnvironment.s1ContextId(), policy.processing,
        policy.qualification,
        S1ModelSelector.installationStamp(this).orEmpty(),
    )

    private fun loadModel(policy: PolishPolicy.LocalS1) {
        if (destroyed || poisoned.get()) { finishWarm(); return }
        val requested = try { configuration(policy) } catch (error: Exception) { finishWarm(); throw error }
        if (requested.artifactStamp.isNotEmpty() && resident.matches(requested)) {
            resident.ensure(requested) { error("Matching runtime must not reload") }
            modelReady = true; finishWarm(); return
        }
        modelReady = false
        // The load and its deadline race once, atomically (`EngineDeadline`, review round 1): a load that returns after
        // the timer won never publishes readiness, and a timer after a load that won never ends the process.
        val stall = runCatching {
            deadline.arm(MODEL_LOAD_DEADLINE_MS) {
                poisoned.set(true)
                endProcess("model load stalled past $MODEL_LOAD_DEADLINE_MS ms")
            }
        }.getOrNull()
        try {
            val selection = S1ModelSelector.resolve(this)
            if (selection == null) {
                resident.clear(); s1Runtime.close()
                modelStatus = "${S1Config.MODEL_NAME} is not verified in app-private storage"
                DebugLogger.warn(TAG, modelStatus)
                return
            }
            if (stall?.current == EngineDeadline.State.EXPIRED) return

            modelStatus = "Loading ${S1Config.MODEL_NAME}"
            val started = SystemClock.elapsedRealtime()
            try {
                val resolved = requested.copy(artifactStamp = selection.installationStamp)
                val loaded = resident.ensure(resolved, failures = { s1Runtime.failedComputeUnits.split(',').filter(String::isNotEmpty).map(ProcessingBackend::fromWire).toSet() }, failureStamp = { lastLoadStamp }, release = { s1Runtime.close() }) { candidates ->
                    try { s1Runtime.load(selection.file.path, candidates.map { it.wire }) } finally { lastLoadStamp = ProcessingEvidenceStamp.now(this) }
                    ProcessingBackend.fromWire(s1Runtime.activeComputeUnit)
                }
                lastLoadObservation = loaded.requested to (s1Runtime.failedComputeUnits to s1Runtime.initializationFailed)
                val result = "GenieX on ${loaded.backend.wire}"
                if (stall != null && !stall.cancel()) return
                modelReady = true
                val elapsed = SystemClock.elapsedRealtime() - started
                modelStatus = "Ready on ${s1Runtime.activeComputeUnit.uppercase()} in ${elapsed}ms (standard model)"
                DebugLogger.log(TAG, "${S1Config.MODEL_NAME} loaded: $result; $modelStatus")
            } catch (exception: Throwable) {
                lastLoadObservation = requested to (s1Runtime.failedComputeUnits to (s1Runtime.initializationFailed || exception is S1RuntimeReleaseException))
                if (exception is S1RuntimeReleaseException) { poisoned.set(true); endProcess("model replacement release failed") }
                modelReady = false
                modelStatus = "S1 unavailable; deterministic fallback active"
                DebugLogger.error(TAG, modelStatus, exception)
            }
        } catch (release: S1RuntimeReleaseException) {
            poisoned.set(true); endProcess("unavailable model release failed")
        } finally {
            stall?.cancel()
            finishWarm()
        }
    }

    /** A separate operation namespace, sharing only the existing native worker and deadline primitive. */
    private fun qualify(entry: ProcessingCheckRegistry.Entry, backend: ProcessingBackend, callback: IProcessingCheckCallback?) {
        val contextId = ProcessingEnvironment.s1ContextId()
        try {
            ProcessingQualificationTask(
                deadline, MODEL_LOAD_DEADLINE_MS + LocalPolishBudget.SHIPPED.hardMs, poisoned,
                destroyed = { destroyed }, close = { s1Runtime.close() },
                invalidate = { resident.clear(); modelReady = false },
                released = { activeQualifications.decrementAndGet() }, endProcess = ::endProcess,
            ).run(entry, load = {
                if (backend !in ProcessingEnvironment.standardPolishBackends) ProcessingCheckStatus.NOT_IMPLEMENTED
                else {
                    val selection = S1ModelSelector.resolve(this)
                    if (selection == null) ProcessingCheckStatus.MODEL_MISSING else {
                        s1Runtime.load(selection.file.path, listOf(backend.wire))
                        ProcessingCheckStatus.AVAILABLE
                    }
                }
            }, canary = {
                val input = "um please send the report tomorrow morning"
                val generated = s1Runtime.generate(
                    S1Config.SYSTEM_PROMPT, S1PromptBuilder.buildUserPrompt(input, S1ControlSettings.DEFAULT),
                    S1PromptBuilder.maxOutputTokens(input), LocalPolishBudget.SHIPPED.cooperativeMs,
                )
                if (generated == null) ProcessingCheckStatus.EXPIRED else {
                    val complete = s1Runtime.lastGeneration
                    val cleaned = generated.substringAfterLast("</think>").trim()
                    val valid = cleaned.isNotBlank() && !generated.startsWith("ERROR:") && TextSafety.refusal(input, cleaned) == null && complete != null && complete.stopReason == "eos" && !complete.reachedCap &&
                        ProcessingBackend.fromWire(s1Runtime.activeComputeUnit) == backend
                    if (valid) ProcessingCheckStatus.AVAILABLE else ProcessingCheckStatus.CANARY_FAILED
                }
            }, answer = { status -> callback?.onChecked(ProcessingCheckResult(entry.operationId, backend, contextId, status, ProcessingEvidenceStamp.now(this))) })
        } finally { qualificationRegistry.release(entry) }
    }

    /**
     * @return the accepted model text, or null with the reason [record]ed: a thrown or `ERROR:`
     * generation is [PolishReason.LOCAL_FAILED]; a blank or unsafe answer is
     * [PolishReason.OUTPUT_REJECTED].
     */
    private fun polishWithS1(
        rawText: String,
        control: S1ControlSettings,
        cooperativeMs: Long,
        log: TakeLog,
        record: (PolishReason) -> Unit,
    ): String? {
        debugStall()
        log.log("S1 control line: ${control.controlLine()}")
        val userPrompt = S1PromptBuilder.buildUserPrompt(rawText, control)
        log.words("local_prompt") { userPrompt }
        val output = try {
            val generated = s1Runtime.generate(
                S1Config.SYSTEM_PROMPT,
                userPrompt,
                S1PromptBuilder.maxOutputTokens(rawText),
                cooperativeMs,
            )
            if (generated == null) {
                record(PolishReason.LOCAL_TIMEOUT)
                return null
            }
            s1Runtime.lastGeneration?.let { log.log(it.logLine()) }
            generated.trim()
        } catch (exception: Exception) {
            log.error("S1 generation threw", exception)
            record(PolishReason.LOCAL_FAILED)
            return null
        }

        if (output.startsWith("ERROR:")) {
            log.warn("S1 generation failed")
            record(PolishReason.LOCAL_FAILED)
            return null
        }

        val cleaned = output.substringAfterLast("</think>").trim()
        log.words("local_output") { cleaned }
        if (cleaned.isBlank()) {
            record(PolishReason.OUTPUT_REJECTED)
            return null
        }
        val refusal = TextSafety.refusal(rawText, cleaned)
        if (refusal != null) {
            // The refusal names a rule and counts only, never words (PayloadSanitizer stays untouched).
            log.warn("Rejected unsafe S1 output: $refusal")
            record(PolishReason.OUTPUT_REJECTED)
            return null
        }
        return cleaned
    }
}
