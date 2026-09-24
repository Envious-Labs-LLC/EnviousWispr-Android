package com.envi.wispr.audio

import android.os.IBinder
import com.envi.wispr.debug.DebugLogger

/**
 * What the two capture interfaces may do to the service (#361): its operations and its reads, never its fields.
 * `AudioCaptureService` implements it; [CaptureBinderAdapters] are its only callers.
 */
internal interface CaptureOperations {
    /** A legacy start: no take id, so it may never recover an abandoned recorder (#213). */
    fun startLegacy(autoStopOnSilence: Boolean, pauseSeconds: Float, pick: InputDevicePick, keepEarbudsReady: Boolean): Boolean

    /**
     * The only start that may recover an abandoned recorder (#213), for both interfaces' `startCaptureForTake`: it
     * comes from the session owner, which admits a take only after it finished with the earlier one.
     */
    fun startTake(autoStopOnSilence: Boolean, pauseSeconds: Float, inputDevicePick: String?, keepEarbudsReady: Boolean, takeId: String?): Boolean

    /** The take is over: the warm hold decides whether the service outlives the owner's unbind (#26). */
    fun finishTake(): Boolean
    fun stopCapture()
    val takePeakAmplitude: Float
    val liveState: Int
    val liveAfterMs: Long
    val effectiveInputDevice: String
    val inputRouteKind: Int
    val inputRouteReason: Int
    val lastStartFailure: Int
    val silenceStopStatus: Int
    val isCapturing: Boolean
    val terminalReason: Int
    val currentAmplitude: Float
    fun spectrumBands(): FloatArray
    val audioFilePath: String?
    val elapsedMs: Long
    fun waitForFileReady(timeoutMs: Long): Boolean
}

/**
 * The capture process's two binder interfaces (#361), apart from the service. [legacy] keeps every
 * `IAudioCaptureService` transaction and its old meaning for the older clients and the device tests
 * (`architecture-rules.md` RULE: aidl-is-append-only); [forTake] is the owner's five-operation take interface
 * (#220), one binder per take binding, so a registration issued after its unbind reaches a closed epoch and is
 * refused. Each method is one call of [ops] or one listener slot, except two legacy ones that read no service state:
 * `getMaxDurationMs` returns the one recording limit, and `getAudioData` is the empty answer of a transaction
 * retired for the file path. The service decides everything else.
 */
