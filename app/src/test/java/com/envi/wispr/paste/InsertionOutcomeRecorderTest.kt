package com.envi.wispr.paste

import com.envi.wispr.history.DeletedTake
import com.envi.wispr.history.Enqueued
import com.envi.wispr.history.HistoryRow
import com.envi.wispr.history.TranscriptDao
import com.envi.wispr.history.TranscriptEntity
import com.envi.wispr.history.TranscriptRepository
import com.envi.wispr.history.WriteKind
import com.envi.wispr.insertion.ClipboardOutcome
import com.envi.wispr.insertion.InsertionResults
import com.envi.wispr.insertion.WordsKept
import com.envi.wispr.telemetry.AnalyticsEvent
import com.envi.wispr.telemetry.InsertionResultKind
import com.envi.wispr.telemetry.InsertionRouteKind
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Observability Contract with a History half (#359, carrying #176 G1 D3, #277 and #292): an accepted insertion's
 * ending is recorded once, the History outcome and the `insertion.terminal` event carrying the same value, and only
 * by the writer that won the row. When this fails, History and the analytics disagree about where the words went,
 * or one insertion is reported twice. Each row names its mutation.
 */
class InsertionOutcomeRecorderTest {
    private class OutcomeDao(private val answer: Int) : TranscriptDao {
        val outcomes = mutableListOf<String>()
        val statusUpdates = mutableListOf<String>()
        /** The timings each outcome write carried (#378 D8): insertion, then end to end. */
        val timings = mutableListOf<Pair<Long?, Long?>>()
        override fun observeAll(): Flow<List<TranscriptEntity>> = flowOf(emptyList())
        override suspend fun insert(transcript: TranscriptEntity): Long = 1L
        override suspend fun updateRecovered(transcript: TranscriptEntity): Int = error("Use the real Room recovery fixture")
        override suspend fun findByTakeId(takeId: String): TranscriptEntity? = null
        override suspend fun insertDeletedTakes(takes: List<DeletedTake>) = Unit
        override suspend fun isTakeDeleted(takeId: String): Boolean = false
        override suspend fun rowTakeIds(): List<String> = emptyList()
        override suspend fun setKept(id: Long, kept: Boolean) = Unit
        override suspend fun delete(transcript: TranscriptEntity) = Unit
        override suspend fun deleteAll() = Unit
        override suspend fun deleteById(id: Long): Int = 1
        override suspend fun deleteWordlessRows(): Int = 0
        override suspend fun updateStatus(id: Long, status: String, stateChangedAtMs: Long, interrupted: Boolean, insertionResult: String?): Int {
            statusUpdates += "$id:$status:$insertionResult:$interrupted"
            return 1
        }
        override suspend fun finalize(id: Long, originalText: String, finalText: String, speechEngine: String, polishEngine: String, polishLatencyMs: Long, insertionResult: String, durationMs: Long, stateChangedAtMs: Long, polishReason: String, polishStatus: Int, polishContext: String, captureDevice: String, status: String, interrupted: Boolean, liveAfterMs: Long?, asrMs: Long?): Int = 1
        override suspend fun finalizeInsertionOutcome(id: Long, status: String, result: String, stateChangedAtMs: Long, interrupted: Boolean, insertionMs: Long?, endToEndMs: Long?): Int {
            outcomes += "$id:$status:$result:$interrupted"
            timings += insertionMs to endToEndMs
            return answer
        }
        override suspend fun recoverStaleDrafts(cutoffMs: Long, nowMs: Long): Int = 0
        override suspend fun recoverStaleProcessingRows(cutoffMs: Long, nowMs: Long): Int = 0
        override suspend fun recoverStaleReadyRows(cutoffMs: Long, nowMs: Long): Int = 0
        override suspend fun staleReadyRowIds(cutoffMs: Long): List<Long> = emptyList()
        override suspend fun promoteUnroutedToReady(id: Long, nowMs: Long): Int = 1
        override suspend fun recoverStaleUnroutedRows(cutoffMs: Long, nowMs: Long): Int = 0
    }

    private class SavedRow(private val id: Long) : HistoryRow {
        override fun resolveOnQueue(): Long = id
        override val savedNow: Boolean = id > 0L
        override fun wordsKept(): WordsKept = WordsKept.LOST
    }

    private val events = mutableListOf<AnalyticsEvent>()
    private val crumbs = mutableListOf<Map<String, String?>>()
    private val warnings = mutableListOf<String>()
    private val terminals = mutableListOf<Pair<String, String>>()

    private fun recorder(
        dao: OutcomeDao,
        admitted: Enqueued = Enqueued.ACCEPTED,
        onTerminal: (String, String) -> Unit = { takeId, result -> terminals += takeId to result },
    ) = InsertionOutcomeRecorder(
        enqueue = { _, kind, body ->
            assertEquals("an outcome is a take's last word on its row", WriteKind.TERMINAL, kind)
            if (admitted == Enqueued.ACCEPTED) runBlocking { body(TranscriptRepository(dao, clock = { 1_000L })) }
            admitted
        },
        capture = { events += it },
        outcomeTrail = { crumbs += it },
        warn = { warnings += it },
        onTerminal = onTerminal,
    )

    private fun ending(row: HistoryRow, takeId: String? = "take-1", endToEndMs: Long? = null) = InsertionEnding(
        row = row,
        takeId = takeId,
        targetPackage = "com.google.android.gm",
        status = TranscriptEntity.STATUS_INSERTION_INTERRUPTED,
        result = InsertionResults.COMMITTED,
        interrupted = true,
        clipboard = ClipboardOutcome.COPIED,
        latencyMs = 420L,
        endToEndMs = endToEndMs,
    )

    /** #378 D8. MUTATION: drop the timings from the outcome write. The row keeps the insertion's and the take's times. */
    @Test fun theOutcomeWriteCarriesTheInsertionAndEndToEndTimes() {
        val dao = OutcomeDao(answer = 1)
        recorder(dao).record(ending(SavedRow(7L), endToEndMs = 4_400L))
        assertEquals(listOf<Pair<Long?, Long?>>(420L to 4_400L), dao.timings)
    }

    /** Row 1: the writer that wins the row writes the outcome and reports it once, with the same value. */
    @Test fun theWinningWriterRecordsAndReportsOnce() {
        val dao = OutcomeDao(answer = 1)
        recorder(dao).record(ending(SavedRow(7L)))
        assertEquals(listOf("7:${TranscriptEntity.STATUS_INSERTION_INTERRUPTED}:${InsertionResults.COMMITTED}:true"), dao.outcomes)
        val event = events.single() as AnalyticsEvent.InsertionTerminal
        assertEquals("the event carries the History row's result", InsertionResultKind.COMMITTED, event.result)
        assertEquals(InsertionRouteKind.COMMIT, event.route)
        assertEquals(false, event.recovered)
        assertEquals("take-1", event.takeId)
        assertEquals("com.google.android.gm", event.targetApp)
        assertEquals(420L, event.latencyMs)
        assertEquals("copied", event.clipboard)
        assertEquals(InsertionHandoff.SCHEDULED, event.handoff)
        assertEquals(listOf(mapOf("take_id" to "take-1", "result" to InsertionResults.COMMITTED, "target_app" to "com.google.android.gm")), crumbs)
    }

    /** Row 2: a row another writer already won reports nothing from here. MUTATION m1: report whatever the write said. */
    @Test fun aLosingWriterReportsNothing() {
        val dao = OutcomeDao(answer = 0)
        recorder(dao).record(ending(SavedRow(7L)))
        assertEquals(1, dao.outcomes.size)
        assertTrue("the winner reports, not this writer", events.isEmpty() && crumbs.isEmpty())
    }

    /** Row 3: a refused write (#292) still reports the insertion once. MUTATION m2: a refused write reports nothing. */
    @Test fun aRefusedWriteStillReportsOnce() {
        val dao = OutcomeDao(answer = 1)
        recorder(dao, admitted = Enqueued.REJECTED).record(ending(SavedRow(7L)))
        assertTrue(dao.outcomes.isEmpty())
        val event = events.single() as AnalyticsEvent.InsertionTerminal
        assertEquals("take-1", event.takeId)
        assertEquals(InsertionResultKind.COMMITTED, event.result)
    }

    /** Row 4: a debug probe (no take, no row) leaves a breadcrumb and no event or write. */
    @Test fun aProbeLeavesOnlyABreadcrumb() {
        val dao = OutcomeDao(answer = 1)
        recorder(dao).record(ending(HistoryRow.None, takeId = null))
        assertTrue(dao.outcomes.isEmpty())
        assertTrue(events.isEmpty())
        assertEquals(listOf(mapOf("take_id" to null, "result" to InsertionResults.COMMITTED, "target_app" to "com.google.android.gm")), crumbs)
    }

    /** Row 5, Drift Guard: the runner no longer writes History or telemetry itself. MUTATION m3: the runner captures an event again. */
    @Test fun theRunnerHandsItsEndingToTheRecorder() {
        val runner = java.io.File("src/main/java/com/envi/wispr/paste/AccessibilityInsertionRunner.kt").readText()
        for (gone in listOf("Telemetry.", "historyWrites(", "finalizeInsertionOutcome(", "AnalyticsEvent")) {
            assertTrue("the runner no longer holds $gone", !runner.contains(gone))
        }
        assertTrue(runner.contains("outcomes.record("))
    }

    /** #17: verified History recovery marks the row completed without emitting a duplicate terminal event; failed recovery leaves the row retryable. */
    @Test fun historyRecoveryUpdatesRowOnlyOnVerifiedDelivery() {
        val dao = OutcomeDao(answer = 1)
        val verifiedRecovery = InsertionEnding(
            row = HistoryRow.Saved,
            takeId = null,
            targetPackage = "com.google.android.gm",
            status = TranscriptEntity.STATUS_COMPLETED,
            result = InsertionResults.COMMITTED,
            interrupted = false,
            clipboard = null,
            latencyMs = 180L,
            recoveryTranscriptId = 19L,
        )
        recorder(dao).record(verifiedRecovery)
        assertEquals(listOf("19:completed:committed:false"), dao.statusUpdates)
        assertTrue("recovery must not call finalizeInsertionOutcome", dao.outcomes.isEmpty())
        assertTrue("recovery must not emit duplicate insertion.terminal", events.isEmpty())

        dao.statusUpdates.clear()
        val failedRecovery = verifiedRecovery.copy(
            status = TranscriptEntity.STATUS_INSERTION_INTERRUPTED,
            result = InsertionResults.COPY_ONLY,
            interrupted = true,
            clipboard = ClipboardOutcome.COPIED,
        )
        recorder(dao).record(failedRecovery)
        assertTrue("failed recovery leaves row unchanged", dao.statusUpdates.isEmpty())
        assertTrue(events.isEmpty())

        val unverifiedRecovery = verifiedRecovery.copy(
            status = TranscriptEntity.STATUS_INSERTION_INTERRUPTED,
            result = InsertionResults.COPY_ONLY_UNVERIFIED,
            interrupted = true,
            clipboard = ClipboardOutcome.COPIED,
        )
        recorder(dao).record(unverifiedRecovery)
        assertEquals(listOf("19:insertion_interrupted:copy_only_unverified:true"), dao.statusUpdates)
        assertTrue(
            "unverified recovery is flagged in session state",
            19L in com.envi.wispr.ui.HistoryInsertionRecovery.sessionUnverifiedIds.value,
        )
        com.envi.wispr.ui.HistoryInsertionRecovery.resetSessionRecoveredForTest()

        recorder(dao, admitted = Enqueued.REJECTED).record(verifiedRecovery)
        assertTrue("rejected recovery write logs a warning", warnings.any { it.contains("19") })
    }

    /** #560, #563: live takes notify the terminal observer independently of History save and survive observer errors. */
    @Test fun liveTakesNotifyTerminalObserverIndependentlyOfHistoryAndStayLimbSafe() {
        val dao = OutcomeDao(answer = 1)
        recorder(dao).record(ending(SavedRow(7L), takeId = "take-saved"))
        recorder(dao).record(ending(HistoryRow.None, takeId = "take-nosave"))
        recorder(dao, admitted = Enqueued.REJECTED).record(ending(SavedRow(8L), takeId = "take-rejected"))
        assertEquals(
            listOf(
                "take-saved" to InsertionResults.COMMITTED,
                "take-nosave" to InsertionResults.COMMITTED,
                "take-rejected" to InsertionResults.COMMITTED,
            ),
            terminals,
        )

        // Throwing observer must never prevent History write or InsertionTerminal event (limb-safe).
        val throwingDao = OutcomeDao(answer = 1)
        events.clear()
        recorder(throwingDao, onTerminal = { _, _ -> error("observer failure") })
            .record(ending(SavedRow(9L), takeId = "take-limb"))
        assertEquals(1, throwingDao.outcomes.size)
        assertEquals(1, events.size)
    }

    /** #560, #563: History recovery retries and debug probes (null takeId) do not notify the live terminal observer. */
    @Test fun recoveryAndDebugProbeDoNotNotifyTerminalObserver() {
        val dao = OutcomeDao(answer = 1)
        recorder(dao).record(ending(HistoryRow.None, takeId = null))
        recorder(dao).record(
            InsertionEnding(
                row = HistoryRow.Saved,
                takeId = "take-recovery",
                targetPackage = "com.google.android.gm",
                status = TranscriptEntity.STATUS_COMPLETED,
                result = InsertionResults.COMMITTED,
                interrupted = false,
                clipboard = null,
                latencyMs = 100L,
                recoveryTranscriptId = 21L,
            ),
        )
        assertTrue("recovery and probe must not fire live terminal observer", terminals.isEmpty())
    }
}
