package com.envi.wispr.ui

import com.envi.wispr.polish.PolishOutcome
import com.envi.wispr.polish.PolishPolicy

/**
 * The three helper processes as the session owner sees them (#186): exactly the AIDL members the owner
 * calls, as Kotlin, so the coordinator never names a binder proxy and a JVM test can implement each link.
 *
 * A binder exception propagates through a link unchanged, so every `runCatching` in the owner keeps its
 * meaning. The production implementations in `PipelineBindings` wrap the proxies and build the callback
 * Stubs, which extend `android.os.Binder` and cannot be constructed off the phone.
 */

/** One picture of the microphone, as `IAudioSpectrumListener.onSpectrum` delivers it (#187). */
internal fun interface SpectrumListener {
    fun onSpectrum(bands: FloatArray)
}

/**
 * The take's events as `ITakeListener` delivers them (#115). Every method arrives on a binder thread; the
 * owner posts each to its main thread before acting, which serialises them in delivery order.
 */
internal interface TakeListener {
    fun onLive(takeId: String, forced: Boolean, routeKind: Int, routeReason: Int, liveAfterMs: Long)
    fun onTick(takeId: String, elapsedMs: Long)
    fun onSilenceStatus(takeId: String, status: Int)
    fun onEnded(ending: TakeEnding)
}

/**
 * The ending as `ITakeListener.onEnded` carries it: the take it belongs to, the closed file, and every fact
 * the owner once asked for. The owner discards an ending whose [takeId] is not its take's: the publisher is
 * service-scoped, so a previous take's ending can reach the next take's listener.
 */
internal data class TakeEnding(
    val takeId: String,
    val terminalReason: Int,
    val startFailure: Int,
    /** The CLOSED file, or null for an ending with no file (a start refused before capture began). */
    val audioFilePath: String?,
    val silenceStatus: Int,
    val takePeakAmplitude: Float,
    val effectiveInputDevice: String,
)

/**
 * `IAudioCaptureService`, the members the owner uses (#115): three COMMANDS and two listener members. The
 * owner reads nothing from the audio process; every fact it needs is pushed through [TakeListener] and
 * [SpectrumListener]. A synchronous call into a process that stops answering has no timeout, so the fewer
 * there are the smaller the surface the owner's silence bound has to cover.
 */
internal interface CaptureLink {
    fun startCaptureForTake(autoStopOnSilence: Boolean, pauseSeconds: Float, inputDevicePick: String, keepEarbudsReady: Boolean, takeId: String): Boolean
    fun stopCapture()
    fun finishTake(): Boolean
    /**
     * Receive the recorder's picture as the audio process publishes it (#187): the production link
     * builds the binder Stub and registers it; a later call replaces the earlier registration.
     */
    fun listenForSpectrum(listener: SpectrumListener)
    /**
     * Receive the take's events (#115). Registered BEFORE `startCaptureForTake` so no event precedes it.
     * Both listener slots are the binding's and die with it: the owner never calls the audio process to
     * drop one, because on the path that matters most that process is the one that stopped answering.
     */
    fun listenForTake(listener: TakeListener)
}

/** The answer to one `IAsrService.transcribeFileForTake`; the engine answers once. */
internal interface SpeechListener {
    fun onResult(text: String?)
    fun onError(message: String?)
    fun onFailure(reason: Int, detail: String?)
}

/** `IAsrService`, the member the owner uses. */
internal interface SpeechLink {
    fun transcribeFileForTake(audioFilePath: String, takeId: String, listener: SpeechListener)
}

/** The answer to one `IPolishService.polishRequestForTake`; the engine answers once. */
internal interface PolishListener {
    fun onOutcome(outcome: PolishOutcome?)
    fun onResult(text: String?, engine: String?, latencyMs: Long)
    fun onError(message: String?)
}

/** `IPolishService`, the members the owner uses. */
internal interface PolishLink {
    fun warmUpWithPolicy(policy: PolishPolicy)
    fun polishRequestForTake(
        requestId: Long,
        rawText: String,
        removeFillers: Boolean,
        spokenEmoji: Boolean,
        spokenPunctuation: Boolean,
        policy: PolishPolicy,
        takeId: String,
        listener: PolishListener,
    )
    fun cancel(requestId: Long)
}

/**
 * The owner's view of the three connections. Production is `PipelineBindings`; a test provides a fake
 * whose links are set before the connected callbacks fire, as the platform does.
 */
internal interface PipelineController {
    interface Listener {
        fun onCaptureConnected()
        fun onCaptureDisconnected()
        fun onSpeechConnected()
        fun onSpeechDisconnected()
        fun onPolishConnected()
        fun onPolishDisconnected()
    }

    val capture: CaptureLink?
    val speech: SpeechLink?
    val polish: PolishLink?

    /**
     * Binds the three services in order; the owner's bind-failure handling lives at the call site. Audio and
     * speech decide whether the take can run ([BindOutcome.result]); polish is a limb, so its refusal is a
     * separate fact the owner records and the take goes on to the deterministic text (#234).
     */
    fun bind(listener: Listener): BindOutcome

    /** Unbinds whatever is bound and clears the links. Idempotent. */
    fun unbind()

    /** `stopService` on the capture service, for the paths that stop it rather than let it hold the earbuds. */
    fun stopAudioService()

    enum class BindResult { BOUND, AUDIO_BIND_FAILED, ASR_BIND_FAILED }

    /** What [bind] reports: whether the heart's two services bound, and separately whether polish did. */
    data class BindOutcome(val result: BindResult, val polishBound: Boolean)
}
