package com.envi.wispr.ui

import com.envi.wispr.history.TranscriptEntity
import com.envi.wispr.insertion.InsertionResults
import com.envi.wispr.paste.AutoPasteAvailability
import com.envi.wispr.paste.DictationTargetPin
import com.envi.wispr.paste.InsertionHandoff
import com.envi.wispr.settings.AppPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

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
 * row the owner created for it (null until it has), [ended] is the owner back at IDLE, and [takeId]
 * is the stable take id published at `showStarting` before any row exists (#560). Nothing is
 * inferred from time or from the order of rows (Codex reviews 2 to 9, 2026-09-14).
 */
internal data class PracticeTake(
    val held: Boolean?,
    val transcriptId: Long? = null,
    val ended: Boolean = false,
    val takeId: String? = null,
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
 * off the row matching [PracticeTake.takeId] (falling back to [PracticeTake.transcriptId]). The owner
 * goes IDLE before its row is final (`surface.hide()` does not wait for History or the route), so a
 * row still open OR not yet emitted by Room for an identified take that has not ended without a row
 * ([endedWithoutRow]) is WORKING. Only an explicit wordless/save-failed ending or a final non-landed
 * row yields NOTHING_LANDED (#560).
 */
internal fun judgePracticeTake(
    take: PracticeTake,
    lesson: PracticeLesson,
    rows: List<TranscriptEntity>,
    endedWithoutRow: Set<String> = emptySet(),
): PracticeOutcome? {
    if (!take.ended) return null
    if (take.takeId != null && take.takeId in endedWithoutRow) return PracticeOutcome.NOTHING_LANDED
    val row = take.takeId?.let { id -> rows.firstOrNull { it.takeId == id } }
        ?: take.transcriptId?.let { id -> rows.firstOrNull { it.id == id } }
    if (row == null) return if (take.takeId != null) PracticeOutcome.WORKING else PracticeOutcome.NOTHING_LANDED
    if (practiceRowStillOpen(row.status) == true) return PracticeOutcome.WORKING
    return when {
        row.status != TranscriptEntity.STATUS_COMPLETED -> PracticeOutcome.NOTHING_LANDED
        row.insertionResult != InsertionResults.COMMITTED && row.insertionResult != InsertionResults.PASTED -> PracticeOutcome.NOTHING_LANDED
        lesson == PracticeLesson.HOLD && take.held != true -> PracticeOutcome.LANDED_BY_TAP
        else -> PracticeOutcome.LANDED
    }
}

/** One `onboarding.practice` row: the lesson the take answered and its final verdict. */
internal data class PracticeReport(val lesson: PracticeLesson, val outcome: PracticeOutcome)

/** What the screen shows for the latest take, and the rows to report now: each take's final verdict, once. */
internal data class PracticeJudgement(val shown: PracticeOutcome?, val reports: List<PracticeReport>)

/**
 * The practice takes the screen follows, and their `onboarding.practice` rows (#560). The owner's signals
 * are independent: the snapshot going IDLE ends the take, `endedWithoutRow` marks takes with no saved row,
 * and the History row reaching its final status says where the words went; IDLE routinely comes first. So a
 * take is reported only once its verdict is final, never while it is [PracticeOutcome.WORKING], and only
 * once. A take that ended and is still WORKING when the next one starts stays followed until its final
 * verdict arrives; only the latest take drives the screen. Pure, so every order the signals can arrive in
 * is replayed by `OnboardingTelemetryTest` without a phone.
 */
internal class PracticeTakeFollower {
    private class Followed(val take: PracticeTake, val lesson: PracticeLesson)

    private var latest: Followed? = null
    private var latestReported = false
    /** Ended takes whose verdict was still WORKING when a newer take started. */
    private val unresolved = mutableListOf<Followed>()

    val take: PracticeTake? get() = latest?.take

    /**
     * The owner's snapshot, reduced by the caller: [ours] is a take aimed at the practice box, false once the
     * owner is IDLE. [takeId], [transcriptId] and [held] are the snapshot's, null until it names them;
     * [lesson] is the lesson on screen when the take starts.
     */
    fun follow(ours: Boolean, transcriptId: Long?, held: Boolean?, lesson: PracticeLesson, takeId: String? = null) {
        val current = latest
        latest = if (ours) {
            val replaced = current != null && !current.take.ended &&
                takeId != null && current.take.takeId != null && current.take.takeId != takeId
            if (current == null || current.take.ended || replaced) {
                if (current != null && !latestReported) {
                    val ended = if (current.take.ended) current else Followed(current.take.copy(ended = true), current.lesson)
                    unresolved += ended
                }
                latestReported = false
                Followed(PracticeTake(held = held, transcriptId = transcriptId, takeId = takeId), lesson)
            } else {
                val take = current.take
                Followed(
                    take.copy(
                        held = take.held ?: held,
                        transcriptId = take.transcriptId ?: transcriptId,
                        takeId = take.takeId ?: takeId,
                    ),
                    current.lesson,
                )
            }
        } else {
            current?.let { if (it.take.ended) it else Followed(it.take.copy(ended = true), it.lesson) }
        }
    }

    /** Null while no take is followed. */
    fun judge(rows: List<TranscriptEntity>, endedWithoutRow: Set<String> = emptySet()): PracticeJudgement? {
        val reports = mutableListOf<PracticeReport>()
        unresolved.removeAll { followed ->
            val outcome = judgePracticeTake(followed.take, followed.lesson, rows, endedWithoutRow)
                ?.takeIf { it != PracticeOutcome.WORKING }
                ?: return@removeAll false
            reports += PracticeReport(followed.lesson, outcome)
            true
        }
        val current = latest ?: return if (reports.isEmpty()) null else PracticeJudgement(null, reports)
        val outcome = judgePracticeTake(current.take, current.lesson, rows, endedWithoutRow)
        if (outcome != null && outcome != PracticeOutcome.WORKING && !latestReported) {
            latestReported = true
            reports += PracticeReport(current.lesson, outcome)
        }
        return PracticeJudgement(outcome, reports)
    }

    /**
     * The latest take is no longer the screen's: one it stopped watching mid-way, or the lesson moved on. An
     * ended take still waiting for its row stays followed, since its identity is known.
     */
    fun forget() {
        val current = latest
        if (current != null && current.take.ended && !latestReported) unresolved += current
        latest = null
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
 * #563: one take proves setup works when it was pinned to an editor and handed to auto-paste, in a field that
 * is not the practice box. That take needed the microphone, the speech model and a live auto-paste service, so
 * a user who dictates this way is set up whether or not they ever finished, or dismissed, the guided flow.
 * Practice takes are excluded: settling then would close setup under the user mid-lesson.
 */
internal fun dictationProvesSetup(pin: DictationTargetPin, targetFieldId: String?, handoff: InsertionHandoff): Boolean =
    pin == DictationTargetPin.PINNED &&
        handoff == InsertionHandoff.SCHEDULED &&
        targetFieldId != OnboardingViewModel.PRACTICE_FIELD_ID

/**
 * Marks setup complete after a take that [dictationProvesSetup] (#563), so `app.launched` stops reporting an
 * active bubble user as never set up. Application-owned (`ModelBootstrapApplication`), because the session's
 * Service stops right after the handoff and most such takes start with the app closed. The guided flow's own
 * models must be on the phone too. No `onboarding.completed` row: that row is the guided flow's last step,
 * which this user did not take there. The write is never awaited and never fails the take.
 */
internal class OnboardingSettlement(
    private val preferences: AppPreferences,
    private val setupModelsReady: () -> Boolean,
    private val scope: CoroutineScope,
) {
    fun dictationProvedSetup(): Job = scope.launch {
        runCatching {
            if (preferences.authoritativeState.first().onboardingComplete) return@runCatching
            if (setupModelsReady()) preferences.completeOnboarding()
        }
    }
}

/** A slow file check cannot overwrite a newer permission answer. */
internal fun AppReadiness.withVerifiedModels(checked: AppReadiness): AppReadiness = copy(
    speechModelReady = checked.speechModelReady,
    polishModelReady = checked.polishModelReady,
    onboardingPolishModelReady = checked.onboardingPolishModelReady,
    eg1PolishModelReady = checked.eg1PolishModelReady,
    modelsChecked = checked.modelsChecked,
)
