package com.envi.wispr.ui

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import com.envi.wispr.history.TranscriptEntity
import com.envi.wispr.insertion.InsertionResults
import com.envi.wispr.paste.AutoPasteAvailability
import com.envi.wispr.settings.AppPreferences
import com.envi.wispr.telemetry.AnalyticsEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Observability Contract (#560, #563): the onboarding rows report what happened, once. Not product coverage:
 * the practice screen's own verdict rules are `OnboardingPolicyTest`'s.
 *
 * The practice rows replay the owner's independent signals through the app-owned [PracticeTakeTracker] and
 * [PracticeTakeFollower]: snapshot IDLE (`surface.hide()`), terminal delivery outcomes arriving before or
 * after registration and before or after IDLE, History save failures, leaving Practice mid-take or while
 * WORKING, and overlapping takes. The step, completion, and external-settlement rows drive the shell view
 * model and [OnboardingSettlement] against real preferences files on a multi-threaded dispatcher.
 *
 * Competing writes are LAUNCHED before either is joined (`test-honesty.md` RULE:
 * a-single-threaded-test-cannot-distinguish-atomic-from-check-then-act).
 *
 * REVERTS that turn rows red: drop `STATUS_SAVED_UNROUTED` from `practiceRowStillOpen`; return NOTHING_LANDED
 * when an identified take has no terminal result yet; ignore terminal delivery when History rows are empty;
 * drop tracked takes on `leave()`; settle `onboardingComplete` on `registerCandidate` before COMMITTED/PASTED;
 * keep failed candidates queued; emit `OnboardingStageReached` without checking the stored step changed; let
 * `advanceOnboardingStep` write past a dismissal or Back/Demo tap; emit `OnboardingCompleted` on every call.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class OnboardingTelemetryTest {
    @get:Rule val tempFolder = TemporaryFolder()

    // ---- #560: app-owned PracticeTakeTracker + PracticeTakeFollower ----

    private fun row(status: String, result: String, id: Long = 6L, takeId: String? = "take-$id") = TranscriptEntity(
        id = id, originalText = "um tell grandma", finalText = "Tell Grandma.", createdAtMs = 1_000L, durationMs = 1_000L,
        speechEngine = "Parakeet", polishEngine = "none", polishLatencyMs = 0L, insertionResult = result, status = status,
        takeId = takeId,
    )

    private val immediateScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    private fun newTracker(): Pair<PracticeTakeTracker, MutableList<Pair<String, String>>> {
        val reports = mutableListOf<Pair<String, String>>()
        val tracker = PracticeTakeTracker(
            capture = { event ->
                val practice = event as AnalyticsEvent.OnboardingPractice
                synchronized(reports) { reports += practice.lesson to practice.outcome }
            },
            scope = immediateScope,
        )
        return tracker to reports
    }

    private fun PracticeTakeFollower.startTake(
        takeId: String = "take-6",
        transcriptId: Long? = 6L,
        held: Boolean = false,
        lesson: PracticeLesson = PracticeLesson.TAP,
    ) {
        follow(ours = true, transcriptId = null, held = held, lesson = lesson, takeId = takeId)
        if (transcriptId != null) {
            follow(ours = true, transcriptId = transcriptId, held = held, lesson = lesson, takeId = takeId)
        }
    }

    private fun PracticeTakeFollower.ownerHides() =
        follow(ours = false, transcriptId = null, held = null, lesson = PracticeLesson.TAP, takeId = null)

    @Test fun theOwnerGoingIdleBeforeTerminalResultCommitsReportsLandedOnce() {
        val (tracker, reports) = newTracker()
        val practice = PracticeTakeFollower(tracker)
        // attachTranscript never arrived before hide(); only takeId is known.
        practice.startTake(takeId = "take-6", transcriptId = null)
        assertEquals(PracticeScreenJudgement(null), practice.judge(emptyList()))
        practice.ownerHides()
        // Missing row and in-flight statuses stay WORKING (never prematurely latch NOTHING_LANDED).
        assertEquals(PracticeScreenJudgement(PracticeOutcome.WORKING), practice.judge(emptyList()))
        assertEquals(PracticeScreenJudgement(PracticeOutcome.WORKING), practice.judge(listOf(row(TranscriptEntity.STATUS_SAVED_UNROUTED, "pending"))))
        assertEquals(PracticeScreenJudgement(PracticeOutcome.WORKING), practice.judge(listOf(row(TranscriptEntity.STATUS_READY_FOR_INSERTION, "pending"))))
        assertEquals(emptyList<Pair<String, String>>(), reports)
        // Authoritative terminal COMMITTED lands even if Room still has empty rows (e.g. failed History save).
        tracker.onTerminal("take-6", InsertionResults.COMMITTED)
        assertEquals(PracticeScreenJudgement(PracticeOutcome.LANDED, landedTap = true), practice.judge(emptyList()))
        // Duplicate terminal or later History emissions never double-report.
        tracker.onTerminal("take-6", InsertionResults.COMMITTED)
        practice.judge(listOf(row(TranscriptEntity.STATUS_COMPLETED, InsertionResults.COMMITTED)))
        assertEquals(listOf("tap" to "landed"), reports)
    }

    @Test fun aTerminalResultArrivingBeforeRegistrationOrBeforeIdleReportsOnce() {
        val (tracker, reports) = newTracker()
        val practice = PracticeTakeFollower(tracker)
        // Order A: terminal arrives BEFORE IDLE on a HOLD take.
        practice.startTake(takeId = "take-hold", held = true, lesson = PracticeLesson.HOLD)
        tracker.onTerminal("take-hold", InsertionResults.PASTED)
        assertEquals(listOf("hold" to "landed"), reports)
        // UI keeps verdict null while still running, then shows LANDED once IDLE.
        assertNull(checkNotNull(practice.judge(emptyList())).shown)
        practice.ownerHides()
        assertEquals(PracticeScreenJudgement(PracticeOutcome.LANDED, landedHold = true), practice.judge(emptyList()))
        assertEquals(listOf("hold" to "landed"), reports)

        // Order B: terminal arrives BEFORE track() registration (e.g. ultra-fast cancel).
        tracker.onTerminal("take-fast-cancel", TakeDeliveryObserver.RESULT_NOT_DELIVERED)
        assertEquals(listOf("hold" to "landed"), reports)
        practice.startTake(takeId = "take-fast-cancel", transcriptId = null, held = false, lesson = PracticeLesson.TAP)
        practice.ownerHides()
        assertEquals(PracticeOutcome.NOTHING_LANDED, checkNotNull(practice.judge(emptyList())).shown)
        assertEquals(listOf("hold" to "landed", "tap" to "nothing_landed"), reports)
    }

    @Test fun wordlessAndNonLandedDeliveryResultsReportNothingLandedOnce() {
        val (tracker, reports) = newTracker()
        val practice = PracticeTakeFollower(tracker)
        for ((index, result) in listOf(
            InsertionResults.COPY_ONLY,
            InsertionResults.CLIPBOARD,
            InsertionResults.HISTORY_ONLY,
            InsertionResults.INSERTION_FAILED,
            TakeDeliveryObserver.RESULT_NOT_DELIVERED,
        ).withIndex()) {
            val id = "take-fail-$index"
            practice.startTake(takeId = id, transcriptId = null)
            practice.ownerHides()
            assertEquals(PracticeOutcome.WORKING, checkNotNull(practice.judge(emptyList())).shown)
            tracker.onTerminal(id, result)
            assertEquals(PracticeOutcome.NOTHING_LANDED, checkNotNull(practice.judge(emptyList())).shown)
            tracker.onTerminal(id, result)
        }
        assertEquals(List(5) { "tap" to "nothing_landed" }, reports)
    }

    /** Leaving Practice mid-take (before IDLE) still reports its verdict once; later non-practice takes are ignored. */
    @Test fun leavingPracticeMidTakeStillReportsOnceAndDoesNotAdoptLaterTakes() {
        val (tracker, reports) = newTracker()
        val practice = PracticeTakeFollower(tracker)
        practice.startTake(takeId = "take-mid", held = false, lesson = PracticeLesson.TAP)
        assertTrue("leaving before IDLE clears UI take", practice.leave())
        assertNull(practice.take)
        // Terminal arrives while user is away: app-owned tracker still emits once.
        tracker.onTerminal("take-mid", InsertionResults.COMMITTED)
        assertEquals(listOf("tap" to "landed"), reports)
        // A take started elsewhere after leavePractice() is never registered or adopted.
        tracker.onTerminal("take-external", InsertionResults.COMMITTED)
        assertEquals(listOf("tap" to "landed"), reports)
        // Re-entering Practice (even receiving another snapshot for the same take) does not double-report.
        tracker.track("take-mid", PracticeLesson.TAP, false)
        assertEquals(listOf("tap" to "landed"), reports)
    }

    /** Leaving Practice while WORKING (after IDLE) reports once while away and updates UI cleanly on return. */
    @Test fun leavingPracticeWhileWorkingReportsOnceAndShowsVerdictOnReturn() {
        val (tracker, reports) = newTracker()
        val practice = PracticeTakeFollower(tracker)
        practice.startTake(takeId = "take-working", held = false, lesson = PracticeLesson.TAP)
        practice.ownerHides()
        assertEquals(PracticeScreenJudgement(PracticeOutcome.WORKING), practice.judge(emptyList()))
        assertFalse("ended take stays visible for return", practice.leave())
        // Terminal arrives while paused: emitted once immediately.
        tracker.onTerminal("take-working", InsertionResults.COMMITTED)
        assertEquals(listOf("tap" to "landed"), reports)
        // User returns to Practice: UI sees LANDED and unlocks completion without double-reporting.
        assertEquals(PracticeScreenJudgement(PracticeOutcome.LANDED, landedTap = true), practice.judge(emptyList()))
        assertEquals(listOf("tap" to "landed"), reports)
    }

    /** A second take while the first is still resolving (including when IDLE was conflated): each gets its own report. */
    @Test fun aTakeStillResolvingWhenTheNextStartsReportsBothAndCreditsCompletion() {
        val (tracker, reports) = newTracker()
        val practice = PracticeTakeFollower(tracker)
        practice.startTake(takeId = "take-6", transcriptId = null, held = true, lesson = PracticeLesson.HOLD)
        // Even if IDLE was conflated before take-7 began, switching to take-7 keeps take-6 tracked.
        practice.startTake(takeId = "take-7", transcriptId = 7L, held = false, lesson = PracticeLesson.TAP)
        assertEquals(PracticeScreenJudgement(null), practice.judge(emptyList()))
        // Take-6 commits while take-7 is still recording: reports HOLD/LANDED once, credits hold, UI stays on take-7.
        tracker.onTerminal("take-6", InsertionResults.COMMITTED)
        assertEquals(PracticeScreenJudgement(null, landedHold = true), practice.judge(emptyList()))
        assertEquals(listOf("hold" to "landed"), reports)
        // Take-7 finishes and fails delivery: reports TAP/NOTHING_LANDED once.
        practice.ownerHides()
        assertEquals(PracticeScreenJudgement(PracticeOutcome.WORKING, landedHold = true), practice.judge(emptyList()))
        tracker.onTerminal("take-7", InsertionResults.COPY_ONLY)
        assertEquals(PracticeScreenJudgement(PracticeOutcome.NOTHING_LANDED, landedHold = true), practice.judge(emptyList()))
        assertEquals(listOf("hold" to "landed", "tap" to "nothing_landed"), reports)
    }

    /** #560: external dictations evicting `terminals` (cap 64) never regress an ended no-row practice take to WORKING. */
    @Test fun evictingSharedTerminalCacheNeverRegressesAnEndedPracticeTakeToWorking() {
        val (tracker, reports) = newTracker()
        val practice = PracticeTakeFollower(tracker)
        // Case 1: no-row landed take (History save failed), user left while WORKING, 65 external takes evict terminals.
        practice.startTake(takeId = "take-practice-landed", transcriptId = null, held = false, lesson = PracticeLesson.TAP)
        practice.ownerHides()
        assertFalse(practice.leave())
        tracker.onTerminal("take-practice-landed", InsertionResults.COMMITTED)
        repeat(PracticeTakeTracker.MAX_ENTRIES + 1) { index ->
            tracker.onTerminal("external-$index", InsertionResults.COMMITTED)
        }
        assertFalse("precondition: practice takeId was evicted from shared terminals", "take-practice-landed" in tracker.state.value.terminalResults)
        assertEquals(PracticeScreenJudgement(PracticeOutcome.LANDED, landedTap = true), practice.judge(emptyList()))

        // Case 2: wordless no-row take whose terminal result was also evicted from terminals.
        practice.startTake(takeId = "take-practice-empty", transcriptId = null, held = false, lesson = PracticeLesson.TAP)
        practice.ownerHides()
        tracker.onTerminal("take-practice-empty", TakeDeliveryObserver.RESULT_NOT_DELIVERED)
        repeat(PracticeTakeTracker.MAX_ENTRIES + 1) { index ->
            tracker.onTerminal("external-2-$index", InsertionResults.COMMITTED)
        }
        assertFalse("take-practice-empty" in tracker.state.value.terminalResults)
        assertEquals(PracticeScreenJudgement(PracticeOutcome.NOTHING_LANDED, landedTap = true), practice.judge(emptyList()))
        assertEquals(listOf("tap" to "landed", "tap" to "nothing_landed"), reports)
    }

    /** #560: synchronous collector sees terminalResults + verdicts atomically, and follow() sets visible/session before track(). */
    @Test fun synchronousStateCollectorSeesAtomicOutcomeAndFollowOrder() = runBlocking {
        val (tracker, reports) = newTracker()
        val practice = PracticeTakeFollower(tracker)
        val observed = mutableListOf<Triple<String?, PracticeScreenJudgement?, PracticeTrackerState>>()
        val collector = launch(Dispatchers.Unconfined) {
            tracker.state.collect { state ->
                observed += Triple(practice.take?.takeId, practice.judge(emptyList(), state), state)
            }
        }
        try {
            // Order A: normal flow — onTerminal updates terminalResults AND verdicts in one atomic StateFlow emission.
            practice.startTake(takeId = "take-atomic", transcriptId = null, held = false, lesson = PracticeLesson.TAP)
            practice.ownerHides()
            observed.clear()
            tracker.onTerminal("take-atomic", InsertionResults.COMMITTED)
            assertEquals("one atomic state emission on onTerminal", 1, observed.size)
            val (visibleId, judgement, state) = observed.single()
            assertEquals("take-atomic", visibleId)
            assertEquals(InsertionResults.COMMITTED, state.terminalResults["take-atomic"])
            assertEquals(PracticeVerdict(PracticeLesson.TAP, PracticeOutcome.LANDED), state.verdicts["take-atomic"])
            assertEquals(PracticeScreenJudgement(PracticeOutcome.LANDED, landedTap = true), judgement)

            // Order B: early terminal BEFORE follow() — synchronous collector triggered inside track() already sees visible + sessionTakeIds.
            tracker.onTerminal("take-early", InsertionResults.COMMITTED)
            observed.clear()
            practice.startTake(takeId = "take-early", transcriptId = null, held = true, lesson = PracticeLesson.HOLD)
            assertEquals("one atomic state emission when early-terminal take registers", 1, observed.size)
            val (earlyVisibleId, earlyJudgement, earlyState) = observed.single()
            assertEquals("visible must be set before tracker.track() emits", "take-early", earlyVisibleId)
            assertEquals(PracticeVerdict(PracticeLesson.HOLD, PracticeOutcome.LANDED), earlyState.verdicts["take-early"])
            assertEquals("sessionTakeIds must already contain take-early", PracticeScreenJudgement(null, landedTap = true, landedHold = true), earlyJudgement)
            assertEquals(listOf("tap" to "landed", "hold" to "landed"), reports)
        } finally {
            collector.cancel()
        }
    }

    // ---- #563: onboarding.stage_reached, onboarding.completed, and external settlement ----

    private val mainPool = Executors.newFixedThreadPool(2)
    private val mainDispatcher = mainPool.asCoroutineDispatcher()
    private val storeJob = Job()
    private val storeScope = CoroutineScope(storeJob + Dispatchers.IO)
    private val store by lazy { PreferenceDataStoreFactory.create(scope = storeScope) { File(tempFolder.root, "onboarding.preferences_pb") } }
    private val preferences by lazy { AppPreferences(store) }
    private val captured = mutableListOf<AnalyticsEvent>()
    private val shellLazy = lazy { EnviousWisprViewModel(preferences) { event -> synchronized(captured) { captured += event } } }
    private val shell by shellLazy

    private fun reported(): List<Pair<String, Map<String, Any?>>> = synchronized(captured) {
        captured.map { it.name to it.properties().filterKeys { key -> key != "elapsed_s" } }
    }

    private fun stages(): List<Any?> = reported().map { it.second["stage"] }

    private val ready = AppReadiness(microphoneGranted = true, speechModelReady = true, polishModelReady = true, onboardingPolishModelReady = true)

    // Two real worker threads behind Main so concurrent ViewModel launches genuinely race DataStore's edit lock.
    @Before fun setUp() = Dispatchers.setMain(mainDispatcher)

    @After fun tearDown() {
        runBlocking {
            if (shellLazy.isInitialized()) {
                val vmJob = checkNotNull(shell.viewModelScope.coroutineContext[Job])
                ViewModelStore().apply { put("shell", shell); clear() }
                vmJob.join()
            }
            storeJob.cancelAndJoin()
        }
        Dispatchers.resetMain()
        mainPool.shutdown()
        mainPool.awaitTermination(5, TimeUnit.SECONDS)
    }

    @Test fun reachingPermissionsStoresTheStepAndReportsTheStageOnce() = runBlocking {
        assertEquals(OnboardingStage.DOWNLOADS, preferences.setOnboardingStep(OnboardingStage.DOWNLOADS.ordinal))
        val stored = preferences.state.first().onboardingStep
        val next = checkNotNull(onboardingStepToRecord(stored, onboardingStage(stored, ready, AutoPasteAvailability.LIVE)))
        // The screen's effect can run concurrently for one transition (a recreation before the write lands).
        val first = shell.advanceOnboardingStep(stored, next)
        val second = shell.advanceOnboardingStep(stored, next)
        listOf(first, second).joinAll()
        assertEquals(listOf("onboarding.stage_reached" to mapOf<String, Any?>("stage" to "permissions")), reported())
        assertEquals(OnboardingStage.PERMISSIONS.ordinal, preferences.state.first().onboardingStep)
        // Back from the demo is a new transition to the same stage, and reports again.
        shell.setOnboardingStep(OnboardingStage.DEMO.ordinal).join()
        shell.setOnboardingStep(OnboardingStage.PERMISSIONS.ordinal).join()
        assertEquals(listOf("permissions", "demo", "permissions"), stages())
    }

    @Test fun theDownloadsFinishingNeverOverwritesASetUpLaterOrAnotherStep() = runBlocking {
        val downloads = OnboardingStage.DOWNLOADS.ordinal
        val permissions = OnboardingStage.PERMISSIONS.ordinal
        preferences.setOnboardingStep(downloads)
        // "Set up later" landed first: the advance stores nothing and reports nothing.
        preferences.dismissOnboarding()
        shell.advanceOnboardingStep(downloads, permissions).join()
        val refused = preferences.state.first()
        assertEquals(downloads to true, refused.onboardingStep to refused.onboardingDismissed)
        assertEquals(emptyList<Any?>(), stages())

        // Back (WELCOME) or DEMO landed first: advance from DOWNLOADS is refused.
        preferences.setOnboardingStep(OnboardingStage.WELCOME.ordinal)
        shell.advanceOnboardingStep(downloads, permissions).join()
        assertEquals(OnboardingStage.WELCOME.ordinal, preferences.state.first().onboardingStep)
        preferences.setOnboardingStep(OnboardingStage.DEMO.ordinal)
        shell.advanceOnboardingStep(downloads, permissions).join()
        assertEquals(OnboardingStage.DEMO.ordinal, preferences.state.first().onboardingStep)
        assertEquals(emptyList<Any?>(), stages())

        // Concurrent race: advance vs dismiss, both launched before joining either.
        preferences.setOnboardingStep(downloads)
        val advance = shell.advanceOnboardingStep(downloads, permissions)
        val dismiss = shell.dismissOnboarding()
        listOf(advance, dismiss).joinAll()
        val raced = preferences.state.first()
        assertTrue("a dismissal is never cleared by the advance", raced.onboardingDismissed)
        assertEquals(if (raced.onboardingStep == permissions) listOf("permissions") else emptyList(), stages())
    }

    @Test fun aDoubleTapReportsOneStageAndAnOutOfRangeStepReportsTheStageStored() = runBlocking {
        shell.setOnboardingStep(9).join()
        assertEquals(OnboardingStage.PRACTICE.ordinal, preferences.state.first().onboardingStep)
        shell.setOnboardingStep(-1).join()
        assertEquals(OnboardingStage.WELCOME.ordinal, preferences.state.first().onboardingStep)
        val first = shell.setOnboardingStep(OnboardingStage.DEMO.ordinal)
        val second = shell.setOnboardingStep(OnboardingStage.DEMO.ordinal)
        listOf(first, second).joinAll()
        assertEquals(listOf("practice", "welcome", "demo"), stages())
    }

    @Test fun finishingSetupTwiceCountsOneCompletion() = runBlocking {
        val first = shell.completeOnboarding()
        val second = shell.completeOnboarding()
        listOf(first, second).joinAll()
        assertTrue(preferences.state.first().onboardingComplete)
        assertEquals(listOf("onboarding.completed" to emptyMap<String, Any?>()), reported())
    }

    @Test fun externalSettlementCompletesOnlyOnCommittedOrPastedAndCleansUpFailures() = runBlocking {
        // User still in the guided flow (not dismissed): SCHEDULED alone never completes setup.
        preferences.setOnboardingStep(OnboardingStage.PERMISSIONS.ordinal)
        var modelsReady = true
        val settlement = OnboardingSettlement(preferences, setupModelsReady = { modelsReady }, scope = storeScope)

        assertNull(settlement.registerCandidate("take-copy"))
        assertEquals(1, settlement.pendingCandidateCount())
        assertFalse("SCHEDULED alone must leave onboardingComplete false", preferences.state.first().onboardingComplete)

        // Terminal failures leave onboardingComplete false and evict the candidate.
        assertNull(settlement.onTerminal("take-copy", InsertionResults.COPY_ONLY))
        assertEquals(0, settlement.pendingCandidateCount())
        assertFalse(preferences.state.first().onboardingComplete)

        assertNull(settlement.registerCandidate("take-failed"))
        assertNull(settlement.onTerminal("take-failed", InsertionResults.INSERTION_FAILED))
        assertEquals(0, settlement.pendingCandidateCount())
        assertFalse(preferences.state.first().onboardingComplete)

        assertNull(settlement.registerCandidate("take-aborted"))
        assertNull(settlement.onTerminal("take-aborted", TakeDeliveryObserver.RESULT_NOT_DELIVERED))
        assertEquals(0, settlement.pendingCandidateCount())
        assertFalse(preferences.state.first().onboardingComplete)

        // Unregistered practice take receiving COMMITTED never settles setup.
        assertNull(settlement.onTerminal("take-practice", InsertionResults.COMMITTED))
        assertFalse(preferences.state.first().onboardingComplete)

        // Missing setup models leave setup incomplete even on COMMITTED.
        modelsReady = false
        assertNull(settlement.registerCandidate("take-no-models"))
        checkNotNull(settlement.onTerminal("take-no-models", InsertionResults.COMMITTED)).join()
        assertEquals(0, settlement.pendingCandidateCount())
        assertFalse("missing setup models must not complete setup", preferences.state.first().onboardingComplete)

        // Registered external take receiving COMMITTED with models ready completes setup (0 OnboardingCompleted rows).
        modelsReady = true
        assertNull(settlement.registerCandidate("take-committed"))
        checkNotNull(settlement.onTerminal("take-committed", InsertionResults.COMMITTED)).join()
        assertEquals(0, settlement.pendingCandidateCount())
        val settled = preferences.state.first()
        assertTrue(settled.onboardingComplete)
        assertFalse(settled.onboardingDismissed)
        // Later Finish tap on an already settled setup counts no OnboardingCompleted row either.
        shell.completeOnboarding().join()
        assertEquals(emptyList<Pair<String, Map<String, Any?>>>(), reported())

        // Verify PASTED also settles cleanly on a fresh store.
        val pastedStore = PreferenceDataStoreFactory.create(scope = storeScope) { File(tempFolder.root, "pasted.preferences_pb") }
        val pastedPrefs = AppPreferences(pastedStore)
        val pastedSettlement = OnboardingSettlement(pastedPrefs, setupModelsReady = { true }, scope = storeScope)
        assertNull(pastedSettlement.registerCandidate("take-pasted"))
        checkNotNull(pastedSettlement.onTerminal("take-pasted", InsertionResults.PASTED)).join()
        assertTrue(pastedPrefs.state.first().onboardingComplete)
    }
}
