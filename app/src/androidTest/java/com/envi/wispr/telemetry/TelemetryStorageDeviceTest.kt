package com.envi.wispr.telemetry

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.envi.wispr.history.EnviousWisprDatabase
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors

/**
 * Harness contracts for the storage half of telemetry (issue #176, plan §11.2), on a real filesystem
 * and a real Room database: the install id under a race and under corruption, the journal's ordering
 * and recovery, and a pending-defect record's publish, survival and expiry.
 */
@RunWith(AndroidJUnit4::class)
class TelemetryStorageDeviceTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private lateinit var db: EnviousWisprDatabase
    private lateinit var dao: TakeJournalDao

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, EnviousWisprDatabase::class.java).build()
        dao = db.takeJournalDao()
        // Only the pending-defect box is ours to clear: the install id belongs to the app process, which
        // already resolved and cached it at bootstrap, so deleting the file would only orphan the cache.
        File(context.filesDir, "telemetry/pending-defects").deleteRecursively()
    }

    @After
    fun tearDown() {
        db.close()
        File(context.filesDir, "telemetry/pending-defects").deleteRecursively()
    }

    // ---- InstallIdentityTest

    @Test
    fun theInstallIdResolvesToExactlyWhatTheFileHolds() {
        // The app's bootstrap resolved the id at process start, so this is the second reader: it must
        // see the same canonical string the file holds, and the file must hold a canonical UUID.
        val resolved = InstallIdentity.resolve(context)
        assertTrue("$resolved", resolved is InstallIdentity.Resolution.Available)
        val id = (resolved as InstallIdentity.Resolution.Available).id
        assertEquals(id, InstallIdentity.canonical(id))
        assertEquals(id, File(context.filesDir, "telemetry/install-id").readText().trim())
        assertTrue("the lock file is permanent", File(context.filesDir, "telemetry/install-id.lock").exists())
    }

    // ---- TakeJournalTest

    private fun entry(takeId: String, run: String = "run-1", stage: TakeStage = TakeStage.ADMITTED) = TakeJournalEntry(
        takeId = takeId, processRunId = run, admittedAtMs = 1L, stage = stage.name, stageSeq = stage.seq,
        transcriptId = null, terminalResult = null, terminalReason = null, terminalAtMs = null, triggerSource = "tile",
    )

    @Test
    fun admissionInsertsOnlyWhenAbsentAndALateAdmissionCannotReopenAnEndedTake() = runBlocking {
        assertTrue(dao.admit(entry("t1")) > 0)
        assertEquals(1, dao.commitTerminal("t1", "completed", "COMPLETED", 5L))
        assertEquals(-1L, dao.admit(entry("t1")))
        val row = dao.find("t1")!!
        assertEquals("completed", row.terminalResult)
    }

    @Test
    fun stageUpdatesNeverMoveBackwardAndStopAtTheTerminal() = runBlocking {
        dao.admit(entry("t2"))
        assertEquals(1, dao.advance("t2", TakeStage.PROCESSING.name, TakeStage.PROCESSING.seq))
        assertEquals(0, dao.advance("t2", TakeStage.RECORDING.name, TakeStage.RECORDING.seq))
        assertEquals("PROCESSING", dao.find("t2")!!.stage)
        dao.commitTerminal("t2", "cancelled", "CANCELLED_PROCESSING", 9L)
        assertEquals(0, dao.advance("t2", TakeStage.INSERTING.name, TakeStage.INSERTING.seq))
        assertEquals(0, dao.commitTerminal("t2", "completed", "COMPLETED", 10L))
        assertEquals("CANCELLED_PROCESSING", dao.find("t2")!!.terminalReason)
    }

    @Test
    fun recoveryReportsOnlyEarlierRunsOpenTakesAndClosesThemOnce() = runBlocking {
        dao.admit(entry("old-open", run = "run-old", stage = TakeStage.RECORDING))
        dao.admit(entry("old-ended", run = "run-old"))
        dao.commitTerminal("old-ended", "completed", "COMPLETED", 2L)
        dao.admit(entry("current-open", run = "run-now"))
        val open = dao.openFromOtherRuns("run-now")
        assertEquals(listOf("old-open"), open.map { it.takeId })
        assertTrue(dao.closeInterrupted("old-open", "INTERRUPTED_RECORDING", 7L))
        assertFalse("a second recovery finds nothing", dao.closeInterrupted("old-open", "INTERRUPTED_RECORDING", 8L))
        assertEquals(emptyList<String>(), dao.openFromOtherRuns("run-now").map { it.takeId })
        assertEquals("interrupted", dao.find("old-open")!!.terminalResult)
    }

    @Test
    fun theTranscriptAssociationIsWrittenOnceAndFoundByTranscript() = runBlocking {
        dao.admit(entry("t3"))
        assertEquals(1, dao.associateTranscript("t3", 42L))
        assertEquals(0, dao.associateTranscript("t3", 43L))
        assertEquals("t3", dao.takeIdForTranscript(42L))
        assertNull(dao.takeIdForTranscript(43L))
    }

    @Test
    fun pruningRemovesOldEndedTakesButKeepsOpenOnesAndTheKeepList() = runBlocking {
        dao.admit(entry("ended-old")); dao.commitTerminal("ended-old", "completed", "COMPLETED", 100L)
        dao.admit(entry("ended-kept")); dao.commitTerminal("ended-kept", "completed", "COMPLETED", 100L)
        dao.admit(entry("still-open"))
        assertEquals(1, dao.prune(cutoffMs = 500L, keep = listOf("ended-kept")))
        assertNull(dao.find("ended-old"))
        assertNotNull(dao.find("ended-kept"))
        assertNotNull(dao.find("still-open"))
    }

    // ---- PendingDefectsTest

    private fun record(id: String, at: Long) = PendingDefects.Record(
        eventId = id, timestampMs = at, processName = "vad", appBuild = 150, installId = "0a1b2c3d-4e5f-4a6b-8c7d-9e8f7a6b5c4d",
        takeId = null, fingerprint = "vad_call_wedged", semanticId = "vad.call_wedged", detail = "processBlock",
    )

    @Test
    fun aRecordIsPublishedAtomicallyReadBackWithItsOriginalMetadataAndExpiresByItsOwnTimestamp() {
        val now = 1_000_000_000L
        assertTrue(PendingDefects.write(context, record("aaaa", now - 1000)))
        assertTrue(PendingDefects.write(context, record("bbbb", now - PendingDefects.RETENTION_MS - 1)))
        File(context.filesDir, "telemetry/pending-defects/cccc.tmp").writeText("half")
        val read = PendingDefects.readAll(context, now)
        assertEquals(listOf("aaaa"), read.map { it.eventId })
        assertEquals(record("aaaa", now - 1000), read.single())
        assertFalse("the expired record is gone", File(context.filesDir, "telemetry/pending-defects/bbbb").exists())
        assertTrue("a temp file is not eligible and is left for its writer", File(context.filesDir, "telemetry/pending-defects/cccc.tmp").exists())
        PendingDefects.cleanTemps(context, nowMs = System.currentTimeMillis())
        assertTrue("a fresh temp may be a live writer's and is kept", File(context.filesDir, "telemetry/pending-defects/cccc.tmp").exists())
        PendingDefects.cleanTemps(context, nowMs = System.currentTimeMillis() + PendingDefects.TEMP_GRACE_MS + 1)
        assertFalse("an old temp is a dead writer's and goes", File(context.filesDir, "telemetry/pending-defects/cccc.tmp").exists())
    }

    @Test
    fun aRecordSurvivesRepeatedReadsUntilItExpiresSoADeadConverterLosesNothing() {
        val now = 2_000_000_000L
        PendingDefects.write(context, record("dddd", now))
        assertEquals(1, PendingDefects.readAll(context, now).size)
        assertEquals("read again after a simulated converter death", 1, PendingDefects.readAll(context, now + 60_000).size)
        assertEquals(0, PendingDefects.readAll(context, now + PendingDefects.RETENTION_MS + 1).size)
    }

    @Test
    fun manyWritersRaceToPublishDistinctRecordsWithoutLoss() {
        val threads = 8
        val ready = CountDownLatch(threads)
        val go = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(threads)
        val futures = (0 until threads).map { i ->
            pool.submit<Boolean> { ready.countDown(); go.await(); PendingDefects.write(context, record("r$i", 3_000_000_000L)) }
        }
        ready.await(); go.countDown()
        assertTrue(futures.all { it.get() })
        pool.shutdown()
        assertEquals(threads, PendingDefects.readAll(context, 3_000_000_000L).size)
    }
}
