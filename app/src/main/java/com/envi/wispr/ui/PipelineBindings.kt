package com.envi.wispr.ui

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Handler
import android.os.IBinder
import android.os.DeadObjectException
import com.envi.wispr.debug.DebugLogger
import com.envi.wispr.models.EngineBindings
import com.envi.wispr.asr.AsrService
import com.envi.wispr.asr.IAsrCallback
import com.envi.wispr.asr.IAsrService
import com.envi.wispr.audio.AudioCaptureService
import com.envi.wispr.audio.IAudioTakeService
import com.envi.wispr.audio.IAudioSpectrumListener
import com.envi.wispr.audio.ITakeListener
import com.envi.wispr.polish.IPolishCallback
import com.envi.wispr.polish.IPolishService
import com.envi.wispr.polish.PolishOutcome
import com.envi.wispr.polish.PolishPolicy
import com.envi.wispr.polish.PolishService

/**
 * Owns the three binder connections the session needs, `:audio`, `:asr` and `:polish` (#186).
 *
 * Bound through [appContext], the application context, never the Service: the destroyed-session
 * cleanup thread unbinds AFTER `Service.onDestroy` has returned (`DictationSessionCoordinator.destroy`),
 * which Android defines as a dead Service. Binding through the application makes that late unbind legal
 * instead of accidental. This is the one declared deviation from "no behaviour change" in the #186 plan.
 *
 * The connection callbacks arrive on the main thread; each assigns its link BEFORE calling the listener,
 * so a listener that reads the link on the same thread sees it. The listener owns the log line for each
 * event, so the six lines read in the same order they did when the Service owned the connections. The
 * links are `@Volatile` because the owner's worker threads read them after main wrote them.
 */
