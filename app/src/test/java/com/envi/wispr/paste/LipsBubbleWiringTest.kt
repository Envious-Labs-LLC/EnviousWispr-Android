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
        assertTrue(branch.contains("revalidateBubbleField(overlay)"))
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
    fun theServiceSubscribesToWindowsChangedForTheKeyboard() {
        assertTrue(config.contains("typeWindowsChanged"))
        assertTrue(config.contains("flagRetrieveInteractiveWindows"))
        assertTrue(config.contains("android:canRetrieveWindowContent=\"true\""))
        assertTrue(service.contains("AccessibilityEvent.TYPE_WINDOWS_CHANGED"))
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
