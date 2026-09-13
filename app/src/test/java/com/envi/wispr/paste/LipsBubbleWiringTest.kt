package com.envi.wispr.paste

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * DRIFT GUARD for the lips bubble's wiring (issue #135). None of these is product coverage; each pins
 * a decision the plan's reviews settled, so that a later edit cannot quietly undo it.
 */
class LipsBubbleWiringTest {

    private val service = File("src/main/java/com/envi/wispr/paste/PasteAccessibilityService.kt").readText()
    private val session = File("src/main/java/com/envi/wispr/ui/DictationSessionService.kt").readText()
    private val overlay = File("src/main/java/com/envi/wispr/paste/RecordingAccessibilityOverlay.kt").readText()
    private val launcher = File("src/main/java/com/envi/wispr/ui/VoiceInputActivity.kt").readText()
    private val manifest = File("src/main/AndroidManifest.xml").readText()
    private val config = File("src/main/res/xml/accessibility_service_config.xml").readText()

    @Test
    fun theAccessibilityServicePublishesNothingToTheOverlayBus() {
        // Only the session owner may say what phase a take is in. A hide() here made a mid-take
        // reconnect look like IDLE (review round 1).
        assertFalse(service.contains("RecordingOverlayState.hide()"))
        assertFalse(service.contains("RecordingOverlayState.show"))
    }

    @Test
    fun theSessionOwnerPublishesIdleOnlyWhereItCanNoLongerRefuseAStart() {
        // finishSession publishes IDLE right before stopSelf; onDestroy publishes it because the
        // owner is gone. Every other terminal path publishes PROCESSING.
        val finish = session.substringAfter("private fun finishSession()").substringBefore("private fun promoteToForeground")
        assertTrue(finish.contains("RecordingOverlayState.showProcessing()"))
        assertTrue(finish.contains("RecordingOverlayState.hide()"))
        assertTrue(finish.indexOf("RecordingOverlayState.hide()") > finish.indexOf("DictationNotificationController.dismiss"))
        listOf("private fun stopAndTranscribe()", "private fun cancelRecording()", "private fun cancelStarting()", "private fun showError(")
            .forEach { head ->
                val body = session.substringAfter(head).substringBefore("\n    private fun ")
                assertTrue("$head must publish PROCESSING", body.contains("RecordingOverlayState.showProcessing()"))
                assertFalse("$head must not publish IDLE", body.contains("RecordingOverlayState.hide()"))
            }
    }

    @Test
    fun theBubbleMintsATokenAndSendsReleaseAndCancelWithIt() {
        assertTrue(overlay.contains("BubbleRequests.mint()"))
        assertTrue(overlay.contains("DictationSessionService.ACTION_STOP, it.encode())"))
        assertTrue(overlay.contains("DictationSessionService.ACTION_CANCEL, it.encode())"))
        // A tap starts a new request only at IDLE.
        assertTrue(overlay.contains("if (snapshot.phase != RecordingOverlayState.Phase.IDLE) return null"))
        // A hold's release and cancel go to the request THAT hold created, never to an earlier take.
        assertTrue(overlay.contains("holdRequest = startDictation()"))
        assertTrue(overlay.contains("holdRequest?.let { DictationSessionService.sendCommand(service, DictationSessionService.ACTION_STOP, it.encode()) }"))
        assertFalse(overlay.contains("currentRequest"))
    }

    @Test
    fun focusLeavingTheEditorForANonEditableControlIsRevalidated() {
        val branch = service.substringAfter("AccessibilityEvent.TYPE_VIEW_FOCUSED, AccessibilityEvent.TYPE_VIEW_CLICKED ->").substringBefore("AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED")
        assertTrue(branch.contains("if (remembered) {"))
        // An unremembered focus discovers (the return to an app whose editor is already focused emits no
        // fresh focus event for the editor); a click does not, to stay cheap (BUG 1, 2026-09-13).
        assertTrue(branch.contains("revalidateBubbleField(overlay, discover = event.eventType == AccessibilityEvent.TYPE_VIEW_FOCUSED)"))
    }

    @Test
    fun theOwnerResolvesEveryMarkedCommandThroughTheLedgerBeforeDispatch() {
        val handler = session.substringAfter("override fun onStartCommand").substringBefore("override fun onBind")
        assertTrue(handler.indexOf("admitBubbleCommand(") < handler.indexOf("when (intent?.action ?: ACTION_START)"))
        assertTrue(session.contains("BubbleRequests.resolveStart("))
        assertTrue(session.contains("BubbleRequests.resolveCommand("))
        // The early release is consumed at the RECORDING transition, nowhere else.
        val start = session.substringAfter("private fun tryStartRecording()").substringBefore("private fun startPolling()")
        assertTrue(start.contains("if (stopAfterRecording)"))
    }

    @Test
    fun theLauncherKnowsStartAndForwardsTheRequest() {
        assertTrue(launcher.contains("const val EXTRA_START = \"start\""))
        assertTrue(launcher.contains("const val EXTRA_REQUEST = \"request\""))
        assertTrue(launcher.contains("intent.getBooleanExtra(EXTRA_START, false) -> DictationSessionService.ACTION_START"))
        assertTrue(launcher.contains("intent.getStringExtra(EXTRA_REQUEST)"))
        // The default stays TOGGLE for the side button.
        assertTrue(launcher.contains("else -> DictationSessionService.ACTION_TOGGLE"))
    }

