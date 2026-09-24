package com.envi.wispr

import com.envi.wispr.KotlinSourceLexer.Kind
import com.envi.wispr.KotlinSourceLexer.callStarts
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

    /** Code and strings, comments blanked: a commented-out assertion is not an assertion. */
    private fun live(text: String) = view(text, Kind.CODE, Kind.STRING).replace(Regex("\\s+"), " ")

    /** The assertions that replaced the skips, pinned so deleting one fails (#305 review). MUTATION m5. */
    @Test
    fun theReplacementAssertionsAreThere() {
        val detectorReady = "assertTrue(\"the detector must be ready before the audio starts\", ready)"
        // (file, assertion, how many times): a count, because one file repeats an assertion in two rows.
        val required = listOf(
            Triple("SilenceStoppedTakeTranscribesDeviceTest.kt", detectorReady, 1),
            Triple("SilenceStoppedTakeTranscribesDeviceTest.kt", "assertFalse(\"the speech model must be installed on this phone: \$error\", error.contains(\"not ready\", true))", 1),
            Triple("CaptureWithSilenceStopDeviceTest.kt", detectorReady, 2),
            Triple("SilenceDetectorDeviceTest.kt", "assertTrue(\"the speech fixture must be present (pushed to the app's cache)\", fixture.isFile && fixture.length() > blockBytes)", 1),
        )
        required.forEach { (file, line, times) ->
            assertEquals("$file must assert $times time(s): $line", times, Regex(Regex.escape(line)).findAll(live(rows.getValue(file))).count())
        }
        // As many permission checks and start assertions as capture starts (counts, not positions).
        mapOf("SilenceStoppedTakeTranscribesDeviceTest.kt" to 1, "CaptureWithSilenceStopDeviceTest.kt" to 7).forEach { (file, starts) ->
            val text = rows.getValue(file)
            assertEquals("$file: one permission check per capture start", starts, callStarts(text, "requireMicrophone").size)
            assertEquals("$file: one start assertion per capture start", starts, Regex("assertTrue\\(\"capture must start \\(last start failure").findAll(live(text)).count())
        }
    }

    /** MUTATIONS m4 and m6: the NOT RUN line is a live call, made before the assumption. */
    @Test
    fun theOneSkipLogsNotRunBeforeItAssumes() {
        val log = callStarts(notRun, "warn").singleOrNull()?.first ?: -1
        val assume = callStarts(notRun, "assumeTrue").singleOrNull()?.first ?: -1
        assertTrue("DeviceNotRun logs through DebugLogger.warn: $notRun", log >= 0 && live(notRun).contains("DebugLogger.warn(\"DeviceNotRun\", \"NOT RUN: \$row: \$reason\")"))
        assertTrue("and logs before it assumes", assume > log)
    }
}
