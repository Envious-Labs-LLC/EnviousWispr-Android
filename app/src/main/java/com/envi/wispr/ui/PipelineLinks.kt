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

/** `IAudioCaptureService`, the members the owner uses. */
internal interface CaptureLink {
    fun startCaptureForTake(autoStopOnSilence: Boolean, pauseSeconds: Float, inputDevicePick: String, keepEarbudsReady: Boolean, takeId: String): Boolean
    fun lastStartFailure(): Int
    fun stopCapture()
    fun waitForFileReady(timeoutMs: Long): Boolean
    fun liveState(): Int
    fun isCapturing(): Boolean
    fun audioFilePath(): String?
    fun elapsedMs(): Long
    fun silenceStopStatus(): Int
    fun inputRouteKind(): Int
    fun inputRouteReason(): Int
    fun liveAfterMs(): Long
    fun terminalReason(): Int
    /**
     * Receive the recorder's picture as the audio process publishes it (#187): the production link
     * builds the binder Stub and registers it; a later call replaces the earlier registration.
     */
    fun listenForSpectrum(listener: SpectrumListener)
    /** Unregister the Stub `listenForSpectrum` registered, if any. Idempotent. */
    fun stopListeningForSpectrum()
    fun effectiveInputDevice(): String?
    fun takePeakAmplitude(): Float
    fun finishTake(): Boolean
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
     * Binds the three services in order; the owner's bind-failure handling lives at the call site. Returns
     * which of the three bound so the owner can end the take with the right reason.
     */
    fun bind(listener: Listener): BindResult

    /** Unbinds whatever is bound and clears the links. Idempotent. */
    fun unbind()

    /**
     * [beforeUnbind] then [unbind], posted to the main looper, for a cleanup thread that outlives the
     * Service; the owner passes its idempotent polish-cancel backstop, as the old posted unbind ran it.
     */
    fun postUnbindToMain(beforeUnbind: () -> Unit)

    /** `stopService` on the capture service, for the paths that stop it rather than let it hold the earbuds. */
    fun stopAudioService()

    enum class BindResult { BOUND, AUDIO_BIND_FAILED, ASR_BIND_FAILED, POLISH_BIND_FAILED }
}
