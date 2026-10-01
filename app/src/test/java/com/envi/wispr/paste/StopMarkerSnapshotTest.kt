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
class StopMarkerSnapshotTest {

    /**
     * The snapshot half of "session start never waits on storage": a reader gets an answer while the read
     * is held. That the session reads this snapshot and not storage is `AutoPasteWiringTest`'s row.
     */
    @Test
    fun aReadStuckOnStorageNeverBlocksAReader() {
        val release = CountDownLatch(1)
        val reading = CountDownLatch(1)
        val snapshot = StopMarkerSnapshot(background = { work -> Thread(work).start() })
        snapshot.load {
            reading.countDown()
            release.await()
            LastServiceStop.UNCLEAN
        }
        assertTrue("the read never started", reading.await(5, TimeUnit.SECONDS))
        assertEquals(StopMarkerState.Loading, snapshot.current.value)
        assertEquals(
            "Readiness while the marker is held back must be today's two-input answer",
            AutoPasteAvailability.NOT_PERMITTED,
            AutoPasteReadiness.evaluate(AccessibilityPermissionCheck.REVOKED, false, snapshot.current.value),
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
        val snapshot = StopMarkerSnapshot(background = { work -> Thread { work(); loadFinished.countDown() }.start() })
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
        assertEquals(StopMarkerState.Available(LastServiceStop.CLEAN), snapshot.current.value)
    }

    /**
     * Code review r1: an orderly turn-off drops liveness before its clean mark lands, and in between the
     * running service's armed marker read UNCLEAN, which is the switched-off state for the user's own
     * turn-off. Withdrawing suppresses the distinction until the clean mark is recorded.
     */
    @Test
    fun anOrderlyWithdrawalNeverReadsAsSwitchedOff() {
        val snapshot = StopMarkerSnapshot(background = { work -> work() })
        snapshot.recorded(LastServiceStop.UNCLEAN) // armed on connect
        snapshot.withdrawing()
        assertEquals(
            AutoPasteAvailability.NOT_PERMITTED,
            AutoPasteReadiness.evaluate(AccessibilityPermissionCheck.REVOKED, false, snapshot.current.value),
        )
        snapshot.recorded(LastServiceStop.CLEAN)
        assertEquals(StopMarkerState.Available(LastServiceStop.CLEAN), snapshot.current.value)
    }

    @Test
    fun anUnreadableMarkerIsUnavailableUntilAWriteRestoresIt() {
        val snapshot = StopMarkerSnapshot(background = { work -> work() })
        snapshot.load { throw IllegalStateException("disk") }
        assertEquals(StopMarkerState.Unavailable, snapshot.current.value)
        snapshot.recorded(LastServiceStop.UNCLEAN)
        assertEquals(StopMarkerState.Available(LastServiceStop.UNCLEAN), snapshot.current.value)
    }

    /** Either reader may start the load; only the first reads storage. */
    @Test
    fun onlyTheFirstLoadReadsStorage() {
        var reads = 0
        val snapshot = StopMarkerSnapshot(background = { work -> work() })
        snapshot.load { reads++; LastServiceStop.NEVER }
        snapshot.load { reads++; LastServiceStop.UNCLEAN }
        assertEquals(1, reads)
        assertEquals(StopMarkerState.Available(LastServiceStop.NEVER), snapshot.current.value)
    }

    private fun awaitState(snapshot: StopMarkerSnapshot, expected: StopMarkerState) = runBlocking {
        withTimeout(5_000) { snapshot.current.first { it == expected } }
    }
}
