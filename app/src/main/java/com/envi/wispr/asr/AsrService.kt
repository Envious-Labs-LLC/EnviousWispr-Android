package com.envi.wispr.asr

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.RemoteException
import android.os.SystemClock
import com.envi.wispr.debug.DebugLogger
import com.envi.wispr.debug.TakeLog
import com.envi.wispr.audio.PcmAudio
import com.envi.wispr.audio.RecordingLimits
import com.envi.wispr.models.LegacyModelSweep
import com.envi.wispr.models.ModelManifest
import com.envi.wispr.models.ModelStorage
import com.envi.wispr.recovery.RecoveryAudioStore
import com.envi.wispr.recovery.RecoveryClaimRelease
import com.envi.wispr.recovery.RecoveryContextDigest
import com.envi.wispr.recovery.RecoveryModelIdentity
import com.envi.wispr.recovery.RecoveryRecordIdentity
import com.envi.wispr.recovery.RecoveryRecordRef
import com.envi.wispr.recovery.RecoveryReplayRef
import com.envi.wispr.recovery.RecoveryResult
import com.envi.wispr.recovery.RecoveryResultCodec
import com.envi.wispr.recovery.RecoveryStoreResult
import com.envi.wispr.recovery.RecoveryStoreException
import com.envi.wispr.recovery.RecoveryStoreFailure
import com.envi.wispr.process.EngineDeadline
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.UUID

/**
 * ASR service running in a separate process (:asr).
 *
 * Accepts audio via file path (not byte array) to avoid AIDL size limits.
 * Runs [ParakeetEngine]: Parakeet Ultra (int8, #458) on ONNX Runtime through the macOS batch recipe (#374).
 * Returns raw text so the isolated polish service can run S1-mini or a safe fallback.
 */
class AsrService : Service() {
    internal enum class WorkKind { FOREGROUND, RECOVERY }
    private data class LoadedRecognizer(val engine: ParakeetEngine, val model: RecoveryModelIdentity)
    internal sealed class ResultRoute {
        internal data object Ordinary : ResultRoute()
        internal data class Recovery(val replay: RecoveryReplayRef, val first: Long, val count: Long, val completed: Boolean) : ResultRoute()
    }

    companion object {
        private const val TAG = "AsrService"
        private val processResources by lazy { ProcessResources() }

        /**
         * Hands the text over; [onDelivered] runs only when a callback existed and did not throw. The legacy model
         * sweep (#374) hangs off it, so a phone never loses the old model for a result nobody received.
         */
        internal fun deliverResult(callback: IAsrCallback?, text: String, onDelivered: () -> Unit, onThrew: (Throwable) -> Unit) {
            if (callback == null) return
            runCatching { callback.onResult(text) }.onSuccess { onDelivered() }.onFailure(onThrew)
        }

        /** The take id a take-less legacy byte-array request carries in the local log (#378). */
        private const val LEGACY_TAKE = "untracked"

        /**
         * The refusal the user reads if a file somehow arrives longer than the cap.
         *
         * Built from the limit rather than written out, because the two used to be separate: the number
         * in the sentence was 120 while the capture process was free to be changed to anything else.
         */
        private val OVER_LIMIT_MESSAGE =
            "This recording is longer than the ${RecordingLimits.MAX_DURATION_MINUTES} minute limit."
    }

    /** Process-owned scheduling/control only; every model remains owned by its service instance. */
    private class ProcessResources {
        private val worker = Executors.newSingleThreadExecutor {
            Thread(it, "AsrTranscriptionThread").apply { isDaemon = true }
        }
        val control = Executors.newSingleThreadExecutor {
            Thread(it, "AsrNativeControl").apply { isDaemon = true }
        }
        private val scheduler = ScheduledThreadPoolExecutor(1) {
            Thread(it, "AsrWatchdog").apply { isDaemon = true }
        }.apply { removeOnCancelPolicy = true }
        val watchdog = AsrWatchdog(EngineDeadline(scheduler)) { why ->
            DebugLogger.warn(TAG, "Ending the speech process: $why")
            android.os.Process.killProcess(android.os.Process.myPid())
        }
        val lane = RecognizerLane(worker, watchdog)
        val requests = AtomicLong()
        private val admission = Any()
        private var foreground = 0
        private val recoveries = mutableSetOf<Enrollment>()

