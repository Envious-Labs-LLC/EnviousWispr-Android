package com.envi.wispr.audio

import android.media.AudioDeviceInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * The warm hold observes its silence writer's exit, bounded (#257). The watch runs with a fake clock, a fake
 * scheduler that only queues, and a counting reporter; the owner rows drive the REAL `WarmHoldOwner` and its
 * `eligible`, with tracks whose writer the test holds or releases. No row sleeps.
 */
class SilenceWriterWatchTest {

    /** Queues every scheduled task with its delay; nothing runs until the test says so. */
    private class QueueScheduler {
        val queued = ArrayList<Pair<Runnable, Long>>()
        val schedule: (Runnable, Long) -> Unit = { task, delay -> queued += task to delay }
        fun runAll() = queued.toList().also { queued.clear() }.forEach { it.first.run() }
    }

    /** A track whose writer is running until the test releases it. */
    private class HeldTrack(@Volatile var exited: Boolean = false, private val failPlay: Boolean = false) : WarmHold.SilentTrack {
        var onFailed: (() -> Unit)? = null
        override fun play(onFailed: () -> Unit) { if (failPlay) throw IllegalStateException("no track"); this.onFailed = onFailed }
        override fun stop() = Unit
        override fun writerExited(): Boolean = exited
    }

    private var now = 1_000L
    private val scheduler = QueueScheduler()
    private var reports = 0
    private val watch = SilenceWriterWatch(clock = { now }, schedule = scheduler.schedule, report = { reports++ })

    // ---- the watch ---------------------------------------------------------------------------------------------

    /**
     * Row 3: a writer still running past the bound is reported once, by the sweep; later sweeps and admissions
     * report nothing more, and every later admission refuses, even after the writer exits.
     * MUTATIONS: report on every sweep; clear the latch when the writer exits.
     */
    @Test fun anOverdueWriterIsReportedOnceAndLatchesTheProcess() {
        val track = HeldTrack()
        watch.stopped(track)
        now += SilenceWriterWatch.EXIT_BOUND_MS + 1
        scheduler.runAll()
        assertEquals("reported once", 1, reports)
        watch.sweep()
        assertFalse(watch.admit())
        scheduler.runAll()
        assertEquals("never again", 1, reports)
        track.exited = true
        assertFalse("latched for the process, even after the writer exits", watch.admit())
    }

    /**
     * Row 3b: a writer still running at exactly the bound is overdue; a sweep that fires early relative to the
     * clock reschedules the remainder and reports nothing yet. MUTATION: `>` instead of `>=`.
     */
    @Test fun theBoundIsInclusiveAndAnEarlySweepComesBack() {
        val track = HeldTrack()
        watch.stopped(track)
        now += SilenceWriterWatch.EXIT_BOUND_MS - 500
        scheduler.runAll()
        assertEquals("early: nothing reported", 0, reports)
        assertEquals("the remainder is rescheduled", listOf(500L), scheduler.queued.map { it.second })
        now += 500
        scheduler.runAll()
        assertEquals("exactly at the bound is overdue", 1, reports)
    }

    /**
     * Row 7: the sweep and the report run on the watch's worker, never inline: `stopped` queues one sweep at the
     * bound; an overdue writer found by `admit` queues the report. MUTATION: call the reporter inline from `admit`.
     */
    @Test fun theSweepAndTheReportAreQueuedNeverInline() {
        watch.stopped(HeldTrack())
        assertEquals(listOf(SilenceWriterWatch.EXIT_BOUND_MS), scheduler.queued.map { it.second })
        scheduler.queued.clear()
        now += SilenceWriterWatch.EXIT_BOUND_MS
        assertFalse(watch.admit())
        assertEquals("admit did not report inline", 0, reports)
        assertEquals("it queued the report", listOf(0L), scheduler.queued.map { it.second })
        scheduler.runAll()
        assertEquals(1, reports)
    }

    /**
     * Row 5: the real writer thread with a blocked write: not exited while the write blocks after `stop()`, exited
     * once it returns; a thread never started is exited. MUTATION: `exited()` returns true.
     */
    @Test fun theWriterThreadIsExitedOnlyOnceItsBlockedWriteReturns() {
        assertTrue("never started", SilenceWriterThread(write = { 0 }, onFailed = {}).exited())
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val writer = SilenceWriterThread(write = { entered.countDown(); release.await(10, TimeUnit.SECONDS); 0 }, onFailed = {})
        writer.start()
        assertTrue(entered.await(10, TimeUnit.SECONDS))
        writer.stop()
        assertFalse("still blocked in its write", writer.exited())
        release.countDown()
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (!writer.exited()) {
            check(System.nanoTime() < deadline) { "the writer never left its loop" }
            Thread.onSpinWait() // deadline-fallback: bounds a wait on the writer thread's own exit
        }
        assertTrue(writer.exited())
    }

    // ---- the owner ---------------------------------------------------------------------------------------------

    private class NoScheduler : RouteScheduler {
        override fun post(runnable: Runnable) = Unit
        override fun postDelayed(runnable: Runnable, delayMs: Long) = Unit
        override fun removeCallbacks(runnable: Runnable) = Unit
    }