    @Test
    fun noAppearOnTopPermissionWasAdded() {
        // The adjudicated decision: the bubble lives in the accessibility window.
        assertFalse(manifest.contains("SYSTEM_ALERT_WINDOW"))
    }

    @Test
    fun theServiceRequestsTheKeyboardPipeInBothPlacesTheFlagsLive() {
        // configureEventMode replaces serviceInfo whole on every call, so the input method flag must
        // be in that call or the first dictation wipes it; the XML mirrors it for the bind (#141).
        assertTrue(config.contains("flagInputMethodEditor"))
        val eventMode = service.substringAfter("private fun configureEventMode(").substringBefore("\n    }")
        assertTrue(eventMode.contains("AccessibilityServiceInfo.FLAG_INPUT_METHOD_EDITOR"))
        assertTrue(service.contains("override fun onCreateInputMethod(): InputMethod = EditorInputSession(this)"))
    }

    @Test
    fun theServiceSubscribesToWindowsChangedForTheKeyboard() {
        assertTrue(config.contains("typeWindowsChanged"))
        assertTrue(config.contains("flagRetrieveInteractiveWindows"))
        assertTrue(config.contains("android:canRetrieveWindowContent=\"true\""))
        assertTrue(service.contains("AccessibilityEvent.TYPE_WINDOWS_CHANGED"))
    }

    @Test
    fun aReconnectAndAWindowSwitchDiscoverAnAlreadyFocusedEditor() {
        // Discovery is a traversal, so it is allowed on connect, on a window state change, and on a
        // windows-changed that carries a focus/active/added/removed change (a real app switch), never on
        // the cosmetic windows-changed stream (BUG 1, 2026-09-13).
        assertTrue(service.contains("revalidateBubbleField(it, discover = true)"))
        assertTrue(service.contains("revalidateBubbleField(overlay, discover = discoveryWarranted(event))"))
        val gate = service.substringAfter("private fun discoveryWarranted(").substringBefore("\n    /**")
        assertTrue(gate.contains("event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return true"))
        assertTrue(gate.contains("AccessibilityEvent.WINDOWS_CHANGE_FOCUSED"))
        assertTrue(gate.contains("AccessibilityEvent.WINDOWS_CHANGE_ACTIVE"))
        assertTrue(gate.contains("AccessibilityEvent.WINDOWS_CHANGE_ADDED"))
        assertTrue(gate.contains("AccessibilityEvent.WINDOWS_CHANGE_REMOVED"))
        assertTrue(gate.contains("event.windowChanges and relevant) != 0"))
        val body = service.substringAfter("private fun revalidateBubbleField(").substringBefore("\n    /**")
        assertTrue(body.contains("if (!stillFocused && discover)"))
        assertTrue(body.contains("findFocusedEditableTarget()"))
        // Split screen: an editor in the other pane keeps its focus flag. Both the remembered editor
        // and a discovered one must sit in the window that has input focus, or the bubble hides.
        assertTrue(body.contains("isSafeFocusedEditor(target.node) && isInFocusedWindow(target.windowId)"))
        assertTrue(body.contains("findFocusedEditableTarget()?.takeIf { isInFocusedWindow(it.windowId) }"))
        assertTrue(service.contains("windows.any { it.id == windowId && it.isFocused }"))
    }

    @Test
    fun discoverySearchesOnlyTheInputFocusedWindow() {
        // findFocusedEditableTarget must not return an editor from an unfocused window, or pinTarget could
        // rediscover the stale editor it just rejected and pin the departed app (Codex fast-follow,
        // 2026-09-13). The filter is applied during the search: the active-root shortcut is gated on
        // window focus, and the windows loop skips unfocused windows.
        val body = service.substringAfter("private fun findFocusedEditableTarget(): TargetSnapshot?").substringBefore("private fun findFocusedEditableTarget(root")
        assertTrue(body.contains("activeRoot?.takeIf { isInFocusedWindow(it.windowId) }"))
        assertTrue(body.contains("if (!window.isFocused) continue"))
    }

    @Test
    fun pinningReusesATargetOnlyWhileItsWindowStillHasFocus() {
        // A remembered editor in the app the user just left keeps its own focus flag; node focus alone
        // would pin the departed field and the words would land there. Both reuse checks in pinTarget
        // require the target's window to still own input focus (Codex review, BUG 1, 2026-09-13).
        val body = service.substringAfter("private fun pinTarget(").substringBefore("private fun findFocusedEditableTarget(")
        assertTrue(body.contains("existing.node.refresh() && isSafeFocusedEditor(existing.node) && isInFocusedWindow(existing.windowId)"))
        assertTrue(body.contains("!isSafeFocusedEditor(target.node) || !isInFocusedWindow(target.windowId)"))
    }

    @Test
    fun aScreenReaderDoubleTapStartsDictationThroughTheClickAction() {
        val bubble = overlay.substringAfter("private fun buildBubble()").substringBefore("private fun buildPillColumn()")
        assertTrue(bubble.contains("setOnClickListener { startDictation() }"))
        assertTrue(bubble.contains("Double tap to dictate"))
    }

    @Test
    fun theOverlayIsCreatedOncePerServiceInstance() {
        // A repeat onServiceConnected (any package install triggers one) must not drop the bubble's state.
        assertTrue(service.contains("if (recordingOverlay == null) {"))
    }

    @Test
    fun theOverlayWindowTitleIsUnchangedForTheDeviceHarness() {
        assertEquals(1, Regex("\"EnviousWispr recording controls\"").findAll(overlay).count())
    }
}
