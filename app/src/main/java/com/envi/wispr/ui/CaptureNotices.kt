package com.envi.wispr.ui

import com.envi.wispr.audio.AudioCaptureService
import com.envi.wispr.audio.InputRouteKind
import com.envi.wispr.audio.InputRouteReason
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The words the session owner says about the microphone, decided from the codes the capture process
 * reports over the binder, never from the display label and never from the app's own device list.
 */
object CaptureNotices {
    /** macOS `Recording failure` copy, verbatim from the catalog. */
    const val NO_MICROPHONE = "No microphone found. Please connect one."

    /** Today's line for every other start failure, unchanged. */
    const val START_FAILED = "Microphone capture could not start safely"

    /** The Bluetooth tip, once per app process, like macOS's once per launch. */
    const val BLUETOOTH_TIP = "Bluetooth mic: give it a moment before you speak"

    fun startFailureLine(lastStartFailure: Int): String = when (lastStartFailure) {
        AudioCaptureService.START_FAILURE_NO_INPUT_DEVICE -> NO_MICROPHONE
        else -> START_FAILED
    }

    /** "AirPods Pro 3 is not connected, using the phone": said once for the take whose pick was absent. */
    fun pickMissingLine(pickedName: String): String = "$pickedName is not connected, using the phone"

    fun pickIsMissing(reasonCode: Int): Boolean = InputRouteReason.fromCode(reasonCode) == InputRouteReason.PICK_MISSING
}

/**
 * Says the Bluetooth tip at most once per app process, and only when the user has tips on and the take
 * actually started on a Bluetooth microphone. Process-scoped on purpose: the recorder line is a nudge,
 * not a setting, and a nudge that repeats every take teaches the user to stop reading the recorder.
 */
class BluetoothTipGate {
    private val shown = AtomicBoolean(false)

    /** True exactly once, on the first Bluetooth take while tips are on. */
    fun shouldShow(routeKindCode: Int, tipsEnabled: Boolean): Boolean {
        if (!tipsEnabled) return false
        if (InputRouteKind.fromCode(routeKindCode) != InputRouteKind.BLUETOOTH) return false
        return shown.compareAndSet(false, true)
    }
}
