package com.envi.wispr.ui

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Product Outcome (#236): a stalled polish process can never freeze dictation. The warm-up at connect is a
 * synchronous call into `:polish`; it now runs on IO, so a warm-up that never returns leaves the take free to
 * start, stop, transcribe and land its words. Every wait here is bounded, and a held warm-up is released in
 * `finally`.
 */
class WarmUpOffMainTest {
    private val rig = DictationSessionRig()

    @After fun tearDown() = rig.close()

    /** Row 1. MUTATION: call the warm-up on main (the rig's main thread then blocks and the take never ends). */
    @Test fun aWarmUpThatNeverReturnsCannotHoldTheTake() {
        val held = CountDownLatch(1)
        rig.polish.holdWarmUp = held
        try {
            val coordinator = rig.coordinator()
            coordinator.onCreated()
            rig.command(coordinator, DictationSessionService.ACTION_START)
            rig.surface.awaitShown()
            assertTrue("the warm-up was sent", rig.polish.warmUpEntered.await(10, TimeUnit.SECONDS))
            rig.command(coordinator, DictationSessionService.ACTION_STOP)
            rig.speech.awaitRequest().onResult("hello world")
            val polish = rig.polish
            polish.awaitRequest().onOutcome(polish.outcome("Hello world."))
            assertEquals(TerminalReason.COMPLETED, rig.endings.awaitOne())
            assertEquals(1, rig.insertion.pastes.size)
            assertTrue("the warm-up ran off the main thread: ${rig.polish.warmUpThreads}", rig.polish.warmUpThreads.none { it == rig.mainThread.name })
        } finally {
            held.countDown()
        }
    }

    /** Row 2. MUTATION: drop the live-take guard (the reconnect after the take ended then warms). */
    @Test fun aReconnectAfterTheTakeEndedSendsNoWarmUp() {
        val coordinator = rig.coordinator()
        coordinator.onCreated()
        rig.command(coordinator, DictationSessionService.ACTION_START)
        rig.surface.awaitShown()
        rig.command(coordinator, DictationSessionService.ACTION_STOP)
        rig.speech.awaitRequest().onResult("hello world")
        val polish = rig.polish
        polish.awaitRequest().onOutcome(polish.outcome("Hello world."))
        rig.endings.awaitOne()
        rig.host.awaitStopped()
        val warmedBefore = polish.warmed.size
        val skipsBefore = rig.log.count("Polish warm-up not sent: take")
        rig.pipeline.reconnectPolish()
        rig.log.awaitLine("Polish warm-up not sent: take", skipsBefore + 1)
        assertEquals("no warm-up for a take that already ended", warmedBefore, polish.warmed.size)
    }

    // Shape rows: the service and setup halves, read as source (the call's thread cannot be staged in the JVM).

    private fun read(path: String) = File("src/main/java/com/envi/wispr/$path").readText()

    private fun body(text: String, start: String): String {
        val from = text.indexOf(start)
        assertTrue("$start is missing", from >= 0)
        return text.substring(from).substringBefore("\n    }\n")
    }

    /** Row 4a. MUTATION: drop the catch in the service's warm-up. */
    @Test fun theServiceLogsAFailedWarmUp() {
        val warmUp = body(read("polish/PolishService.kt"), "override fun warmUpWithPolicy(policy: PolishPolicy?)")
        assertTrue(warmUp.contains("runCatching { if (policy is PolishPolicy.LocalS1) ensureModelLoaded() }"))
        assertTrue(warmUp.contains("DebugLogger.warn(TAG, \"Polish warm-up failed:"))
    }

    /** Row 4b. MUTATION: drop the reset of `modelLoading` when the queue refuses the load. */
    @Test fun aRefusedLoadNeverLeavesLoadingStuck() {
        val ensure = body(read("polish/PolishService.kt"), "private fun ensureModelLoaded()")
        val refusal = ensure.substringAfter("catch (refused: java.util.concurrent.RejectedExecutionException)")
        assertTrue("the refusal is caught", ensure.contains("catch (refused: java.util.concurrent.RejectedExecutionException)"))
        assertTrue("and resets loading", refusal.substringBefore("}").contains("modelLoading = false"))
    }

    /** Row 3b (setup). MUTATION: send the setup warm-up outside the IO block, or never cancel it on stop. */
    @Test fun setupWarmsOnIoAndCancelsOnStop() {
        val warmUp = read("ui/EngineWarmUp.kt")
        // The IO block alone: from its opening to its own closing brace, indented as the block's opener.
        val io = warmUp.substringAfter("withContext(Dispatchers.IO) {").substringBefore("\n                }\n", "")
        assertTrue("the call itself sits inside the IO block", io.contains("service.warmUpWithPolicy(policy)"))
        assertTrue("and checks the binding is still wanted", io.contains("if (!polishBound) return@withContext"))
        val stop = body(warmUp, "fun stop()")
        assertTrue("stop cancels the warm-up job", stop.contains("warming?.cancel()"))
        assertTrue("stop clears the flag before it cancels", stop.indexOf("polishBound = false") in 0 until stop.indexOf("warming?.cancel()"))
        assertTrue("the flag is volatile", warmUp.contains("@Volatile private var polishBound"))
    }
}