        internal inner class Enrollment(val kind: WorkKind) {
            private val revoked = AtomicBoolean()
            private val request = AtomicReference<AsrRequest?>()
            private var retired = false // admission lock
            val allowed: Boolean get() = !revoked.get()
            fun markRevoked() { revoked.set(true) }
            fun cancelRevoked() { if (revoked.get()) request.get()?.cancel() }
            fun attach(value: AsrRequest) { request.set(value); cancelRevoked() }
            fun retire() {
                synchronized(admission) {
                    if (!retired) {
                        retired = true
                        when (kind) {
                            WorkKind.FOREGROUND -> { check(foreground > 0); foreground-- }
                            WorkKind.RECOVERY -> recoveries.remove(this)
                        }
                    }
                }
            }
        }

        fun enroll(kind: WorkKind): Enrollment {
            val revoked: List<Enrollment>
            val entry: Enrollment
            synchronized(admission) {
                entry = Enrollment(kind)
                when (kind) {
                    WorkKind.FOREGROUND -> {
                        foreground++
                        revoked = recoveries.toList()
                        revoked.forEach { it.markRevoked() }
                    }
                    WorkKind.RECOVERY -> {
                        revoked = emptyList()
                        if (foreground > 0 || watchdog.wedged) entry.markRevoked() else recoveries += entry
                    }
                }
            }
            // No callback, request lock, native control or cleanup under process admission.
            revoked.forEach { it.cancelRevoked() }
            return entry
        }
    }
    private val resources = processResources
    private val watchdog = resources.watchdog
    private val owner = RecognizerOwner<LoadedRecognizer>(
        resources.lane, watchdog, resources.control,
        free = { loaded ->
            loaded.engine.close()
            DebugLogger.log(TAG, "Speech engine closed")
        },
        discarded = { DebugLogger.log(TAG, "ASR answer discarded: the service closed during the decode") },
        releaseBoundMs = AsrBounds.RELEASE_BOUND_MS,
    )

    private val entriesLock = Any()
    private val entries = mutableSetOf<Entry>()

    /** Preparing requests retain their cleanup obligation until linkToDeath really returns. */
    private inner class Entry(val takeId: String, val callback: IAsrCallback?, kind: WorkKind = WorkKind.FOREGROUND) {
        val enrollment = resources.enroll(kind)
        var recoveryInput: RecoveryAudioStore.ReplayInputLease? = null
        val callbackBinder: IBinder? = callback?.asBinder()
        private val identity = resources.requests.incrementAndGet()
        private val callbackIdentity = System.identityHashCode(callbackBinder)
        private var linked = false
        private val death = IBinder.DeathRecipient {
            request.cancel()
            AsrDiagnostics.callbackDied(owner.generationIdentity, identity, takeId, callbackIdentity)
        }
        val request: AsrRequest = owner.request(
            cleanup = {
                try {
                    closeAsrNative(listOf(
                        AutoCloseable {
                            val input = recoveryInput
                            recoveryInput = null
                            if (input != null && input.closeOnWorker() != RecoveryClaimRelease.RELEASED) {
                                throw RecoveryStoreException(RecoveryStoreFailure.OWNERSHIP_UNCERTAIN)
                            }
                        },
                        AutoCloseable { if (linked) callbackBinder?.unlinkToDeath(death, 0); Unit },
                    ))
                }
                finally { synchronized(entriesLock) { entries.remove(this) } }
            },
            retired = {
                try { AsrDiagnostics.retired(owner.generationIdentity, identity, takeId, callbackIdentity, it, request.cancellation.isCancelled) }
                finally { enrollment.retire() }
            },
            controlFailed = { DebugLogger.warn(TAG, "Speech cancellation control failed: ${it.javaClass.simpleName}") },
        ).also(enrollment::attach)
        fun register() {
            synchronized(entriesLock) { entries += this }
            AsrDiagnostics.registered(owner.generationIdentity, identity, takeId, callbackIdentity)
            try {
                if (callbackBinder != null) {
                    try { callbackBinder.linkToDeath(death, 0); linked = true }
                    catch (_: RemoteException) { request.cancel() }
                }
            } catch (failure: Throwable) {
                watchdog.poison("Speech callback registration")
                request.cancel()
                throw failure
            } finally { request.registrationComplete() }
        }
        fun active() = AsrDiagnostics.active(owner.generationIdentity, identity, takeId, callbackIdentity)
        fun observer(): AsrObserver = AsrDiagnostics.observer(owner.generationIdentity, identity, takeId, callbackIdentity)
    }

