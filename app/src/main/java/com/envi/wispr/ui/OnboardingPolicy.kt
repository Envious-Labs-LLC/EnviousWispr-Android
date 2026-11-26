package com.envi.wispr.ui

import com.envi.wispr.history.TranscriptEntity
import com.envi.wispr.insertion.InsertionResults
import com.envi.wispr.paste.AutoPasteAvailability
import com.envi.wispr.paste.DictationTargetPin
import com.envi.wispr.settings.AppPreferences
import com.envi.wispr.telemetry.AnalyticsEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
 * Three answers, read off the take's terminal delivery outcome (falling back to its History row when
 * no `takeId` is present). Earlier rounds judged extra editor state and each found an ordering that
 * lied (Codex reviews 1 to 5, 2026-09-14); judging the authoritative delivery outcome directly keeps
 * History failures from turning a landed take into `NOTHING_LANDED` (#560).
 */
internal enum class PracticeOutcome {
    /** The take is still finishing: transcribing, polishing, or inserting (bounded by the owner). */
    WORKING,
    /** The words are in the practice box, by the route any other app gets. */
    LANDED,
    /** Landed, but the hold lesson was answered with a tap. Still a success; the hint asks for a hold. */
    LANDED_BY_TAP,
    /**
     * Nothing landed: silent, cancelled, failed, or the words missed the box. None of them put words
     * in the box, so the screen says that one thing and asks for another try.
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
 * Maps a take's terminal delivery [result] (`COMMITTED`/`PASTED` vs fallback/wordless/teardown) to its
 * practice verdict (#560). Independent of whether History persisted a row.
 */
internal fun practiceOutcomeForResult(result: String, lesson: PracticeLesson, held: Boolean?): PracticeOutcome = when {
    !OnboardingSettlement.isLandedInsertionResult(result) -> PracticeOutcome.NOTHING_LANDED
    lesson == PracticeLesson.HOLD && held != true -> PracticeOutcome.LANDED_BY_TAP
    else -> PracticeOutcome.LANDED
}

/**
 * What the practice screen says about [take]: null while the owner is busy with it; otherwise read
 * off [terminalResults] keyed by [PracticeTake.takeId] first (authoritative even when History save
 * fails), falling back to the matching History row. An identified take with no terminal result and
 * no final row yet stays WORKING (#560).
 */
internal fun judgePracticeTake(
    take: PracticeTake,
    lesson: PracticeLesson,
    rows: List<TranscriptEntity>,
    terminalResults: Map<String, String> = emptyMap(),
): PracticeOutcome? {
    if (!take.ended) return null
    take.takeId?.let { id ->
        terminalResults[id]?.let { result -> return practiceOutcomeForResult(result, lesson, take.held) }
    }
    val row = take.takeId?.let { id -> rows.firstOrNull { it.takeId == id } }
        ?: take.transcriptId?.let { id -> rows.firstOrNull { it.id == id } }
    if (row == null) return if (take.takeId != null) PracticeOutcome.WORKING else PracticeOutcome.NOTHING_LANDED
    if (practiceRowStillOpen(row.status) == true) return PracticeOutcome.WORKING
    return when {
        row.status != TranscriptEntity.STATUS_COMPLETED -> PracticeOutcome.NOTHING_LANDED
        else -> practiceOutcomeForResult(row.insertionResult, lesson, take.held)
    }
}

/** One finalized practice verdict recorded by [PracticeTakeTracker]. */
internal data class PracticeVerdict(val lesson: PracticeLesson, val outcome: PracticeOutcome)

/** Atomic snapshot published by [PracticeTakeTracker] so UI outcome and completion unlock never split across frames. */
internal data class PracticeTrackerState(
    val terminalResults: Map<String, String> = emptyMap(),
    val verdicts: Map<String, PracticeVerdict> = emptyMap(),
)

/**
 * Application-owned tracker for identified onboarding practice takes (#560).
 *
 * Joins two independent signals keyed by `takeId`:
 * 1. [track] from `OnboardingViewModel` once the owner names the practice box as target, with the lesson and gesture.
 * 2. [onTerminal] from the delivery paths (`InsertionOutcomeRecorder`, `SessionFinalizer`, `DictationSessionCoordinator`).
 *
 * Whichever arrives first, once BOTH exist for a `takeId`, this tracker emits `AnalyticsEvent.OnboardingPractice`
 * exactly once — even if the user left the practice screen before IDLE or while WORKING, and even if History
 * failed to save the row. Bounded to [MAX_ENTRIES] recent entries so idle memory never grows without limit.
 */
internal class PracticeTakeTracker(
    private val capture: (AnalyticsEvent) -> Unit,
) {
    private data class Registration(val lesson: PracticeLesson, val held: Boolean?)

    private val lock = Any()
    private val tracked = LinkedHashMap<String, Registration>()
    private val terminals = LinkedHashMap<String, String>()
    private val reported = LinkedHashMap<String, PracticeVerdict>()
    private val stateFlow = MutableStateFlow(PracticeTrackerState())

    /** Atomic terminal delivery results and reported practice verdicts (bounded), observed by `OnboardingViewModel`. */
    val state: StateFlow<PracticeTrackerState> = stateFlow.asStateFlow()

    fun track(takeId: String, lesson: PracticeLesson, held: Boolean?) {
        if (takeId.isBlank()) return
        val event = synchronized(lock) {
            if (takeId in reported) return
            val existing = tracked[takeId]
            val updated = if (existing == null) Registration(lesson, held) else existing.copy(held = existing.held ?: held)
            putBounded(tracked, takeId, updated)
            val result = terminals[takeId] ?: return
            reportLocked(takeId, updated, result).also { publishLocked() }
        }
        runCatching { capture(event) }
    }

    fun onTerminal(takeId: String, result: String) {
        if (takeId.isBlank()) return
        val event = synchronized(lock) {
            if (takeId in terminals) return
            putBounded(terminals, takeId, result)
            val pending = if (takeId in reported) null else tracked[takeId]?.let { reportLocked(takeId, it, result) }
            publishLocked()
            pending
        }
        if (event != null) runCatching { capture(event) }
    }

    private fun reportLocked(takeId: String, registration: Registration, result: String): AnalyticsEvent.OnboardingPractice {
        val outcome = practiceOutcomeForResult(result, registration.lesson, registration.held)
        putBounded(reported, takeId, PracticeVerdict(registration.lesson, outcome))
        tracked.remove(takeId)
        return AnalyticsEvent.OnboardingPractice(registration.lesson.name.lowercase(), outcome.name.lowercase())
    }

    private fun publishLocked() {
        stateFlow.value = PracticeTrackerState(terminals.toMap(), reported.toMap())
    }

    private fun <V> putBounded(map: LinkedHashMap<String, V>, key: String, value: V) {
        map[key] = value
        while (map.size > MAX_ENTRIES) {
            val oldest = map.entries.iterator().next().key
            map.remove(oldest)
        }
    }

    companion object {
        const val MAX_ENTRIES = 64
    }
}

/** What the practice screen renders for its visible take, plus any completion earned in this session. */
internal data class PracticeScreenJudgement(
    val shown: PracticeOutcome?,
    val landedTap: Boolean = false,
    val landedHold: Boolean = false,
)

/**
 * Screen-side state for the visible practice take, delegating durable telemetry tracking to the
 * application-owned [PracticeTakeTracker] (#560). Only the visible take drives UI `practiceOutcome`,
 * while takes handed to [tracker] still emit `onboarding.practice` once if the user leaves Practice
 * mid-take or while WORKING.
 */
internal class PracticeTakeFollower(
    private val tracker: PracticeTakeTracker,
) {
    private class Followed(val take: PracticeTake, val lesson: PracticeLesson)

    private var visible: Followed? = null
    /** Identified takes started in this visible practice session (excluding takes left mid-flight before IDLE). */
    private val sessionTakeIds = LinkedHashSet<String>()

    val take: PracticeTake? get() = visible?.take

    fun follow(ours: Boolean, transcriptId: Long?, held: Boolean?, lesson: PracticeLesson, takeId: String? = null) {
        val current = visible
        if (ours) {
            val replaced = current != null && !current.take.ended &&
                takeId != null && current.take.takeId != null && current.take.takeId != takeId
            if (current == null || current.take.ended || replaced) {
                // Assign visible and sessionTakeIds BEFORE tracker.track() so any synchronous collector reaction sees them.
                val next = Followed(PracticeTake(held = held, transcriptId = transcriptId, takeId = takeId), lesson)
                visible = next
                if (takeId != null) {
                    rememberSessionTake(takeId)
                    tracker.track(takeId, lesson, held)
                }
            } else {
                val merged = current.take.copy(
                    held = current.take.held ?: held,
                    transcriptId = current.take.transcriptId ?: transcriptId,
                    takeId = current.take.takeId ?: takeId,
                )
                visible = Followed(merged, current.lesson)
                merged.takeId?.let { id ->
                    rememberSessionTake(id)
                    tracker.track(id, current.lesson, merged.held)
                }
            }
        } else {
            visible = current?.let { if (it.take.ended) it else Followed(it.take.copy(ended = true), it.lesson) }
        }
    }

    /**
     * Called when leaving the practice screen (`leavePractice` / `onCleared`). Stops showing an in-flight
     * take (not yet IDLE) on UI return, while keeping all identified takes tracked in [tracker] so their
     * terminal outcome still emits `onboarding.practice` once. Returns true when UI state should clear.
     */
    fun leave(): Boolean {
        val current = visible ?: return false
        if (current.take.ended) return false
        current.take.takeId?.let(sessionTakeIds::remove)
        visible = null
        return true
    }

    /** Clears the visible take when advancing lessons; [tracker] continues tracking any earlier take. */
    fun forgetVisible() {
        visible = null
    }

    fun judge(
        rows: List<TranscriptEntity>,
        trackerState: PracticeTrackerState = tracker.state.value,
    ): PracticeScreenJudgement? {
        var landedTap = false
        var landedHold = false
        for (id in sessionTakeIds) {
            val verdict = trackerState.verdicts[id] ?: continue
            when (verdict.outcome) {
                PracticeOutcome.LANDED -> if (verdict.lesson == PracticeLesson.HOLD) landedHold = true else landedTap = true
                PracticeOutcome.LANDED_BY_TAP -> landedTap = true
                PracticeOutcome.WORKING, PracticeOutcome.NOTHING_LANDED -> Unit
            }
        }
        val current = visible ?: return if (landedTap || landedHold) PracticeScreenJudgement(null, landedTap, landedHold) else null
        // Check practice-only verdicts BEFORE terminalResults so external dictations evicting terminals never regress an ended take to WORKING.
        val shown = if (!current.take.ended) {
            null
        } else {
            current.take.takeId?.let { trackerState.verdicts[it]?.outcome }
                ?: judgePracticeTake(current.take, current.lesson, rows, trackerState.terminalResults)
        }
        if (current.take.takeId == null) {
            when (shown) {
                PracticeOutcome.LANDED -> if (current.lesson == PracticeLesson.HOLD) landedHold = true else landedTap = true
                PracticeOutcome.LANDED_BY_TAP -> landedTap = true
                PracticeOutcome.WORKING, PracticeOutcome.NOTHING_LANDED, null -> Unit
            }
        }
        return PracticeScreenJudgement(shown, landedTap, landedHold)
    }

    private fun rememberSessionTake(takeId: String) {
        sessionTakeIds.add(takeId)
        while (sessionTakeIds.size > PracticeTakeTracker.MAX_ENTRIES) {
            val oldest = sessionTakeIds.iterator().next()
            sessionTakeIds.remove(oldest)
        }
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
 * through the atomic advance write reports it once. A stage BEHIND the stored step (a permission lost,
 * or the auto-paste service still connecting at a cold start) is never stored: the restored step comes
 * back once the fact does.
 */
internal fun onboardingStepToRecord(step: Int, stage: OnboardingStage): Int? {
    val requested = OnboardingStage.entries.getOrElse(step) { OnboardingStage.WELCOME }
    return stage.ordinal.takeIf { stage > requested }
}

/**
 * #563: whether a take targeting [targetFieldId] with [pin] is eligible to settle `onboarding_complete`
 * once its insertion actually lands (`COMMITTED` or `PASTED`). Practice takes are excluded so setup
 * never closes under the user mid-lesson.
 */
internal fun isExternalSettlementTarget(pin: DictationTargetPin, targetFieldId: String?): Boolean =
    pin == DictationTargetPin.PINNED && targetFieldId != OnboardingViewModel.PRACTICE_FIELD_ID

/**
 * Settles `onboarding_complete = true` once an eligible external take actually lands (`COMMITTED` or
 * `PASTED`, #563), so `app.launched` stops reporting an active bubble user as never set up without
 * settling on a failed handoff or failed insertion. Application-owned (`ModelBootstrapApplication`),
 * because the session's Service stops right after handoff and most such takes start with the app closed.
 * No `onboarding.completed` row is emitted, and `onboardingDismissed` is not required.
 */
internal class OnboardingSettlement(
    private val preferences: AppPreferences,
    private val setupModelsReady: () -> Boolean,
    private val scope: CoroutineScope,
) {
    private val lock = Any()
    private val candidates = LinkedHashSet<String>()
    private val earlyTerminals = LinkedHashMap<String, String>()

    /**
     * Registers [takeId] as an eligible external take BEFORE `finalizer.deliver()`. Bounded to
     * [MAX_ENTRIES] entries; settles immediately if a landed terminal already arrived for [takeId].
     */
    fun registerCandidate(takeId: String): Job? {
        if (takeId.isBlank()) return null
        val earlyResult = synchronized(lock) {
            earlyTerminals.remove(takeId) ?: run {
                candidates.add(takeId)
                while (candidates.size > MAX_ENTRIES) {
                    val oldest = candidates.iterator().next()
                    candidates.remove(oldest)
                }
                return null
            }
        }
        return if (isLandedInsertionResult(earlyResult)) settleAsync() else null
    }

    /**
     * Delivers a take's terminal result. Evicts any candidate for [takeId]; settles ONLY when [takeId]
     * was registered and [result] is `COMMITTED` or `PASTED`.
     */
    fun onTerminal(takeId: String, result: String): Job? {
        if (takeId.isBlank()) return null
        val eligible = synchronized(lock) {
            if (candidates.remove(takeId)) {
                true
            } else {
                if (takeId !in earlyTerminals) {
                    earlyTerminals[takeId] = result
                    while (earlyTerminals.size > MAX_ENTRIES) {
                        val oldest = earlyTerminals.entries.iterator().next().key
                        earlyTerminals.remove(oldest)
                    }
                }
                false
            }
        }
        return if (eligible && isLandedInsertionResult(result)) settleAsync() else null
    }

    /** Exposed for unit tests to verify failed/cancelled candidates are evicted cleanly. */
    internal fun pendingCandidateCount(): Int = synchronized(lock) { candidates.size }

    private fun settleAsync(): Job = scope.launch {
        runCatching {
            if (preferences.authoritativeState.first().onboardingComplete) return@runCatching
            if (setupModelsReady()) preferences.completeOnboarding()
        }
    }

    companion object {
        const val MAX_ENTRIES = 64

        internal fun isLandedInsertionResult(result: String): Boolean =
            result == InsertionResults.COMMITTED || result == InsertionResults.PASTED
    }
}

/**
 * Application-owned fan-out for every take's terminal delivery outcome (#560, #563): forwards each
 * `(takeId, result)` to both [PracticeTakeTracker] and [OnboardingSettlement], independently of
 * History persistence and telemetry opt-in, never blocking or throwing into its caller.
 */
internal class TakeDeliveryObserver(
    val practice: PracticeTakeTracker,
    val settlement: OnboardingSettlement,
) {
    fun onTerminal(takeId: String, result: String) {
        if (takeId.isBlank()) return
        runCatching { practice.onTerminal(takeId, result) }
        runCatching { settlement.onTerminal(takeId, result) }
    }

    companion object {
        /** Explicit no-delivery sentinel for wordless endings and pre-handoff teardowns. */
        const val RESULT_NOT_DELIVERED = "not_delivered"
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
