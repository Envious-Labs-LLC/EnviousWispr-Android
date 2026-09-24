package com.envi.wispr.history

import com.envi.wispr.ui.DictationSessionRig
import com.envi.wispr.ui.RescueOutcome
import com.envi.wispr.ui.RescuedWords
import com.envi.wispr.ui.SaveSlot
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger

/**
 * #346: the start-up History recovery's one owner, driven alone over the session rig's in-memory History. Runs are
 * counted by the telemetry sink, which each run calls once after its stale-row step. Each row names the mutation it
 * turns red on.
 */
class HistoryRecoveryCoordinatorTest {
    private val dir: File = Files.createTempDirectory("rescued-words").toFile()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val dao = DictationSessionRig.FakeTranscriptDao()
    private val repository = TranscriptRepository(dao, clock = { 2_000L })
    private val store = RescuedWords(dir, scope, wallClock = { 1_000L }, warn = {}, trackingBoundMs = 50L)
    private val runs = AtomicInteger()
    private val take = "0a1b2c3d-4e5f-4a6b-8c7d-9e8f7a6b5c4d"

    @After fun tearDown() {
        dao.holdRecovery?.complete(Unit)
        scope.cancel()
        dir.deleteRecursively()
    }

    private fun coordinator(
        recordRecovered: (TranscriptRepository.RecoveredRows) -> Unit = { runs.incrementAndGet() },
        rescueBoundMs: Long = 5_000L,
        staleBoundMs: Long = 5_000L,
    ) = HistoryRecoveryCoordinator(
        repository, store, scope, clock = { 100_000L }, log = {}, warn = {},
        recordRecovered = recordRecovered, rescueBoundMs = rescueBoundMs, staleBoundMs = staleBoundMs,
    )

    /**
     * Callers that ask while a run is in flight share ONE follow-up that begins after it; a caller after both starts
     * a fresh run. MUTATION m1: every call starts its own run.
     */
    @Test fun callersDuringARunShareOneFollowUpAndALaterCallerRunsAgain() = runBlocking {
        dao.holdRecovery = CompletableDeferred()
        val recovery = coordinator()
        val first = recovery.recover()
        val second = recovery.recover()
        val third = recovery.recover()
        assertNotSame("a caller during a run waits for a run that begins after it", first, second)
        assertSame("callers during one run share one follow-up", second, third)
        dao.holdRecovery!!.complete(Unit)
        withTimeout(10_000L) { third.await() }
        assertEquals("the first run and one follow-up", 2, runs.get())
        withTimeout(10_000L) { recovery.recover().await() }
        assertEquals("a later caller runs again, for rows and rescues that became eligible since", 3, runs.get())
    }

    /**
     * Review round 1: a follow-up cancelled before it began is never handed out again; the next caller gets a live run.
     * MUTATION m6: the cancelled follow-up stays shared.
     */
    @Test fun aCancelledFollowUpIsNeverHandedOutAgain() = runBlocking {
        dao.holdRecovery = CompletableDeferred()
        val recovery = coordinator()
        recovery.recover()
        val followUp = recovery.recover()
        followUp.cancel()
        val next = recovery.recover()
        assertNotSame(followUp, next)
        dao.holdRecovery!!.complete(Unit)
        withTimeout(10_000L) { next.await() }
        assertEquals(2, runs.get())
    }

    /**
     * Review round 1: a stale-row scan stalled on the database is cut off at its bound; the rescue step still runs,
     * and the run completes. MUTATION m7: no bound on the stale-row step.
     */
    @Test fun aStalledStaleRowScanIsCutOffAndTheRescueStillRuns() = runBlocking {
        File(dir.apply { mkdirs() }, "$take.words").writeText("1000\nKeep these words.")
        dao.holdRecovery = CompletableDeferred()
        val outcome = withTimeout(10_000L) { coordinator(staleBoundMs = 100L).recover().await() }
        assertTrue(outcome.staleFailure is HistoryRecoveryCoordinator.StaleRecoveryTimeout)
        assertEquals(1, outcome.rescued)
        dao.holdRecovery!!.complete(Unit)
        Unit
    }

    /** A failed stale-row step still runs the rescue step, and says which failed. MUTATION m2: one guard for both. */
    @Test fun aStaleRowFailureStillWritesRescuedWords() = runBlocking {
        File(dir.apply { mkdirs() }, "$take.words").writeText("1000\nKeep these words.")
        val outcome = withTimeout(10_000L) {
            coordinator(recordRecovered = { throw IllegalStateException("telemetry broke") }).recover().await()
        }
        assertTrue(outcome.staleFailure is IllegalStateException)
        assertNull(outcome.rescueFailure)
        assertEquals(1, outcome.rescued)
        assertEquals("Keep these words.", dao.rows.values.single().finalText)
    }

    /**
     * A rescue pass stalled on the database is cut off at its bound, and releases the rescue store's lock: a new take's
     * rescue write then lands. MUTATION m3: no bound on the rescue step.
     */
    @Test fun aStalledRescuePassNeverHoldsANewTakesWords() = runBlocking {
        File(dir.apply { mkdirs() }, "$take.words").writeText("1000\nKeep these words.")
        val stalled = CompletableDeferred<Unit>()
        dao.beforeGuardedInsert = { stalled.await() }
        val outcome = withTimeout(10_000L) { coordinator(rescueBoundMs = 100L).recover().await() }
        assertTrue(outcome.rescueFailure is HistoryRecoveryCoordinator.RescueRecoveryTimeout)
        val next = "1a1b2c3d-4e5f-4a6b-8c7d-9e8f7a6b5c4d"
        assertEquals(RescueOutcome.KEPT, withTimeout(5_000L) { store.keep(next, "New words.", SaveSlot()).await() })
        stalled.complete(Unit)
        assertTrue("the cut-off take's file waits for the next recovery", File(dir, "$take.words").exists())
    }
}
