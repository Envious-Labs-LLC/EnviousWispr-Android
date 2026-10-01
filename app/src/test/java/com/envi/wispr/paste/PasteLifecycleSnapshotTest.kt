package com.envi.wispr.paste

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PRODUCT OUTCOME (#131). When this fails, either a dictation start waits on disk before the recorder
 * appears, or the Permissions page reports a stale stop: it says auto-paste was switched off after the
 * user turned it off themselves, or misses a real switch-off.
 *
 * Real threads, because the property is about two writers racing: the initial read in the background
 * and a lifecycle write on the service's thread.
 */
class PasteLifecycleSnapshotTest {

    /**
     * The snapshot half of "session start never waits on storage": a reader gets an answer while the read
     * is held. That the session reads this snapshot and not storage is `AutoPasteWiringTest`'s row.
     */
    @Test
    fun aReadStuckOnStorageNeverBlocksAReader() {
        val release = CountDownLatch(1)
        val reading = CountDownLatch(1)
        val snapshot = PasteLifecycleSnapshot(background = { work -> Thread(work).start() })
        snapshot.load {
            reading.countDown()
            release.await()
            LastServiceStop.UNCLEAN
        }
        assertTrue("the read never started", reading.await(5, TimeUnit.SECONDS))
        assertEquals(StopMarkerState.Loading, snapshot.current.value.marker)
        assertEquals(
            "Readiness while the marker is held back must be today's two-input answer",
            AutoPasteAvailability.NOT_PERMITTED,
            AutoPasteReadiness.evaluate(AccessibilityPermissionCheck.REVOKED, snapshot.current.value),
        )
        release.countDown()
        awaitState(snapshot, StopMarkerState.Available(LastServiceStop.UNCLEAN))
    }

    /** A clean stop recorded while the initial read was in flight is newer; the older read must not win. */
    @Test
    fun aLifecycleWriteDuringTheInitialReadWins() {
        val reading = CountDownLatch(1)
        val publish = CountDownLatch(1)
        val loadFinished = CountDownLatch(1)
        val snapshot = PasteLifecycleSnapshot(background = { work -> Thread { work(); loadFinished.countDown() }.start() })
        snapshot.load {
            // The disk said unclean (the previous process's armed marker)...
            reading.countDown()
            publish.await()
            LastServiceStop.UNCLEAN
        }
        assertTrue("the read never started", reading.await(5, TimeUnit.SECONDS))
        // ...and then the user turned the service off, which recorded a clean stop.
        snapshot.recorded(LastServiceStop.CLEAN)
        publish.countDown()
        assertTrue("the read never finished", loadFinished.await(5, TimeUnit.SECONDS))
        assertEquals(StopMarkerState.Available(LastServiceStop.CLEAN), snapshot.current.value.marker)
    }

    /**
     * Code review r1 to r3: liveness and the marker are read as ONE value, and no sequence of lifecycle
     * events (an armed connect, an orderly turn-off, its clean mark, an off-and-on reconnect and its arm)
     * ever publishes unbound beside UNCLEAN while no service has died. Every published value is captured,
     * so a value that existed for an instant between two writes is seen too.
     */
    @Test
    fun noOrdinaryOffAndOnEverPublishesUnboundWithAnUncleanMarker() {
        val snapshot = PasteLifecycleSnapshot(background = { work -> work() })
        val seen = mutableListOf(snapshot.current.value)
        fun step(event: () -> Unit) { event(); seen += snapshot.current.value }
        step { snapshot.load { LastServiceStop.NEVER } }
        step { snapshot.connected() }
        step { snapshot.recorded(LastServiceStop.UNCLEAN) } // armed on connect
        step { snapshot.withdrawing() } // the user turns it off
        step { snapshot.recorded(LastServiceStop.CLEAN) }
        step { snapshot.connected() } // and on again
        step { snapshot.recorded(LastServiceStop.UNCLEAN) }
        step { snapshot.withdrawing() }
        val switchedOff = seen.filter {
            AutoPasteReadiness.evaluate(AccessibilityPermissionCheck.REVOKED, it) == AutoPasteAvailability.SWITCHED_OFF_UNEXPECTEDLY
        }
        assertEquals("published pairs that read as switched off: $seen", emptyList<PasteLifecycle>(), switchedOff)
        assertEquals(PasteLifecycle(bound = false, marker = StopMarkerState.Unavailable), snapshot.current.value)
    }

    /** The detection itself: the previous process died armed, and nothing has connected since. */
    @Test
    fun aMarkerLeftArmedByADeadProcessReadsAsSwitchedOff() {
        val snapshot = PasteLifecycleSnapshot(background = { work -> work() })
        snapshot.load { LastServiceStop.UNCLEAN }
        assertEquals(
            AutoPasteAvailability.SWITCHED_OFF_UNEXPECTEDLY,
            AutoPasteReadiness.evaluate(AccessibilityPermissionCheck.REVOKED, snapshot.current.value),
        )
    }

    @Test
    fun anUnreadableMarkerIsUnavailableUntilAWriteRestoresIt() {
        val snapshot = PasteLifecycleSnapshot(background = { work -> work() })
        snapshot.load { throw IllegalStateException("disk") }
        assertEquals(StopMarkerState.Unavailable, snapshot.current.value.marker)
        snapshot.recorded(LastServiceStop.UNCLEAN)
        assertEquals(StopMarkerState.Available(LastServiceStop.UNCLEAN), snapshot.current.value.marker)
    }

    /** Either reader may start the load; only the first reads storage. */
    @Test
    fun onlyTheFirstLoadReadsStorage() {
        var reads = 0
        val snapshot = PasteLifecycleSnapshot(background = { work -> work() })
        snapshot.load { reads++; LastServiceStop.NEVER }
        snapshot.load { reads++; LastServiceStop.UNCLEAN }
        assertEquals(1, reads)
        assertEquals(StopMarkerState.Available(LastServiceStop.NEVER), snapshot.current.value.marker)
    }

    private fun awaitState(snapshot: PasteLifecycleSnapshot, expected: StopMarkerState) = runBlocking {
        withTimeout(5_000) { snapshot.current.first { it.marker == expected } }
    }
}
