package com.envi.wispr.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** The warm hold's start and every end path, against a fake track and a real RouteHold. */
class WarmHoldTest {

    private class FakeTrack(private val failPlay: Boolean = false) : WarmHold.SilentTrack {
        var plays = 0
        var stops = 0
        var onFailed: (() -> Unit)? = null
        override fun play(onFailed: () -> Unit) { if (failPlay) throw IllegalStateException("no track"); plays++; this.onFailed = onFailed }
        override fun stop() { stops++ }
    }

    private class Fixture(failPlay: Boolean = false) {
        var cleared = 0
        var removed = 0
        val route = RouteHold({ cleared++ }, { removed++ }).also { it.markCommunicationSet(); it.markListenerSet() }
        val track = FakeTrack(failPlay)
        val ends = ArrayList<String>()
        var playbackFailures = 0
        val hold = WarmHold(route = route, track = track, onEnded = { ends.add(it) }, onPlaybackFailed = { playbackFailures++ })
    }

    @Test
    fun aHoldPlaysAndStopsTheTrack() {
        val f = Fixture()
        assertTrue(f.hold.start())
        assertEquals(1, f.track.plays)
        assertTrue(f.hold.isActive)
        f.hold.end(WarmHold.END_EXPIRED)
        assertEquals(1, f.track.stops)
        assertEquals("the route is released with the hold", 1, f.cleared)
        assertEquals(listOf(WarmHold.END_EXPIRED), f.ends)
        assertFalse(f.hold.isActive)
    }

    @Test
    fun everyEndPathEndsExactlyOnce() {
        for (reason in listOf(WarmHold.END_EXPIRED, WarmHold.END_DEVICE_CHANGED, WarmHold.END_DEVICE_REMOVED, WarmHold.END_DESTROYED)) {
            val f = Fixture()
            f.hold.start()
            f.hold.end(reason)
            f.hold.end(WarmHold.END_EXPIRED)
            f.hold.end(WarmHold.END_DESTROYED)
            assertNull("no handover after an end", f.hold.handOver())
            assertEquals(reason, listOf(reason), f.ends)
            assertEquals(1, f.track.stops)
            assertEquals(1, f.cleared)
        }
    }

    @Test
    fun aHandoverKeepsTheRouteAndStopsTheTrack() {
        val f = Fixture()
        f.hold.start()
        val handed = f.hold.handOver()
        assertNotNull(handed)
        assertSame(f.route, handed)
        assertEquals("the platform request is untouched", 0, f.cleared)
        assertEquals(1, f.track.stops)
        assertEquals(listOf(WarmHold.END_NEW_TAKE), f.ends)
        assertFalse(f.hold.isActive)
        f.hold.end(WarmHold.END_EXPIRED)
        assertEquals("a later end touches nothing", 0, f.cleared)
    }

    @Test
    fun aTrackThatCannotPlayEndsTheHoldAtOnce() {
        val f = Fixture(failPlay = true)
        assertFalse(f.hold.start())
        assertFalse(f.hold.isActive)
        assertEquals(listOf(WarmHold.END_TRACK_FAILED), f.ends)
        assertEquals("the route is released, as with no hold at all", 1, f.cleared)
    }

    @Test
    fun theWindowIsThirtySeconds() {
        assertEquals(30_000L, WarmHold.HOLD_MS)
    }

    // --- RouteHold: the listener goes at every close; the communication ownership can move ---

    @Test
    fun theListenerIsReleasedAloneAndTheRouteCanBeAdopted() {
        var cleared = 0
        var removed = 0
        val old = RouteHold({ cleared++ }, { removed++ }).also { it.markCommunicationSet(); it.markListenerSet() }
        old.releaseListener()
        assertEquals(1, removed)
        assertEquals("the link stays up", 0, cleared)
        assertFalse(old.isReleased)
        val next = RouteHold({ cleared++ }, { })
        next.adoptCommunicationFrom(old)
        old.release()
        assertEquals("the old hold forgot the request", 0, cleared)
        assertEquals("the listener is not removed twice", 1, removed)
        next.release()
        assertEquals("the new hold owns the one clear", 1, cleared)
    }

    @Test
    fun adoptingFromAHoldWithNothingSetSetsNothing() {
        var cleared = 0
        val old = RouteHold({ cleared++ }, { })
        val next = RouteHold({ cleared++ }, { })
        next.adoptCommunicationFrom(old)
        next.release()
        assertEquals(0, cleared)
    }
}
