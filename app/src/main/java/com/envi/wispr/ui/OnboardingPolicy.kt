package com.envi.wispr.ui

import com.envi.wispr.history.TranscriptEntity
import com.envi.wispr.insertion.InsertionResults
import com.envi.wispr.paste.AutoPasteAvailability
import com.envi.wispr.settings.AppPreferencesState

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
 * Whether a take's row in [status] can still change where its words went: true while the owner is still
 * writing it, false once the status is final, null for a status this table does not know. Every
 * `TranscriptEntity.STATUS_` value has a row here, and `OnboardingPolicyTest` reads them by reflection so
 * the next one cannot fall into the default unnoticed. That default is #560: `saved_unrouted`, the neutral
 * row the owner writes BEFORE the route (#235), was judged final, so a take whose words committed into the
 * box in the same second was reported as nothing landed.
 */
internal fun practiceRowStillOpen(status: String): Boolean? = when (status) {
    TranscriptEntity.STATUS_DRAFT,
    TranscriptEntity.STATUS_PROCESSING,
    TranscriptEntity.STATUS_SAVED_UNROUTED,
    TranscriptEntity.STATUS_READY_FOR_INSERTION,
    -> true
    TranscriptEntity.STATUS_COMPLETED,
    TranscriptEntity.STATUS_INSERTION_INTERRUPTED,
    TranscriptEntity.STATUS_INTERRUPTED,
    TranscriptEntity.STATUS_ASR_ERROR,
    TranscriptEntity.STATUS_NO_SPEECH,
    TranscriptEntity.STATUS_CANCELED,
    -> false
    // An open String: a status no build writes today. Read as final so the screen never waits on it forever.
    else -> null
}

/**
 * What the practice screen says about [take]: null while the owner is busy with it; otherwise read
 * off the row whose id the owner published. A take the owner never gave a row (cancelled before
 * capture), or whose row the owner deleted (silent, cancelled), landed nothing. The owner goes IDLE
 * before its row is final (`surface.hide()` does not wait for the route), so an open row is WORKING.
 */
internal fun judgePracticeTake(take: PracticeTake, lesson: PracticeLesson, rows: List<TranscriptEntity>): PracticeOutcome? {
    if (!take.ended) return null
    val row = take.transcriptId?.let { id -> rows.firstOrNull { it.id == id } } ?: return PracticeOutcome.NOTHING_LANDED
    if (practiceRowStillOpen(row.status) == true) return PracticeOutcome.WORKING
    return when {
        row.status != TranscriptEntity.STATUS_COMPLETED -> PracticeOutcome.NOTHING_LANDED
        row.insertionResult != InsertionResults.COMMITTED && row.insertionResult != InsertionResults.PASTED -> PracticeOutcome.NOTHING_LANDED
        lesson == PracticeLesson.HOLD && take.held != true -> PracticeOutcome.LANDED_BY_TAP
        else -> PracticeOutcome.LANDED
    }
}

/** What the screen shows for the followed take, and the verdict to report: non-null at most once per take. */
internal data class PracticeJudgement(val shown: PracticeOutcome?, val report: PracticeOutcome?)

/**
 * The one practice take the screen follows, and its one `onboarding.practice` verdict (#560). The owner's
 * two signals are independent: the snapshot going IDLE ends the take, and the History row reaching its final
 * status says where the words went, and IDLE routinely comes first. So the verdict is reported only once it
 * is final, never while it is [PracticeOutcome.WORKING], and only once per take. Pure, so every order the
 * two signals can arrive in is replayed by `OnboardingPolicyTest` without a phone.
 */
internal class PracticeTakeFollower {
    var take: PracticeTake? = null
        private set
    private var reported = false

    /**
     * The owner's snapshot, reduced by the caller: [ours] is a take aimed at the practice box, false once the
     * owner is IDLE. [transcriptId] and [held] are the snapshot's, null until it names them.
     */
    fun follow(ours: Boolean, transcriptId: Long?, held: Boolean?) {
        val current = take
        take = if (ours) {
            if (current == null || current.ended) {
                reported = false
                PracticeTake(held = held, transcriptId = transcriptId)
            } else {
                current.copy(held = current.held ?: held, transcriptId = current.transcriptId ?: transcriptId)
            }
        } else {
            current?.let { if (it.ended) it else it.copy(ended = true) }
        }
    }

    /** Null while no take is followed. */
    fun judge(lesson: PracticeLesson, rows: List<TranscriptEntity>): PracticeJudgement? {
        val current = take ?: return null
        val outcome = judgePracticeTake(current, lesson, rows)
        val report = outcome?.takeIf { it != PracticeOutcome.WORKING && !reported }
        if (report != null) reported = true
        return PracticeJudgement(outcome, report)
    }

    /** The take is no longer the screen's: a take it stopped watching, or the lesson moved on. */
    fun forget() {
        take = null
    }
}

/** The models guided setup downloads before anything else. */
private fun AppReadiness.setupModelsReady(): Boolean = speechModelReady && onboardingPolishModelReady

/** What the permissions screen requires before the demo; notifications are optional. */
internal fun AppReadiness.setupPermissionsReady(autoPaste: AutoPasteAvailability): Boolean =
    microphoneGranted && autoPaste == AutoPasteAvailability.LIVE

/** Restored navigation never bypasses the facts required by the next screen. */
internal fun onboardingStage(step: Int, readiness: AppReadiness, autoPaste: AutoPasteAvailability): OnboardingStage {
    val requested = OnboardingStage.entries.getOrElse(step) { OnboardingStage.WELCOME }
    if (requested == OnboardingStage.WELCOME) return requested
    if (!readiness.setupModelsReady()) return OnboardingStage.DOWNLOADS
    if (requested == OnboardingStage.DOWNLOADS) return OnboardingStage.PERMISSIONS
    if ((requested == OnboardingStage.DEMO || requested == OnboardingStage.PRACTICE) &&
        !readiness.setupPermissionsReady(autoPaste)
    ) return OnboardingStage.PERMISSIONS
    return requested
}

/**
 * The step to store when the shown [stage] is AHEAD of the stored [step] (#563), else null. Only one
 * advance happens without a tap: the downloads finishing moves DOWNLOADS on to PERMISSIONS, and nothing
 * recorded it, so `onboarding.stage_reached{stage="permissions"}` left for 1 install in 107. Recording it
 * through the same step write as every tapped move reports it once. A stage BEHIND the stored step (a
 * permission lost, or the auto-paste service still connecting at a cold start) is never stored: the
 * restored step comes back once the fact does.
 */
internal fun onboardingStepToRecord(step: Int, stage: OnboardingStage): Int? {
    val requested = OnboardingStage.entries.getOrElse(step) { OnboardingStage.WELCOME }
    return stage.ordinal.takeIf { stage > requested }
}

/**
 * #563: setup finished in substance by a user who left the guided flow with "Set up later". Every fact the
 * flow requires is in place (its two models, the microphone, live auto-paste) and one of their dictations
 * completed, yet `onboarding_complete` stayed false for as long as they kept using the bubble. Only a
 * DISMISSED setup qualifies: the flow's screens are not showing then, whereas settling a setup the user is
 * still walking through would close it under them, and practice takes complete rows too.
 */
internal fun onboardingSettledOutsideTheFlow(
    preferences: AppPreferencesState,
    readiness: AppReadiness,
    autoPaste: AutoPasteAvailability,
    rows: List<TranscriptEntity>,
): Boolean =
    preferences.onboardingDismissed &&
        !preferences.onboardingComplete &&
        readiness.setupModelsReady() &&
        readiness.setupPermissionsReady(autoPaste) &&
        rows.any { it.status == TranscriptEntity.STATUS_COMPLETED }

/** A slow file check cannot overwrite a newer permission answer. */
internal fun AppReadiness.withVerifiedModels(checked: AppReadiness): AppReadiness = copy(
    speechModelReady = checked.speechModelReady,
    polishModelReady = checked.polishModelReady,
    onboardingPolishModelReady = checked.onboardingPolishModelReady,
    eg1PolishModelReady = checked.eg1PolishModelReady,
    modelsChecked = checked.modelsChecked,
)