    /**
     * How a request wants to hear about a failure. The legacy transactions answer `onError` with the
     * sentences they always sent, so the separately installed instrumentation client keeps working; the
     * versioned request answers `onFailure` with a closed code (issue #176). One decode path, two adapters.
     */
    private fun interface FailureReporter {
        fun report(reason: AsrFailureReason, detail: String)
    }

    private fun legacyReporter(callback: IAsrCallback?) = FailureReporter { reason, detail ->
        // The exact strings the legacy callback has always carried, chosen by reason, not by exception.
        val sentence = when (reason) {
            AsrFailureReason.AUDIO_MISSING -> detail
            AsrFailureReason.OVER_LIMIT -> OVER_LIMIT_MESSAGE
            AsrFailureReason.AUDIO_UNREADABLE -> detail.ifBlank { "Unable to read audio" }
            AsrFailureReason.MODEL_NOT_LOADED -> "ASR model not loaded"
            AsrFailureReason.DECODE_FAILED -> detail.ifBlank { "Unknown transcription error" }
            AsrFailureReason.UNKNOWN -> "Unknown transcription error"
        }
        runCatching { callback?.onError(sentence) }
            .onFailure { DebugLogger.warn(TAG, "Legacy failure callback threw: ${it.javaClass.simpleName}") }
    }

    private fun typedReporter(callback: IAsrCallback?) = FailureReporter { reason, detail ->
        // `detail` stays on the phone: the client logs it and never forwards it (IAsrCallback.aidl).
        runCatching { callback?.onFailure(reason.code, detail) }
            .onFailure { DebugLogger.warn(TAG, "Typed failure callback threw: ${it.javaClass.simpleName}") }
    }

