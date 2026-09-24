package com.envi.wispr.history

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.envi.wispr.insertion.InsertionResults
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The real SQL behind #235's neutral row, on Room: the JVM rig mirrors these queries, and this is the one
 * place their predicates run for real. A neutral row takes an insertion outcome, a scheduled handoff promotes
 * it only while it is still neutral and pending, and recovery reads a stale neutral row as delivery unknown,
 * a stale ready row as an interrupted paste, and leaves a resolved or young row alone.
 */
@RunWith(AndroidJUnit4::class)
class TranscriptRouteDaoTest {
    private lateinit var database: EnviousWisprDatabase
    private lateinit var dao: TranscriptDao

    @Before fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            EnviousWisprDatabase::class.java,
        ).allowMainThreadQueries().build()
        dao = database.transcriptDao()
    }

    @After fun tearDown() = database.close()

    private suspend fun row(status: String, result: String, changedAtMs: Long): Long = dao.insert(
        TranscriptEntity(
            originalText = "words", finalText = "Words.", createdAtMs = changedAtMs, durationMs = 1L,
            speechEngine = "Parakeet", polishEngine = "Deterministic fallback", polishLatencyMs = 0L,
            insertionResult = result, status = status, stateChangedAtMs = changedAtMs,
        ),
    )

    private suspend fun read(id: Long): TranscriptEntity = dao.observeAll().first().single { it.id == id }

    @Test fun anOutcomeLandsOnANeutralRowAndThePromotionThenDoesNothing() = runBlocking {
        val id = row(TranscriptEntity.STATUS_SAVED_UNROUTED, "pending", 1_000L)
        assertEquals(1, dao.finalizeInsertionOutcome(id, TranscriptEntity.STATUS_COMPLETED, InsertionResults.COMMITTED, 2_000L))
        assertEquals("the promotion finds the outcome already written", 0, dao.promoteUnroutedToReady(id, 3_000L))
        assertEquals(InsertionResults.COMMITTED, read(id).insertionResult)
    }

    @Test fun thePromotionLandsFirstAndTheOutcomeStillLands() = runBlocking {
        val id = row(TranscriptEntity.STATUS_SAVED_UNROUTED, "pending", 1_000L)
        assertEquals(1, dao.promoteUnroutedToReady(id, 2_000L))
        assertEquals(TranscriptEntity.STATUS_READY_FOR_INSERTION, read(id).status)
        assertEquals(1, dao.finalizeInsertionOutcome(id, TranscriptEntity.STATUS_COMPLETED, InsertionResults.PASTED, 3_000L))
        assertEquals(InsertionResults.PASTED, read(id).insertionResult)
    }

    @Test fun recoveryReadsEachRowHonestly() = runBlocking {
        val neutral = row(TranscriptEntity.STATUS_SAVED_UNROUTED, "pending", 1_000L)
        val ready = row(TranscriptEntity.STATUS_READY_FOR_INSERTION, "pending", 1_000L)
        val copied = row(TranscriptEntity.STATUS_INSERTION_INTERRUPTED, InsertionResults.CLIPBOARD, 1_000L)
        val young = row(TranscriptEntity.STATUS_SAVED_UNROUTED, "pending", 99_000L)
        val recovered = TranscriptRepository(dao).recoverStaleOpenRows(nowMs = 100_000L, cutoffMs = 50_000L)
        assertEquals(listOf(ready), recovered.readyRowIds)
        assertEquals(TranscriptEntity.STATUS_COMPLETED, read(neutral).status)
        assertEquals(InsertionResults.DELIVERY_UNKNOWN, read(neutral).insertionResult)
        assertEquals(InsertionResults.INSERTION_INTERRUPTED, read(ready).insertionResult)
        assertEquals(InsertionResults.CLIPBOARD, read(copied).insertionResult)
        assertEquals(TranscriptEntity.STATUS_SAVED_UNROUTED, read(young).status)
        assertEquals("an outcome after recovery cannot overwrite delivery unknown", 0,
            dao.finalizeInsertionOutcome(neutral, TranscriptEntity.STATUS_COMPLETED, InsertionResults.PASTED, 101_000L))
    }

    /** #277: a processing row may already have been handed to insertion, so it is delivery unknown; a draft is not attempted. */
    @Test fun recoveryReadsAProcessingRowAsDeliveryUnknownAndADraftAsNotAttempted() = runBlocking {
        val processing = row(TranscriptEntity.STATUS_PROCESSING, "pending", 1_000L)
        val draft = row(TranscriptEntity.STATUS_DRAFT, "pending", 1_000L)
        val recovered = TranscriptRepository(dao).recoverStaleOpenRows(nowMs = 100_000L, cutoffMs = 50_000L)
        assertEquals(TranscriptEntity.STATUS_INTERRUPTED, read(processing).status)
        assertEquals(InsertionResults.DELIVERY_UNKNOWN, read(processing).insertionResult)
        assertEquals("not_attempted", read(draft).insertionResult)
        assertEquals(1, recovered.unknownCount)
    }

    /**
     * #288 on the real SQL: the user's delete marks the take in the same transaction, a late insert for that take then
     * inserts nothing, Delete all marks the rows' takes and the live ones, and a take that was never deleted still saves.
     */
    @Test fun aDeletedTakeIsNeverInsertedAgain() = runBlocking {
        val take = "0a1b2c3d-4e5f-4a6b-8c7d-9e8f7a6b5c4d"
        val live = "1a1b2c3d-4e5f-4a6b-8c7d-9e8f7a6b5c4d"
        val fresh = "2a1b2c3d-4e5f-4a6b-8c7d-9e8f7a6b5c4d"
        fun saved(takeId: String) = TranscriptEntity(
            originalText = "words", finalText = "Words.", createdAtMs = 1L, durationMs = 1L,
            speechEngine = "Parakeet", polishEngine = "Deterministic fallback", polishLatencyMs = 0L,
            insertionResult = "pending", status = TranscriptEntity.STATUS_SAVED_UNROUTED, takeId = takeId,
        )
        val id = dao.insertUnlessDeleted(saved(take))
        dao.deleteForGood(read(id))
        assertEquals("the late save inserts nothing", 0L, dao.insertUnlessDeleted(saved(take)))
        val kept = dao.insertUnlessDeleted(saved(fresh))
        assertTrue(kept > 0L)
        dao.deleteAllForGood(listOf(live))
        assertEquals(0L, dao.insertUnlessDeleted(saved(live)))
        assertEquals(0L, dao.insertUnlessDeleted(saved(fresh)))
        assertTrue(TranscriptRepository(dao).keepRescuedWords(live, "Words.", 1L))
        assertEquals(emptyList<TranscriptEntity>(), dao.observeAll().first())
        assertEquals("a take never deleted still saves", true, dao.insertUnlessDeleted(saved("3a1b2c3d-4e5f-4a6b-8c7d-9e8f7a6b5c4d")) > 0L)
    }
}
