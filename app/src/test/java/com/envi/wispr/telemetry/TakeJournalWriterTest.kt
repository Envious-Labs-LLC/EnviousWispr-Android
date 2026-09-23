package com.envi.wispr.telemetry

import com.envi.wispr.ui.TerminalReason
import com.envi.wispr.ui.TriggerSource
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
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
        /** Every call in order; written on the writer's worker, read by the test, so synchronized and signalled. */
        val calls: MutableList<String> = java.util.Collections.synchronizedList(mutableListOf())
        private val landed = Object()

        /** Waits, bounded, until [n] calls have landed; a named failure, never a silent return, when they do not. */
        fun awaitCalls(n: Int) {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
            synchronized(landed) {
                while (calls.size < n) {
                    val left = deadline - System.nanoTime()
                    check(left > 0) { "only ${calls.size} of $n journal calls landed: $calls" }
                    TimeUnit.NANOSECONDS.timedWait(landed, left)
                }
            }
        }

        private fun record(call: String) {
            synchronized(landed) {
                calls += call
                landed.notifyAll()
            }
        }

        override suspend fun admit(entry: TakeJournalEntry): Long { record("admit:${entry.takeId}:${entry.triggerSource}:${entry.stage}"); return 1 }
        override suspend fun advance(takeId: String, stage: String, seq: Int): Int { record("advance:$takeId:$stage:$seq"); return 1 }
        override suspend fun associateTranscript(takeId: String, transcriptId: Long): Int { record("associate:$takeId:$transcriptId"); return 1 }
        override suspend fun commitTerminal(takeId: String, result: String, reason: String, atMs: Long): Int { record("terminal:$takeId:$result:$reason"); return commitRows }
        override suspend fun openFromOtherRuns(currentRunId: String): List<TakeJournalEntry> { record("open:$currentRunId"); return open }
        override suspend fun find(takeId: String): TakeJournalEntry? = null
        override suspend fun takeIdForTranscript(transcriptId: Long): String? = if (transcriptId == 42L) "take-42" else null
        override suspend fun insertionTakeIdsToKeep(cutoffMs: Long): List<String> = listOf("kept")
        override suspend fun prune(cutoffMs: Long, keep: List<String>): Int { record("prune:" + keep.joinToString(",")); return 0 }
        override suspend fun closeInterrupted(takeId: String, reason: String, atMs: Long): Boolean {
            record("close:$takeId:$reason")
            return closeAnswers[takeId] ?: true
        }
    }

    private fun terminal(takeId: String, reason: TerminalReason) = AnalyticsEvent.DictationTerminal(
        takeId, reason, null, TriggerSource.TILE, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null,
        null, null, null, null, null, null,
    )

    /**
     * The writer is one ordered worker: a probe write queued after [writer]'s last write lands only once that
     * write's whole task (its Room call AND the capture decision after it) has finished. Waiting on the probe
     * is the signal; waiting on the call count alone raced the capture that follows the commit.
     */
    private fun drain(writer: TakeJournalWriter, dao: FakeDao, callsBefore: Int) {
        writer.advance(PROBE, TakeStage.RECORDING)
        dao.awaitCalls(callsBefore + 1)
        check(dao.calls.last() == "advance:$PROBE:RECORDING:1") { "the probe did not land last: ${dao.calls}" }
        dao.calls.removeAt(dao.calls.size - 1)
    }

    private companion object {
        const val PROBE = "drain-probe"
    }

    @Test
    fun writesForOneTakeLandInTheOrderTheyWereQueuedAndTheRowLeavesOnlyAfterItsCommit() = runBlocking {
        val dao = FakeDao()
        val captured = java.util.concurrent.CopyOnWriteArrayList<String>()
        val writer = TakeJournalWriter(dao, "run-1", { captured += it.name + ":" + it.properties()["take_id"] }, nowMs = { 7L })
        val landed = writer.admit("t1", TriggerSource.BUBBLE_HOLD)
        writer.advance("t1", TakeStage.RECORDING)
        writer.associate("t1", 42L)
        writer.terminal(terminal("t1", TerminalReason.COMPLETED))
        assertTrue(withTimeout(10_000) { landed.await() })
        drain(writer, dao, 4)
        assertEquals(
            listOf("admit:t1:BUBBLE_HOLD:ADMITTED", "advance:t1:RECORDING:1", "associate:t1:42", "terminal:t1:completed:COMPLETED"),
            dao.calls,
        )
        assertEquals(listOf("dictation.terminal:t1"), captured)
    }

    @Test
    fun anEndingWhoseCommitChangedNoRowSendsNothing() {
        val dao = FakeDao(commitRows = 0)
        val captured = java.util.concurrent.CopyOnWriteArrayList<String>()
        val writer = TakeJournalWriter(dao, "run-1", { captured += it.name })
        writer.terminal(terminal("never-admitted", TerminalReason.CANCELLED_RECORDING))
        drain(writer, dao, 1)
        assertEquals(listOf("terminal:never-admitted:cancelled:CANCELLED_RECORDING"), dao.calls)
        assertEquals(emptyList<String>(), captured)
    }

    @Test
    fun aTranscriptWithNoJournalRowIsNeverAssociated() {
        val dao = FakeDao()
        val writer = TakeJournalWriter(dao, "run-1", {})
        writer.associate("t1", 0L)
        writer.advance("t1", TakeStage.PROCESSING)
        drain(writer, dao, 1)
        assertEquals(listOf("advance:t1:PROCESSING:2"), dao.calls)
    }

    @Test
    fun recoveryClosesEarlierRunsOpenTakesWithTheStagesReasonReportsEachOnceAndPrunes() {
        fun open(id: String, stage: TakeStage, trigger: String) = TakeJournalEntry(id, "run-old", 1L, stage.name, stage.seq, null, null, null, null, trigger)
        val dao = FakeDao(
            open = listOf(open("a", TakeStage.ADMITTED, "TILE"), open("b", TakeStage.RECORDING, "BUBBLE_TAP"), open("c", TakeStage.PROCESSING, "garbage"), open("d", TakeStage.RECORDING, "ASSIST")),
            closeAnswers = mapOf("d" to false),
        )
        val captured = java.util.concurrent.CopyOnWriteArrayList<Map<String, Any?>>()
        val writer = TakeJournalWriter(dao, "run-now", { captured += it.properties() }, nowMs = { 99L })
        writer.recoverAndPrune()
        drain(writer, dao, 6)
        assertEquals(
            listOf("open:run-now", "close:a:INTERRUPTED_STARTING", "close:b:INTERRUPTED_RECORDING", "close:c:INTERRUPTED_PROCESSING", "close:d:INTERRUPTED_RECORDING", "prune:kept"),
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
