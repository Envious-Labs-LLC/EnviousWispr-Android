package com.envi.wispr.ui

import com.envi.wispr.audio.AudioCaptureService
import com.envi.wispr.audio.InputRouteKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** When these fail, the user is nagged every take, or reads a generic error instead of "connect one". */
class CaptureNoticesTest {

    @Test
    fun noInputDeviceGetsTheMacSentenceAndEverythingElseGetsTodays() {
        fun line(code: Int) = TakeNotices.line(TakeNotices.startFailureReason(code))
        assertEquals("No microphone found. Please connect one.", line(AudioCaptureService.START_FAILURE_NO_INPUT_DEVICE))
        assertEquals("Earbuds could not be used.", line(AudioCaptureService.START_FAILURE_EARBUDS))
        assertEquals("Microphone capture could not start safely", line(AudioCaptureService.START_FAILURE_OTHER))
        assertEquals("Microphone capture could not start safely", line(AudioCaptureService.START_FAILURE_NONE))
        assertEquals("Microphone capture could not start safely", line(99))
    }

    @Test
    fun theBluetoothTipShowsOncePerProcessAndOnlyOnBluetooth() {
        val gate = BluetoothTipGate()
        assertFalse("phone take", gate.shouldShow(InputRouteKind.PHONE.code, tipsEnabled = true))
        assertFalse("wired take", gate.shouldShow(InputRouteKind.WIRED_OR_USB.code, tipsEnabled = true))
        assertTrue("first Bluetooth take", gate.shouldShow(InputRouteKind.BLUETOOTH.code, tipsEnabled = true))
        assertFalse("second Bluetooth take", gate.shouldShow(InputRouteKind.BLUETOOTH.code, tipsEnabled = true))
    }

    @Test
    fun tipsOffMeansNoTipAndDoesNotSpendTheOneShot() {
        val gate = BluetoothTipGate()
        assertFalse(gate.shouldShow(InputRouteKind.BLUETOOTH.code, tipsEnabled = false))
        assertTrue("turning tips on later still gets the one line", gate.shouldShow(InputRouteKind.BLUETOOTH.code, tipsEnabled = true))
    }

    @Test
    fun theSessionOwnerUsesTheProcessGateNotOneItBuildsPerService() {
        // The service stops itself after every take; a gate it constructed would reset every dictation.
        val source = java.io.File("src/main/java/com/envi/wispr/ui/DictationSessionService.kt").readText()
        assertTrue(source.contains("private val bluetoothTipGate = BluetoothTipGate.PROCESS"))
        assertFalse(source.contains("BluetoothTipGate()"))
    }

    @Test
    fun theRecorderSaysAtMostOneMicrophoneLinePerTakeAndACaptureWarningOutranksIt() {
        // One notice slot on the recorder, last write wins: the tip must not overwrite the auto-stop
        // warning, and must not spend its once-per-process allowance in a take that said something else.
        val body = java.io.File("src/main/java/com/envi/wispr/ui/DictationSessionService.kt").readText()
            .substringAfter("private fun publishMicrophoneNoticesIfNeeded(")
            .substringBefore("private fun publishDurationWarningIfNeeded(")
        assertTrue(body.contains("if (silenceNoticeShown || forcedNoticeShown) return"))
        // #173: a pick that was not connected has no line and no latch of its own; the take is an
        // ordinary take for the tip. The old branch returned before the tip was considered.
        assertFalse(body.contains("pickMissing"))
        assertFalse(body.contains("inputRouteReason"))
        assertTrue("the gate is consulted only after the warnings have declined", body.indexOf("bluetoothTipGate.shouldShow") > body.indexOf("forcedNoticeShown) return"))
    }

    @Test
    fun theForcedNoticeIsSaidBeforePollingAndTheTipNamesWhereTheSoundIs() {
        // FORCED is published first, inside the live transition, so the once-per-process tip cannot
        // take the recorder's one slot from it (plan §3.2, 2026-09-18).
        val publish = java.io.File("src/main/java/com/envi/wispr/ui/DictationSessionService.kt").readText()
            .substringAfter("private fun publishLive(")
            .substringBefore("private fun startPolling(")
        assertTrue(publish.indexOf("sayWhileRecording(CaptureNotices.EARBUDS_SILENT)") < publish.indexOf("startPolling()"))
        assertTrue(publish.indexOf("forcedNoticeShown = true") < publish.indexOf("sayWhileRecording(CaptureNotices.EARBUDS_SILENT)"))
        assertEquals("Earbuds are not sending sound.", CaptureNotices.EARBUDS_SILENT)
        // The Android tip no longer asks the user to wait: the recorder waits for the earbuds itself.
        assertFalse(CaptureNotices.BLUETOOTH_TIP.contains("moment"))
    }

    @Test
    fun anUnknownKindCodeIsNotBluetooth() {
        assertFalse(BluetoothTipGate().shouldShow(42, tipsEnabled = true))
    }
}
