package com.envi.wispr.history

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "transcripts",
    indices = [Index(value = ["createdAtMs"])],
)
data class TranscriptEntity(
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
        const val STATUS_INSERTION_INTERRUPTED = "insertion_interrupted"
    }
}
