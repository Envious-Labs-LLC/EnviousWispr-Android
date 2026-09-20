package com.envi.wispr.telemetry

import com.envi.wispr.ui.TerminalReason
import com.envi.wispr.ui.TriggerSource
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Observability contract (issue #176, plan §11.2 `TakeJournalTest`): the one ordered writer applies
 * admission before any later write for the same take, captures the terminal row ONLY after its Room
 * commit changed the row, closes an earlier run's open takes as interrupted with the stage's reason,
 * and never touches the current run's. The DAO is a fake ledger of calls; expected values are literals.
 */
class TakeJournalWriterTest {

    /** Records every call in order and answers with what the test planted. */
    private class FakeDao(
        private val commitRows: Int = 1,
        private val open: List<TakeJournalEntry> = emptyList(),
        private val closeAnswers: Map<String, Boolean> = emptyMap(),
    ) : TakeJournalDao {
        val calls = mutableListOf<String>()
        override suspend fun admit(entry: TakeJournalEntry): Long { calls += "admit:${entry.takeId}:${entry.triggerSource}:${entry.stage}"; return 1 }
        override suspend fun advance(takeId: String, stage: String, seq: Int): Int { calls += "advance:$takeId:$stage:$seq"; return 1 }
        override suspend fun associateTranscript(takeId: String, transcriptId: Long): Int { calls += "associate:$takeId:$transcriptId"; return 1 }
        override suspend fun commitTerminal(takeId: String, result: String, reason: String, atMs: Long): Int { calls += "terminal:$takeId:$result:$reason"; return commitRows }
        override suspend fun openFromOtherRuns(currentRunId: String): List<TakeJournalEntry> { calls += "open:$currentRunId"; return open }
        override suspend fun find(takeId: String): TakeJournalEntry? = null
        override suspend fun takeIdForTranscript(transcriptId: Long): String? = if (transcriptId == 42L) "take-42" else null
        override suspend fun prune(cutoffMs: Long, keep: List<String>): Int { calls += "prune"; return 0 }
        override suspend fun closeInterrupted(takeId: String, reason: String, atMs: Long): Boolean {
            calls += "close:$takeId:$reason"
            return closeAnswers[takeId] ?: true
        }
    }

    private fun terminal(takeId: String, reason: TerminalReason) = AnalyticsEvent.DictationTerminal(
        takeId, reason, null, TriggerSource.TILE, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null,
    )

    private fun settle(dao: FakeDao, expectedCalls: Int) {
        val deadline = System.currentTimeMillis() + 2_000
        while (dao.calls.size < expectedCalls && System.currentTimeMillis() < deadline) Thread.sleep(5)
    }

    @Test
    fun writesForOneTakeLandInTheOrderTheyWereQueuedAndTheRowLeavesOnlyAfterItsCommit() = runBlocking {
        val dao = FakeDao()
        val captured = mutableListOf<String>()
        val writer = TakeJournalWriter(dao, "run-1", { captured += it.name + ":" + it.properties()["take_id"] }, nowMs = { 7L })
        val landed = writer.admit("t1", TriggerSource.BUBBLE_HOLD)
        writer.advance("t1", TakeStage.RECORDING)
        writer.associate("t1", 42L)
        writer.terminal(terminal("t1", TerminalReason.COMPLETED))
        assertTrue(landed.await())
        settle(dao, 4)
        assertEquals(
            listOf("admit:t1:BUBBLE_HOLD:ADMITTED", "advance:t1:RECORDING:1", "associate:t1:42", "terminal:t1:completed:COMPLETED"),
            dao.calls,
        )
        assertEquals(listOf("dictation.terminal:t1"), captured)
    }

    @Test
    fun anEndingWhoseCommitChangedNoRowSendsNothing() {
        val dao = FakeDao(commitRows = 0)
        val captured = mutableListOf<String>()
        val writer = TakeJournalWriter(dao, "run-1", { captured += it.name })
        writer.terminal(terminal("never-admitted", TerminalReason.CANCELLED_RECORDING))
        settle(dao, 1)
        assertEquals(listOf("terminal:never-admitted:cancelled:CANCELLED_RECORDING"), dao.calls)
        assertEquals(emptyList<String>(), captured)
    }

    @Test
    fun aTranscriptWithNoJournalRowIsNeverAssociated() {
        val dao = FakeDao()
        val writer = TakeJournalWriter(dao, "run-1", {})
        writer.associate("t1", 0L)
        writer.advance("t1", TakeStage.PROCESSING)
        settle(dao, 1)
        assertEquals(listOf("advance:t1:PROCESSING:2"), dao.calls)
    }

    @Test
    fun recoveryClosesEarlierRunsOpenTakesWithTheStagesReasonReportsEachOnceAndPrunes() {
        fun open(id: String, stage: TakeStage, trigger: String) = TakeJournalEntry(id, "run-old", 1L, stage.name, stage.seq, null, null, null, null, trigger)
        val dao = FakeDao(
            open = listOf(open("a", TakeStage.ADMITTED, "TILE"), open("b", TakeStage.RECORDING, "BUBBLE_TAP"), open("c", TakeStage.PROCESSING, "garbage"), open("d", TakeStage.RECORDING, "ASSIST")),
            closeAnswers = mapOf("d" to false),
        )
        val captured = mutableListOf<Map<String, Any?>>()
        val writer = TakeJournalWriter(dao, "run-now", { captured += it.properties() }, nowMs = { 99L })
        val done = CountDownLatch(1)
        writer.recoverAndPrune()
        val deadline = System.currentTimeMillis() + 2_000
        while (!dao.calls.contains("prune") && System.currentTimeMillis() < deadline) Thread.sleep(5)
        done.countDown()
        assertTrue(done.await(1, TimeUnit.SECONDS))
        assertEquals(
            listOf("open:run-now", "close:a:INTERRUPTED_STARTING", "close:b:INTERRUPTED_RECORDING", "close:c:INTERRUPTED_PROCESSING", "close:d:INTERRUPTED_RECORDING", "prune"),
            dao.calls,
        )
        assertEquals(
            listOf(
                mapOf("take_id" to "a", "stage" to "admitted", "trigger_source" to "tile"),
                mapOf("take_id" to "b", "stage" to "recording", "trigger_source" to "bubble_tap"),
                mapOf("take_id" to "c", "stage" to "processing", "trigger_source" to "unknown"),
            ),
            captured,
        )
    }

    @Test
    fun theTakeBehindARecoveredTranscriptIsLookedUpThroughTheJournal() = runBlocking {
        val writer = TakeJournalWriter(FakeDao(), "run-1", {})
        assertEquals("take-42", writer.takeIdForTranscript(42L))
        assertEquals(null, writer.takeIdForTranscript(43L))
    }
}
