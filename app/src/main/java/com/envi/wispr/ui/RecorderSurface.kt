package com.envi.wispr.ui

import com.envi.wispr.shortcuts.BubbleRequestToken
import com.envi.wispr.shortcuts.RecordingOverlayState

/**
 * The recorder's state, as the session owner drives it: the eleven [RecordingOverlayState] members the owner
 * uses, and nothing else (#186). The object builds a main-looper `Handler` when first touched, so a JVM
 * test cannot reach it; it fakes this instead.
 */
internal interface RecorderSurface {
    fun showStarting(token: BubbleRequestToken?)
    fun nameTarget(fieldId: String?)
    fun attachTranscript(id: Long)
    fun show()
    fun showProcessing()
    fun showNotice(text: String)
    fun updateElapsed(seconds: Int)
    fun updateBands(takeSerial: Long, bands: FloatArray)
    fun hide()

    /** The serial [show] stamped on the current take; the picture listener stamps it on every picture and [updateBands] compares. */
    fun currentTakeSerial(): Long
}

/** Production: every call delegates to [RecordingOverlayState]. */
internal object OverlayRecorderSurface : RecorderSurface {
    override fun showStarting(token: BubbleRequestToken?) = RecordingOverlayState.showStarting(token)
    override fun nameTarget(fieldId: String?) = RecordingOverlayState.nameTarget(fieldId)
    override fun attachTranscript(id: Long) = RecordingOverlayState.attachTranscript(id)
    override fun show() = RecordingOverlayState.show()
    override fun showProcessing() = RecordingOverlayState.showProcessing()
    override fun showNotice(text: String) = RecordingOverlayState.showNotice(text)
    override fun updateElapsed(seconds: Int) = RecordingOverlayState.updateElapsed(seconds)
    override fun updateBands(takeSerial: Long, bands: FloatArray) = RecordingOverlayState.updateBands(takeSerial, bands)
    override fun hide() = RecordingOverlayState.hide()
    override fun currentTakeSerial(): Long = RecordingOverlayState.snapshots.value.takeSerial
}
