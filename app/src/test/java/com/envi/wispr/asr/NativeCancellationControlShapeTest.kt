package com.envi.wispr.asr

import java.io.File
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Drift Guard: the Android-only mutation differs solely by class name and removal of native termination. */
class NativeCancellationControlShapeTest {
    @Test fun theNativeOmissionControlRetainsEveryKotlinCheckAndNativeCall() {
        val source = File("src/main/java/com/envi/wispr/asr/TdtModel.kt").readText()
        val control = File("src/androidTest/java/com/envi/wispr/asr/NoNativeTerminationControl.kt").readLines()
        assertEquals("One native setter site controls both invocation-owned option handles", 1,
            Regex(Regex.escape("option.setTerminate(true)")).findAll(source).count())
        val sha = MessageDigest.getInstance("SHA-256").digest(source.toByteArray()).joinToString("") { "%02x".format(it) }
        assertTrue("Regenerate the private control from a checkpoint before native qualification", control.first().contains("source SHA256 $sha."))
        val expected = source.replace("TdtModel", "NoNativeTerminationControl")
            .replace("option.setTerminate(true)", "Unit /* MUTATION: native termination removed */")
        assertEquals(expected, control.drop(2).joinToString("\n") + "\n")
    }
}
