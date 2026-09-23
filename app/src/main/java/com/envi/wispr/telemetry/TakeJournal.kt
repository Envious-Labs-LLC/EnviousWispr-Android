package com.envi.wispr.telemetry

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Transaction

/**
 * One row per admitted take: the content-free local ledger that COUNTS dictations (issue #176, plan
 * §3.3). History cannot be the count: it deletes wordless and cancelled drafts, inserts the draft after
 * RECORDING is published, and recovers by row age. This table records admission before capture, the
 * stage as it moves, and the ending when it is committed, and it never holds a word.
 *
 * Column names are the wire vocabulary's spelling where one exists (`stage`, `terminal_result`,
 * `terminal_reason`), so a reader of the phone's database and a reader of PostHog see the same names.
 */
@Entity(tableName = "take_journal")
internal data class TakeJournalEntry(
    @PrimaryKey @ColumnInfo(name = "take_id") val takeId: String,
    /** The main-process run that admitted the take; an open entry from another run is an interruption. */
    @ColumnInfo(name = "process_run_id") val processRunId: String,
    @ColumnInfo(name = "admitted_at_ms") val admittedAtMs: Long,
    /** The last stage the take reached: `TakeStage.name`. */
    @ColumnInfo(name = "stage") val stage: String,
    /** Monotonic within the take; a stage update never moves it backward (G3 E3). */
    @ColumnInfo(name = "stage_seq") val stageSeq: Int,
    /** The History row this take wrote, once it has one; null before that and for wordless takes. */
    @ColumnInfo(name = "transcript_id") val transcriptId: Long?,
    /** `TerminalResult.wire`, or null while the take has no durable ending. */
    @ColumnInfo(name = "terminal_result") val terminalResult: String?,
    /** `TerminalReason.name`, beside the result. */
    @ColumnInfo(name = "terminal_reason") val terminalReason: String?,
    @ColumnInfo(name = "terminal_at_ms") val terminalAtMs: Long?,
    /** The trigger surface, `TriggerSource` name, stamped at admission. */
    @ColumnInfo(name = "trigger_source") val triggerSource: String,
)

/** Where a take is, in wire spelling. The sequence is the ordering the journal enforces. */
internal enum class TakeStage(val seq: Int) {
    ADMITTED(0),
    RECORDING(1),
    PROCESSING(2),
    INSERTING(3),
}

@Dao
internal interface TakeJournalDao {
    /** Admission inserts only when the row is absent; it never replaces one (G3 E3). */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun admit(entry: TakeJournalEntry): Long

    /** Stage updates apply only while the take has no ending and never move backward. */
    @Query(
        "UPDATE take_journal SET stage = :stage, stage_seq = :seq " +
            "WHERE take_id = :takeId AND terminal_result IS NULL AND stage_seq < :seq",
    )
    suspend fun advance(takeId: String, stage: String, seq: Int): Int

    @Query("UPDATE take_journal SET transcript_id = :transcriptId WHERE take_id = :takeId AND transcript_id IS NULL")
    suspend fun associateTranscript(takeId: String, transcriptId: Long): Int

    /** The one conditional commit: a second ending, from anyone, changes nothing. */
    @Query(
        "UPDATE take_journal SET terminal_result = :result, terminal_reason = :reason, terminal_at_ms = :atMs " +
            "WHERE take_id = :takeId AND terminal_result IS NULL",
    )
    suspend fun commitTerminal(takeId: String, result: String, reason: String, atMs: Long): Int

    /** Takes admitted by an earlier run that never recorded an ending. */
    @Query("SELECT * FROM take_journal WHERE terminal_result IS NULL AND process_run_id != :currentRunId")
    suspend fun openFromOtherRuns(currentRunId: String): List<TakeJournalEntry>

    @Query("SELECT * FROM take_journal WHERE take_id = :takeId")
    suspend fun find(takeId: String): TakeJournalEntry?

    @Query("SELECT take_id FROM take_journal WHERE transcript_id = :transcriptId")
    suspend fun takeIdForTranscript(transcriptId: Long): String?

    /**
     * The takes pruning must keep: a History row still waiting for an insertion outcome, or one whose
     * state moved inside the retention window (recovery stamps `stateChangedAtMs` before the recovered
     * row's take is looked up, so the association must outlive that lookup; round 2, F9).
     */
    @Query(
        "SELECT take_id FROM take_journal WHERE transcript_id IN (SELECT id FROM transcripts WHERE " +
            "(status IN ('${com.envi.wispr.history.TranscriptEntity.STATUS_READY_FOR_INSERTION}', " +
            "'${com.envi.wispr.history.TranscriptEntity.STATUS_SAVED_UNROUTED}') AND insertionResult = 'pending') " +
            "OR stateChangedAtMs >= :cutoffMs)",
    )
    suspend fun insertionTakeIdsToKeep(cutoffMs: Long): List<String>

    /** Ended takes older than the cutoff; an entry still associated with a pending insertion is kept by its caller. */
    @Query("DELETE FROM take_journal WHERE terminal_result IS NOT NULL AND terminal_at_ms < :cutoffMs AND take_id NOT IN (:keep)")
    suspend fun prune(cutoffMs: Long, keep: List<String>): Int

    /**
     * Recovery: close an earlier run's open take as interrupted, in the same transaction that reports
     * it, so a death mid-recovery cannot report the same take twice (G3 E3).
     */
    @Transaction
    suspend fun closeInterrupted(takeId: String, reason: String, atMs: Long): Boolean =
        commitTerminal(takeId, INTERRUPTED_RESULT, reason, atMs) == 1

    companion object {
        /** `TerminalResult.INTERRUPTED.wire`, spelled here because a DAO default cannot reference the enum. */
        const val INTERRUPTED_RESULT = "interrupted"
    }
}
