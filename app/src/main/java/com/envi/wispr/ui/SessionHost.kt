package com.envi.wispr.ui

import com.envi.wispr.paste.AutoPasteAvailability
import com.envi.wispr.polish.PolishFailureNotice
import com.envi.wispr.shortcuts.DictationSurfaceState

/**
 * The Android calls the session owner makes, one method each, implemented by [DictationSessionService]
 * (#186). Every method is one or two lines of Android with no decision in it; the coordinator decides,
 * the host acts. A JVM test fakes this so the coordinator runs without a `Service`.
 */
internal interface SessionHost {
    /** `startForeground` with the listening or processing notification. */
    fun promoteToForeground(processing: Boolean)

    /** `DictationSurfaceState.update(context, phase)`: the tile and the notification read it. */
    fun updateSurfacePhase(phase: DictationSurfaceState.Phase)

    fun vibrate(cue: HapticCue)

    /**
     * Two toasts, not one, because today's four toast sites use two contexts: the Service for the polish
     * notice, the fallback line and the error line, and `applicationContext` for a line said after the
     * recorder has gone (Codex review G1, 2026-09-20).
     */
    fun toastFromService(line: String)

    fun toastFromApplication(line: String)

    fun showPolishNotice(notice: PolishFailureNotice)

    /** @return whether the words reached the clipboard. */
    fun copyToClipboard(text: String): Boolean

    /** The permission fact plus the paste service's liveness, read only at a start or a fallback. */
    fun autoPasteAvailability(): AutoPasteAvailability

    /** `stopForeground(STOP_FOREGROUND_REMOVE)` then dismiss the notification. */
    fun removeForegroundAndDismiss()

    /** `stopSelf()`. Separate from [removeForegroundAndDismiss] because the owner resets state between them. */
    fun stopSelfNow()

    /** The Service's one `Handler(Looper.getMainLooper())`: FIFO on the main looper. */
    fun postToMain(runnable: Runnable)
    /**
     * Run [runnable] on the main thread after [delayMs], unless [cancelMainDelayed] removes it first. The
     * owner's only timer, armed for exactly as long as a take's binding is up (#115; RULE: no-idle-cost).
     */
    fun postToMainDelayed(delayMs: Long, runnable: Runnable)
    fun cancelMainDelayed(runnable: Runnable)

    fun onMainThread(): Boolean

    /** `SystemClock.elapsedRealtime()`. */
    fun elapsedRealtimeMs(): Long
}