internal class PipelineBindings(
    private val appContext: Context,
    private val mainHandler: Handler,
    private val log: SessionLog,
) : PipelineController {
    @Volatile override var capture: CaptureLink? = null
        private set

    @Volatile override var speech: SpeechLink? = null
        private set

    @Volatile override var polish: PolishLink? = null
        private set

    private var listener: PipelineController.Listener? = null
    private var audioBound = false
    private var asrBound = false
    private var polishBound = false

    private val audioConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            capture = CaptureProxy(IAudioTakeService.Stub.asInterface(binder))
            listener?.onCaptureConnected()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            capture = null
            listener?.onCaptureDisconnected()
        }
    }

    private val asrConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            clearSpeech()
            speech = speechProxy(IAsrService.Stub.asInterface(binder), mainHandler::post)
            listener?.onSpeechConnected()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            clearSpeech()
            listener?.onSpeechDisconnected()
        }

        override fun onBindingDied(name: ComponentName?) { clearSpeech() }
        override fun onNullBinding(name: ComponentName?) { clearSpeech() }
    }

    private val polishConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            polish = polishProxy(IPolishService.Stub.asInterface(binder), mainHandler::post)
            listener?.onPolishConnected()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            polish = null
            listener?.onPolishDisconnected()
        }
    }

    override fun bind(listener: PipelineController.Listener): PipelineController.BindOutcome {
        this.listener = listener
        // The take-sized interface with a fresh identity per bind (#220), so this binding is its own epoch.
        val audioIntent = AudioCaptureService.takeBindIntent(appContext)
        audioBound = runCatching {
            appContext.bindService(audioIntent, audioConnection, Context.BIND_AUTO_CREATE)
        }.getOrDefault(false)
        if (!audioBound) return PipelineController.BindOutcome(PipelineController.BindResult.AUDIO_BIND_FAILED, polishBound = false)

        // Through the engine registry (#108): a take holds a swap permit, so these are never refused for a swap.
        asrBound = EngineBindings.bind(appContext, EngineBindings.Engine.SPEECH, asrConnection, Context.BIND_AUTO_CREATE) { asrBound = false; clearSpeech() }
        if (!asrBound) return PipelineController.BindOutcome(PipelineController.BindResult.ASR_BIND_FAILED, polishBound = false)

        polishBound = EngineBindings.bind(appContext, EngineBindings.Engine.POLISH, polishConnection, Context.BIND_AUTO_CREATE) { polishBound = false }
        // A refused polish bind does not stop the take (#234): the owner records it and publishes the
        // deterministic text; unbind skips what never bound.
        return PipelineController.BindOutcome(PipelineController.BindResult.BOUND, polishBound)
    }

    override fun unbind() {
        clearSpeech()
        if (audioBound) runCatching { appContext.unbindService(audioConnection) }
        if (asrBound) EngineBindings.unbind(asrConnection)
        if (polishBound) EngineBindings.unbind(polishConnection)
        audioBound = false
        asrBound = false
        polishBound = false
        capture = null
        polish = null
    }

    private fun clearSpeech() {
        val previous = speech
        speech = null
        previous?.close()
    }

    override fun stopAudioService() {
        runCatching { appContext.stopService(Intent(appContext, AudioCaptureService::class.java)) }
            .onFailure { error -> log.warn("Unable to stop audio capture service: ${error.javaClass.simpleName}") }
    }

    /** Pass-through; a binder exception escapes to the caller's `runCatching`, exactly as the proxy's did. */
    private class CaptureProxy(private val service: IAudioTakeService) : CaptureLink {

        override fun startCaptureForTake(autoStopOnSilence: Boolean, pauseSeconds: Float, inputDevicePick: String, keepEarbudsReady: Boolean, takeId: String): Boolean =
            service.startCaptureForTake(autoStopOnSilence, pauseSeconds, inputDevicePick, keepEarbudsReady, takeId)
        override fun stopCapture() = service.stopCapture()
        override fun listenForSpectrum(listener: SpectrumListener) {
            val stub = object : IAudioSpectrumListener.Stub() {
                override fun onSpectrum(bands: FloatArray?) {
                    listener.onSpectrum(bands ?: FloatArray(0))
                }
            }
            service.registerSpectrumListener(stub)
        }
        override fun finishTake(): Boolean = service.finishTake()
        override fun attachRecovery(ref: com.envi.wispr.recovery.RecoveryRecordRef) =
            service.attachRecoveryForTake(ref.identity.takeId.toString(), ref.storeId.toString(), ref.slot,
                ref.identity.generation, ref.identity.context.copyBytes())

        override fun listenForTake(listener: TakeListener) {
            val stub = object : ITakeListener.Stub() {
                override fun onCaptureClosed(takeId: String?, resourcesClosed: Boolean) = listener.onCaptureClosed(takeId.orEmpty(), resourcesClosed)
                override fun onLive(takeId: String?, forced: Boolean, routeKind: Int, routeReason: Int, liveAfterMs: Long) = listener.onLive(takeId.orEmpty(), forced, routeKind, routeReason, liveAfterMs)
                override fun onTick(takeId: String?, elapsedMs: Long) = listener.onTick(takeId.orEmpty(), elapsedMs)
                override fun onSilenceStatus(takeId: String?, status: Int) = listener.onSilenceStatus(takeId.orEmpty(), status)
                override fun onEnded(takeId: String?, terminalReason: Int, startFailure: Int, audioFilePath: String?, silenceStatus: Int, takePeakAmplitude: Float, effectiveInputDevice: String?) =
                    listener.onEnded(TakeEnding(takeId.orEmpty(), terminalReason, startFailure, audioFilePath?.takeIf { it.isNotEmpty() }, silenceStatus, takePeakAmplitude, effectiveInputDevice.orEmpty()))
            }
            service.registerTakeListener(stub)
        }
    }

    /**
     * Prepare one fresh callback Binder, then submit/cancel that EXACT identity. PreparedSpeechSubmission
     * owns delivery and actual-return obligations; terminal callbacks still post through PostingSpeechListener.
     */
    internal class SpeechProxy(private val service: IAsrService, private val post: (Runnable) -> Unit) : SpeechLink {
        private enum class State { OPEN, CLOSED }
        private val lock = Any()
        private var state = State.OPEN
        private val active = mutableSetOf<PreparedSpeechSubmission>()
        private val port = SpeechCancelPort({ DebugLogger.warn("SpeechProxy", "Speech cancellation control failed: ${it.javaClass.simpleName}") })

        override fun prepareFileForTake(audioFilePath: String, takeId: String, listener: SpeechListener): SpeechSubmission =
            prepare(audioFilePath, takeId, listener)

        private fun prepare(audioFilePath: String, takeId: String, listener: SpeechListener): PreparedSpeechSubmission = synchronized(lock) {
            if (state == State.CLOSED) throw DeadObjectException()
            val registration = port.lease()
            try {
                val tail = port.lease()
                try {
                    lateinit var submission: PreparedSpeechSubmission
                    val callback = object : IAsrCallback.Stub() {
                        override fun onResult(text: String?) = submission.onResult(text)
                        override fun onError(message: String?) = submission.onError(message)
                        override fun onFailure(reason: Int, detail: String?) = submission.onFailure(reason, detail)
                    }
                    submission = PreparedSpeechSubmission(PostingSpeechListener(post, listener),
                        issue = { service.transcribeFileForTake(audioFilePath, takeId, callback) },
                        cancelRemote = { service.cancelTranscription(takeId, callback) },
                        port, registration, tail,
                        settled = { synchronized(lock) { active.remove(it) }; Unit })
                    active += submission
                    submission
                } catch (failure: Throwable) { tail.close(); throw failure }
            } catch (failure: Throwable) { registration.close(); throw failure }
        }

        /** Observed v1 ABI, into the SAME core; no new cancellation worker for a normal legacy request. */
        override fun transcribeFileForTake(audioFilePath: String, takeId: String, listener: SpeechListener) {
            val submission = prepare(audioFilePath, takeId, listener)
            try { submission.submit() } finally { submission.releaseUnexposedHandle() }
        }

        override fun close() {
            val pending = synchronized(lock) {
                if (state == State.CLOSED) return
                state = State.CLOSED
                active.toList()
            }
            try { pending.forEach { submission -> runCatching { submission.cancel() }.onFailure(port::report) } }
            finally { port.close() }
        }
    }

    internal companion object {
        /** The production speech proxy; `androidTest` binds the real `:asr` through it (#253). */
        fun speechProxy(service: IAsrService, post: (Runnable) -> Unit): SpeechLink = SpeechProxy(service, post)
        /** The production cleanup transport, shared with the actual-boundary device test. */
        fun polishProxy(service: IPolishService, post: (Runnable) -> Unit): PolishLink = PolishProxy(service, post)
    }

    /** Same Stub contract as [SpeechProxy], through [PostingPolishListener]. */
    private class PolishProxy(private val service: IPolishService, private val post: (Runnable) -> Unit) : PolishLink {
        override fun warmUpWithPolicy(policy: PolishPolicy) = service.warmUpWithPolicy(policy)

        override fun polishRequestWithCleanupForTake(
            requestId: Long,
            rawText: String,
            cleanup: com.envi.wispr.cleanup.CleanupOptions,
            policy: PolishPolicy,
            takeId: String,
            listener: PolishListener,
        ) {
            val posting = PostingPolishListener(post, listener)
            service.polishRequestWithCleanupForTake(
                requestId,
                rawText,
                com.envi.wispr.polish.CleanupRequestOptions(cleanup),
                policy,
                takeId,
                object : IPolishCallback.Stub() {
                    override fun onOutcome(outcome: PolishOutcome?) = posting.onOutcome(outcome)
                },
            )
        }

        override fun cancel(requestId: Long) = service.cancel(requestId)
    }
}

/**
 * A speech callback's values, posted to main before the owner's listener sees them (#253): the binder thread
 * only enqueues and returns, the way the capture listener's events already reach the owner (#115). Always a
 * post, never an inline call, even when already on main.
 */
internal class PostingSpeechListener(private val post: (Runnable) -> Unit, private val listener: SpeechListener) : SpeechListener {
    override fun onResult(text: String?) = post(Runnable { listener.onResult(text) })
    override fun onError(message: String?) = post(Runnable { listener.onError(message) })
    override fun onFailure(reason: Int, detail: String?) = post(Runnable { listener.onFailure(reason, detail) })
}

/** A polish callback's values, posted to main before the owner's listener sees them (#253). */
internal class PostingPolishListener(private val post: (Runnable) -> Unit, private val listener: PolishListener) : PolishListener {
    override fun onOutcome(outcome: PolishOutcome?) = post(Runnable { listener.onOutcome(outcome) })
}
