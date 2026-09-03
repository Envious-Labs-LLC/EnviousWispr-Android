package com.envi.wispr.audio

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class CaptureTokenTest {

    private val source = File("src/main/java/com/envi/wispr/audio/AudioCaptureService.kt").readText()

    @Test
    fun captureTokensSurviveARestartOfTheAudioProcess() {
        // A plain counter restarts at 1 when the process does, and the detector refuses anything not
        // newer than what it has seen, so every take after a restart would be refused forever. The boot
        // clock is shared between processes and keeps the order.
        val body = source.substringAfter("private fun nextCaptureToken()").substringBefore("\n    private fun ")
        assertTrue(body.contains("SystemClock.elapsedRealtimeNanos()"))
        assertTrue("and stays distinct if the clock has not ticked", body.contains("previous + 1L"))
        assertTrue("claimed atomically", body.contains("tokens.compareAndSet(previous, next)"))
        assertTrue("the take takes the ordered token", source.contains("token = nextCaptureToken(),"))
    }
}
