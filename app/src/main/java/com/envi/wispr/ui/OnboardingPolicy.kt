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
 * [boxTextAtStart] is what the practice box held when the take began: words that landed must have
 * changed it, which is how a take aimed at some other app's field is kept from passing practice.
 * [rowId] is the History row this take wrote, bound the first time one is seen and never rebound, so
 * a later dictation elsewhere cannot replace this take's verdict (Codex review, round 2).
 */
internal data class PracticeTake(
    val startedAtMs: Long,
    val held: Boolean?,
    val boxTextAtStart: String,
    val ended: Boolean = false,
    val rowId: Long? = null,
)

internal enum class PracticeOutcome {
    /** The owner is idle but the words are still being put in the box (the insertion has a deadline). */
    WORKING,
    /** The words are in the practice box, by the route any other app gets. */
    LANDED,
    /** Landed, but the hold lesson was answered with a tap. Still a success; the hint asks for a hold. */
    LANDED_BY_TAP,
    /** A finished take whose words did not reach the box. */
    MISSED_BOX,
    /**
     * The take ended with no row: nothing was heard, or it was cancelled, or it failed. The owner
     * deletes the draft row on every one of those, and the phase it publishes cannot tell them apart,
     * so the screen says the one thing true of all three: no words were added.
     */
    NOTHING_ADDED,
}

/** How far before the take was first seen a History row may have been created and still be its row. */
private const val TAKE_ROW_SLACK_MS = 1_000L

/**
 * Bind [take] to its own History row: the EARLIEST row created after the take began, because the owner
 * writes the draft row the moment capture starts. Once bound, the id never changes.
 */
internal fun bindPracticeRow(take: PracticeTake, rows: List<TranscriptEntity>): PracticeTake {
    if (take.rowId != null) return take
    val own = rows.filter { it.createdAtMs >= take.startedAtMs - TAKE_ROW_SLACK_MS }.minByOrNull { it.createdAtMs } ?: return take
    return take.copy(rowId = own.id)
}

/**
 * What the practice screen says about [take], judged from the History row the take wrote AND from the
 * box: a landed row proves words were inserted somewhere, and [boxText] having changed since the take
 * began proves the somewhere was this box. Typing alone writes no row; a take into another app's field
 * changes no box; only the practice take does both. Null while the owner is still busy with the take.
 * A take whose row is gone (the owner deletes the draft of a silent, cancelled or failed take) added
 * nothing.
 */
internal fun judgePracticeTake(take: PracticeTake, lesson: PracticeLesson, rows: List<TranscriptEntity>, boxText: String): PracticeOutcome? {
    if (!take.ended) return null
    val row = take.rowId?.let { id -> rows.firstOrNull { it.id == id } } ?: return PracticeOutcome.NOTHING_ADDED
    return when (row.status) {
        TranscriptEntity.STATUS_DRAFT,
        TranscriptEntity.STATUS_PROCESSING,
        TranscriptEntity.STATUS_READY_FOR_INSERTION,
        -> PracticeOutcome.WORKING
        TranscriptEntity.STATUS_COMPLETED -> when {
            row.insertionResult != InsertionResults.COMMITTED && row.insertionResult != InsertionResults.PASTED -> PracticeOutcome.MISSED_BOX
            boxText == take.boxTextAtStart -> PracticeOutcome.MISSED_BOX
            lesson == PracticeLesson.HOLD && take.held != true -> PracticeOutcome.LANDED_BY_TAP
            else -> PracticeOutcome.LANDED
        }
        else -> PracticeOutcome.MISSED_BOX
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
