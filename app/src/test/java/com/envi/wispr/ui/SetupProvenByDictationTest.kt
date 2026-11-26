package com.envi.wispr.ui

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.envi.wispr.history.Enqueued
import com.envi.wispr.history.HistoryRow
import com.envi.wispr.history.TranscriptEntity
import com.envi.wispr.insertion.ClipboardOutcome
import com.envi.wispr.insertion.InsertionResults
import com.envi.wispr.paste.InsertionEnding
import com.envi.wispr.paste.InsertionHandoff
import com.envi.wispr.paste.InsertionOutcomeRecorder
import com.envi.wispr.settings.AppPreferences
import com.envi.wispr.telemetry.AnalyticsEvent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Observability Contract (#560, #563): driven through the real [DictationSessionCoordinator],
 * [SessionFinalizer], [InsertionOutcomeRecorder], and [TakeDeliveryObserver] on [DictationSessionRig].
 *
 * Covers:
 * 1. (#563) External take registers before `finalizer.deliver()` and settles `onboardingComplete = true`
 *    ONLY when `InsertionOutcomeRecorder` reports `COMMITTED` / `PASTED` (never on `SCHEDULED` alone,
 *    `COPY_ONLY`, `SERVICE_NOT_RUNNING`, or practice-box takes).
 * 2. (#560) Practice take whose History save fails (`dao.failInserts = true`) while insertion succeeds
 *    (`COMMITTED`) still reports `LANDED` once and credits completion (never `NOTHING_LANDED`).
 * 3. (#560) Wordless take and Service `destroy()` during `awaitRescue()` after `commit(COMPLETED)`
 *    both publish `RESULT_NOT_DELIVERED` so Practice settles to `NOTHING_LANDED` instead of hanging.
 *
 * REVERTS that turn rows red: settle immediately on `SCHEDULED`; skip `registerSettlementCandidate`;
 * call `markTakeEndedWithoutRow` on History save failure; drop `finally` in `publishResult`; omit
 * `onTerminal` on non-SCHEDULED fallback or non-COMPLETED arbiter commit.
 */
class SetupProvenByDictationTest {
    @get:Rule val tempFolder = TemporaryFolder()

    private val rig = DictationSessionRig()
    private val storeJob = Job()
    private val storeScope = CoroutineScope(storeJob + Dispatchers.IO)

    @After fun tearDown() {
        rig.holdRescueWrite?.complete(Unit)
        rig.close()
        runBlocking { storeJob.cancelAndJoin() }
    }

    private fun newPrefs(name: String): AppPreferences {
        val store = PreferenceDataStoreFactory.create(scope = storeScope) { File(tempFolder.root, "$name.preferences_pb") }
        return AppPreferences(store)
    }

    private fun runCompletedTake(): String {
        val coordinator = rig.coordinator()
        coordinator.onCreated()
        rig.command(coordinator, DictationSessionService.ACTION_START)
        rig.surface.awaitShown()
        rig.command(coordinator, DictationSessionService.ACTION_STOP)
        rig.speech.awaitRequest().onResult("tell grandma i will call")
        rig.polish.awaitRequest().onOutcome(rig.polish.outcome("Tell Grandma I will call."))
        assertEquals(TerminalReason.COMPLETED, rig.endings.awaitOne())
        rig.host.awaitServiceStopped()
        return rig.surface.startedTakeIds.single()
    }

    private fun recordInsertion(observer: TakeDeliveryObserver, takeId: String, result: String, row: HistoryRow = HistoryRow.None) {
        val recorder = InsertionOutcomeRecorder(
            enqueue = { _, _, _ -> Enqueued.ACCEPTED },
            capture = {},
            outcomeTrail = {},
            warn = {},
            onTerminal = observer::onTerminal,
        )
        val landed = result == InsertionResults.COMMITTED || result == InsertionResults.PASTED
        recorder.record(
            InsertionEnding(
                row = row,
                takeId = takeId,
                targetPackage = "com.google.android.gm",
                status = if (landed) TranscriptEntity.STATUS_COMPLETED else TranscriptEntity.STATUS_INSERTION_INTERRUPTED,
                result = result,
                interrupted = !landed,
                clipboard = if (landed) null else ClipboardOutcome.COPIED,
                latencyMs = 120L,
            ),
        )
    }

    @Test fun externalTakeSettlesOnlyAfterCommittedNeverOnScheduledAlone() = runBlocking {
        val prefs = newPrefs("external-commit")
        val settlement = OnboardingSettlement(prefs, setupModelsReady = { true }, scope = storeScope)
        val observer = TakeDeliveryObserver(PracticeTakeTracker {}, settlement)
        rig.host.onRegisterSettlementCandidate = { id -> settlement.registerCandidate(id) }
        rig.host.onTerminalDelivery = { id, result -> observer.onTerminal(id, result) }

        val takeId = runCompletedTake()
        assertEquals(listOf(takeId), rig.host.settlementCandidates.toList())
        assertEquals(1, settlement.pendingCandidateCount())
        assertFalse("SCHEDULED handoff alone must leave onboardingComplete false", prefs.state.first().onboardingComplete)

        recordInsertion(observer, takeId, InsertionResults.COMMITTED)
        val settled = withTimeout(5_000L) { prefs.state.first { it.onboardingComplete } }
        assertTrue(settled.onboardingComplete)
        assertEquals(0, settlement.pendingCandidateCount())
    }

    @Test fun externalTakeFallingBackToCopyOnlyNeverSettlesAndEvictsCandidate() = runBlocking {
        val prefs = newPrefs("external-copy")
        val settlement = OnboardingSettlement(prefs, setupModelsReady = { true }, scope = storeScope)
        val observer = TakeDeliveryObserver(PracticeTakeTracker {}, settlement)
        rig.host.onRegisterSettlementCandidate = { id -> settlement.registerCandidate(id) }
        rig.host.onTerminalDelivery = { id, result -> observer.onTerminal(id, result) }

        val takeId = runCompletedTake()
        recordInsertion(observer, takeId, InsertionResults.COPY_ONLY)
        assertFalse(prefs.state.first().onboardingComplete)
        assertEquals(0, settlement.pendingCandidateCount())
    }

    @Test fun practiceBoxTakeNeverRegistersForSettlement() {
        rig.insertion.fieldId = OnboardingViewModel.PRACTICE_FIELD_ID
        runCompletedTake()
        assertEquals(emptyList<String>(), rig.host.settlementCandidates.toList())
        assertEquals("precondition: the take was handed off", 1, rig.insertion.requests.size)
    }

    @Test fun nonScheduledFallbackEmitsTerminalOutcomeAndEvictsSettlementCandidate() = runBlocking {
        val prefs = newPrefs("non-scheduled")
        val settlement = OnboardingSettlement(prefs, setupModelsReady = { true }, scope = storeScope)
        val observer = TakeDeliveryObserver(PracticeTakeTracker {}, settlement)
        rig.host.onRegisterSettlementCandidate = { id -> settlement.registerCandidate(id) }
        rig.host.onTerminalDelivery = { id, result -> observer.onTerminal(id, result) }
        rig.insertion.handoff = InsertionHandoff.SERVICE_NOT_RUNNING

        val takeId = runCompletedTake()
        assertEquals(listOf(takeId to InsertionResults.CLIPBOARD), rig.host.terminalDeliveries.toList())
        assertEquals(0, settlement.pendingCandidateCount())
        assertFalse(prefs.state.first().onboardingComplete)
    }

    /** #560: History save fails (`dao.failInserts = true`), insertion commits -> practice lands, never NOTHING_LANDED. */
    @Test fun failedHistorySaveWithCommittedPracticeInsertionStillReportsLandedOnce() {
        val reports = mutableListOf<Pair<String, String>>()
        val tracker = PracticeTakeTracker { event ->
            val practice = event as AnalyticsEvent.OnboardingPractice
            reports += practice.lesson to practice.outcome
        }
        val practice = PracticeTakeFollower(tracker)
        val observer = TakeDeliveryObserver(tracker, OnboardingSettlement(newPrefs("practice-nosave"), { true }, storeScope))
        rig.host.onTerminalDelivery = observer::onTerminal
        rig.insertion.fieldId = OnboardingViewModel.PRACTICE_FIELD_ID
        rig.dao.failInserts = true

        val takeId = runCompletedTake()
        rig.awaitHistoryIdle()
        assertTrue("precondition: History save failed so no row exists", rig.dao.rows.isEmpty())

        // Practice observed the take and saw IDLE before insertion finished: stays WORKING, never NOTHING_LANDED.
        practice.follow(ours = true, transcriptId = null, held = false, lesson = PracticeLesson.TAP, takeId = takeId)
        practice.follow(ours = false, transcriptId = null, held = null, lesson = PracticeLesson.TAP, takeId = null)
        assertEquals(PracticeScreenJudgement(PracticeOutcome.WORKING), practice.judge(emptyList()))
        assertEquals(emptyList<Pair<String, String>>(), reports)

        // Insertion commits independently of History: reports LANDED once and unlocks completion.
        recordInsertion(observer, takeId, InsertionResults.COMMITTED, row = HistoryRow.None)
        assertEquals(listOf("tap" to "landed"), reports)
        assertEquals(PracticeScreenJudgement(PracticeOutcome.LANDED, landedTap = true), practice.judge(emptyList()))
    }

    @Test fun wordlessTakePublishesNotDeliveredTerminal() {
        val coordinator = rig.coordinator()
        coordinator.onCreated()
        rig.command(coordinator, DictationSessionService.ACTION_START)
        rig.surface.awaitShown()
        rig.command(coordinator, DictationSessionService.ACTION_STOP)
        rig.speech.awaitRequest().onResult("")
        assertEquals(TerminalReason.ASR_EMPTY_DESPITE_AUDIO, rig.endings.awaitOne())
        rig.host.awaitServiceStopped()
        val takeId = rig.surface.startedTakeIds.single()
        assertEquals(listOf(takeId to TakeDeliveryObserver.RESULT_NOT_DELIVERED), rig.host.terminalDeliveries.toList())
    }

    /** #560: destroy() during awaitRescue() after commit(COMPLETED) never calls deliver(); finally settles Practice to NOTHING_LANDED. */
    @Test fun destroyDuringAwaitRescueAfterCommitPublishesNotDeliveredTerminal() {
        val reports = mutableListOf<Pair<String, String>>()
        val tracker = PracticeTakeTracker { event ->
            val practice = event as AnalyticsEvent.OnboardingPractice
            synchronized(reports) { reports += practice.lesson to practice.outcome }
        }
        val practice = PracticeTakeFollower(tracker)
        val terminalArrived = CountDownLatch(1)
        val entered = CountDownLatch(1)
        val hold = CompletableDeferred<Unit>()
        rig.insertion.fieldId = OnboardingViewModel.PRACTICE_FIELD_ID
        rig.rescueWriteEntered = entered
        rig.holdRescueWrite = hold
        rig.host.onTerminalDelivery = { id, result ->
            tracker.onTerminal(id, result)
            terminalArrived.countDown()
        }

        val coordinator = rig.coordinator(rescueWaitBoundMs = 10_000L)
        coordinator.onCreated()
        rig.command(coordinator, DictationSessionService.ACTION_START)
        rig.surface.awaitShown()
        val takeId = rig.surface.startedTakeIds.single()
        practice.follow(ours = true, transcriptId = null, held = false, lesson = PracticeLesson.TAP, takeId = takeId)
        rig.command(coordinator, DictationSessionService.ACTION_STOP)
        rig.speech.awaitRequest().onResult("words before destroy")
        rig.polish.awaitRequest().onOutcome(rig.polish.outcome("Words before destroy."))

        // Wait until commit(COMPLETED) succeeded AND rescue write is blocked, so publishResult is suspended in awaitRescue().
        assertEquals(TerminalReason.COMPLETED, rig.endings.awaitOne())
        assertTrue("rescue write should have started", entered.await(5, TimeUnit.SECONDS))

        rig.onMain { coordinator.destroy() }
        assertTrue("finally must publish RESULT_NOT_DELIVERED after destroy cancels awaitRescue", terminalArrived.await(5, TimeUnit.SECONDS))
        practice.follow(ours = false, transcriptId = null, held = null, lesson = PracticeLesson.TAP, takeId = null)
        assertTrue("deliver() must never have run after destroy during awaitRescue", rig.insertion.requests.isEmpty())
        assertEquals(listOf(takeId to TakeDeliveryObserver.RESULT_NOT_DELIVERED), rig.host.terminalDeliveries.toList())
        assertEquals(listOf("tap" to "nothing_landed"), reports)
        assertEquals(PracticeScreenJudgement(PracticeOutcome.NOTHING_LANDED), practice.judge(emptyList()))
    }
}