    private val binder = object : IAsrService.Stub() {

        /**
         * Transcribe audio from a file path.
         * Preferred method — avoids AIDL 1MB transaction limit.
         */
        override fun transcribeFile(audioFilePath: String, callback: IAsrCallback?) {
            transcribeFromFile(audioFilePath, takeId = "", callback, legacyReporter(callback))
        }

        /** The versioned request: same decode, typed failures, the take's id as request context. */
        override fun transcribeFileForTake(audioFilePath: String, takeId: String?, callback: IAsrCallback?) {
            transcribeFromFile(audioFilePath, takeId.orEmpty(), callback, typedReporter(callback))
        }

        /**
         * Legacy method — kept for backward compatibility.
         * Will hit AIDL transaction limit for recordings >~30s.
         */
        override fun transcribe(audioData: ByteArray, callback: IAsrCallback?) {
            DebugLogger.warn(TAG, "Legacy transcribe(ByteArray) called — prefer transcribeFile()")
            val durationSec = PcmAudio.durationSeconds(audioData.size.toLong())
            val failure = legacyReporter(callback)
            val entry = Entry("", callback).also { it.register() }
            owner.use(transcriptionBoundMs(audioData.size.toLong()), entry.request) { rec ->
                entry.active()
                doTranscribe(rec, audioData, durationSec, callback, failure, TakeLog(LEGACY_TAKE, TAG), entry.request, entry.observer())
            }
        }

        override fun isReady(): Boolean = owner.isReady && !watchdog.wedged

        override fun cancelTranscription(takeId: String?, callback: IAsrCallback?) {
            val binder = callback?.asBinder() ?: return
            val matching = synchronized(entriesLock) {
                entries.filter { it.takeId == takeId.orEmpty() && it.callbackBinder === binder }
            }
            matching.forEach { it.request.cancel() }
        }

        override fun transcribeRecovery(takeId: String?, storeId: String?, slot: Int, generation: Long, attempt: String?, contextDigest: ByteArray?, callback: IAsrCallback?) {
            val failure = typedReporter(callback)
            val replay = runCatching {
                fun uuid(value: String?): UUID {
                    require(value?.length == 36)
                    return UUID.fromString(value).also { require(it.toString() == value) }
                }
                require(slot in 0..1 && generation > 0L)
                RecoveryReplayRef(RecoveryRecordRef(uuid(storeId), slot,
                    RecoveryRecordIdentity(uuid(takeId), generation, RecoveryContextDigest.of(requireNotNull(contextDigest)))), uuid(attempt))
            }.getOrNull()
            if (replay == null) {
                failure.report(AsrFailureReason.AUDIO_UNREADABLE, "protected_recovery_unavailable")
                return
            }
            this@AsrService.transcribeRecovery(replay, callback, failure)
        }
    }

    private fun transcribeRecovery(replay: RecoveryReplayRef, callback: IAsrCallback?, failure: FailureReporter) {
        val takeId = replay.record.identity.takeId.toString()
        val entry = Entry(takeId, callback, WorkKind.RECOVERY).also { it.register() }
        owner.use(transcriptionBoundMs(RecordingLimits.MAX_AUDIO_BYTES), entry.request) work@{ rec ->
            entry.active()
            entry.request.check()
            if (!entry.enrollment.allowed) throw AsrCancelled()
            try {
                val input = when (val answer = RecoveryAudioStore(applicationContext).consumeReplayOnWorker(replay)) {
                    is RecoveryStoreResult.Ready -> answer.value
                    RecoveryStoreResult.Busy, is RecoveryStoreResult.Unavailable ->
                        return@work { failure.report(AsrFailureReason.AUDIO_UNREADABLE, "protected_recovery_unavailable") }
                }
                entry.recoveryInput = input
                input.readPrefixOnWorker { entry.request.check() }.use { prefix ->
                    if (prefix.byteCount == 0) return@work { failure.report(AsrFailureReason.AUDIO_UNREADABLE, "protected_recovery_unavailable") }
                    if (!entry.request.tighten(transcriptionBoundMs(prefix.byteCount.toLong()))) return@work { }
                    doTranscribe(rec, prefix.pcm, PcmAudio.durationSeconds(prefix.byteCount.toLong()), callback, failure,
                        TakeLog(takeId, TAG), entry.request, entry.observer(), prefix.byteCount,
                        ResultRoute.Recovery(replay, prefix.firstSample, prefix.sampleCount.toLong(), prefix.completedCapture))
                }
            } catch (cancelled: AsrCancelled) {
                throw cancelled
            } catch (cleanup: AsrCleanupFailure) {
                throw cleanup
            } catch (_: Exception) {
                { failure.report(AsrFailureReason.AUDIO_UNREADABLE, "protected_recovery_unavailable") }
            }
        }
    }

