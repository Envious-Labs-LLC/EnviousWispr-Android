package com.envi.wispr.history

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface TranscriptDao {
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
        status: String = TranscriptEntity.STATUS_READY_FOR_INSERTION,
        interrupted: Boolean = false,
    ): Int

    @Query(
            "UPDATE transcripts SET status = :status, insertionResult = :result, stateChangedAtMs = :stateChangedAtMs, interrupted = :interrupted " +
            "WHERE id = :id AND status = '${TranscriptEntity.STATUS_READY_FOR_INSERTION}' " +
            "AND insertionResult = 'pending'",
    )
    suspend fun finalizeInsertionOutcome(
        id: Long,
        status: String,
        result: String,
        stateChangedAtMs: Long,
        interrupted: Boolean = false,
    ): Int

    @Query(
        "UPDATE transcripts SET status = '${TranscriptEntity.STATUS_INTERRUPTED}', " +
            "insertionResult = 'not_attempted', stateChangedAtMs = :nowMs, interrupted = 1 " +
            "WHERE stateChangedAtMs <= :cutoffMs AND status IN " +
            "('${TranscriptEntity.STATUS_DRAFT}', '${TranscriptEntity.STATUS_PROCESSING}')",
    )
    suspend fun recoverStaleDrafts(cutoffMs: Long, nowMs: Long): Int

    @Query(
        "UPDATE transcripts SET status = '${TranscriptEntity.STATUS_INSERTION_INTERRUPTED}', " +
            "insertionResult = 'insertion_interrupted', stateChangedAtMs = :nowMs, interrupted = 1 " +
            "WHERE stateChangedAtMs <= :cutoffMs AND status = '${TranscriptEntity.STATUS_READY_FOR_INSERTION}'",
    )
    suspend fun recoverStaleReadyRows(cutoffMs: Long, nowMs: Long): Int
}