    private inline fun <reified T> stub(): T =
        T::class.java.getDeclaredConstructor().also { it.isAccessible = true }.newInstance()

    /** A route that is Bluetooth in every way `eligible` reads. */
    private fun bluetoothRoute(): TakeRoute {
        val info = stub<AudioDeviceInfo>()
        val resolved = ResolvedRoute(
            target = InputDeviceCandidate(id = 7, type = AudioDeviceInfo.TYPE_BLUETOOTH_SCO, name = "Earbuds", isSource = true, isSink = false),
            info = info,
            reason = InputRouteReason.PICKED,
            needsBluetooth = true,
            sink = info,
        )
        val effective = EffectiveDevice(InputRouteReason.PICKED).also { it.observe(AudioDeviceInfo.TYPE_BLUETOOTH_SCO, "Earbuds") }
        return TakeRoute(
            hold = RouteHold({}, {}).also { it.markCommunicationSet() },
            resolved = resolved,
            effective = effective,
            gate = LiveGate(gated = true),
            phonePicked = false,
            listenerSlot = AtomicReference(null),
            scheduler = NoScheduler(),
            unregisterDeviceCallback = {},
            tag = "test",
        )
    }

    private fun owner(watch: SilenceWriterWatch, track: () -> WarmHold.SilentTrack, keepAlive: () -> Unit = {}) = WarmHoldOwner(
        tag = "test",
        scheduler = NoScheduler(),
        locked = { it() },
        addCommListener = {},
        removeCommListener = {},
        registerDeviceCallback = {},
        unregisterDeviceCallback = {},
        newTrack = track,
        watch = watch,
        keepAlive = keepAlive,
        onIdle = {},
    )

    private fun WarmHoldOwner.admitsAHold() = eligible(bluetoothRoute(), CaptureEnding.Manual, keepEarbudsReady = true, destroyed = false)

    /**
     * Row 1: on every end path, a blocked writer denies the next hold, and its release admits it; nothing is
     * reported inside the bound. Row 2 is each path's first assertion (the real `eligible`, a Bluetooth route).
     * MUTATIONS: never record the stopped track; record only in `end`, not `handOver`; drop the liveness check.
     */
    @Test fun everyEndPathHoldsTheNextHoldUntilItsWriterExits() {
        val paths = listOf<Pair<String, (WarmHoldOwner) -> Unit>>(
            "expiry" to { it.close(WarmHold.END_EXPIRED) },
            "handover" to { it.handOver() },
            "device removal" to { it.close(WarmHold.END_DEVICE_REMOVED) },
            "destroy" to { it.close(WarmHold.END_DESTROYED) },
            "keep-alive failure" to { it.finishTake() },
        )
        for ((name, end) in paths) {
            val watch = SilenceWriterWatch(clock = { now }, schedule = QueueScheduler().schedule, report = { reports++ })
            val track = HeldTrack()
            val owner = owner(watch, { track }, keepAlive = { if (name == "keep-alive failure") throw IllegalStateException("no start") })
            assertTrue("$name: the hold starts", owner.start(bluetoothRoute()))
            end(owner)
            assertFalse("$name: the hold ended", owner.isActive)
            assertFalse("$name: a running writer holds the next hold", owner.admitsAHold())
            track.exited = true
            assertTrue("$name: its exit admits the next hold", owner.admitsAHold())
        }
        // A failed start stops a track whose writer never ran; the fake stands in for one still running.
        val watch = SilenceWriterWatch(clock = { now }, schedule = QueueScheduler().schedule, report = { reports++ })
        val failed = HeldTrack(failPlay = true)
        val owner = owner(watch, { failed })
        assertFalse("the failed start", owner.start(bluetoothRoute()))
        assertFalse("failed start: recorded", owner.admitsAHold())
        failed.exited = true
        assertTrue(owner.admitsAHold())
        assertEquals("nothing reported inside the bound", 0, reports)
    }

    /**
     * Row 4: a destroyed owner's overdue writer is reported by the next owner's first admission in the same
     * process, which refuses. MUTATION: make the owner's default a new watch instead of `PROCESS`.
     */
    @Test fun aNewOwnerInTheSameProcessSeesTheOldOwnersWriter() {
        val queue = QueueScheduler()
        val shared = SilenceWriterWatch(clock = { now }, schedule = queue.schedule, report = { reports++ })
        val track = HeldTrack()
        val first = owner(shared, { track })
        assertTrue(first.start(bluetoothRoute()))
        first.close(WarmHold.END_DESTROYED)
        queue.queued.clear() // the destroyed service's sweep never ran
        now += SilenceWriterWatch.EXIT_BOUND_MS
        val second = owner(shared, { HeldTrack() })
        assertFalse("the next owner refuses", second.admitsAHold())
        queue.runAll()
        assertEquals("and reports once", 1, reports)
        val owner = File("src/main/java/com/envi/wispr/audio/WarmHoldOwner.kt").readText()
        assertTrue("production owners share the process watch", owner.contains("private val watch: SilenceWriterWatch = SilenceWriterWatch.PROCESS,"))
    }
}
