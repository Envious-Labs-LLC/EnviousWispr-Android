package com.envi.wispr.ui

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Handler
import android.os.IBinder
import com.envi.wispr.asr.AsrService
import com.envi.wispr.asr.IAsrCallback
import com.envi.wispr.asr.IAsrService
import com.envi.wispr.audio.AudioCaptureService
import com.envi.wispr.audio.IAudioCaptureService
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
            capture = CaptureProxy(IAudioCaptureService.Stub.asInterface(binder))
            listener?.onCaptureConnected()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            capture = null
            listener?.onCaptureDisconnected()
        }
    }

    private val asrConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            speech = SpeechProxy(IAsrService.Stub.asInterface(binder))
            listener?.onSpeechConnected()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            speech = null
            listener?.onSpeechDisconnected()
        }
    }

    private val polishConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            polish = PolishProxy(IPolishService.Stub.asInterface(binder))
            listener?.onPolishConnected()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            polish = null
            listener?.onPolishDisconnected()
        }
    }

    override fun bind(listener: PipelineController.Listener): PipelineController.BindResult {
        this.listener = listener
        val audioIntent = Intent(appContext, AudioCaptureService::class.java)
        audioBound = runCatching {
            appContext.bindService(audioIntent, audioConnection, Context.BIND_AUTO_CREATE)
        }.getOrDefault(false)
        if (!audioBound) return PipelineController.BindResult.AUDIO_BIND_FAILED

        asrBound = runCatching {
            appContext.bindService(Intent(appContext, AsrService::class.java), asrConnection, Context.BIND_AUTO_CREATE)
        }.getOrDefault(false)
        if (!asrBound) return PipelineController.BindResult.ASR_BIND_FAILED

        polishBound = runCatching {
            appContext.bindService(Intent(appContext, PolishService::class.java), polishConnection, Context.BIND_AUTO_CREATE)
        }.getOrDefault(false)
        if (!polishBound) return PipelineController.BindResult.POLISH_BIND_FAILED
        return PipelineController.BindResult.BOUND
    }

    override fun unbind() {
        if (audioBound) runCatching { appContext.unbindService(audioConnection) }
        if (asrBound) runCatching { appContext.unbindService(asrConnection) }
        if (polishBound) runCatching { appContext.unbindService(polishConnection) }
        audioBound = false
        asrBound = false
        polishBound = false
        capture = null
        speech = null
        polish = null
    }

    override fun postUnbindToMain(beforeUnbind: () -> Unit) {
        mainHandler.post {
            beforeUnbind()
            unbind()
        }
    }

    override fun stopAudioService() {
        runCatching { appContext.stopService(Intent(appContext, AudioCaptureService::class.java)) }
            .onFailure { error -> log.warn("Unable to stop audio capture service: ${error.message}") }
    }

    /** Pass-through; a binder exception escapes to the caller's `runCatching`, exactly as the proxy's did. */
    private class CaptureProxy(private val service: IAudioCaptureService) : CaptureLink {

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

        override fun listenForTake(listener: TakeListener) {
            val stub = object : ITakeListener.Stub() {
                override fun onLive(forced: Boolean, routeKind: Int, routeReason: Int, liveAfterMs: Long) = listener.onLive(forced, routeKind, routeReason, liveAfterMs)
                override fun onTick(elapsedMs: Long) = listener.onTick(elapsedMs)
                override fun onSilenceStatus(status: Int) = listener.onSilenceStatus(status)
                override fun onEnded(terminalReason: Int, startFailure: Int, audioFilePath: String?, silenceStatus: Int, takePeakAmplitude: Float, effectiveInputDevice: String?) =
                    listener.onEnded(TakeEnding(terminalReason, startFailure, audioFilePath?.takeIf { it.isNotEmpty() }, silenceStatus, takePeakAmplitude, effectiveInputDevice.orEmpty()))
            }
            service.registerTakeListener(stub)
        }
    }

    /**
     * One fresh anonymous Stub per request, built synchronously inside this call after every argument is
     * evaluated and immediately before the proxy call; never cached, the listener never stored. Every
     * callback invokes the listener directly on the binder thread it arrived on, with no post.
     */
    private class SpeechProxy(private val service: IAsrService) : SpeechLink {
        override fun transcribeFileForTake(audioFilePath: String, takeId: String, listener: SpeechListener) {
            service.transcribeFileForTake(audioFilePath, takeId, object : IAsrCallback.Stub() {
                override fun onResult(text: String?) = listener.onResult(text)
                override fun onError(message: String?) = listener.onError(message)
                override fun onFailure(reason: Int, detail: String?) = listener.onFailure(reason, detail)
            })
        }
    }

    /** Same Stub contract as [SpeechProxy]. */
    private class PolishProxy(private val service: IPolishService) : PolishLink {
        override fun warmUpWithPolicy(policy: PolishPolicy) = service.warmUpWithPolicy(policy)

        override fun polishRequestForTake(
            requestId: Long,
            rawText: String,
            removeFillers: Boolean,
            spokenEmoji: Boolean,
            spokenPunctuation: Boolean,
            policy: PolishPolicy,
            takeId: String,
            listener: PolishListener,
        ) {
            service.polishRequestForTake(
                requestId,
                rawText,
                removeFillers,
                spokenEmoji,
                spokenPunctuation,
                policy,
                takeId,
                object : IPolishCallback.Stub() {
                    override fun onOutcome(outcome: PolishOutcome?) = listener.onOutcome(outcome)
                    override fun onResult(text: String?, engine: String?, latencyMs: Long) = listener.onResult(text, engine, latencyMs)
                    override fun onError(message: String?) = listener.onError(message)
                },
            )
        }

        override fun cancel(requestId: Long) = service.cancel(requestId)
    }
}
