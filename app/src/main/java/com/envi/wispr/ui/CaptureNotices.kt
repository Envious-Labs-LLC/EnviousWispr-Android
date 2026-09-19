package com.envi.wispr.ui

import com.envi.wispr.audio.AudioCaptureService
import com.envi.wispr.audio.InputRouteKind
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

    /**
     * The Bluetooth tip, once per app process, like macOS's once per launch. Reworded from the macOS
     * "give it a moment" on 2026-09-18: Android now waits for the earbuds itself (the live gate), so the
     * moment is taken before the pill opens and the tip only names where the sound is coming from.
     */
    const val BLUETOOTH_TIP = "Recording through your earbuds"

    /** The earbuds are connected, the link never delivered sound after one reset, and the take proceeds on them anyway. */
    const val EARBUDS_SILENT = "Earbuds are not sending sound."

    /** The earbuds are connected and only the phone would have recorded; by rule the take does not start. */
    const val EARBUDS_UNUSABLE = "Earbuds could not be used."

    fun startFailureLine(lastStartFailure: Int): String = when (lastStartFailure) {
        AudioCaptureService.START_FAILURE_NO_INPUT_DEVICE -> NO_MICROPHONE
        AudioCaptureService.START_FAILURE_EARBUDS -> EARBUDS_UNUSABLE
        else -> START_FAILED
    }
}

/**
 * Says the Bluetooth tip at most once per app process, and only when the user has tips on and the take
 * actually started on a Bluetooth microphone. Process-scoped on purpose: the recorder line is a nudge,
 * not a setting, and a nudge that repeats every take teaches the user to stop reading the recorder.
 */
class BluetoothTipGate {
    private val shown = AtomicBoolean(false)

    companion object {
        /**
         * The one gate for this app process. The session owner is a service that stops itself after
         * every dictation, so a gate held on the service would reset every take (Codex review, 2026-09-17).
         */
        val PROCESS = BluetoothTipGate()
    }

    /** True exactly once, on the first Bluetooth take while tips are on. */
    fun shouldShow(routeKindCode: Int, tipsEnabled: Boolean): Boolean {
        if (!tipsEnabled) return false
        if (InputRouteKind.fromCode(routeKindCode) != InputRouteKind.BLUETOOTH) return false
        return shown.compareAndSet(false, true)
    }
}
