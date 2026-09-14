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
 */
internal data class PracticeTake(
    val startedAtMs: Long,
    val held: Boolean?,
    val processed: Boolean = false,
    val ended: Boolean = false,
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
    /** The take was transcribed and produced no words. */
    NO_WORDS,
    /** The take ended before transcription: cancelled or lost. */
    NOTHING_ADDED,
}

/** How far before the take was first seen a History row may have been created and still be its row. */
private const val TAKE_ROW_SLACK_MS = 1_000L

/**
 * What the practice screen says about [take], judged from the History row the take wrote, never from
 * the text in the box: typing is allowed there, and a row is the one record the box did not write.
 * Null while the owner is still busy with the take.
 */
internal fun judgePracticeTake(take: PracticeTake, lesson: PracticeLesson, rows: List<TranscriptEntity>): PracticeOutcome? {
    if (!take.ended) return null
    val row = rows.filter { it.createdAtMs >= take.startedAtMs - TAKE_ROW_SLACK_MS }.maxByOrNull { it.createdAtMs }
        ?: return if (take.processed) PracticeOutcome.NO_WORDS else PracticeOutcome.NOTHING_ADDED
    return when (row.status) {
        TranscriptEntity.STATUS_DRAFT,
        TranscriptEntity.STATUS_PROCESSING,
        TranscriptEntity.STATUS_READY_FOR_INSERTION,
        -> PracticeOutcome.WORKING
        TranscriptEntity.STATUS_COMPLETED -> when (row.insertionResult) {
            InsertionResults.COMMITTED, InsertionResults.PASTED ->
                if (lesson == PracticeLesson.HOLD && take.held != true) PracticeOutcome.LANDED_BY_TAP else PracticeOutcome.LANDED
            else -> PracticeOutcome.MISSED_BOX
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
