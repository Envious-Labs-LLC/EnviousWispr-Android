package com.envi.wispr.ui

import com.envi.wispr.history.TranscriptEntity
import com.envi.wispr.insertion.InsertionResults
import com.envi.wispr.paste.AutoPasteAvailability

internal enum class OnboardingStage { WELCOME, DOWNLOADS, PERMISSIONS, PRACTICE }

/** The two gestures practice teaches, in this order: a tap first, then press and hold. */
internal enum class PracticeLesson { TAP, HOLD }

/**
 * One take the practice screen is following, from the moment the session owner left IDLE.
 * [held] is the gesture the owner's snapshot named, or null for a take that carried no bubble token.
 * [endedAtMs] is when the owner returned to IDLE, or null while it is busy. [rowId] is the History row
 * this take wrote, bound the first time one is seen and never rebound. The owner writes that row when
 * capture starts, so it was created between the take's start and its end: a row created after the end
 * belongs to a later dictation and is never this take's (Codex reviews, rounds 2 and 6).
 */
internal data class PracticeTake(
    val startedAtMs: Long,
    val held: Boolean?,
    val endedAtMs: Long? = null,
    val rowId: Long? = null,
) {
    val ended: Boolean get() = endedAtMs != null
}

/**
 * Three answers, read off ONE fact: this take's own History row. Earlier rounds judged more (the box's
 * text, a freshness flag, a lock on the verdict) and each round found one more ordering in which the
 * extra reads lied; the fourth deleted them (Codex reviews 1 to 5, 2026-09-14). The row is the one
 * record the insertion path writes about where the words went, so it is the one thing judged.
 */
internal enum class PracticeOutcome {
    /** The take's row is still being written: transcribing, polishing, or inserting (bounded by the owner). */
    WORKING,
    /** The words are in the practice box, by the route any other app gets. */
    LANDED,
    /** Landed, but the hold lesson was answered with a tap. Still a success; the hint asks for a hold. */
    LANDED_BY_TAP,
    /**
     * Nothing landed: silent, cancelled, failed, or the words missed the box. The owner records those
     * differently (some delete the row, one keeps an empty error row, one keeps a copy-only row) and
     * none of them put words in the box, so the screen says that one thing and asks for another try.
     */
    NOTHING_LANDED,
}

/** How far before the take was first seen a History row may have been created and still be its row. */
private const val TAKE_ROW_SLACK_MS = 1_000L

/**
 * Bind [take] to its own History row: the EARLIEST row created after the take began and, once the take
 * has ended, no later than that end. Once bound, the id never changes.
 */
internal fun bindPracticeRow(take: PracticeTake, rows: List<TranscriptEntity>): PracticeTake {
    if (take.rowId != null) return take
    val own = rows
        .filter { it.createdAtMs >= take.startedAtMs - TAKE_ROW_SLACK_MS && (take.endedAtMs == null || it.createdAtMs <= take.endedAtMs) }
        .minByOrNull { it.createdAtMs } ?: return take
    return take.copy(rowId = own.id)
}

/**
 * What the practice screen says about [take]: null while the owner is busy with it; otherwise read
 * off the take's own History row. The practice screen follows the owner only while it is in front, and
 * then the pinned target is the practice box, so a landed row IS words in the box.
 */
internal fun judgePracticeTake(take: PracticeTake, lesson: PracticeLesson, rows: List<TranscriptEntity>): PracticeOutcome? {
    if (!take.ended) return null
    val row = take.rowId?.let { id -> rows.firstOrNull { it.id == id } } ?: return PracticeOutcome.NOTHING_LANDED
    return when (row.status) {
        TranscriptEntity.STATUS_DRAFT,
        TranscriptEntity.STATUS_PROCESSING,
        TranscriptEntity.STATUS_READY_FOR_INSERTION,
        -> PracticeOutcome.WORKING
        TranscriptEntity.STATUS_COMPLETED -> when {
            row.insertionResult != InsertionResults.COMMITTED && row.insertionResult != InsertionResults.PASTED -> PracticeOutcome.NOTHING_LANDED
            lesson == PracticeLesson.HOLD && take.held != true -> PracticeOutcome.LANDED_BY_TAP
            else -> PracticeOutcome.LANDED
        }
        else -> PracticeOutcome.NOTHING_LANDED
    }
}

/** Restored navigation never bypasses the facts required by the next screen. */
internal fun onboardingStage(step: Int, readiness: AppReadiness, autoPaste: AutoPasteAvailability): OnboardingStage {
    val requested = OnboardingStage.entries.getOrElse(step) { OnboardingStage.WELCOME }
    if (requested == OnboardingStage.WELCOME) return requested
    if (!readiness.requiredModelsReady) return OnboardingStage.DOWNLOADS
    if (requested == OnboardingStage.DOWNLOADS) return OnboardingStage.PERMISSIONS
    if (requested == OnboardingStage.PRACTICE &&
        (!readiness.microphoneGranted || autoPaste != AutoPasteAvailability.LIVE)
    ) return OnboardingStage.PERMISSIONS
    return requested
}

/** A slow file check cannot overwrite a newer permission answer. */
internal fun AppReadiness.withVerifiedModels(checked: AppReadiness): AppReadiness = copy(
    speechModelReady = checked.speechModelReady,
    polishModelReady = checked.polishModelReady,
)
