package com.envi.wispr.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Drift Guard (testing-philosophy.md), source text, for #419: a stop request keeps the microphone open for a
 * short window so the last syllable reaches the file. It proves the wiring and the ORDER of the writes, not the
 * audio; the window's own behaviour is `StopGraceWindowTest`, and the audio is proved on the phone by TTS takes
 * stopped at the last sample (`scripts/uat/silent-audio/run.py --tail-blocks 0`), which lost 105 to 133 ms
 * before this change.
 */
class StopGraceWiringTest {

    private val service = File("src/main/java/com/envi/wispr/audio/AudioCaptureService.kt").readText()

    private fun member(signature: String): String {
        val start = service.indexOf(signature)
        assertTrue("$signature must exist", start >= 0)
        val end = listOf("\n    /**", "\n    private fun ", "\n    fun ", "\n    override fun ")
            .map { service.indexOf(it, start + 1) }.filter { it > start }.minOrNull() ?: service.length
        return service.substring(start, end)
    }

    @Test
    fun theWindowIsShortAndBounded() {
        val grace = Regex("STOP_GRACE_MS = (\\d+)L").find(service)?.groupValues?.get(1)?.toLong()
        val fallback = Regex("STOP_GRACE_FALLBACK_MS = (\\d+)L").find(service)?.groupValues?.get(1)?.toLong()
        assertTrue("the window must exist and cover the measured 133 ms loss", grace != null && grace >= 150L)
        assertTrue("the window delays every finish, so it stays under half a second", grace!! <= 500L)
        assertTrue("the fallback stop must exist and be bounded", fallback != null && fallback!! in 1L..500L)
    }

    @Test
    fun theWindowIsArmedBeforeTheTakeStopsBeingRecording() {
        val claim = member("private fun claimEnding(")
        val armed = claim.indexOf("active.grace.arm(")
        val cleared = claim.indexOf("isRecording.set(false)")
        assertTrue("claimEnding must arm the window", armed >= 0)
        assertTrue("the capture thread reads both without the lock, so the window comes first", armed in 0 until cleared)
    }

    @Test
    fun onlyAStopRequestAsksForAWindow() {
        val ending = member("private fun endTakeLocked(")
        assertTrue(ending.contains("reason == TERMINAL_REASON_MANUAL && !destroyed"))
        assertEquals("the window is requested in exactly one place", 1, Regex("STOP_GRACE_MS else 0L").findAll(ending).count())
        assertTrue(ending.contains("claimEnding(active, reason, graceMs)"))
        assertFalse("the loop's own endings claim with no window", member("private fun captureLoop(").contains("graceMs"))
    }

    @Test
    fun aStalledReadIsStillUnblocked() {
        val ending = member("private fun endTakeLocked(")
        assertTrue("the fallback stop is posted off the capture thread", ending.contains("routeHandler.postDelayed("))
        assertTrue(ending.contains("STOP_GRACE_MS + STOP_GRACE_FALLBACK_MS"))
        assertTrue("the immediate stop remains for every ending that is not a stop request", ending.contains("active.record.stop()"))
    }

    @Test
    fun destroyClosesTheWindowAndStopsTheRecorderBecauseTheFallbackDiesWithTheRouteThread() {
        val destroy = member("override fun onDestroy()")
        val cancel = destroy.indexOf("active.grace.cancel()")
        val stop = destroy.indexOf("active.record.stop()")
        val quit = destroy.indexOf("routeThread.quitSafely()")
        assertTrue(cancel >= 0 && stop > cancel)
        assertTrue("both happen before the route thread is told to quit", quit > stop)
    }

    @Test
    fun theLoopReadsOnWhileTheWindowIsOpen() {
        val loop = member("private fun captureLoop(active: CaptureSession)")
        assertTrue(loop.contains("isRecording.get() || active.grace.isOpen(SystemClock.elapsedRealtime())"))
        assertFalse("the ending is still claimed once, so the window cannot change the reason", loop.contains("TERMINAL_REASON_MANUAL"))
    }
}