internal class CaptureBinderAdapters(
    private val ops: CaptureOperations,
    private val spectrumListener: ListenerSlots.Slot<IAudioSpectrumListener>,
    private val takeListener: ListenerSlots.Slot<ITakeListener>,
) {
    val legacy: IBinder = object : IAudioCaptureService.Stub() {
        override fun startCapture(): Boolean = ops.startLegacy(autoStopOnSilence = false, pauseSeconds = 0f, pick = InputDevicePick.Auto, keepEarbudsReady = false)

        override fun startCaptureWithSilenceStop(autoStopOnSilence: Boolean, pauseSeconds: Float): Boolean =
            ops.startLegacy(autoStopOnSilence, pauseSeconds, InputDevicePick.Auto, keepEarbudsReady = false)

        override fun startCaptureWithInputDevice(autoStopOnSilence: Boolean, pauseSeconds: Float, inputDevicePick: String?): Boolean =
            ops.startLegacy(autoStopOnSilence, pauseSeconds, InputDevicePick.parse(inputDevicePick), keepEarbudsReady = false)

        override fun startCaptureWithInputDeviceHeld(autoStopOnSilence: Boolean, pauseSeconds: Float, inputDevicePick: String?, keepEarbudsReady: Boolean): Boolean =
            ops.startLegacy(autoStopOnSilence, pauseSeconds, InputDevicePick.parse(inputDevicePick), keepEarbudsReady)

        // The only start that may recover an abandoned recorder (#213): it comes from the session owner, which
        // admits a take only after it finished with the earlier one. The four legacy starts above carry no
        // such proof and only ever refuse.
        override fun startCaptureForTake(autoStopOnSilence: Boolean, pauseSeconds: Float, inputDevicePick: String?, keepEarbudsReady: Boolean, takeId: String?): Boolean =
            ops.startTake(autoStopOnSilence, pauseSeconds, inputDevicePick, keepEarbudsReady, takeId)

        override fun getTakePeakAmplitude(): Float = ops.takePeakAmplitude
        override fun getLiveState(): Int = ops.liveState
        override fun getLiveAfterMs(): Long = ops.liveAfterMs
        override fun finishTake(): Boolean = ops.finishTake()
        override fun getEffectiveInputDevice(): String = ops.effectiveInputDevice
        override fun getInputRouteKind(): Int = ops.inputRouteKind
        override fun getInputRouteReason(): Int = ops.inputRouteReason
        override fun getLastStartFailure(): Int = ops.lastStartFailure
        override fun getSilenceStopStatus(): Int = ops.silenceStopStatus
        override fun stopCapture() = ops.stopCapture()
        override fun isCapturing(): Boolean = ops.isCapturing
        override fun getTerminalReason(): Int = ops.terminalReason
        override fun getCurrentAmplitude(): Float = ops.currentAmplitude

        // LEGACY since #187: no production caller. Always BAND_COUNT long, never empty: the length is the contract.
        override fun getSpectrumBands(): FloatArray = ops.spectrumBands()

        override fun registerSpectrumListener(listener: IAudioSpectrumListener?) {
            spectrumListener.register(SlotOrigin.Legacy, listener)
        }

        override fun unregisterSpectrumListener(listener: IAudioSpectrumListener?) {
            spectrumListener.unregisterLegacy(listener)
        }

        override fun registerTakeListener(listener: ITakeListener?) {
            takeListener.register(SlotOrigin.Legacy, listener)
        }

        override fun unregisterTakeListener(listener: ITakeListener?) {
            takeListener.unregisterLegacy(listener)
        }

        override fun getAudioFilePath(): String? = ops.audioFilePath
        override fun getElapsedMs(): Long = ops.elapsedMs
        override fun getMaxDurationMs(): Long = RecordingLimits.MAX_DURATION_MS
        override fun waitForFileReady(timeoutMs: Long): Boolean = ops.waitForFileReady(timeoutMs)

        // Legacy method retained for old clients. Audio is now file-backed.
        override fun getAudioData(): ByteArray {
            DebugLogger.warn(TAG, "getAudioData() called, use getAudioFilePath() instead")
            return ByteArray(0)
        }
    }

    /**
     * The owner's take-sized interface (#220): the five operations a take uses. Each method is a one-line call of
     * the same operation the legacy binder calls, so the two interfaces cannot drift apart in behaviour.
     */
    fun forTake(epoch: Long): IBinder = object : IAudioTakeService.Stub() {
        private val from = SlotOrigin.Take(epoch)

        override fun startCaptureForTake(autoStopOnSilence: Boolean, pauseSeconds: Float, inputDevicePick: String?, keepEarbudsReady: Boolean, takeId: String?): Boolean =
            ops.startTake(autoStopOnSilence, pauseSeconds, inputDevicePick, keepEarbudsReady, takeId)

        override fun stopCapture() = ops.stopCapture()

        override fun finishTake(): Boolean = ops.finishTake()

        override fun registerSpectrumListener(listener: IAudioSpectrumListener?) {
            if (!spectrumListener.register(from, listener)) DebugLogger.warn(TAG, "Picture listener refused: its take binding already ended")
        }

        override fun registerTakeListener(listener: ITakeListener?) {
            if (!takeListener.register(from, listener)) DebugLogger.warn(TAG, "Take listener refused: its take binding already ended")
        }
    }

    private companion object {
        /** The service's own tag, so these lines read as they always did. */
        const val TAG = "AudioCapture"
    }
}
