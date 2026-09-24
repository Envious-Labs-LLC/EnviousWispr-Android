package com.envi.wispr.history

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "transcripts",
    indices = [Index(value = ["createdAtMs"]), Index(value = ["takeId"], unique = true)],
)
internal data class TranscriptEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val originalText: String,
    val finalText: String,
    val createdAtMs: Long,
    val durationMs: Long,
    val speechEngine: String,
    val polishEngine: String,
    val polishLatencyMs: Long,
    val insertionResult: String,
    val kept: Boolean = false,
    val recovered: Boolean = false,
    val interrupted: Boolean = false,
    val status: String = STATUS_COMPLETED,
    val stateChangedAtMs: Long = 0L,
    // Why the polish ended the way it did (#77): the `PolishReason` name, the HTTP status, and the latched
    // policy as a `PolishContext` token. Schema values: never rename a member or a token without a
    // migration or an alias. Rows from older builds carry the defaults and render as before.
    @ColumnInfo(defaultValue = "''") val polishReason: String = "",
    @ColumnInfo(defaultValue = "0") val polishStatus: Int = 0,
    @ColumnInfo(defaultValue = "''") val polishContext: String = "",
    // Which microphone actually captured, in order: "AirPods Pro 3" or "AirPods Pro 3, then Phone"
    // (#26). Empty means unknown: rows from older builds, or a take whose capture process died before
    // the label was read. Never rendered as "Phone" when empty.
    @ColumnInfo(defaultValue = "''") val captureDevice: String = "",
    // The take this row records (#288): set by the session owner's draft and saved-row inserts, null on rows from
    // older builds and on rows no take wrote. Unique, so a take's rescued words are written into History at most once.
    val takeId: String? = null,
) {
    companion object {
        const val STATUS_DRAFT = "draft"
        const val STATUS_PROCESSING = "processing"
        const val STATUS_COMPLETED = "completed"
        /**
         * Historical only. Nothing writes either value: a dictation that produced no words has its
         * draft row deleted by `DictationSessionService.discardDraft`, so it never reaches History.
         * They survive as the target of `TranscriptDao.deleteWordlessRows`, which removes the rows
         * an older build left on a phone.
         */
        const val STATUS_NO_SPEECH = "no_speech"
        const val STATUS_CANCELED = "canceled"
        const val STATUS_ASR_ERROR = "asr_error"
        const val STATUS_INTERRUPTED = "interrupted"
        const val STATUS_READY_FOR_INSERTION = "ready_for_insertion"
        /**
         * The finalized row before its route is recorded (#235): durable, with its words, and neither
         * ready nor terminal. The session owner decides the route within its History bound; a scheduled
         * handoff promotes the row to ready later, a copy reconciles it to the clipboard outcome, and a row
         * that outlives the cutoff in this state is recovered as `delivery_unknown`, never as a paste.
         */
        const val STATUS_SAVED_UNROUTED = "saved_unrouted"
        const val STATUS_INSERTION_INTERRUPTED = "insertion_interrupted"
    }
}
