package com.envi.wispr.ui

import com.envi.wispr.history.TranscriptEntity
import com.envi.wispr.insertion.InsertionResults
import com.envi.wispr.paste.AutoPasteAvailability

/**
 * The setup screens in order. DEMO (founder 2026-09-14) is the drawn walkthrough of the bubble that
 * plays once the permissions are granted, before the real practice; it is skippable and needs the
 * same facts as practice, so a restored step can never show it without them.
 */
internal enum class OnboardingStage { WELCOME, DOWNLOADS, PERMISSIONS, DEMO, PRACTICE }

/** The two gestures practice teaches, in this order: a tap first, then press and hold. */
internal enum class PracticeLesson { TAP, HOLD }

/**
 * One take of the PRACTICE BOX the screen is following, from the moment the session owner left IDLE
 * with the box as its target. Everything here is copied off the owner's published snapshot: [held]
 * is the gesture its token named (null for a take with no bubble token), [transcriptId] is the History
 * row the owner created for it (null until it has), [ended] is the owner back at IDLE. Nothing is
 * inferred from time or from the order of rows (Codex reviews 2 to 9, 2026-09-14).
 */
internal data class PracticeTake(
    val held: Boolean?,
    val transcriptId: Long? = null,
    val ended: Boolean = false,
)

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

/**
 * What the practice screen says about [take]: null while the owner is busy with it; otherwise read
 * off the row whose id the owner published. A take the owner never gave a row (cancelled before
 * capture), or whose row the owner deleted (silent, cancelled), landed nothing.
 */
internal fun judgePracticeTake(take: PracticeTake, lesson: PracticeLesson, rows: List<TranscriptEntity>): PracticeOutcome? {
    if (!take.ended) return null
    val row = take.transcriptId?.let { id -> rows.firstOrNull { it.id == id } } ?: return PracticeOutcome.NOTHING_LANDED
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
    if ((requested == OnboardingStage.DEMO || requested == OnboardingStage.PRACTICE) &&
        (!readiness.microphoneGranted || autoPaste != AutoPasteAvailability.LIVE)
    ) return OnboardingStage.PERMISSIONS
    return requested
}

/** A slow file check cannot overwrite a newer permission answer. */
internal fun AppReadiness.withVerifiedModels(checked: AppReadiness): AppReadiness = copy(
    speechModelReady = checked.speechModelReady,
    polishModelReady = checked.polishModelReady,
)
