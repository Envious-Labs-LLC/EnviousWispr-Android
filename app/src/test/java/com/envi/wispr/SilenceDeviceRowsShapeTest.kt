package com.envi.wispr

import com.envi.wispr.KotlinSourceLexer.Kind
import com.envi.wispr.KotlinSourceLexer.body
import com.envi.wispr.KotlinSourceLexer.callStarts
import com.envi.wispr.KotlinSourceLexer.calls
import com.envi.wispr.KotlinSourceLexer.normalized
import com.envi.wispr.KotlinSourceLexer.view
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Drift Guard (#305), not product coverage: an instrumented `assumeTrue` that fails is written as PASS, so the three
 * silence device test files must not skip their stageable preconditions (capture start, detector readiness, the
 * pushed fixture, the speech model). The one remaining skip, a missing microphone permission, goes through
 * `DeviceNotRun`, which logs `NOT RUN` before it assumes. Read off the source because the rows run on a device.
 * MUTATIONS m1 to m4.
 */
class SilenceDeviceRowsShapeTest {
    private val rows = listOf(
        "SilenceStoppedTakeTranscribesDeviceTest.kt",
        "CaptureWithSilenceStopDeviceTest.kt",
        "SilenceDetectorDeviceTest.kt",
    ).associateWith { File("src/androidTest/java/com/envi/wispr/$it").readText() }

    private val notRun = File("src/androidTest/java/com/envi/wispr/DeviceNotRun.kt").readText()

    @Test
    fun theSilenceRowsAssertTheirPreconditionsInsteadOfSkipping() {
        rows.forEach { (file, text) ->
            assertEquals("$file calls assumeTrue, which a device run reports as a pass", 0, callStarts(text, "assumeTrue").size)
            assertTrue("$file imports org.junit.Assume", !Regex("(?m)^import org\\.junit\\.Assume").containsMatchIn(text))
        }
    }

    /**
     * How many CODE calls of [function] inside [row]'s body have exactly [args] (comments removed, whitespace folded).
     * Parsed calls, not text: an assertion written into a string, a raw string or a comment is not a call, and an
     * assertion in one row never counts for another (#305 review rounds 1 and 2).
     */
    private fun callsIn(text: String, row: String, function: String, vararg args: String): Int =
        calls(body(text, row), function).count { (_, raw) ->
            raw.map { normalized(view(it, Kind.CODE, Kind.STRING)) }.filter { it.isNotEmpty() } == args.map(::normalized)
        }

    private val detectorReady = arrayOf("\"the detector must be ready before the audio starts\"", "ready")
    private val captureStarts = arrayOf("\"capture must start (last start failure \${capture.lastStartFailure})\"", "started")

    /** The assertions that replaced the skips, one per row that needs it. MUTATIONS m5, m7, m8. */
    @Test
    fun theReplacementAssertionsAreThere() {
        val stopped = rows.getValue("SilenceStoppedTakeTranscribesDeviceTest.kt")
        val take = "fun aTakeThatEndedOnSilenceStillProducesWords()"
        assertEquals(1, callsIn(stopped, take, "assertTrue", *detectorReady))
        assertEquals(1, callsIn(stopped, take, "assertFalse", "\"the speech model must be installed on this phone: \$error\"", "error.contains(\"not ready\", true)"))

        val capture = rows.getValue("CaptureWithSilenceStopDeviceTest.kt")
        listOf("fun realSpeechThroughTheMicrophoneEndsTheTakeWhenItStops()", "fun theWaitSettingDecidesWhetherAThinkingPauseEndsTheTake()").forEach { row ->
            assertEquals("$row asserts the detector is ready", 1, callsIn(capture, row, "assertTrue", *detectorReady))
        }

        val detector = rows.getValue("SilenceDetectorDeviceTest.kt")
        assertEquals(1, callsIn(detector, "private fun speechBlocks()", "assertTrue", "\"the speech fixture must be present (pushed to the app's cache)\"", "fixture.isFile && fixture.length() > blockBytes"))

        // Every capture-starting row checks the permission and asserts the start, once each.
        val starting = mapOf(
            stopped to listOf(take),
            capture to listOf(
                "fun realSpeechThroughTheMicrophoneEndsTheTakeWhenItStops()",
                "fun theWaitSettingDecidesWhetherAThinkingPauseEndsTheTake()",
                "fun aQuietRoomNeverEndsATakeByItselfThroughTheWholeRealPath()",
                "fun theDetectorBecomesReadyForARealTakeAndTheStatusSaysSo()",
                "fun withTheSwitchOffNoDetectorIsAskedForAtAll()",
                "fun anOutOfRangePauseRefusesAutoStopButStillRecords()",
                "fun aRegisteredListenerReceivesThePictureDuringATake()",
            ),
        )
        starting.forEach { (text, rowsThatStart) ->
            rowsThatStart.forEach { row ->
                assertEquals("$row checks the permission once", 1, calls(body(text, row), "requireMicrophone").size)
                assertEquals("$row asserts its start once", 1, callsIn(text, row, "assertTrue", *captureStarts))
            }
        }
    }

    /** MUTATIONS m4, m6, m9: the NOT RUN line is the live `warn` call, with its tag and message, before the assumption. */
    @Test
    fun theOneSkipLogsNotRunBeforeItAssumes() {
        val skip = body(notRun, "fun skipUnless(condition: Boolean, row: String, reason: String)")
        val warn = calls(skip, "warn").singleOrNull()
        assertTrue("one live warn call in skipUnless: $skip", warn != null)
        assertEquals(listOf("\"DeviceNotRun\"", "\"NOT RUN: \$row: \$reason\""), warn!!.second.map { normalized(view(it, Kind.CODE, Kind.STRING)) })
        val assume = callStarts(skip, "assumeTrue").singleOrNull()?.first ?: -1
        assertTrue("and it logs before it assumes", assume > warn.first)
    }
}
