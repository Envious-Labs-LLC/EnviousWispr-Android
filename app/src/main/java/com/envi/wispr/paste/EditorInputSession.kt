package com.envi.wispr.paste

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.InputMethod
import android.view.inputmethod.EditorInfo
import com.envi.wispr.debug.DebugLogger

/**
 * The accessibility input method (API 33): the pipe through which a dictation is committed straight
 * into the focused editor, with no clipboard write and therefore no system "Copied" notice (#141
 * chunk 2).
 *
 * The framework starts and finishes input on this object as focus moves between editors. The input
 * session and the pinned node are two identities, so the service only commits after proving they
 * coincide, and [generation] is what makes that proof hold across the write: it is bumped on every
 * start and finish, the service captures it with the connection at the eligibility check, and a
 * commit on a connection whose generation has moved is refused, never sent.
 */
internal class EditorInputSession(service: AccessibilityService) : InputMethod(service) {
    private companion object {
        const val TAG = "PasteService"
    }

    /** The connection and package the service captured at one eligibility check. */
    class Captured(
        val connection: AccessibilityInputConnection,
        val packageName: String?,
        val generation: Long,
    )

    @Volatile
    var generation: Long = 0L
        private set

    override fun onStartInput(attribute: EditorInfo, restarting: Boolean) {
        super.onStartInput(attribute, restarting)
        generation += 1
        DebugLogger.debug(TAG, "Input started on the pipe: package=${attribute.packageName} restarting=$restarting generation=$generation")
    }

    override fun onFinishInput() {
        super.onFinishInput()
        generation += 1
        DebugLogger.debug(TAG, "Input finished on the pipe: generation=$generation")
    }

    /** The current session, or null when no editor has input started on this pipe right now. */
    fun capture(): Captured? {
        if (!currentInputStarted) return null
        val connection = currentInputConnection ?: return null
        return Captured(connection, currentInputEditorInfo?.packageName, generation)
    }

    /** Whether [captured] is still the live session: same generation, input still started. */
    fun isLive(captured: Captured): Boolean = currentInputStarted && captured.generation == generation
}
