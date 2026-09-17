package com.envi.wispr.ui

import com.envi.wispr.audio.AudioCaptureService
import com.envi.wispr.audio.InputRouteKind
import com.envi.wispr.audio.InputRouteReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** When these fail, the user is nagged every take, or reads a generic error instead of "connect one". */
class CaptureNoticesTest {

    @Test
    fun noInputDeviceGetsTheMacSentenceAndEverythingElseGetsTodays() {
        assertEquals("No microphone found. Please connect one.", CaptureNotices.startFailureLine(AudioCaptureService.START_FAILURE_NO_INPUT_DEVICE))
        assertEquals("Microphone capture could not start safely", CaptureNotices.startFailureLine(AudioCaptureService.START_FAILURE_OTHER))
        assertEquals("Microphone capture could not start safely", CaptureNotices.startFailureLine(AudioCaptureService.START_FAILURE_NONE))
        assertEquals("Microphone capture could not start safely", CaptureNotices.startFailureLine(99))
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
        assertTrue(body.contains("if (silenceNoticeShown || pickMissingNoticeShown) return"))
        assertTrue("the pick-missing branch returns before the tip is considered", body.indexOf("return") < body.indexOf("val kind"))
        assertTrue("the gate is consulted only after every other line has declined", body.indexOf("bluetoothTipGate.shouldShow") > body.indexOf("pickIsMissing"))
    }

    @Test
    fun anUnknownKindCodeIsNotBluetooth() {
        assertFalse(BluetoothTipGate().shouldShow(42, tipsEnabled = true))
    }

    @Test
    fun onlyThePickMissingReasonArmsThePickMissingLine() {
        InputRouteReason.entries.forEach { reason ->
            assertEquals(reason.name, reason == InputRouteReason.PICK_MISSING, CaptureNotices.pickIsMissing(reason.code))
        }
        assertEquals("AirPods Pro 3 is not connected. Using Auto instead.", CaptureNotices.pickMissingLine("AirPods Pro 3"))
    }
}
