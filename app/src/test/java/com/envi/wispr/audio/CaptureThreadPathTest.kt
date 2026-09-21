package com.envi.wispr.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Drift Guard (testing-philosophy.md), source text. When this fails, a future edit put an allocation, a
 * log, a lock, a wait or a binder call on the capture thread, which the user feels as dropped audio.
 *
 * Since #188 the capture loop hands every read to two owners through one `offer` each, and those two
 * bodies are the whole of what the limbs may run on the capture thread. The loop itself keeps its own
 * exit logs (the cap, the ceiling, a failure), which `SilenceStopWiringTest` pins, so the forbidden set
 * for the loop is narrower than for the offers.
 */
class CaptureThreadPathTest {

    private val service = File("src/main/java/com/envi/wispr/audio/AudioCaptureService.kt").readText()
    private val feed = File("src/main/java/com/envi/wispr/audio/DetectorFeed.kt").readText()
    private val picture = File("src/main/java/com/envi/wispr/audio/PicturePublisher.kt").readText()

    private fun member(source: String, signature: String): String {
        val start = source.indexOf(signature)
        assertTrue("$signature must exist", start >= 0)
        val end = listOf("\n    /**", "\n    private fun ", "\n    fun ", "\n    override fun ")
            .map { source.indexOf(it, start + 1) }.filter { it > start }.minOrNull() ?: source.length
        return source.substring(start, end)
    }

    @Test
    fun theCaptureThreadOffersAndNeverWaits() {
        val loop = member(service, "private fun captureLoop(active: CaptureSession)")
        assertTrue(loop.contains("active.detector.offer(buffer, bytesRead, position)"))
        assertTrue(loop.contains("active.picture.offer(buffer, bytesRead, position)"))
        val limbCalls = Regex("active\\.(detector|picture)\\.(\\w+)").findAll(loop).map { it.groupValues[2] }.toSet()
        assertEquals("the loop touches the two limbs through offer and nothing else", setOf("offer"), limbCalls)
        listOf("bindService(", "unbindService(", "Thread.sleep", ".transact", "remote.", "ByteArray(").forEach { token ->
            assertFalse("captureLoop must not contain $token", loop.contains(token))
        }

        val offers = mapOf(
            "DetectorFeed.offer" to member(feed, "fun offer(buffer: ByteArray, bytesRead: Int, position: Long)"),
            "PicturePublisher.offer" to member(picture, "fun offer(buffer: ByteArray, bytesRead: Int, position: Long)"),
        )
        val forbidden = listOf(
            "ByteArray(", "DebugLogger", "synchronized", "Thread.sleep", ".transact", "remote.",
            "bindService(", "unbindService(", "\"",
        )
        offers.forEach { (name, body) ->
            forbidden.forEach { token -> assertFalse("$name must not contain $token", body.contains(token)) }
        }
        assertTrue("the detector offer hands whole blocks to its ring", offers.getValue("DetectorFeed.offer").contains("ring.offer("))
        assertTrue("the picture offer hands the chunk to its ring", offers.getValue("PicturePublisher.offer").contains("spectrumRing.offer("))
    }
}
