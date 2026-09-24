package com.envi.wispr

import com.envi.wispr.KotlinSourceLexer.callStarts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Drift Guard (#305), not product coverage: an instrumented `assumeTrue` that fails is written as PASS, so the three
 * silence device test files must not call it (a comment or a string that mentions it is not a call). The one remaining
 * skip, a missing microphone permission, goes through `DeviceNotRun`, which is outside these files. Read off the
 * source because the rows run on a device. What each row asserts instead is read in review, not pinned here: three
 * review rounds each found a new way to satisfy a text or call-shape pin, so only the property the audit named is
 * guarded. MUTATIONS m1 to m3.
 */
class SilenceDeviceRowsShapeTest {
    private val rows = listOf(
        "SilenceStoppedTakeTranscribesDeviceTest.kt",
        "CaptureWithSilenceStopDeviceTest.kt",
        "SilenceDetectorDeviceTest.kt",
    ).associateWith { File("src/androidTest/java/com/envi/wispr/$it").readText() }

    @Test
    fun theSilenceRowsAssertTheirPreconditionsInsteadOfSkipping() {
        rows.forEach { (file, text) ->
            assertEquals("$file calls assumeTrue, which a device run reports as a pass", 0, callStarts(text, "assumeTrue").size)
            assertTrue("$file imports org.junit.Assume", !Regex("(?m)^import org\\.junit\\.Assume").containsMatchIn(text))
        }
    }
}
