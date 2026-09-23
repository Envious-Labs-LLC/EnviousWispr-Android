package com.envi.wispr.audio

import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Handler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicReference

/**
 * Harness Contract (testing-philosophy.md): the subjects are the four REAL audio owners of #188, and every
 * platform edge each one touches at close is a constructor-injected operation armed to COUNT, so a
 * second `close` that reached a platform object again would read as two, whatever the object itself
 * would have tolerated. When this fails, the user sees the earbuds' route torn down twice, a detector
 * unbound after another take bound it, or a thread interrupted that belongs to the next take.
 *
 * The Android value objects (`AudioDeviceInfo`, `AudioManager`, `Handler`) come from the SDK stubs with
 * `unitTests.isReturnDefaultValues` (app/build.gradle.kts); none of their answers is asserted here.
 */
class AudioLimbCloseTest {

    /** The route thread's stand-in: remembers what was posted and counts what was removed. */
    private class CountingScheduler : RouteScheduler {
        val posted = ArrayList<Runnable>()
        var removed = 0
        override fun post(runnable: Runnable) { posted += runnable }
        override fun postDelayed(runnable: Runnable, delayMs: Long) { posted += runnable }
        override fun removeCallbacks(runnable: Runnable) { removed++ }
    }

    private class CountingTrack : WarmHold.SilentTrack {
        var plays = 0
        var stops = 0
        override fun play() { plays++ }
        override fun stop() { stops++ }
    }

    /** An SDK stub instance: its constructor and methods return defaults, and nothing here reads them. */
    private inline fun <reified T> stub(): T =
        T::class.java.getDeclaredConstructor().also { it.isAccessible = true }.newInstance()

    private fun bluetoothRoute(hold: RouteHold, scheduler: RouteScheduler, unregister: (AudioDeviceCallback) -> Unit): TakeRoute {
        val info = stub<AudioDeviceInfo>()
        val resolved = ResolvedRoute(
            target = InputDeviceCandidate(id = 7, type = AudioDeviceInfo.TYPE_BLUETOOTH_SCO, name = "Earbuds", isSource = true, isSink = false),
            info = info,
            reason = InputRouteReason.PICKED,
            needsBluetooth = true,
            sink = info,
        )
        return TakeRoute(
            hold = hold,
            resolved = resolved,
            effective = EffectiveDevice(InputRouteReason.PICKED),
            gate = LiveGate(gated = true),
            phonePicked = false,
            listenerSlot = AtomicReference(null),
            scheduler = scheduler,
            unregisterDeviceCallback = unregister,
            tag = "test",
        )
    }

    @Test
    fun closingThePictureTwiceInterruptsTheAnalyserOnce() {
        var interrupts = 0
        val picture = PicturePublisher(ListenerSlots().slot<IAudioSpectrumListener> { it }, "test", interrupt = { interrupts++; it.interrupt() })
        picture.start(stillLive = { true })
        picture.close()
        picture.close()
        assertEquals("the analyser is interrupted exactly once", 1, interrupts)
    }

    @Test
    fun closingTheDetectorFeedTwiceInterruptsAndUnbindsOnce() {
        var binds = 0
        var unbinds = 0
        var interrupts = 0
        val feed = DetectorFeed(
            tag = "test",
            autoStop = true,
            bind = { binds++; true },
            unbind = { unbinds++ },
            interrupt = { interrupts++; it.interrupt() },
        )
        feed.start(pauseSeconds = 1.5f, token = 1L, takeId = "t", isCurrent = { true }, stillLive = { true }, endOnSilence = {})
        assertEquals("the feed bound its detector once", 1, binds)
        feed.close(unbindNow = true)
        feed.close(unbindNow = true)
        assertEquals("the feeder is interrupted exactly once", 1, interrupts)
        assertEquals("the one connection is unbound exactly once, by the close or by the feeder's exit, never both", 1, unbinds)
    }

    @Test
    fun closingTheRouteTwiceRemovesEachWatchAndReleasesTheHoldOnce() {
        var cleared = 0
        var listenerRemoved = 0
        var unregistered = 0
        val scheduler = CountingScheduler()
        val hold = RouteHold({ cleared++ }, { listenerRemoved++ }).also { it.markCommunicationSet(); it.markListenerSet() }
        val route = bluetoothRoute(hold, scheduler) { unregistered++ }
        route.watchSink(stub<AudioManager>(), Handler())
        route.armDeadline(stub<AudioManager>(), locked = { it() }, stillWaiting = { true }, onRefused = {})
        assertEquals("the deadline was armed", 1, scheduler.posted.size)
        route.close(keepRoute = false)
        route.close(keepRoute = false)
        assertEquals("the deadline is removed once", 1, scheduler.removed)
        assertEquals("the sink watch is unregistered once", 1, unregistered)
        assertEquals("the communication device is cleared once", 1, cleared)
        assertEquals("the routing listener is removed once", 1, listenerRemoved)
        assertTrue(hold.isReleased)
    }

    @Test
    fun closingTheWarmHoldTwiceEndsItOnce() {
        var cleared = 0
        var listenerRemoved = 0
        var commAdded = 0
        var commRemoved = 0
        var registered = 0
        var unregistered = 0
        var kept = 0
        var idle = 0
        val scheduler = CountingScheduler()
        val track = CountingTrack()
        val owner = WarmHoldOwner(
            tag = "test",
            scheduler = scheduler,
            locked = { it() },
            addCommListener = { commAdded++ },
            removeCommListener = { commRemoved++ },
            registerDeviceCallback = { registered++ },
            unregisterDeviceCallback = { unregistered++ },
            newTrack = { track },
            keepAlive = { kept++ },
            onIdle = { idle++ },
        )
        val hold = RouteHold({ cleared++ }, { listenerRemoved++ }).also { it.markCommunicationSet() }
        val route = bluetoothRoute(hold, scheduler) { }
        assertTrue("the hold starts", owner.start(route))
        assertTrue(owner.isActive)
        assertEquals(1, track.plays)
        assertEquals("the expiry was armed", 1, scheduler.posted.size)
        assertEquals(1, commAdded)
        assertEquals(1, registered)
        owner.close(WarmHold.END_DESTROYED)
        owner.close(WarmHold.END_DESTROYED)
        assertTrue(!owner.isActive)
        assertEquals("the silent track stops once", 1, track.stops)
        assertEquals("the expiry is removed once", 1, scheduler.removed)
        assertEquals("the communication listener is removed once", 1, commRemoved)
        assertEquals("the device callback is unregistered once", 1, unregistered)
        assertEquals("the route the hold carried is released once", 1, cleared)
        assertEquals("and the service is told it may stop, once", 1, idle)
        assertEquals("a destroyed hold never asked the service to stay", 0, kept)
    }
}
