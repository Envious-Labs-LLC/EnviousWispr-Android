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
    private class HeldTrack(
        @Volatile var exited: Boolean = false,
        private val failPlay: Boolean = false,
        @Volatile var releaseReturned: Boolean = true,
    ) : WarmHold.SilentTrack {
        var onFailed: (() -> Unit)? = null
        override fun play(onFailed: () -> Unit) { if (failPlay) throw IllegalStateException("no track"); this.onFailed = onFailed }
        override fun stop() = Unit
        override fun writerExited(): Boolean = exited
        override fun released(): Boolean = releaseReturned
    }

    private var now = 1_000L
    private val scheduler = QueueScheduler()
    private var reports = 0
    private var releaseReports = 0
    private val watch = SilenceWriterWatch(clock = { now }, schedule = scheduler.schedule, report = { reports++ }, reportRelease = { releaseReports++ })

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
        val second = HeldTrack()
        watch.stopped(second)
        now += SilenceWriterWatch.EXIT_BOUND_MS
        scheduler.runAll()
        assertFalse(watch.admit())
        scheduler.runAll()
        assertEquals("never again", 1, reports)
        track.exited = true
        second.exited = true
        assertFalse("latched for the process, even after every writer exits", watch.admit())
    }

    /**
     * Row 3c (#333): a stopped track whose writer exited but whose release did not return is reported once, as its
     * own defect, and latches the process; a stuck writer is still its own report. MUTATION m1: the watch settles a
     * track on its writer alone.
     */
    @Test fun anUnconfirmedReleaseIsReportedOnceAndLatchesTheProcess() {
        val track = HeldTrack(exited = true, releaseReturned = false)
        watch.stopped(track)
        assertFalse("still watched before the bound", watch.admit())
        now += SilenceWriterWatch.EXIT_BOUND_MS
        scheduler.runAll()
        assertEquals("reported once, as the release defect", 1, releaseReports)
        assertEquals("not as a stuck writer", 0, reports)
        val stuck = HeldTrack()
        watch.stopped(stuck)
        now += SilenceWriterWatch.EXIT_BOUND_MS
        scheduler.runAll()
        assertEquals("a stuck writer is still its own report", 1, reports)
        assertEquals("the release is never reported again", 1, releaseReports)
        track.releaseReturned = true
        stuck.exited = true
        assertFalse("latched for the process", watch.admit())
    }

    /** Row 3d (#333): a track whose writer exited and whose release returned settles with no report. */
    @Test fun aSettledTrackIsNeverReported() {
        watch.stopped(HeldTrack(exited = true))
        now += SilenceWriterWatch.EXIT_BOUND_MS
        scheduler.runAll()
        assertTrue(watch.admit())
        scheduler.runAll()
        assertEquals(0, reports)
        assertEquals(0, releaseReports)
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
     * Row 3c (code review round 1): a sweep is bound to its own stop. A writer that exited leaves its sweep with
     * nothing to reschedule, even while a newer writer is pending; the newer one has its own sweep.
     * MUTATION: reschedule against the earliest pending writer instead of the sweep's own.
     */
    @Test fun anExitedWritersSweepNeverReschedulesForANewerOne() {
        val a = HeldTrack()
        watch.stopped(a)
        now += 1_500
        watch.stopped(HeldTrack())
        a.exited = true
        now += 500
        val (aSweep, _) = scheduler.queued.removeAt(0)
        aSweep.run()
        assertEquals("only the newer writer's own sweep is queued", listOf(SilenceWriterWatch.EXIT_BOUND_MS), scheduler.queued.map { it.second })
        assertEquals(0, reports)
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
        assertTrue("the writer left its loop", writer.awaitExit(10_000))
        assertTrue(writer.exited())
    }

    // ---- the owner ---------------------------------------------------------------------------------------------

    private class NoScheduler : RouteScheduler {
        override fun post(runnable: Runnable) = Unit
        override fun postDelayed(runnable: Runnable, delayMs: Long) = Unit
        override fun removeCallbacks(runnable: Runnable) = Unit
    }

    /** Keeps the hold's expiry so a row fires it as the route thread would. */
    private class ExpiryScheduler : RouteScheduler {
        val delayed = ArrayList<Runnable>()
        override fun post(runnable: Runnable) = Unit
        override fun postDelayed(runnable: Runnable, delayMs: Long) { delayed += runnable }
        override fun removeCallbacks(runnable: Runnable) { delayed.remove(runnable) }
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

    private fun owner(
        watch: SilenceWriterWatch,
        track: () -> WarmHold.SilentTrack,
        keepAlive: () -> Unit = {},
        scheduler: RouteScheduler = NoScheduler(),
        onDeviceCallback: (android.media.AudioDeviceCallback) -> Unit = {},
    ) = WarmHoldOwner(
        tag = "test",
        scheduler = scheduler,
        locked = { it() },
        addCommListener = {},
        removeCommListener = {},
        registerDeviceCallback = onDeviceCallback,
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
        val expiry = ExpiryScheduler()
        var deviceCallback: android.media.AudioDeviceCallback? = null
        val paths = listOf<Pair<String, (WarmHoldOwner) -> Unit>>(
            // The hold's own expiry, as the route thread fires it.
            "expiry" to { expiry.delayed.single().run() },
            "handover" to { it.handOver() },
            // The platform's removal of the held sink (the stub answers the same type and name the hold holds).
            "device removal" to { checkNotNull(deviceCallback).onAudioDevicesRemoved(arrayOf(stub<AudioDeviceInfo>())) },
            "destroy" to { it.close(WarmHold.END_DESTROYED) },
            "keep-alive failure" to { it.finishTake() },
        )
        for ((name, end) in paths) {
            expiry.delayed.clear()
            deviceCallback = null
            val watch = SilenceWriterWatch(clock = { now }, schedule = QueueScheduler().schedule, report = { reports++ }, reportRelease = { releaseReports++ })
            val track = HeldTrack()
            val owner = owner(
                watch,
                { track },
                keepAlive = { if (name == "keep-alive failure") throw IllegalStateException("no start") },
                scheduler = expiry,
                onDeviceCallback = { deviceCallback = it },
            )
            assertTrue("$name: the hold starts", owner.start(bluetoothRoute()))
            end(owner)
            assertFalse("$name: the hold ended", owner.isActive)
            assertFalse("$name: a running writer holds the next hold", owner.admitsAHold())
            track.exited = true
            assertTrue("$name: its exit admits the next hold", owner.admitsAHold())
        }
        // A failed start stops a track whose writer never ran, as production's is: it admits the next hold at once.
        val watch = SilenceWriterWatch(clock = { now }, schedule = QueueScheduler().schedule, report = { reports++ }, reportRelease = { releaseReports++ })
        val owner = owner(watch, { HeldTrack(exited = true, failPlay = true) })
        assertFalse("the failed start", owner.start(bluetoothRoute()))
        assertTrue("a writer that never ran holds nothing", owner.admitsAHold())
        assertEquals("nothing reported inside the bound", 0, reports)
    }

    /**
     * Row 4: a destroyed owner's overdue writer is reported by the next owner's first admission in the same
     * process, which refuses. MUTATION: make the owner's default a new watch instead of `PROCESS`.
     */
    @Test fun aNewOwnerInTheSameProcessSeesTheOldOwnersWriter() {
        val queue = QueueScheduler()
        val shared = SilenceWriterWatch(clock = { now }, schedule = queue.schedule, report = { reports++ }, reportRelease = { releaseReports++ })
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