    private fun transcribeFromFile(audioFilePath: String, takeId: String, callback: IAsrCallback?, failure: FailureReporter) {
        // This process's start of the take (#378): the take id travels to the local log as a field.
        val log = TakeLog(takeId, TAG).also { it.startPipeline() }
        val entry = Entry(takeId, callback).also { it.register() }
        owner.use(transcriptionBoundMs(RecordingLimits.MAX_AUDIO_BYTES), entry.request) { rec ->
            entry.active()
            entry.request.check()
            val file = File(audioFilePath)
            if (!file.exists()) {
                log.error("Audio file not found (take=$takeId)")
                return@use { failure.report(AsrFailureReason.AUDIO_MISSING, "Audio file not found: $audioFilePath") }
            }
            // Not an independent limit. `RecordingLimits` owns the number and the capture process
            // stops a take before this can be reached, so arriving here means something upstream is
            // wrong rather than that the user talked for too long.
            // Metadata belongs to the same guarded worker; tighten from its original start, never restart the budget.
            val audioBytes = file.length()
            if (audioBytes > RecordingLimits.MAX_AUDIO_BYTES) {
                log.warn(
                    "Audio file is $audioBytes bytes, over the " +
                        "${RecordingLimits.MAX_AUDIO_BYTES} byte ceiling",
                )
                return@use { failure.report(AsrFailureReason.OVER_LIMIT, OVER_LIMIT_MESSAGE) }
            }
            if (!entry.request.tighten(transcriptionBoundMs(audioBytes))) return@use { }
            entry.request.check()
            // The read is its own boundary (G1 D4): a failure here is the FILE, never the decoder.
            val audioData = try {
                file.readBytes()
            } catch (e: Exception) {
                log.error("Failed to read audio file", e)
                val detail = e.javaClass.simpleName
                return@use { failure.report(AsrFailureReason.AUDIO_UNREADABLE, detail) }
            }
            val durationSec = PcmAudio.durationSeconds(audioData.size.toLong())
            log.log("Read ${audioData.size} bytes (${String.format("%.1f", durationSec)}s) for take $takeId")
            log.mark("asr_file_read")
            entry.request.check()
            doTranscribe(rec, audioData, durationSec, callback, failure, log, entry.request, entry.observer())
        }
    }

    /** One task's bound: maximum before metadata, then exact remaining time from the SAME task start. */
    private fun transcriptionBoundMs(audioBytes: Long): Long =
        AsrBounds.requestBoundMs((PcmAudio.durationSeconds(audioBytes) * 1000f).toLong()) + AsrBounds.DECODE_GRACE_MS

    /**
     * Decodes on the worker and RETURNS the answer's delivery rather than making it, so the owner can
     * discard an answer that finished after the service closed (#212). Every path returns exactly one.
     */
    private fun doTranscribe(
        rec: LoadedRecognizer?,
        audioData: ByteArray,
        durationSec: Float,
        callback: IAsrCallback?,
        failure: FailureReporter,
        log: TakeLog,
        request: AsrRequest,
        observer: AsrObserver,
        validBytes: Int = audioData.size,
        route: ResultRoute = ResultRoute.Ordinary,
    ): () -> Unit {
        log.log("Transcribing $validBytes bytes (${String.format("%.1f", durationSec)}s) (PID: ${android.os.Process.myPid()})")

        if (rec == null) {
            log.error("Recognizer not initialized")
            return { failure.report(AsrFailureReason.MODEL_NOT_LOADED, "") }
        }

        // The decode is its own boundary (G1 D4): only the recogniser's own work is inside this try, so
        // a throwing DELIVERY below can never be reported as a decode failure or produce a second callback.
        val rawText = try {
            request.check()
            val samples = PcmAudio.toFloatSamples(audioData, validBytes) { request.check() }
            request.check()
            log.mark("pcm_to_float")

            val t0 = SystemClock.elapsedRealtime()

            val result = rec.engine.transcribe(samples, request.cancellation, observer)

            val decodeMs = SystemClock.elapsedRealtime() - t0
            val text = result.text.trim()
            val rtf = if (durationSec > 0) decodeMs / (durationSec * 1000) else 0f

            log.mark("asr_decode")
            // Transcript text is user content and never enters logcat: logcat keeps only the aggregate. The
            // ordinary words go to the local log file alone, and only while Detailed log is on (#378).
            // Recovery never invokes the word-bearing logger or evaluates its lazy content supplier.
            log.log("Decode: ${decodeMs}ms, RTF=${String.format("%.2f", rtf)}, textChars=${text.length}, windows=${result.windows}, repairs=${result.repairs}")
            if (route == ResultRoute.Ordinary) log.words("raw_speech") { text }
            text
        } catch (cancelled: AsrCancelled) {
            throw cancelled
        } catch (cleanup: AsrCleanupFailure) {
            throw cleanup
        } catch (e: Exception) {
            if (route == ResultRoute.Ordinary) log.error("Transcription failed", e)
            else log.warn("Protected speech decode failed")
            val detail = if (route == ResultRoute.Ordinary) e.javaClass.simpleName else "protected_recovery_unavailable"
            return { failure.report(AsrFailureReason.DECODE_FAILED, detail) }
        }

        val resultText = try {
            when (route) {
                ResultRoute.Ordinary -> rawText
                is ResultRoute.Recovery -> RecoveryResultCodec.encode(RecoveryResult(
                    route.replay, route.first, route.count, route.completed, rec.model, rawText,
                ))
            }
        } catch (_: Exception) {
            return { failure.report(AsrFailureReason.DECODE_FAILED, "protected_recovery_unavailable") }
        }
        log.log(DebugLogger.pipelineSummary())
        return {
            deliverResult(callback, resultText, onDelivered = {
                request.check()
                if (route == ResultRoute.Ordinary) sweepLegacyModelOnce()
                request.check()
            }) {
                log.warn("Result delivery threw: ${it.javaClass.simpleName}; not reported as a decode failure")
            }
        }
    }

