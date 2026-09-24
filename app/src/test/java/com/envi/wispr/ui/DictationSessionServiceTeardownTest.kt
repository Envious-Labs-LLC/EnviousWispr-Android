package com.envi.wispr.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Drift Guard (#306): the dictation Service's teardown invalidates the session BEFORE the language detector closes,
 * and closes the detector through the process-wide background closer, never on main. A source row, because the
 * order of two calls in `onDestroy` is the property and a Service cannot be destroyed on the JVM.
 * MUTATION m1: restore the original order (detector close first, on main).
 */
class DictationSessionServiceTeardownTest {
    private val service = File("src/main/java/com/envi/wispr/ui/DictationSessionService.kt").readText()

    @Test
    fun theSessionIsInvalidatedBeforeTheDetectorClosesOffMain() {
        val destroy = service.substringAfter("override fun onDestroy() {").substringBefore("\n    }\n")
        val owner = destroy.indexOf("coordinator.destroy()")
        val close = destroy.indexOf("BackgroundCloser.PROCESS.close(languageDetector)")
        assertTrue("the owner is destroyed in onDestroy: $destroy", owner >= 0)
        assertTrue("the detector is closed through the background closer: $destroy", close >= 0)
        assertTrue("the session is invalidated before the detector closes: $destroy", owner < close)
        assertFalse("the Service never closes the detector itself, on main", service.contains("languageDetector.close()"))
    }
}
