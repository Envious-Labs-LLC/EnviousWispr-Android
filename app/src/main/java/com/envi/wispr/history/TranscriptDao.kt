package com.envi.wispr.history

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.envi.wispr.insertion.InsertionResults
import kotlinx.coroutines.flow.Flow

@Dao
internal interface TranscriptDao {
    @Query("SELECT * FROM transcripts ORDER BY kept DESC, createdAtMs DESC, id DESC")
    fun observeAll(): Flow<List<TranscriptEntity>>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(transcript: TranscriptEntity): Long

    @Query("UPDATE transcripts SET kept = :kept WHERE id = :id")
    suspend fun setKept(id: Long, kept: Boolean)

    @Delete
    suspend fun delete(transcript: TranscriptEntity)

    @Query("DELETE FROM transcripts")
    suspend fun deleteAll()

    @Query("DELETE FROM transcripts WHERE id = :id")
    suspend fun deleteById(id: Long): Int

    /**
     * Removes rows written by an older build for a dictation that produced no words.
     *
     * Nothing writes either status any more — the session owner deletes its own draft instead — so
     * this exists for the rows already on a phone. It matches on STATUS alone rather than on empty
     * text, because a draft row in flight is also textless and is not finished being written.
     */
    @Query(
        "DELETE FROM transcripts WHERE status IN " +
            "('${TranscriptEntity.STATUS_NO_SPEECH}', '${TranscriptEntity.STATUS_CANCELED}')",
    )
    suspend fun deleteWordlessRows(): Int

    @Query(
        "UPDATE transcripts SET status = :status, stateChangedAtMs = :stateChangedAtMs, " +
            "insertionResult = COALESCE(:insertionResult, insertionResult), " +
            "interrupted = :interrupted " +
            "WHERE id = :id",
    )
    suspend fun updateStatus(id: Long, status: String, stateChangedAtMs: Long, interrupted: Boolean = false, insertionResult: String? = null): Int

    @Query(
        "UPDATE transcripts SET originalText = :originalText, finalText = :finalText, " +
            "speechEngine = :speechEngine, polishEngine = :polishEngine, " +
            "polishLatencyMs = :polishLatencyMs, insertionResult = :insertionResult, " +
            "polishReason = :polishReason, polishStatus = :polishStatus, polishContext = :polishContext, " +
            "captureDevice = :captureDevice, " +
            "durationMs = :durationMs, status = :status, stateChangedAtMs = :stateChangedAtMs, interrupted = :interrupted " +
            "WHERE id = :id",
    )
    suspend fun finalize(
        id: Long,
        originalText: String,
        finalText: String,
        speechEngine: String,
        polishEngine: String,
        polishLatencyMs: Long,
        insertionResult: String,
        durationMs: Long,
        stateChangedAtMs: Long,
        polishReason: String,
        polishStatus: Int,
        polishContext: String,
        captureDevice: String,
        status: String = TranscriptEntity.STATUS_READY_FOR_INSERTION,
        interrupted: Boolean = false,
    ): Int

    @Query(
            "UPDATE transcripts SET status = :status, insertionResult = :result, stateChangedAtMs = :stateChangedAtMs, interrupted = :interrupted " +
            "WHERE id = :id AND status IN ('${TranscriptEntity.STATUS_READY_FOR_INSERTION}', " +
            "'${TranscriptEntity.STATUS_SAVED_UNROUTED}') AND insertionResult = 'pending'",
    )
    suspend fun finalizeInsertionOutcome(
        id: Long,
        status: String,
        result: String,
        stateChangedAtMs: Long,
        interrupted: Boolean = false,
    ): Int

    /** A take that died while recording never reached insertion: nothing was attempted. */
    @Query(
        "UPDATE transcripts SET status = '${TranscriptEntity.STATUS_INTERRUPTED}', " +
            "insertionResult = 'not_attempted', stateChangedAtMs = :nowMs, interrupted = 1 " +
            "WHERE stateChangedAtMs <= :cutoffMs AND status = '${TranscriptEntity.STATUS_DRAFT}'",
    )
    suspend fun recoverStaleDrafts(cutoffMs: Long, nowMs: Long): Int

    /**
     * A take that died after it stopped recording and before its save landed (#277): its words may already have
     * been handed to insertion, because insertion never waits on this row, so the honest reading is delivery
     * unknown, never "not attempted".
     */
    @Query(
        "UPDATE transcripts SET status = '${TranscriptEntity.STATUS_INTERRUPTED}', " +
            "insertionResult = '${InsertionResults.DELIVERY_UNKNOWN}', stateChangedAtMs = :nowMs, interrupted = 1 " +
            "WHERE stateChangedAtMs <= :cutoffMs AND status = '${TranscriptEntity.STATUS_PROCESSING}'",
    )
    suspend fun recoverStaleProcessingRows(cutoffMs: Long, nowMs: Long): Int

    /**
     * A scheduled handoff's row, promoted from neutral to ready AFTER the handoff and never awaited (#235):
     * conditional, so an outcome that already landed wins and this updates nothing.
     */
    @Query(
        "UPDATE transcripts SET status = '${TranscriptEntity.STATUS_READY_FOR_INSERTION}', stateChangedAtMs = :nowMs " +
            "WHERE id = :id AND status = '${TranscriptEntity.STATUS_SAVED_UNROUTED}' AND insertionResult = 'pending'",
    )
    suspend fun promoteUnroutedToReady(id: Long, nowMs: Long): Int

    /**
     * A neutral row that outlived the cutoff: its words are kept, and no route was recorded, so the
     * honest reading is delivery unknown, never an interrupted paste (#235).
     */
    @Query(
        "UPDATE transcripts SET status = '${TranscriptEntity.STATUS_COMPLETED}', " +
            "insertionResult = '${InsertionResults.DELIVERY_UNKNOWN}', stateChangedAtMs = :nowMs, interrupted = 1 " +
            "WHERE stateChangedAtMs <= :cutoffMs AND status = '${TranscriptEntity.STATUS_SAVED_UNROUTED}' " +
            "AND insertionResult = 'pending'",
    )
    suspend fun recoverStaleUnroutedRows(cutoffMs: Long, nowMs: Long): Int

    @Query(
        "UPDATE transcripts SET status = '${TranscriptEntity.STATUS_INSERTION_INTERRUPTED}', " +
            "insertionResult = '${InsertionResults.INSERTION_INTERRUPTED}', stateChangedAtMs = :nowMs, interrupted = 1 " +
            "WHERE stateChangedAtMs <= :cutoffMs AND status = '${TranscriptEntity.STATUS_READY_FOR_INSERTION}' " +
            "AND insertionResult = 'pending'",
    )
    suspend fun recoverStaleReadyRows(cutoffMs: Long, nowMs: Long): Int

    @Query(
        "SELECT id FROM transcripts WHERE stateChangedAtMs <= :cutoffMs AND " +
            "status = '${TranscriptEntity.STATUS_READY_FOR_INSERTION}' AND insertionResult = 'pending'",
    )
    suspend fun staleReadyRowIds(cutoffMs: Long): List<Long>

    /**
     * The ready rows recovered, BY ID, in the one transaction that recovers them: each is an insertion
     * outcome telemetry reports (issue #176, G2 D4), and a select after the update would find nothing.
     */
    @Transaction
    suspend fun recoverStaleReadyRowsReturningIds(cutoffMs: Long, nowMs: Long): List<Long> {
        val ids = staleReadyRowIds(cutoffMs)
        if (ids.isNotEmpty()) recoverStaleReadyRows(cutoffMs, nowMs)
        return ids
    }
}
