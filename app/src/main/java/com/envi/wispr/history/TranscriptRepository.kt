package com.envi.wispr.history

import kotlinx.coroutines.flow.Flow

class TranscriptRepository(private val dao: TranscriptDao, private val clock: () -> Long = System::currentTimeMillis) {
    companion object {
        /** Exceeds the 2.5 second insertion retry window, allowing live rows to finish. */
        const val STALE_OPEN_ROW_AGE_MS = HistoryRecovery.STALE_OPEN_ROW_AGE_MS
    }
    val transcripts: Flow<List<TranscriptEntity>> = dao.observeAll()

    suspend fun insert(transcript: TranscriptEntity): Long = dao.insert(
        transcript.copy(stateChangedAtMs = transcript.stateChangedAtMs.takeIf { it > 0L } ?: clock()),
    )

    suspend fun setKept(id: Long, kept: Boolean) = dao.setKept(id, kept)

    suspend fun delete(transcript: TranscriptEntity) = dao.delete(transcript)

    suspend fun deleteAll() = dao.deleteAll()

    /** Removes one row outright. The session owner's exit for a dictation with no words in it. */
    suspend fun discard(id: Long) = dao.deleteById(id)

    /** One-time cleanup of no-speech and cancelled rows written before they stopped being saved. */
    suspend fun pruneWordlessRows() = dao.deleteWordlessRows()

    suspend fun updateStatus(id: Long, status: String, interrupted: Boolean = false, insertionResult: String? = null) =
        dao.updateStatus(id, status, clock(), interrupted, insertionResult)

    suspend fun finalize(
        id: Long,
        originalText: String,
        finalText: String,
        speechEngine: String,
        polishEngine: String,
        polishLatencyMs: Long,
        insertionResult: String,
        durationMs: Long,
        polishReason: String,
        polishStatus: Int,
        polishContext: String,
        stateChangedAtMs: Long = clock(),
    ) = dao.finalize(
        id = id,
        originalText = originalText,
        finalText = finalText,
        speechEngine = speechEngine,
        polishEngine = polishEngine,
        polishLatencyMs = polishLatencyMs,
        insertionResult = insertionResult,
        durationMs = durationMs,
        stateChangedAtMs = stateChangedAtMs,
        polishReason = polishReason,
        polishStatus = polishStatus,
        polishContext = polishContext,
    )

    suspend fun finalizeInsertionOutcome(id: Long, status: String, result: String, interrupted: Boolean = false) =
        dao.finalizeInsertionOutcome(id, status, result, clock(), interrupted)

    suspend fun recoverStaleOpenRows(nowMs: Long, cutoffMs: Long = nowMs - STALE_OPEN_ROW_AGE_MS) {
        dao.recoverStaleDrafts(cutoffMs, nowMs)
        dao.recoverStaleReadyRows(cutoffMs, nowMs)
    }
}
