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
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
 * the practice screen's own verdict is `OnboardingPolicyTest`'s.
 *
 * The practice rows replay the owner's independent signals in every order: the snapshot going IDLE
 * (`surface.hide()` ends every take), `endedWithoutRow` marking takes with no saved row, and the History
 * row arriving and reaching its final status. The step and completion rows drive the shell view model against
 * a real preferences file on a multi-threaded dispatcher and read the rows it reports.
 *
 * Competing writes are LAUNCHED before either is joined (`test-honesty.md` RULE:
 * a-single-threaded-test-cannot-distinguish-atomic-from-check-then-act).
 *
 * REVERTS that turn rows red: drop `STATUS_SAVED_UNROUTED` from `practiceRowStillOpen`; return NOTHING_LANDED
 * when an identified take's row has not arrived yet; report the first verdict in `PracticeTakeFollower.judge`
 * whatever it is; drop a still-resolving take when the next starts; emit `OnboardingStageReached` without
 * checking the stored step changed; let `advanceOnboardingStep` write past a dismissal or Back/Demo tap; emit
 * `OnboardingCompleted` on every `completeOnboarding`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class OnboardingTelemetryTest {
    @get:Rule val tempFolder = TemporaryFolder()

    // ---- #560: onboarding.practice carries the take's FINAL verdict, once ----

    private fun row(status: String, result: String, id: Long = 6L, takeId: String? = "take-$id") = TranscriptEntity(
        id = id, originalText = "um tell grandma", finalText = "Tell Grandma.", createdAtMs = 1_000L, durationMs = 1_000L,
        speechEngine = "Parakeet", polishEngine = "none", polishLatencyMs = 0L, insertionResult = result, status = status,
        takeId = takeId,
    )

    /** One take of the practice box: STARTING with takeId (before any row), optionally followed by attachTranscript. */
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

    /** The owner's `surface.hide()`: IDLE, so not ours any more. */
    private fun PracticeTakeFollower.ownerHides() =
        follow(ours = false, transcriptId = null, held = null, lesson = PracticeLesson.TAP, takeId = null)

    private fun PracticeTakeFollower.judgeRows(
        vararg rows: TranscriptEntity,
        endedWithoutRow: Set<String> = emptySet(),
    ): PracticeJudgement = checkNotNull(judge(rows.toList(), endedWithoutRow))

    private fun tap(outcome: PracticeOutcome) = PracticeReport(PracticeLesson.TAP, outcome)

    @Test fun theOwnerGoingIdleBeforeRowArrivesOrCommitsReportsLandedOnce() {
        val practice = PracticeTakeFollower()
        // attachTranscript never arrived before hide() (dropped as a no-op at IDLE); only takeId is known.
        practice.startTake(takeId = "take-6", transcriptId = null)
        assertEquals(PracticeJudgement(null, emptyList()), practice.judgeRows())
        practice.ownerHides()
        // Missing row at IDLE stays WORKING (never prematurely latches NOTHING_LANDED).
        assertEquals(PracticeJudgement(PracticeOutcome.WORKING, emptyList()), practice.judgeRows())
        // The order build 327 recorded: IDLE while the saved row waits for its route.
        assertEquals(PracticeJudgement(PracticeOutcome.WORKING, emptyList()), practice.judgeRows(row(TranscriptEntity.STATUS_SAVED_UNROUTED, "pending")))
        assertEquals(PracticeJudgement(PracticeOutcome.WORKING, emptyList()), practice.judgeRows(row(TranscriptEntity.STATUS_READY_FOR_INSERTION, "pending")))
        val landed = row(TranscriptEntity.STATUS_COMPLETED, InsertionResults.COMMITTED)
        assertEquals(PracticeJudgement(PracticeOutcome.LANDED, listOf(tap(PracticeOutcome.LANDED))), practice.judgeRows(landed))
        // Every later History emission re-judges the same take; it is reported once.
        assertEquals(PracticeJudgement(PracticeOutcome.LANDED, emptyList()), practice.judgeRows(landed))
    }

    @Test fun aRowFinalBeforeTheOwnerGoesIdleIsReportedAtIdle() {
        val practice = PracticeTakeFollower()
        val landed = row(TranscriptEntity.STATUS_COMPLETED, InsertionResults.PASTED)
        practice.startTake(held = true, lesson = PracticeLesson.HOLD)
        assertEquals(emptyList<PracticeReport>(), practice.judgeRows(landed).reports)
        practice.ownerHides()
        assertEquals(
            PracticeJudgement(PracticeOutcome.LANDED, listOf(PracticeReport(PracticeLesson.HOLD, PracticeOutcome.LANDED))),
            practice.judgeRows(landed),
        )
    }

    @Test fun aTakeThatPutNoWordsInTheBoxReportsNothingLandedOnce() {
        val practice = PracticeTakeFollower()
        // 1. The route missed the box: the saved row reconciles to copy-only.
        practice.startTake(takeId = "take-6", transcriptId = 6L)
        practice.ownerHides()
        assertEquals(emptyList<PracticeReport>(), practice.judgeRows(row(TranscriptEntity.STATUS_SAVED_UNROUTED, "pending")).reports)
        val copied = row(TranscriptEntity.STATUS_COMPLETED, InsertionResults.COPY_ONLY)
        assertEquals(PracticeJudgement(PracticeOutcome.NOTHING_LANDED, listOf(tap(PracticeOutcome.NOTHING_LANDED))), practice.judgeRows(copied))
        assertEquals(emptyList<PracticeReport>(), practice.judgeRows(copied).reports)

        // 2. Explicit wordless/save-failed ending arriving AFTER IDLE (even while stale draft is still in Room).
        practice.startTake(takeId = "take-wordless", transcriptId = 7L)
        practice.ownerHides()
        val staleDraft = row(TranscriptEntity.STATUS_DRAFT, "pending", id = 7L, takeId = "take-wordless")
        assertEquals(PracticeJudgement(PracticeOutcome.WORKING, emptyList()), practice.judgeRows(copied, staleDraft))
        assertEquals(
            PracticeJudgement(PracticeOutcome.NOTHING_LANDED, listOf(tap(PracticeOutcome.NOTHING_LANDED))),
            practice.judgeRows(copied, staleDraft, endedWithoutRow = setOf("take-wordless")),
        )
        assertEquals(emptyList<PracticeReport>(), practice.judgeRows(copied, endedWithoutRow = setOf("take-wordless")).reports)

        // 3. Explicit wordless ending already present BEFORE IDLE: reported at IDLE, never while still running.
        practice.startTake(takeId = "take-cancelled", transcriptId = null)
        assertEquals(
            PracticeJudgement(null, emptyList()),
            practice.judgeRows(copied, endedWithoutRow = setOf("take-wordless", "take-cancelled")),
        )
        practice.ownerHides()
        assertEquals(
            PracticeJudgement(PracticeOutcome.NOTHING_LANDED, listOf(tap(PracticeOutcome.NOTHING_LANDED))),
            practice.judgeRows(copied, endedWithoutRow = setOf("take-wordless", "take-cancelled")),
        )
    }

    /** A second take while the first is still resolving (including when IDLE was conflated): each gets its own row. */
    @Test fun aTakeStillResolvingWhenTheNextStartsIsReportedWhenItsRowIsFinal() {
        val practice = PracticeTakeFollower()
        practice.startTake(takeId = "take-6", transcriptId = null, held = true, lesson = PracticeLesson.HOLD)
        // Even if IDLE was conflated before take-7 began, switching to take-7 marks take-6 ended and retains it.
        practice.startTake(takeId = "take-7", transcriptId = 7L, held = false, lesson = PracticeLesson.TAP)
        val secondDraft = row(TranscriptEntity.STATUS_DRAFT, "pending", id = 7L, takeId = "take-7")
        assertEquals(PracticeJudgement(null, emptyList()), practice.judgeRows(secondDraft))
        // Take-6's row arrives and commits while take-7 is still recording: take-6 reports HOLD/LANDED; UI stays on take-7.
        val firstLanded = row(TranscriptEntity.STATUS_COMPLETED, InsertionResults.COMMITTED, id = 6L, takeId = "take-6")
        assertEquals(
            PracticeJudgement(null, listOf(PracticeReport(PracticeLesson.HOLD, PracticeOutcome.LANDED))),
            practice.judgeRows(firstLanded, secondDraft),
        )
        // Take-7 finishes and fails to save: reports TAP/NOTHING_LANDED once.
        practice.ownerHides()
        assertEquals(PracticeJudgement(PracticeOutcome.WORKING, emptyList()), practice.judgeRows(firstLanded, secondDraft))
        assertEquals(
            PracticeJudgement(PracticeOutcome.NOTHING_LANDED, listOf(tap(PracticeOutcome.NOTHING_LANDED))),
            practice.judgeRows(firstLanded, secondDraft, endedWithoutRow = setOf("take-7")),
        )
        assertEquals(
            PracticeJudgement(PracticeOutcome.NOTHING_LANDED, emptyList()),
            practice.judgeRows(firstLanded, secondDraft, endedWithoutRow = setOf("take-7")),
        )
    }

    // ---- #563: onboarding.stage_reached and onboarding.completed, against a real preferences file ----

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

    // ---- #563: a dictation into another app settles setup, with no onboarding.completed row ----

    @Test fun aDictationThatProvedSetupCompletesItOnlyWithTheSetupModels() = runBlocking {
        // A user still inside the guided flow, never dismissed, who dictates into another app.
        preferences.setOnboardingStep(OnboardingStage.PERMISSIONS.ordinal)
        OnboardingSettlement(preferences, setupModelsReady = { false }, scope = storeScope).dictationProvedSetup().join()
        assertFalse("a missing setup model leaves setup open", preferences.state.first().onboardingComplete)
        OnboardingSettlement(preferences, setupModelsReady = { true }, scope = storeScope).dictationProvedSetup().join()
        val settled = preferences.state.first()
        assertTrue(settled.onboardingComplete)
        assertFalse(settled.onboardingDismissed)
        // The settlement reports nothing, and a later Finish tap on a complete setup counts no completion.
        shell.completeOnboarding().join()
        assertEquals(emptyList<Pair<String, Map<String, Any?>>>(), reported())
    }
}
