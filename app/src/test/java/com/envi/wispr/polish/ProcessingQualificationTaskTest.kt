package com.envi.wispr.polish

import com.envi.wispr.process.EngineDeadline
import com.envi.wispr.processing.*
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test

/** Product Outcome: cancelled, expired or destroyed checks cannot enable or leave a native model resident. */
class ProcessingQualificationTaskTest {
    private class Scheduler : ScheduledExecutorService by unsupportedScheduler() {
        val due = CopyOnWriteArrayList<Runnable>()
        override fun schedule(command: Runnable, delay: Long, unit: TimeUnit): ScheduledFuture<*> {
            assertEquals(72_000, unit.toMillis(delay))
            due += command
            return object : ScheduledFuture<Unit> {
                override fun cancel(mayInterruptIfRunning: Boolean) = true
                override fun isCancelled() = false
                override fun isDone() = false
                override fun get() = Unit
                override fun get(timeout: Long, unit: TimeUnit) = Unit
                override fun getDelay(unit: TimeUnit) = 0L
                override fun compareTo(other: Delayed) = 0
            }
        }
        fun fire() { due.forEach { it.run() } }
    }
    private class Rig {
        val scheduler = Scheduler()
        val registry = ProcessingCheckRegistry()
        val entry = registry.register(1)!!
        val poison = AtomicBoolean()
        val destroyed = AtomicBoolean()
        val calls = CopyOnWriteArrayList<String>()
        val answers = CopyOnWriteArrayList<ProcessingCheckStatus>()
        val releases = AtomicInteger()
        var close: () -> Unit = { calls += "close" }
        val task get() = ProcessingQualificationTask(EngineDeadline(scheduler), 72_000, poison, destroyed::get, { close() }, { calls += "invalidate" }, { releases.incrementAndGet() }, { calls += "exit" })
        fun run(load: () -> ProcessingCheckStatus = { calls += "load"; ProcessingCheckStatus.AVAILABLE }, canary: () -> ProcessingCheckStatus = { calls += "canary"; ProcessingCheckStatus.AVAILABLE }) = task.run(entry, load, canary, answers::add)
    }
    private fun await(latch: CountDownLatch, what: String) = assertTrue(what, latch.await(5, TimeUnit.SECONDS))
    private fun held(rig: Rig, phase: String, action: () -> Unit) {
        val entered = CountDownLatch(1); val release = CountDownLatch(1); val done = CountDownLatch(1)
        val worker = Executors.newSingleThreadExecutor()
        val gate = { entered.countDown(); await(release, "Held $phase was not released"); ProcessingCheckStatus.AVAILABLE }
        if (phase == "close") rig.close = { gate(); rig.calls += "close" }
        try {
            worker.execute { try { rig.run(load = if (phase == "load") gate else { { rig.calls += "load"; ProcessingCheckStatus.AVAILABLE } }, canary = if (phase == "canary") gate else { { rig.calls += "canary"; ProcessingCheckStatus.AVAILABLE } }) } finally { done.countDown() } }
            await(entered, "Qualifier never entered $phase")
            action()
            release.countDown(); await(done, "Qualifier did not release after $phase")
        } finally { release.countDown(); worker.shutdownNow() }
    }
    @Test fun cancellationBeforeAdmissionDoesNoNativeWork() {
        val r = Rig(); r.registry.cancel(1); r.run()
        assertTrue(r.calls.isEmpty()); assertTrue(r.answers.isEmpty()); assertEquals(1, r.releases.get())
    }
    @Test fun cancellationDuringLoadSkipsCanaryAndClosesBeforeReturning() {
        val r = Rig(); held(r, "load") { r.registry.cancel(1) }
        assertFalse(r.calls.contains("canary")); assertTrue(r.calls.contains("close")); assertTrue(r.answers.isEmpty()); assertEquals(1, r.releases.get())
    }
    @Test fun cancellationDuringCanaryClosesAndSuppressesAvailability() {
        val r = Rig(); held(r, "canary") { r.registry.cancel(1) }
        assertTrue(r.calls.contains("close")); assertTrue(r.answers.isEmpty())
    }
    @Test fun destructionDuringLoadRejectsTheQueuedCanaryAndLateAnswer() {
        val r = Rig(); held(r, "load") { r.destroyed.set(true) }
        assertFalse(r.calls.contains("canary")); assertTrue(r.answers.isEmpty()); assertTrue(r.calls.contains("close"))
    }
    @Test fun hardExpiryDuringLoadPoisonsExitsAndNeverPublishesLateSuccess() {
        val r = Rig(); held(r, "load") { r.scheduler.fire() }
        assertTrue(r.poison.get()); assertEquals(listOf(ProcessingCheckStatus.EXPIRED), r.answers); assertFalse(r.calls.contains("canary")); assertTrue(r.calls.contains("exit")); assertEquals(1, r.releases.get())
    }
    @Test fun closeIsInsideTheDeadlineAndCannotPublishPrematureAvailability() {
        val r = Rig(); held(r, "close") { assertTrue(r.answers.isEmpty()); r.scheduler.fire() }
        assertEquals(listOf(ProcessingCheckStatus.EXPIRED), r.answers); assertTrue(r.poison.get())
    }
    @Test fun cooperativeExpiryExitsWithoutReusingOrClosingTheUnhealthyRuntime() {
        val r = Rig(); r.run(canary = { ProcessingCheckStatus.EXPIRED })
        assertEquals(listOf(ProcessingCheckStatus.EXPIRED), r.answers); assertFalse(r.calls.contains("close")); assertTrue(r.poison.get()); assertTrue(r.calls.contains("exit"))
    }
    @Test fun sdkFailureIsCommonRuntimeFailureAndStillReleases() {
        val r = Rig(); r.run(load = { throw S1RuntimeInitializationException(IllegalStateException()) })
        assertEquals(listOf(ProcessingCheckStatus.RUNTIME_FAILED), r.answers); assertTrue(r.calls.contains("close")); assertFalse(r.calls.contains("canary"))
    }
    @Test fun completedQualifierClosesBeforeAQueuedRealModelCanLoad() {
        val r = Rig(); val worker = Executors.newSingleThreadExecutor(); val done = CountDownLatch(1)
        try {
            worker.execute { r.run() }
            worker.execute { r.calls += "real-load"; done.countDown() }
            await(done, "A real load queued behind the qualifier never ran")
            assertTrue(r.calls.indexOf("close") < r.calls.indexOf("real-load"))
            assertEquals(listOf(ProcessingCheckStatus.AVAILABLE), r.answers)
            r.scheduler.fire()
            assertFalse("Late timer poisoned healthy released work", r.poison.get())
            assertEquals(1, r.scheduler.due.size)
        } finally { worker.shutdownNow() }
    }
}
private fun unsupportedScheduler(): ScheduledExecutorService = java.lang.reflect.Proxy.newProxyInstance(
    ScheduledExecutorService::class.java.classLoader, arrayOf(ScheduledExecutorService::class.java),
) { _, method, _ -> throw UnsupportedOperationException(method.name) } as ScheduledExecutorService