    /** Set once this process has swept the model the engine swap replaced (#374). */
    private var legacySwept = false

    /**
     * After the first successful decode on the new engine, remove the sherpa-onnx model it replaced (#374), so a
     * phone is never left with neither. Runs on the worker, after the result was handed over; never throws.
     */
    private fun sweepLegacyModelOnce() {
        if (legacySwept) return
        legacySwept = true
        val removed = LegacyModelSweep.sweep(ModelStorage.root(this))
        if (removed > 0) DebugLogger.log(TAG, "Removed the replaced speech model: $removed bytes")
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        DebugLogger.log(TAG, "AsrService created (PID: ${android.os.Process.myPid()})")
        // Receipt verification and native model loading are deliberately off the service main thread.
        owner.load(AsrBounds.LOAD_BOUND_MS) { initRecognizer() }
    }

    override fun onDestroy() {
        // Borrowed process resources survive this instance; actual retirement gates the next generation's LOAD.
        owner.close { AsrDiagnostics.ownerRetired(owner.generationIdentity) }
        super.onDestroy()
        DebugLogger.log(TAG, "AsrService destroyed; recognizer release queued")
    }

    /** Returns the loaded engine, or null when the model is not verified or fails to load. */
    private fun initRecognizer(): LoadedRecognizer? {
        return try {
            val t0 = SystemClock.elapsedRealtime()
            // The current revision or a previous one this engine loads (#108): an update in progress keeps dictating.
            val descriptor = ModelStorage.usable(this, ModelManifest.parakeet)
            if (descriptor == null) {
                DebugLogger.warn(TAG, "Parakeet model is not verified in app-private storage")
                return null
            }
            val preprocessor = assets.open(ParakeetEngine.PREPROCESSOR_ASSET).use { it.readBytes() }
            AsrDiagnostics.modelOpening(owner.generationIdentity)
            val engine = ParakeetEngine.open(ModelStorage.directory(this, descriptor), preprocessor)
            DebugLogger.log(TAG, "Speech engine initialized in ${SystemClock.elapsedRealtime() - t0}ms (${descriptor.id})")
            LoadedRecognizer(engine, RecoveryModelIdentity.loaded(descriptor))
        } catch (cleanup: AsrCleanupFailure) {
            throw cleanup
        } catch (e: Throwable) {
            // The class name only: an ONNX Runtime message can quote paths or model internals.
            DebugLogger.warn(TAG, "Failed to initialize the speech engine: ${e.javaClass.simpleName}")
            null
        }
    }

}
