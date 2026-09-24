package com.envi.wispr

import com.envi.wispr.KotlinSourceLexer.callStarts
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

    @Test
    fun theOneSkipLogsNotRunBeforeItAssumes() {
        val log = notRun.indexOf("DebugLogger.warn(\"DeviceNotRun\"")
        val assume = callStarts(notRun, "assumeTrue").singleOrNull()?.first ?: -1
        assertTrue("DeviceNotRun logs NOT RUN: $notRun", log >= 0 && notRun.contains("NOT RUN: "))
        assertTrue("and logs before it assumes", assume > log)
    }
}
