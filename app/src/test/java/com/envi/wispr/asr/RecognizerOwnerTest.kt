package com.envi.wispr.asr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.After
import java.util.Collections
import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Drift Guard (#212): the recognizer's ownership and ordering. Load, every decode and the release run on
 * one worker, the release after every admitted task, never on the caller's thread; an answer finished
 * after close is discarded; a request after close is refused once. Not product coverage: the product
 * outcome is the emulator take in the plan's hardware pass.
 *
 * Every wait is a latch; its deadline exists only so a regression fails instead of hanging.
 */
class RecognizerOwnerTest {

    private class Recognizer(val name: String)

    private val events: MutableList<String> = Collections.synchronizedList(mutableListOf())
    private val freeThreads: MutableList<Thread> = Collections.synchronizedList(mutableListOf())
    private val freed = AtomicInteger(0)
    private val discards = AtomicInteger(0)

    private val fixtureWorkers = mutableListOf<ExecutorService>()
    private fun worker(): ExecutorService = Executors.newSingleThreadExecutor { Thread(it, "TestAsrWorker") }.also { fixtureWorkers += it }

    @After fun stopFixtureWorkersEvenAfterAFailedAssertion() {
        fixtureWorkers.forEach { it.shutdownNow() }
        fixtureWorkers.forEach { assertTrue("Fixture worker did not exit", it.awaitTermination(5, TimeUnit.SECONDS)) }
    }

    private class Context(val worker: ExecutorService) {
        val scheduler = com.envi.wispr.process.ManualDeadlineScheduler()
        val watchdog = AsrWatchdog(com.envi.wispr.process.EngineDeadline(scheduler)) { error(it) }
        val control = QueueWorker()
        val lane = RecognizerLane(worker, watchdog)
    }
    private val contexts = java.util.IdentityHashMap<ExecutorService, Context>()
    private val retirementSignals = java.util.IdentityHashMap<RecognizerOwner<Recognizer>, CountDownLatch>()
    private fun context(worker: ExecutorService) = contexts.getOrPut(worker) { Context(worker) }
    private fun owner(worker: ExecutorService): RecognizerOwner<Recognizer> {
        val context = context(worker)
        return RecognizerOwner(
            context.lane, context.watchdog, context.control,
            free = { freeThreads += Thread.currentThread(); freed.incrementAndGet(); events += "free" },
            discarded = { discards.incrementAndGet(); events += "discarded" },
            releaseBoundMs = UNBOUNDED,
        )
    }
    private fun request(owner: RecognizerOwner<Recognizer>, retired: (AsrRequest.Retirement) -> Unit = {
        if (it == AsrRequest.Retirement.NEVER_STARTED) events += "retired unstarted"
    }): AsrRequest = owner.request(cleanup = {}, retired = retired, controlFailed = { throw it }).also { it.registrationComplete() }

    private fun close(owner: RecognizerOwner<Recognizer>, after: () -> Unit = {}) {
        val signal = retirementSignals.getOrPut(owner) { CountDownLatch(1) }
        owner.close { after(); signal.countDown() }
    }

    private companion object {
        /** Ordering rows use the manual scheduler, whose timers run only when the fixture explicitly fires them. */
        const val UNBOUNDED = 0L
    }

    /** A wait on the worker cannot throw into the test, so a deadline it reaches is recorded and asserted. */
    private val workerWaitFailures: MutableList<String> = Collections.synchronizedList(mutableListOf())

    private fun CountDownLatch.awaitOrFail(what: String) =
        assertTrue("$what never happened", await(5, TimeUnit.SECONDS))

    private fun CountDownLatch.awaitOnWorker(what: String) {
        if (!await(5, TimeUnit.SECONDS)) workerWaitFailures += what
    }

    private fun ExecutorService.awaitTerminatedOrFail() {
        retirementSignals.values.forEach { it.awaitOrFail("actual owner retirement") }
        assertFalse("Closing an owner must not shut its borrowed worker", isShutdown)
        shutdown() // The fixture owns its process worker, after every owner's actual retirement.
        assertTrue("the fixture worker never stopped", awaitTermination(5, TimeUnit.SECONDS))
        assertEquals("a worker gate reached its deadline", emptyList<String>(), workerWaitFailures.toList())
    }

    /** Parks the worker on a task submitted straight to it; returns the latch that releases it. */
    private fun ExecutorService.park(): CountDownLatch {
        val parked = CountDownLatch(1)
        val release = CountDownLatch(1)
        execute {
            parked.countDown()
            release.awaitOnWorker("the gate's release")
        }
        parked.awaitOrFail("the gate")
        return release
    }

    private fun loaded(worker: ExecutorService): RecognizerOwner<Recognizer> {
        val owner = owner(worker)
        val done = CountDownLatch(1)
        owner.load(UNBOUNDED) { Recognizer("parakeet") }
        // A FIFO barrier after the load task: signalled once the owner has installed the recognizer.
        worker.execute { done.countDown() }
        done.awaitOrFail("the load")
        assertTrue("the owner is ready after loading", owner.isReady)
        return owner
    }

    // REVERT R1 (free inline in close) and R8 (deliver regardless of closed) turn this red.
    @Test
    fun aCloseDuringADecodeFreesOnlyAfterItAndDiscardsItsAnswer() {
        val worker = worker()
        val owner = loaded(worker)
        val inside = CountDownLatch(1)
        val finish = CountDownLatch(1)
        owner.use(UNBOUNDED, request(owner)) { _ ->
            inside.countDown()
            finish.awaitOnWorker("the decode's finish")
            events += "work-end"
            return@use { events += "delivered" }
        }
        inside.awaitOrFail("the decode")
        close(owner)
        assertEquals("nothing is freed while the decode is inside the recognizer", 0, freed.get())
        finish.countDown()
        worker.awaitTerminatedOrFail()
        assertEquals(listOf("work-end", "discarded", "free"), events.toList())
    }

    // REVERT R1 and R5 (isReady returns ready alone) turn this red.
    @Test
    fun aCloseDuringTheLoadFreesOnlyAfterTheLoad() {
        val worker = worker()
        val owner = owner(worker)
        val inside = CountDownLatch(1)
        val finish = CountDownLatch(1)
        val opened = Recognizer("parakeet")
        owner.load(UNBOUNDED) {
            inside.countDown()
            finish.awaitOnWorker("the load's finish")
            opened
        }
        inside.awaitOrFail("the load")
        // Queued straight onto the worker between the load and the release: it reads readiness in the
        // one moment a finished load has written it and the release has not yet cleared it.
        var readyBetween: Boolean? = null
        worker.execute { readyBetween = owner.isReady }
        close(owner)
        assertEquals("nothing is freed while the load is inside the recognizer", 0, freed.get())
        assertFalse(owner.isReady)
        finish.countDown()
        worker.awaitTerminatedOrFail()
        assertEquals(1, freed.get())
        assertEquals("a load that finished after close never reads as ready", false, readyBetween)
        assertFalse(owner.isReady)
    }

    // REVERT R1 and R6 (free on a new thread) turn this red.
    @Test
    fun anIdleCloseFreesOnTheWorkerOnce() {
        val worker = worker()
        val owner = loaded(worker)
        close(owner)
        worker.awaitTerminatedOrFail()
        assertEquals(1, freed.get())
        assertEquals("TestAsrWorker", freeThreads.single().name)
        assertNotEquals(Thread.currentThread(), freeThreads.single())
    }

    // REVERT R2 (drop the in-worker closed check) turns this red.
    @Test
    fun aQueuedRequestCancelledByCloseNeverRunsThenTheModelFrees() {
        val worker = worker()
        val owner = loaded(worker)
        val gate = worker.park()
        owner.use(UNBOUNDED, request(owner)) { _ ->
            events += "work"
            return@use { events += "delivered" }
        }
        close(owner)
        gate.countDown()
        worker.awaitTerminatedOrFail()
        assertEquals(listOf("retired unstarted", "free"), events.toList())
    }

    // REVERT R3 (drop the RejectedExecutionException catch) turns this red.
    @Test
    fun aRequestAfterTheOwnerClosedRetiresOnceWithoutRunning() {
        val worker = worker()
        val owner = loaded(worker)
        close(owner)
        worker.awaitTerminatedOrFail()
        val refusals = AtomicInteger(0)
        owner.use(UNBOUNDED, request(owner) { if (it == AsrRequest.Retirement.NEVER_STARTED) refusals.incrementAndGet() }) { _ ->
            events += "work"
            return@use {}
        }
        assertEquals(1, refusals.get())
        assertFalse(events.contains("work"))
    }

    @Test
    fun aLoadAfterCloseNeverOpens() {
        val worker = worker()
        val owner = owner(worker)
        close(owner)
        val opens = AtomicInteger(0)
        owner.load(UNBOUNDED) {
            opens.incrementAndGet()
            Recognizer("parakeet")
        }
        worker.awaitTerminatedOrFail()
        assertEquals(0, opens.get())
        assertEquals(0, freed.get())
    }

    /** Counts what the owner submits, so a second release task is visible even when it finds nothing to free. */
    private class CountingExecutor(private val inner: ExecutorService) : AbstractExecutorService() {
        val submissions = AtomicInteger(0)
        override fun execute(command: Runnable) {
            submissions.incrementAndGet()
            inner.execute(command)
        }
        override fun shutdown() = inner.shutdown()
        override fun shutdownNow(): MutableList<Runnable> = inner.shutdownNow()
        override fun isShutdown() = inner.isShutdown
        override fun isTerminated() = inner.isTerminated
        override fun awaitTermination(timeout: Long, unit: TimeUnit) = inner.awaitTermination(timeout, unit)
    }

    // REVERT R4 (an unconditional closed.set(true) in place of the compare-and-set) turns this red.
    @Test
    fun closeTwiceSubmitsOneReleaseAndFreesOnce() {
        val worker = CountingExecutor(worker())
        val owner = loaded(worker)
        val before = worker.submissions.get()
        close(owner)
        close(owner)
        worker.awaitTerminatedOrFail()
        assertEquals("one release task, however many closes", before + 1, worker.submissions.get())
        assertEquals(1, freed.get())
    }

    @Test
    fun isReadyStaysFalseAfterClose() {
        val worker = worker()
        val owner = loaded(worker)
        assertTrue(owner.isReady)
        close(owner)
        assertFalse(owner.isReady)
        worker.awaitTerminatedOrFail()
        assertFalse(owner.isReady)
    }

    // REVERT R6 and R9 (always discard) turn this red.
    @Test
    fun loadUseAndFreeRunOnOneWorkerInOrder() {
        val worker = worker()
        val threads: MutableList<Thread> = Collections.synchronizedList(mutableListOf())
        val owner = owner(worker)
        val delivered = CountDownLatch(1)
        owner.load(UNBOUNDED) {
            threads += Thread.currentThread()
            events += "open"
            Recognizer("parakeet")
        }
        owner.use(UNBOUNDED, request(owner)) { recognizer ->
            threads += Thread.currentThread()
            events += "use:${recognizer?.name}"
            return@use {
                threads += Thread.currentThread()
                events += "delivery"
                delivered.countDown()
            }
        }
        // Closed only once the answer is out: a close before the use ran would refuse it, by design.
        delivered.awaitOrFail("the delivery")
        close(owner)
        worker.awaitTerminatedOrFail()
        threads += freeThreads
        assertEquals(listOf("open", "use:parakeet", "delivery", "free"), events.toList())
        assertEquals("one worker thread for every touch", 1, threads.toSet().size)
    }

    // REVERT R9 (always discard) turns this red.
    @Test
    fun anAnswerFinishedWhileOpenIsDelivered() {
        val worker = worker()
        val owner = loaded(worker)
        val delivered = CountDownLatch(1)
        owner.use(UNBOUNDED, request(owner)) { _ ->
            return@use { delivered.countDown() }
        }
        delivered.awaitOrFail("the delivery")
        assertEquals(0, discards.get())
        close(owner)
        worker.awaitTerminatedOrFail()
    }

    /**
     * #357 review round 1: the close's follow-up (the service stops its watchdog there) runs after the release, on the
     * worker, and also when no recognizer was ever loaded. MUTATION m6: the follow-up only after a real release.
     */
    @Test
    fun theCloseFollowUpRunsAfterTheReleaseAndWithNothingLoaded() {
        val loadedWorker = worker()
        close(loaded(loadedWorker)) { events += "after" }
        loadedWorker.awaitTerminatedOrFail()
        assertEquals(listOf("free", "after"), events.toList())

        events.clear()
        val emptyWorker = worker()
        close(owner(emptyWorker)) { events += "after" }
        emptyWorker.awaitTerminatedOrFail()
        assertEquals(listOf("after"), events.toList())
    }

    /**
     * #357 review round 2: every worker task runs whole inside the owner's bound, named, with its bound: the load,
     * a use (its delivery included) and the release. MUTATION m7: the release outside the bound.
     */
    @Test
    fun everyWorkerTaskRunsWholeInsideTheBound() {
        val worker = worker()
        val context = context(worker)
        fun assertArmed(bound: Long) {
            val timer = context.scheduler.queue.last()
            assertEquals(bound, timer.first)
            val field = timer.second.javaClass.declaredFields.single { it.type == com.envi.wispr.process.EngineDeadline.Handle::class.java }
            field.isAccessible = true
            assertEquals(com.envi.wispr.process.EngineDeadline.State.ARMED,
                (field.get(timer.second) as com.envi.wispr.process.EngineDeadline.Handle).current)
        }
        val owner = RecognizerOwner<Recognizer>(context.lane, context.watchdog, context.control,
            free = { assertArmed(7); events += "free" },
            discarded = { events += "discarded" }, releaseBoundMs = 7L)
        owner.load(5L) { assertArmed(5); events += "open"; Recognizer("parakeet") }
        val delivered = CountDownLatch(1)
        val retired = CountDownLatch(1)
        owner.use(6L, request(owner) { retired.countDown() }) { _ ->
            assertArmed(6); events += "use"
            return@use { assertArmed(6); events += "delivered"; delivered.countDown() }
        }
        delivered.awaitOrFail("the delivery")
        retired.awaitOrFail("the whole request retirement")
        close(owner)
        worker.awaitTerminatedOrFail()
        assertEquals(listOf("open", "use", "delivered", "free"), events.toList())
        assertEquals(listOf(5L, 6L, 1_000L, 7L), context.scheduler.queue.map { it.first })
        context.scheduler.fireAll()
        assertFalse(context.watchdog.wedged)
    }

    /** Harness Contract: queued executor, matching PolishFallbackLaneTest's controlled worker. */
    private class QueueWorker : java.util.concurrent.Executor {
        val queued = java.util.ArrayDeque<Runnable>()
        override fun execute(command: Runnable) { queued.addLast(command) }
        fun runAll() { while (queued.isNotEmpty()) queued.removeFirst().run() }
    }

    @Test fun theNextGenerationWaitsForFreeAndTheEntireRetirementTail() {
        val worker = QueueWorker()
        val watchdog = AsrWatchdog(com.envi.wispr.process.EngineDeadline(com.envi.wispr.process.ManualDeadlineScheduler())) { error(it) }
        val lane = RecognizerLane(worker, watchdog)
        val first = lane.generation()
        val next = lane.generation()
        assertTrue(first.enqueue { events += "open first" })
        assertTrue(next.enqueue { events += "open next" })
        worker.runAll()
        assertEquals(listOf("open first"), events.toList())
        assertTrue(first.seal { events += "actual free" })
        worker.runAll()
        assertEquals("Free alone does not certify pending request/close-grace retirement", listOf("open first", "actual free"), events.toList())
        assertTrue(first.completeRetirement())
        worker.runAll()
        assertEquals(listOf("open first", "actual free", "open next"), events.toList())
        assertFalse(first.enqueue { error("A retired generation cannot regain admission") })
    }

    @Test fun retirementInsideTheReleaseCannotStartAnotherGenerationBeforeItsBodyExits() {
        val worker = QueueWorker()
        val watchdog = AsrWatchdog(com.envi.wispr.process.EngineDeadline(com.envi.wispr.process.ManualDeadlineScheduler())) { error(it) }
        val lane = RecognizerLane(worker, watchdog)
        val first = lane.generation()
        val next = lane.generation()
        next.enqueue { events += "open next" }
        first.seal {
            events += "free"
            assertTrue(first.completeRetirement())
            assertTrue("The old command still owns the worker", worker.queued.isEmpty())
            events += "release body exited"
        }
        worker.runAll()
        assertEquals(listOf("free", "release body exited", "open next"), events.toList())
    }

    @Test fun aWinningCloseExpiryBlocksTheNextGenerationBeforeItsKillCallback() {
        val worker = QueueWorker()
        val scheduler = com.envi.wispr.process.ManualDeadlineScheduler()
        val watchdog = AsrWatchdog(com.envi.wispr.process.EngineDeadline(scheduler)) { error("Kill callback has not run") }
        val lane = RecognizerLane(worker, watchdog)
        val first = lane.generation()
        val next = lane.generation()
        val close = checkNotNull(watchdog.begin(1_000, "owner close"))
        next.enqueue { error("A replacement must not load after old retirement expiry") }
        first.seal { events += "actual free" }
        worker.runAll()
        val timer = scheduler.queue.single().second
        val field = timer.javaClass.declaredFields.single { it.type == com.envi.wispr.process.EngineDeadline.Handle::class.java }
        field.isAccessible = true
        assertTrue((field.get(timer) as com.envi.wispr.process.EngineDeadline.Handle).expire())
        assertFalse(close.complete())
        assertFalse(first.completeRetirement())
        assertTrue(worker.queued.isEmpty())
        assertTrue(watchdog.wedged)
    }

    @Test fun aRefusedLaneCommandPoisonsTheSameAuthorityInsteadOfAdvancingGenerations() {
        val scheduler = com.envi.wispr.process.ManualDeadlineScheduler()
        val endings = mutableListOf<String>()
        val watchdog = AsrWatchdog(com.envi.wispr.process.EngineDeadline(scheduler)) { endings += it }
        val worker = java.util.concurrent.Executor { throw java.util.concurrent.RejectedExecutionException("worker unavailable") }
        val lane = RecognizerLane(worker, watchdog)
        lane.generation().enqueue { error("No command was scheduled") }
        assertTrue(watchdog.wedged)
        assertEquals(listOf("Speech worker refused command scheduling"), endings)
        assertFalse(lane.generation().enqueue { error("No successor can run") })
    }

    /** Harness Contract: the same controlled lane/timer rig, without a model or Binder fake. */
    private inner class RequestRig {
        val scheduler = com.envi.wispr.process.ManualDeadlineScheduler()
        val control = QueueWorker()
        val endings = mutableListOf<String>()
        val watchdog = AsrWatchdog(com.envi.wispr.process.EngineDeadline(scheduler)) { endings += it }
        val retirements = mutableListOf<AsrRequest.Retirement>()
        var open = true
        var onCleanup: () -> Unit = {}
        var cleanups = 0
        val request = AsrRequest(watchdog, control, { open }, { cleanups++; onCleanup() }, { retirements += it }, { events += "control failure" })
        fun ready() { request.registrationComplete() }
        fun timerStates() = scheduler.queue.map { (_, timer) ->
            val field = timer.javaClass.declaredFields.single { it.type == com.envi.wispr.process.EngineDeadline.Handle::class.java }
            field.isAccessible = true
            (field.get(timer) as com.envi.wispr.process.EngineDeadline.Handle).current
        }
    }

    @Test fun cancellationDuringRegistrationRetainsTheCleanupObligationUntilItReturns() {
        val rig = RequestRig()
        rig.request.cancel()
        assertTrue(rig.retirements.isEmpty())
        assertEquals(0, rig.cleanups)
        rig.ready()
        assertEquals(listOf(AsrRequest.Retirement.NEVER_STARTED), rig.retirements)
        assertEquals(1, rig.cleanups)
        assertFalse(rig.request.claim(50))
        rig.request.cancel()
        assertEquals(1, rig.cleanups)
        assertTrue(rig.scheduler.queue.isEmpty())
        assertTrue(rig.control.queued.isEmpty())
    }

    @Test fun cancellingAQueuedRequestMakesItsWrapperInertBeforeAnyDeadline() {
        val rig = RequestRig()
        rig.ready()
        rig.request.cancel()
        assertFalse(rig.request.claim(50))
        assertTrue(rig.scheduler.queue.isEmpty())
        assertTrue(rig.control.queued.isEmpty())
        assertEquals(listOf(AsrRequest.Retirement.NEVER_STARTED), rig.retirements)
    }

    @Test fun activeCancellationKeepsBothBoundsUntilTheBodyAndCleanupActuallyExit() {
        val rig = RequestRig()
        rig.ready()
        assertTrue(rig.request.claim(325_000))
        rig.request.cancel()
        rig.request.cancel()
        assertEquals(listOf(325_000L, 1_000L), rig.scheduler.queue.map { it.first })
        assertEquals(1, rig.control.queued.size)
        assertTrue("Logical cancellation must be visible before its queued JNI control", rig.request.cancellation.isCancelled)
        org.junit.Assert.assertThrows(AsrCancelled::class.java) { rig.request.check() }
        assertFalse(rig.request.deliver { error("Cancelled words must not be sent") })
        assertTrue(rig.retirements.isEmpty())
        rig.onCleanup = {
            assertEquals(List(2) { com.envi.wispr.process.EngineDeadline.State.ARMED }, rig.timerStates())
            assertTrue(rig.retirements.isEmpty())
        }
        rig.request.finish()
        assertEquals(List(2) { com.envi.wispr.process.EngineDeadline.State.CANCELLED }, rig.timerStates())
        assertEquals(listOf(AsrRequest.Retirement.BODY_COMPLETED), rig.retirements)
        rig.control.runAll() // A delayed exact cancellation only sees this request's detached options.
        assertTrue(rig.request.cancellation.isCancelled)
        assertTrue(rig.endings.isEmpty())
    }

    @Test fun cancellationDuringAuthorizedDeliveryKeepsTheTailWithoutAuthorizingAnotherAnswer() {
        val rig = RequestRig()
        rig.ready()
        assertTrue(rig.request.claim(50))
        assertTrue(rig.request.deliver { events += "callback entered"; rig.request.cancel() })
        assertFalse(rig.request.deliver { error("Duplicate delivery") })
        assertTrue(rig.retirements.isEmpty())
        rig.request.finish()
        assertEquals(listOf("callback entered"), events.toList())
        assertEquals(listOf(AsrRequest.Retirement.BODY_COMPLETED), rig.retirements)
    }

    @Test fun expiryDuringRetirementCleanupCannotBeReportedAsSuccessfulRetirement() {
        val rig = RequestRig()
        rig.ready()
        assertTrue(rig.request.claim(50))
        rig.request.cancel()
        rig.onCleanup = { rig.scheduler.queue.last().second.run() }
        rig.request.finish()
        assertEquals(listOf(AsrRequest.Retirement.INCOMPLETE), rig.retirements)
        assertTrue(rig.watchdog.wedged)
    }

    @Test fun failedRetirementCleanupPoisonsInsteadOfReleasingTheNextGeneration() {
        val rig = RequestRig()
        rig.ready()
        assertTrue(rig.request.claim(50))
        rig.onCleanup = { error("unlink failed") }
        rig.request.finish()
        assertEquals(listOf(AsrRequest.Retirement.INCOMPLETE), rig.retirements)
        assertTrue(rig.watchdog.wedged)
        assertEquals(1, rig.endings.size)
    }

    @Test fun actualOwnerRetirementWaitsForARegistrationThatReturnsAfterNativeFree() {
        val worker = QueueWorker()
        val scheduler = com.envi.wispr.process.ManualDeadlineScheduler()
        val watchdog = AsrWatchdog(com.envi.wispr.process.EngineDeadline(scheduler)) { error(it) }
        val lane = RecognizerLane(worker, watchdog)
        val first = RecognizerOwner<Recognizer>(lane, watchdog, worker,
            free = { events += "free old" }, discarded = {}, releaseBoundMs = 7)
        first.load(5) { Recognizer("old") }
        worker.runAll()
        val pending = first.request(cleanup = { events += "unlink old" }, retired = {}, controlFailed = { throw it })
        first.close { events += "old retired" }
        val second = RecognizerOwner<Recognizer>(lane, watchdog, worker, free = {}, discarded = {}, releaseBoundMs = 7)
        second.load(5) { events += "open new"; Recognizer("new") }
        worker.runAll()
        assertEquals(listOf("free old"), events.toList())
        assertFalse(second.isReady)
        pending.registrationComplete()
        worker.runAll()
        assertEquals(listOf("free old", "unlink old", "old retired", "open new"), events.toList())
        assertTrue(second.isReady)
        second.close()
        worker.runAll()
    }

    @Test fun anActualOwnerFreeFailureNeverAcknowledgesRetirementOrLoadsTheNextModel() {
        val worker = QueueWorker()
        val scheduler = com.envi.wispr.process.ManualDeadlineScheduler()
        val endings = mutableListOf<String>()
        val watchdog = AsrWatchdog(com.envi.wispr.process.EngineDeadline(scheduler)) { endings += it }
        val lane = RecognizerLane(worker, watchdog)
        val first = RecognizerOwner<Recognizer>(lane, watchdog, worker,
            free = { events += "free attempted"; error("native free failed") }, discarded = {}, releaseBoundMs = 7)
        first.load(5) { Recognizer("old") }
        worker.runAll()
        first.close { error("Failed free cannot acknowledge retirement") }
        val second = RecognizerOwner<Recognizer>(lane, watchdog, worker, free = {}, discarded = {}, releaseBoundMs = 7)
        second.load(5) { events += "unsafe new model"; Recognizer("new") }
        worker.runAll()
        assertEquals(listOf("free attempted"), events.toList())
        assertTrue(watchdog.wedged)
        assertEquals(listOf("Speech model release did not complete native cleanup"), endings)
        assertFalse(second.isReady)
    }
}
