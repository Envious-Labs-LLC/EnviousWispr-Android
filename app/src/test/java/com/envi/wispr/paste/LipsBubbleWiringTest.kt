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
        // Both cancels share one body since the live gate (a cancelled start has capture running too).
        listOf("private fun cancelRecording()", "private fun cancelStarting()").forEach { head ->
            val body = session.substringAfter(head).substringBefore("\n    private fun ")
            assertTrue("$head delegates to the shared cancel", body.contains("cancelCaptureAndFinish()"))
        }
        listOf("private fun stopAndTranscribe()", "private fun cancelCaptureAndFinish()", "private fun showError(")
            .forEach { head ->
                val body = session.substringAfter(head).substringBefore("\n    private fun ")
                assertTrue("$head must publish PROCESSING", body.contains("RecordingOverlayState.showProcessing()"))
                assertFalse("$head must not publish IDLE", body.contains("RecordingOverlayState.hide()"))
            }
    }

    @Test
    fun theOwnerPublishesTheTakesTargetAndRowSoNoReaderGuessesThem() {
        // The onboarding practice judges ONLY the row the owner names, for a take aimed at ITS box.
        val begin = session.substringAfter("private fun beginSession()").substringBefore("\n    private fun ")
        assertTrue(begin.contains("RecordingOverlayState.nameTarget("))
        assertTrue(begin.indexOf("nameTarget(") > begin.indexOf("pinTargetForDictation()"))
        assertTrue(session.contains("RecordingOverlayState.attachTranscript(id)"))
        val viewModel = File("src/main/java/com/envi/wispr/ui/OnboardingViewModel.kt").readText()
        assertTrue(viewModel.contains("snapshot.targetFieldId == PRACTICE_FIELD_ID"))
        assertTrue(viewModel.contains("snapshot.transcriptId"))
        assertFalse(viewModel.contains("System.currentTimeMillis"))
    }

    @Test
    fun theBubbleMintsATokenAndSendsReleaseAndCancelWithIt() {
        assertTrue(overlay.contains("BubbleRequests.mint(held)"))
        // The gesture is on the token, so the owner's snapshot can say which one a take answers.
        assertTrue(overlay.contains("startDictation(held = false)"))
        assertTrue(overlay.contains("DictationSessionService.ACTION_STOP, it.encode())"))
        assertTrue(overlay.contains("DictationSessionService.ACTION_CANCEL, it.encode())"))
        // A tap starts a new request only at IDLE.
        assertTrue(overlay.contains("if (snapshot.phase != RecordingOverlayState.Phase.IDLE) return null"))
        // A hold's release and cancel go to the request THAT hold created, never to an earlier take.
        assertTrue(overlay.contains("holdRequest = startDictation(held = true)"))
        assertTrue(overlay.contains("holdRequest?.let { DictationSessionService.sendCommand(service, DictationSessionService.ACTION_STOP, it.encode()) }"))
        assertFalse(overlay.contains("currentRequest"))
    }

    @Test
    fun focusLeavingTheEditorForANonEditableControlIsRevalidated() {
        val branch = service.substringAfter("AccessibilityEvent.TYPE_VIEW_FOCUSED, AccessibilityEvent.TYPE_VIEW_CLICKED ->").substringBefore("AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED")
        // Remembered = field found the direct way: activate it and stop any discovery retries chasing it.
        assertTrue(branch.contains("if (remembered) {"))
        assertTrue(branch.contains("cancelDiscoveryRetries()"))
        // An unremembered focus discovers WITH retry (the return to an app whose editor is already focused
        // emits no fresh focus event for the editor, and the editor can be exposed ~2 s late); a click just
        // re-checks without a traversal, to stay cheap (BUG 1 + phone-pass lag, 2026-09-13).
        assertTrue(branch.contains("event.eventType == AccessibilityEvent.TYPE_VIEW_FOCUSED) {"))
        assertTrue(branch.contains("discoverFieldWithRetry()"))
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
        // the cosmetic windows-changed stream (BUG 1, 2026-09-13). Connect and app-switch both run the
        // discover-with-retry sequence so a late-exposed editor still lights the bubble (phone-pass lag).
        assertTrue(service.contains("mainHandler.post { if (connectGeneration == discoveryGeneration) discoverFieldWithRetry() }"))
        assertTrue(service.contains("if (discoveryWarranted(event)) discoverFieldWithRetry() else if (revalidateBubbleField(overlay)) cancelDiscoveryRetries()"))
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
    fun discoveryRetriesUntilTheReturningEditorIsAccessible() {
        // A real Samsung exposes a returning editor ~2 s late, so the first look finds nothing. Re-look at
        // fixed offsets so the bubble lights without a re-tap; one guarded sequence, coalesced per window,
        // cancelled on success and teardown (phone pass + Codex, 2026-09-13).
        assertTrue(service.contains("DISCOVERY_RETRY_DELAYS_MS = longArrayOf(250L, 750L, 2_250L)"))
        val start = service.substringAfter("private fun discoverFieldWithRetry()").substringBefore("private fun scheduleDiscoveryRetry")
        // Coalesce: a sequence already chasing the same focused window (unknown -1 included) is not restarted.
        assertTrue(start.contains("discoveryRetryRunnable != null && focusedId == discoveryWindowId) return"))
        assertTrue(start.contains("if (revalidateBubbleField(overlay, discover = true)) return"))
        assertTrue(start.contains("discoveryStartUptime = SystemClock.uptimeMillis()"))
        assertTrue(start.contains("scheduleDiscoveryRetry(discoveryGeneration)"))
        val sched = service.substringAfter("private fun scheduleDiscoveryRetry(").substringBefore("private fun cancelDiscoveryRetries")
        // Absolute offsets from the first attempt, so a slow traversal cannot stretch the schedule.
        assertTrue(sched.contains("postAtTime(runnable, discoveryStartUptime + DISCOVERY_RETRY_DELAYS_MS[discoveryAttempt])"))
        // Generation guard: a superseding transition or teardown makes an in-flight attempt do nothing.
        assertTrue(sched.contains("if (generation != discoveryGeneration) return@Runnable"))
        // Stops the moment a field is found.
        assertTrue(sched.contains("if (revalidateBubbleField(overlay, discover = true)) {"))
        assertTrue(sched.contains("cancelDiscoveryRetries()"))
        val cancel = service.substringAfter("private fun cancelDiscoveryRetries()").substringBefore("private fun focusedWindowId")
        assertTrue(cancel.contains("discoveryGeneration++"))
        assertTrue(cancel.contains("mainHandler.removeCallbacks(it)"))
        // Cancelled on every teardown so retries never leak past the service.
        assertTrue(service.substringAfter("override fun onInterrupt()").substringBefore("override fun onUnbind").contains("cancelDiscoveryRetries()"))
        assertTrue(service.substringAfter("override fun onUnbind(").substringBefore("override fun onDestroy").contains("cancelDiscoveryRetries()"))
        assertTrue(service.substringAfter("override fun onDestroy()").substringBefore("private fun ").contains("cancelDiscoveryRetries()"))
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
        assertTrue(bubble.contains("setOnClickListener { startDictation(held = false) }"))
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
