package com.envi.wispr.asr

import com.envi.wispr.process.EngineDeadline
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Product Outcome (#357): a native load or decode in `:asr` that never returns ends the process, so the next take
 * gets a fresh one instead of queueing behind it. When this fails, the user sees every take after a stuck one end in
 * "Speech service stopped answering". Real scheduler, latches, no sleeps; each row names its mutation.
 */
class AsrWatchdogTest {
    private val scheduler = Executors.newSingleThreadScheduledExecutor()
    private val ended = CopyOnWriteArrayList<String>()
    private val endedSignal = CountDownLatch(1)
    private val watchdog = AsrWatchdog(EngineDeadline(scheduler)) { why -> ended += why; endedSignal.countDown() }

    @After fun tearDown() {
        scheduler.shutdownNow()
    }

    /** Row 1: work that finishes first returns its value and never ends the process. MUTATION m1: the bound never cancelled. */
    @Test fun workInTimeReturnsAndNeverEndsTheProcess() {
        assertEquals("words", watchdog.guard(200L, "a decode") { "words" })
        // The bound's own time passes; a cancelled bound runs nothing.
        scheduler.schedule({}, 300L, TimeUnit.MILLISECONDS).get(5, TimeUnit.SECONDS)
        assertTrue("the process was never ended: $ended", ended.isEmpty())
        assertFalse(watchdog.wedged)
    }

    /**
     * Row 2: work past its bound ends the process once, with its reason, marks the watchdog wedged, and delivers
     * nothing when it finally returns. MUTATION m2: no expiry action; m3: a late value is delivered.
     */
    @Test fun workPastItsBoundEndsTheProcessAndDeliversNothing() {
        val value = watchdog.guard(50L, "a decode") {
            assertTrue("the bound ended the process while the work was stuck", endedSignal.await(5, TimeUnit.SECONDS))
            "late words"
        }
        assertNull("a late value delivers nothing", value)
        assertEquals(listOf("a decode outlived its 50 ms bound"), ended.toList())
        assertTrue(watchdog.wedged)
    }

    /** Row 3: work that throws cancels its bound; the throw reaches the caller and the process stays. */
    @Test fun workThatThrowsCancelsItsBound() {
        val thrown = runCatching { watchdog.guard(200L, "the model load") { error("load failed") } }.exceptionOrNull()
        assertEquals("load failed", thrown?.message)
        scheduler.schedule({}, 300L, TimeUnit.MILLISECONDS).get(5, TimeUnit.SECONDS)
        assertTrue(ended.isEmpty())
    }

    /** Row 4, Drift Guard: the load and the decode both run inside the watchdog, and readiness drops once it fires. MUTATION m4: the decode unguarded. */
    @Test fun theLoadAndTheDecodeAreGuarded() {
        val service = java.io.File("src/main/java/com/envi/wispr/asr/AsrService.kt").readText()
        assertTrue(service.contains("owner.load { watchdog.guard(AsrBounds.LOAD_BOUND_MS, \"the model load\") { initRecognizer() } }"))
        val decode = service.indexOf("rec.decode(stream)")
        val guard = service.lastIndexOf("watchdog.guard(decodeBoundMs, \"a decode\")", decode)
        assertTrue("the decode runs inside the watchdog", decode >= 0 && guard >= 0 && service.indexOf('}', guard) > decode)
        assertTrue(service.contains("override fun isReady(): Boolean = owner.isReady && !watchdog.wedged"))
    }
}
