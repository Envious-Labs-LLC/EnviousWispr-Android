package com.envi.wispr.asr

import com.envi.wispr.process.EngineDeadline
import com.envi.wispr.process.ManualDeadlineScheduler
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
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

    /**
     * Row 4, Drift Guard: every task on the only worker runs whole inside the watchdog, by construction. The model
     * owner wraps each submitted task (its admission, work, delivery or refusal) and the release; the service hands
     * it the watchdog and a bound for the load and for each transcription, sized before the task runs. MUTATION m4:
     * the owner's submit unbounded.
     */
    @Test fun everyWorkerTaskIsBoundedByTheOwner() {
        val owner = java.io.File("src/main/java/com/envi/wispr/asr/RecognizerOwner.kt").readText()
        assertTrue(owner.contains("if (!request.claim(boundMs)) return@enqueue"))
        assertTrue(owner.contains("request.finish()"))
        assertTrue(owner.contains("watchdog.guard(releaseBoundMs, \"the recognizer release\") {"))
        val request = java.io.File("src/main/java/com/envi/wispr/asr/AsrRequest.kt").readText()
        assertTrue(request.contains("watchdog.begin(boundMs, \"a transcription\")"))
        assertTrue(request.contains("current.task.complete()"))
        val service = java.io.File("src/main/java/com/envi/wispr/asr/AsrService.kt").readText()
        assertTrue(service.contains("releaseBoundMs = AsrBounds.RELEASE_BOUND_MS,"))
        assertTrue(service.contains("owner.load(AsrBounds.LOAD_BOUND_MS) { initRecognizer() }"))
        assertEquals("both entry points pass a sized bound", 2, Regex("""owner\.use\(transcriptionBoundMs\(""").findAll(service).count())
        assertTrue(service.contains("override fun isReady(): Boolean = owner.isReady && !watchdog.wedged"))
    }

    /** Row 5: a throw after the bound is late too: it delivers nothing and never reaches the caller. MUTATION m5: the late throw rethrown. */
    @Test fun aThrowPastItsBoundDeliversNothing() {
        val value = watchdog.guard(50L, "a decode") {
            assertTrue(endedSignal.await(5, TimeUnit.SECONDS))
            error("late failure")
        }
        assertNull(value)
        assertEquals(listOf("a decode outlived its 50 ms bound"), ended.toList())
    }

    @Test fun winningExpiryIsVisibleBeforeItsActionRuns() {
        val manual = ManualDeadlineScheduler()
        val local = AsrWatchdog(EngineDeadline(manual)) { error("Expiry action has not run") }
        val answer = local.guard(200L, "a decode") {
            val timer = manual.queue.single().second
            val fields = timer.javaClass.declaredFields.filter { it.type == EngineDeadline.Handle::class.java }
            assertEquals("The actual deadline command captures one first-wins handle", 1, fields.size)
            fields.single().isAccessible = true
            val handle = fields.single().get(timer) as EngineDeadline.Handle
            assertTrue(handle.expire()) // Stage the real CAS-to-callback gap, not a delayed kill after poisoning.
            assertTrue("Expiry must block admission before the callback runs", local.wedged)
            "late"
        }
        assertNull(answer)
        assertTrue("Removing the expired handle cannot restore health", local.wedged)
    }

    @Test fun anEndingProcessNeverRunsAnotherTask() {
        val manual = ManualDeadlineScheduler()
        val local = AsrWatchdog(EngineDeadline(manual)) { }
        assertNull(local.guard(200L, "a decode") { manual.fireAll(); "late" })
        var ran = false
        assertNull(local.guard(200L, "a later decode") { ran = true; "new" })
        assertFalse("A later job cannot touch a model while the process is ending", ran)
    }

    @Test fun metadataTightensAgainstTheOriginalStartAndOldTimerCannotEndTheNextJob() {
        val manual = ManualDeadlineScheduler()
        var now = 100L
        val exits = mutableListOf<String>()
        val local = AsrWatchdog(EngineDeadline(manual), nowMs = { now }) { exits += it }
        val task = checkNotNull(local.begin(325_000, "a transcription"))
        now += 20
        assertTrue(task.tighten(50))
        assertEquals("Metadata consumed twenty milliseconds of the fifty millisecond task", 30L, manual.queue.last().first)
        val staleTimers = manual.queue.map { it.second }
        assertTrue(task.complete())
        val next = checkNotNull(local.begin(50, "next transcription"))
        staleTimers.forEach { it.run() }
        assertTrue("Both maximum and exact stale timers lost their first-wins handles", next.active)
        assertTrue(exits.isEmpty())
        assertTrue(next.complete())
    }

    @Test fun metadataThatUsedTheExactBudgetEndsBeforeNativeAdmission() {
        val manual = ManualDeadlineScheduler()
        var now = 100L
        val exits = mutableListOf<String>()
        val local = AsrWatchdog(EngineDeadline(manual), nowMs = { now }) { exits += it }
        val task = checkNotNull(local.begin(325_000, "a transcription"))
        now += 50
        assertFalse(task.tighten(50))
        assertFalse(task.active)
        assertFalse(task.complete())
        assertNull(local.begin(50, "a later transcription"))
        manual.fireAll()
        assertEquals(listOf("a transcription outlived its 50 ms bound"), exits)
    }

    @Test fun aSeparateCloseGraceStillOwnsExpiryAfterTaskTightening() {
        val manual = ManualDeadlineScheduler()
        val local = AsrWatchdog(EngineDeadline(manual), nowMs = { 100L }) { }
        val task = checkNotNull(local.begin(325_000, "a transcription"))
        val close = checkNotNull(local.begin(1_000, "owner close"))
        val closeTimer = manual.queue.last().second
        assertTrue(task.tighten(50))
        closeTimer.run()
        assertTrue(local.wedged)
        assertFalse("Finishing a task cannot erase a different retirement expiry", task.complete())
        assertFalse(close.complete())
        assertNull(local.begin(50, "replacement load"))
    }

    @Test fun incompleteCleanupPermanentlyPoisonsEvenIfTheKillActionReturns() {
        val manual = ManualDeadlineScheduler()
        val exits = mutableListOf<String>()
        val local = AsrWatchdog(EngineDeadline(manual)) { exits += it }
        val old = checkNotNull(local.begin(50, "release"))
        local.poison("Model release")
        local.poison("Options release")
        assertFalse(old.complete())
        manual.fireAll()
        assertEquals(listOf("Model release did not complete native cleanup"), exits)
        assertTrue(local.wedged)
        assertNull(local.begin(50, "replacement load"))
    }

    @Test fun removingAnExpiredScopeCannotRestoreHealthWithoutAnEarlierRead() {
        val manual = ManualDeadlineScheduler()
        val local = AsrWatchdog(EngineDeadline(manual)) { error("Expiry action has not run") }
        assertNull(local.guard(200L, "a decode") {
            val timer = manual.queue.single().second
            val field = timer.javaClass.declaredFields.single { it.type == EngineDeadline.Handle::class.java }
            field.isAccessible = true
            assertTrue((field.get(timer) as EngineDeadline.Handle).expire())
            "late without reading wedged first"
        })
        assertTrue(local.wedged)
        assertNull(local.begin(200, "replacement load"))
    }

    @Test fun failureArmingAGracePermanentlyRefusesSuccessAndReplacementWork() {
        val manual = ManualDeadlineScheduler()
        var calls = 0
        val rejecting = object : ScheduledExecutorService by manual {
            override fun schedule(command: Runnable, delay: Long, unit: TimeUnit): ScheduledFuture<*> {
                if (++calls == 2) throw RejectedExecutionException("grace scheduling failed")
                return manual.schedule(command, delay, unit)
            }
        }
        val exits = mutableListOf<String>()
        val local = AsrWatchdog(EngineDeadline(rejecting)) { exits += it }
        assertNull(local.guard(200L, "a decode") {
            org.junit.Assert.assertThrows(RejectedExecutionException::class.java) { local.begin(1_000, "cancel grace") }
            "must not publish success"
        })
        assertTrue(local.wedged)
        assertNull(local.begin(200, "replacement load"))
        assertEquals(listOf("Speech deadline control could not establish a bound"), exits)
    }

    @Test fun aBlockedProcessEndingCallbackDoesNotHoldTheAdmissionMonitor() {
        val manual = ManualDeadlineScheduler()
        val workers = Executors.newFixedThreadPool(2)
        lateinit var local: AsrWatchdog
        local = AsrWatchdog(EngineDeadline(manual)) {
            workers.submit {
                assertTrue(local.wedged)
                assertNull(local.begin(200, "replacement load"))
            }.get(5, TimeUnit.SECONDS)
        }
        try {
            val scope = checkNotNull(local.begin(200, "a decode"))
            workers.submit { manual.fireAll() }.get(5, TimeUnit.SECONDS)
            assertFalse(scope.complete())
        } finally {
            workers.shutdownNow()
            assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS))
        }
    }

}
