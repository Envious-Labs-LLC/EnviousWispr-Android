package com.envi.wispr.ui

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.lifecycle.ViewModelStore
import com.envi.wispr.history.TranscriptEntity
import com.envi.wispr.insertion.InsertionResults
import com.envi.wispr.paste.AutoPasteAvailability
import com.envi.wispr.settings.AppPreferences
import com.envi.wispr.telemetry.AnalyticsEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
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

/**
 * Observability Contract (#560, #563): the onboarding rows report what happened, once. Not product coverage:
 * the practice screen's own verdict is `OnboardingPolicyTest`'s.
 *
 * The practice rows replay the owner's two independent signals in both orders: the snapshot going IDLE
 * (`surface.hide()` ends every take) and the take's History row reaching its final status. The step and
 * completion rows drive the shell view model against a real preferences file and read the rows it reports.
 *
 * REVERTS that turn rows red: drop `STATUS_SAVED_UNROUTED` from `practiceRowStillOpen`; report the first
 * verdict in `PracticeTakeFollower.judge` whatever it is; emit `OnboardingStageReached` without checking the
 * stored step changed; emit `OnboardingCompleted` from `settleOnboarding` or on every `completeOnboarding`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class OnboardingTelemetryTest {
    @get:Rule val tempFolder = TemporaryFolder()

    // ---- #560: onboarding.practice carries the take's FINAL verdict, once ----

    private fun row(status: String, result: String) = TranscriptEntity(
        id = 6L, originalText = "um tell grandma", finalText = "Tell Grandma.", createdAtMs = 1_000L, durationMs = 1_000L,
        speechEngine = "Parakeet", polishEngine = "none", polishLatencyMs = 0L, insertionResult = result, status = status,
    )

    /** One take of the practice box, from the owner's STARTING to its RECORDING with the draft row named. */
    private fun PracticeTakeFollower.startTake(held: Boolean = false) {
        follow(ours = true, transcriptId = null, held = held)
        follow(ours = true, transcriptId = 6L, held = held)
    }

    /** The owner's `surface.hide()`: IDLE, so not ours any more. */
    private fun PracticeTakeFollower.ownerHides() = follow(ours = false, transcriptId = null, held = null)

    @Test fun theOwnerGoingIdleBeforeTheInsertionCommitsReportsLandedOnce() {
        val practice = PracticeTakeFollower()
        val reports = mutableListOf<PracticeOutcome>()
        fun judge(vararg rows: TranscriptEntity): PracticeOutcome? =
            checkNotNull(practice.judge(PracticeLesson.TAP, rows.toList())).also { it.report?.let(reports::add) }.shown

        practice.startTake()
        assertNull("a take still recording has no verdict", judge(row(TranscriptEntity.STATUS_DRAFT, "pending")))
        practice.ownerHides()
        // The order build 327 recorded: IDLE while the saved row waits for its route.
        assertEquals(PracticeOutcome.WORKING, judge(row(TranscriptEntity.STATUS_SAVED_UNROUTED, "pending")))
        assertEquals(PracticeOutcome.WORKING, judge(row(TranscriptEntity.STATUS_READY_FOR_INSERTION, "pending")))
        assertEquals(emptyList<PracticeOutcome>(), reports)
        assertEquals(PracticeOutcome.LANDED, judge(row(TranscriptEntity.STATUS_COMPLETED, InsertionResults.COMMITTED)))
        // Every later History emission re-judges the same take; it is reported once.
        judge(row(TranscriptEntity.STATUS_COMPLETED, InsertionResults.COMMITTED))
        assertEquals(listOf(PracticeOutcome.LANDED), reports)
    }

    @Test fun aRowFinalBeforeTheOwnerGoesIdleIsReportedAtIdle() {
        val practice = PracticeTakeFollower()
        val landed = listOf(row(TranscriptEntity.STATUS_COMPLETED, InsertionResults.PASTED))
        practice.startTake(held = true)
        assertNull(checkNotNull(practice.judge(PracticeLesson.HOLD, landed)).report)
        practice.ownerHides()
        assertEquals(PracticeJudgement(PracticeOutcome.LANDED, PracticeOutcome.LANDED), practice.judge(PracticeLesson.HOLD, landed))
    }

    @Test fun aTakeThatPutNoWordsInTheBoxReportsNothingLandedOnce() {
        val practice = PracticeTakeFollower()
        // The route missed the box: the saved row reconciles to copy-only.
        practice.startTake()
        practice.ownerHides()
        assertNull(checkNotNull(practice.judge(PracticeLesson.TAP, listOf(row(TranscriptEntity.STATUS_SAVED_UNROUTED, "pending")))).report)
        val copied = listOf(row(TranscriptEntity.STATUS_COMPLETED, InsertionResults.COPY_ONLY))
        assertEquals(PracticeJudgement(PracticeOutcome.NOTHING_LANDED, PracticeOutcome.NOTHING_LANDED), practice.judge(PracticeLesson.TAP, copied))
        assertNull(checkNotNull(practice.judge(PracticeLesson.TAP, copied)).report)

        // A silent take: the owner deleted its draft, so the row is gone. A new take, so a new report.
        practice.startTake()
        assertNull(checkNotNull(practice.judge(PracticeLesson.TAP, copied)).report)
        practice.ownerHides()
        assertEquals(PracticeJudgement(PracticeOutcome.NOTHING_LANDED, PracticeOutcome.NOTHING_LANDED), practice.judge(PracticeLesson.TAP, emptyList()))
        assertNull(checkNotNull(practice.judge(PracticeLesson.TAP, emptyList())).report)
    }

    // ---- #563: onboarding.stage_reached and onboarding.completed, against a real preferences file ----

    private val storeScope = CoroutineScope(Job() + Dispatchers.IO)
    private val store by lazy { PreferenceDataStoreFactory.create(scope = storeScope) { File(tempFolder.root, "onboarding.preferences_pb") } }
    private val preferences by lazy { AppPreferences(store) }
    private val captured = mutableListOf<AnalyticsEvent>()
    private val shell by lazy { EnviousWisprViewModel(preferences) { event -> synchronized(captured) { captured += event } } }

    private fun reported(): List<Pair<String, Map<String, Any?>>> = synchronized(captured) {
        captured.map { it.name to it.properties().filterKeys { key -> key != "elapsed_s" } }
    }

    private val ready = AppReadiness(microphoneGranted = true, speechModelReady = true, polishModelReady = true, onboardingPolishModelReady = true)

    // The view model's scope is Main; Unconfined runs it on whichever thread resumes it, and each call's Job is its done signal.
    @Before fun setUp() = Dispatchers.setMain(Dispatchers.Unconfined)

    @After fun tearDown() {
        ViewModelStore().apply { put("shell", shell); clear() }
        storeScope.cancel()
        Dispatchers.resetMain()
    }

    @Test fun reachingPermissionsStoresTheStepAndReportsTheStageOnce() = runBlocking {
        assertTrue(preferences.setOnboardingStep(OnboardingStage.DOWNLOADS.ordinal))
        val stored = preferences.state.first().onboardingStep
        assertEquals("precondition: setup waits on the downloads screen", 1, stored)
        val next = checkNotNull(onboardingStepToRecord(stored, onboardingStage(stored, ready, AutoPasteAvailability.LIVE)))
        // The screen's effect can run twice for one transition (a recreation before the write lands).
        shell.setOnboardingStep(next).join()
        shell.setOnboardingStep(next).join()
        assertEquals(listOf("onboarding.stage_reached" to mapOf<String, Any?>("stage" to "permissions")), reported())
        assertEquals(2, preferences.state.first().onboardingStep)
        // Back from the demo is a new transition to the same stage, and reports again.
        shell.setOnboardingStep(OnboardingStage.DEMO.ordinal).join()
        shell.setOnboardingStep(OnboardingStage.PERMISSIONS.ordinal).join()
        assertEquals(listOf("permissions", "demo", "permissions"), reported().map { it.second["stage"] })
    }

    @Test fun finishingSetupTwiceCountsOneCompletion() = runBlocking {
        shell.completeOnboarding().join()
        shell.completeOnboarding().join()
        assertTrue(preferences.state.first().onboardingComplete)
        assertEquals(listOf("onboarding.completed" to emptyMap<String, Any?>()), reported())
    }

    @Test fun aSetupSettledOutsideTheFlowIsCompleteWithoutACompletionRow() = runBlocking {
        preferences.dismissOnboarding()
        val before = preferences.state.first()
        val rows = listOf(row(TranscriptEntity.STATUS_COMPLETED, InsertionResults.COMMITTED))
        assertTrue("precondition: a dismissed setup with every fact and a dictation", onboardingSettledOutsideTheFlow(before, ready, AutoPasteAvailability.LIVE, rows))
        shell.settleOnboarding().join()
        val after = preferences.state.first()
        assertTrue(after.onboardingComplete)
        assertFalse(after.onboardingDismissed)
        assertFalse("settled once, nothing left to settle", onboardingSettledOutsideTheFlow(after, ready, AutoPasteAvailability.LIVE, rows))
        // A later Finish tap on an already complete setup counts no completion either.
        shell.completeOnboarding().join()
        assertEquals(emptyList<Pair<String, Map<String, Any?>>>(), reported())
    }
}
