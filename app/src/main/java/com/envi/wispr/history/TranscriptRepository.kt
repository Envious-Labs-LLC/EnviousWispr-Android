package com.envi.wispr.history

import kotlinx.coroutines.flow.Flow

internal class TranscriptRepository(private val dao: TranscriptDao, private val clock: () -> Long = System::currentTimeMillis) {
    companion object {
        /** Exceeds the 2.5 second insertion retry window, allowing live rows to finish. */
        const val STALE_OPEN_ROW_AGE_MS = HistoryRecovery.STALE_OPEN_ROW_AGE_MS
    }
    val transcripts: Flow<List<TranscriptEntity>> = dao.observeAll()

    /**
     * The one door into History for a row (#288): never for a take the user deleted, read in the insert's own
     * transaction, so no writer of a take (its draft, its save, a recovery) can bring deleted words back. 0 then.
     */
    suspend fun insert(transcript: TranscriptEntity): Long = dao.insertUnlessDeleted(
        transcript.copy(stateChangedAtMs = transcript.stateChangedAtMs.takeIf { it > 0L } ?: clock()),
    )

    suspend fun setKept(id: Long, kept: Boolean) = dao.setKept(id, kept)

    /** The user's delete (#288): the take's words stay deleted, whatever writes for it later. */
    suspend fun delete(transcript: TranscriptEntity) = dao.deleteForGood(transcript)

    /** The user's Delete all (#288); [liveTakes] are takes with words and no row yet, deleted with the rest. */
    suspend fun deleteAll(liveTakes: Collection<String> = emptyList()) = dao.deleteAllForGood(liveTakes)

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
        captureDevice: String,
        status: String,
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
        captureDevice = captureDevice,
        status = status,
    )

    /** The id of the row [takeId] wrote, or 0 (#288). */
    suspend fun rowIdForTake(takeId: String): Long = dao.findByTakeId(takeId)?.id ?: 0L

    /**
     * Writes a take's rescued words into History (#288), once per take: a row the take already wrote keeps its words
     * when it has any and receives the rescued ones when it has none (a draft that never saved); with no row, one is
     * inserted, marked as an interrupted insertion so it reads as the words' last known place. True when the rescue is
     * no longer needed: History holds the words, or the user deleted the take. The rescue holds the final text only, so
     * it is also the row's original text.
     */
    suspend fun keepRescuedWords(takeId: String, text: String, createdAtMs: Long): Boolean {
        val existing = dao.findByTakeId(takeId)
        if (existing != null) {
            if (existing.finalText.isNotBlank()) return true
            return dao.finalize(
                id = existing.id,
                originalText = text,
                finalText = text,
                speechEngine = existing.speechEngine,
                polishEngine = existing.polishEngine,
                polishLatencyMs = existing.polishLatencyMs,
                insertionResult = com.envi.wispr.insertion.InsertionResults.INSERTION_FAILED,
                durationMs = existing.durationMs,
                stateChangedAtMs = clock(),
                polishReason = existing.polishReason,
                polishStatus = existing.polishStatus,
                polishContext = existing.polishContext,
                captureDevice = existing.captureDevice,
                status = TranscriptEntity.STATUS_INSERTION_INTERRUPTED,
                interrupted = true,
            ) > 0
        }
        // Never for a take the user deleted: nothing is inserted then, and the rescue is done all the same.
        insert(
            TranscriptEntity(
                originalText = text,
                finalText = text,
                createdAtMs = createdAtMs,
                durationMs = 0L,
                speechEngine = "Parakeet",
                polishEngine = com.envi.wispr.polish.PolishEngineLabels.NOT_RECORDED,
                polishLatencyMs = 0L,
                insertionResult = com.envi.wispr.insertion.InsertionResults.INSERTION_FAILED,
                interrupted = true,
                status = TranscriptEntity.STATUS_INSERTION_INTERRUPTED,
                takeId = takeId,
            ),
        )
        return true
    }

    /** After a scheduled handoff, never awaited by the owner (#235). */
    suspend fun promoteUnroutedToReady(id: Long) = dao.promoteUnroutedToReady(id, clock())

    suspend fun finalizeInsertionOutcome(id: Long, status: String, result: String, interrupted: Boolean = false) =
        dao.finalizeInsertionOutcome(id, status, result, clock(), interrupted)

    /**
     * What one recovery pass closed: the ready rows by id, because each is an insertion outcome to report, and
     * how many neutral rows became delivery unknown (#235), because each is a take whose route was lost.
     */
    data class RecoveredRows(val readyRowIds: List<Long>, val unknownCount: Int = 0)

    /**
     * One cutoff, four scans in this order (#235, #277): drafts, then processing rows, then neutral rows, then ready
     * rows, each update conditional on its source status, so a row promoted from neutral to ready between the scans
     * is read once, as ready. A processing row and a neutral row are both delivery unknown: insertion never waits on
     * either (#277), so neither can say the words were not handed over.
     */
    suspend fun recoverStaleOpenRows(nowMs: Long, cutoffMs: Long = nowMs - STALE_OPEN_ROW_AGE_MS): RecoveredRows {
        dao.recoverStaleDrafts(cutoffMs, nowMs)
        val unknownCount = dao.recoverStaleProcessingRows(cutoffMs, nowMs) + dao.recoverStaleUnroutedRows(cutoffMs, nowMs)
        return RecoveredRows(dao.recoverStaleReadyRowsReturningIds(cutoffMs, nowMs), unknownCount)
    }
}
