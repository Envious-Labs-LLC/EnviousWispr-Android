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

    /**
     * #327: going live costs the capture thread no allocation and no lock. `TakeRoute.markLive` writes the clock
     * and posts nothing (the event worker posts the route's prebuilt announce), `TakeEventPublisher.publishLive`
     * claims one of two preallocated Live slots and builds no event and offers nothing to the queue (#343), and `EffectiveDevice`'s
     * three capture-thread reads take no lock. MUTATIONS m1 (markLive posts again), m2 (the event built before the
     * claim) and m4 (a synchronized read again).
     */
    @Test
    fun goingLiveAllocatesNothingOnTheCaptureThread() {
        val route = File("src/main/java/com/envi/wispr/audio/TakeRoute.kt").readText()
        val markLive = member(route, "fun markLive()")
        listOf("post", "DebugLogger", "\"").forEach { token -> assertFalse("markLive must not contain $token", markLive.contains(token)) }
        val loop = member(service, "private fun captureLoop(active: CaptureSession)")
        assertTrue("the worker posts the route's announce", loop.contains("onDelivered = if (forced) null else active.route.announceFromWorker"))
        val device = File("src/main/java/com/envi/wispr/audio/InputDevicePick.kt").readText()
        val record = device.substringAfter("internal class EffectiveDevice(")
        listOf("val kind: InputRouteKind\n        get() = startKind", "val currentKind: InputRouteKind\n        get() = latestKind", "\n    fun reasonCode(): Int = reason.code")
            .forEach { read -> assertTrue("a lock-free capture-thread read: $read", record.contains(read)) }
        val publisher = File("src/main/java/com/envi/wispr/audio/TakeEventPublisher.kt").readText()
        val publishLive = member(publisher, "fun publishLive(")
        assertTrue("a free slot is claimed", publishLive.contains("slot.state.compareAndSet(LIVE_IDLE, LIVE_WRITING)"))
        assertTrue("a stale READY slot is replaced, only after no slot was free", publishLive.indexOf("slot.state.compareAndSet(LIVE_READY, LIVE_WRITING)") > publishLive.indexOf("slot.state.compareAndSet(LIVE_IDLE, LIVE_WRITING)"))
        val fill = member(publisher, "private fun fill(")
        listOf("Event.", "queue.", "DebugLogger", "while (").forEach { token -> assertFalse("fill must not contain $token", fill.contains(token)) }
        // #343: no queue fallback, so nothing is built and no node is offered on the capture thread. MUTATION m3.
        listOf("Event.Live(", "queue.offer(", "DebugLogger", "while (").forEach { token ->
            assertFalse("publishLive must not contain $token", publishLive.contains(token))
        }
    }
}
