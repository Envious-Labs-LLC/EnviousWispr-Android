package com.envi.wispr.ui

import com.envi.wispr.history.TranscriptEntity
import com.envi.wispr.history.TranscriptRepository
import com.envi.wispr.insertion.ClipboardOutcome
import com.envi.wispr.insertion.InsertionResults
import com.envi.wispr.paste.InsertionHandoff
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * Product Outcome (#235, founder decision 2026-09-23): a stuck History save never holds the user's words.
 * The words wait at most the owner's bound for their save; past it they go to the clipboard with the usual
 * line, the stuck save only reconciles its row later, and no row ever claims a paste that was not handed off.
 *
 * A held save is staged with the rig's `holdFinalize` gate, never a race against a fast timer: the save
 * cannot answer until the row releases it, so a short bound always expires first. Each row names the
 * compiling mutation that turns it red (recorded in the PR's receipts).
 */
class BoundedHistorySaveTest {
    private val rig = DictationSessionRig()

    @After fun tearDown() {
        rig.dao.holdFinalize?.complete(Unit)
        rig.dao.holdPromotion?.complete(Unit)
        rig.close()
    }

    private fun takeWithPolishedWords(coordinator: DictationSessionCoordinator, text: String = "Keep these words.") {
        coordinator.onCreated()
        rig.command(coordinator, DictationSessionService.ACTION_START)
        rig.surface.awaitShown()
        rig.command(coordinator, DictationSessionService.ACTION_STOP)
        rig.speech.awaitRequest().onResult("keep these words")
        val polish = rig.polish
        polish.awaitRequest().onOutcome(polish.outcome(text))
    }

    private fun toasts() = rig.host.events.filter { it.startsWith("toast:") }

    private fun theOnlyRow(): TranscriptEntity = rig.awaitHistoryIdle().let { rig.dao.rows.values.single() }

    /** Row 1. MUTATION: await the save without the bound. */
    @Test fun aStuckSaveStillDeliversTheWordsOnTheClipboardWithinTheBound() {
        rig.dao.holdFinalize = CompletableDeferred()
        val coordinator = rig.coordinator(historySaveBoundMs = 50L)
        takeWithPolishedWords(coordinator)
        assertEquals(TerminalReason.COMPLETED, rig.endings.awaitOne())
        rig.host.awaitServiceStopped()
        assertTrue("nothing was handed to the accessibility service", rig.insertion.pastes.isEmpty())
        assertTrue(rig.host.events.contains("clipboard:Keep these words."))
        assertEquals("one line tells the user where the words went", 1, toasts().size)
        assertEquals("timed_out", rig.endings.facts.single().historySave)
        assertEquals(listOf("history_save_timed_out"), rig.defects.map { it.first }.filter { it.startsWith("history") })
    }

    /** Row 2. MUTATION: skip the reconciliation when the late row lands after the copy. */
    @Test fun aLateSaveReconcilesItsRowAndNeverPastesOrAnnouncesAgain() {
        rig.dao.holdFinalize = CompletableDeferred()
        val coordinator = rig.coordinator(historySaveBoundMs = 50L)
        takeWithPolishedWords(coordinator)
        rig.endings.awaitOne()
        rig.host.awaitServiceStopped()
        rig.dao.holdFinalize!!.complete(Unit)
        val row = theOnlyRow()
        assertEquals(TranscriptEntity.STATUS_INSERTION_INTERRUPTED, row.status)
        assertEquals(InsertionResults.CLIPBOARD, row.insertionResult)
        assertTrue(rig.insertion.pastes.isEmpty())
        assertEquals(1, toasts().size)
        assertEquals("the take's facts stay as committed", "timed_out", rig.endings.facts.single().historySave)
    }

    /** Row 3. MUTATION: announce on the late failure. */
    @Test fun aLateFailureIsDiagnosedAndChangesNothingTheUserSaw() {
        rig.dao.holdFinalize = CompletableDeferred()
        rig.dao.failFinalize = true
        val coordinator = rig.coordinator(historySaveBoundMs = 50L)
        takeWithPolishedWords(coordinator)
        rig.endings.awaitOne()
        rig.host.awaitServiceStopped()
        rig.dao.holdFinalize!!.complete(Unit)
        rig.awaitHistoryIdle()
        rig.log.awaitLine("History save failed after its bound")
        assertEquals(1, toasts().size)
        assertTrue(rig.insertion.pastes.isEmpty())
        assertEquals("timed_out", rig.endings.facts.single().historySave)
    }

    /** Row 4. MUTATION: always take the timeout route. */
    @Test fun aSaveThatAnswersInTimePastesAsTodayAndIsPromotedAfterTheHandoff() {
        val coordinator = rig.coordinator(historySaveBoundMs = 5_000L)
        takeWithPolishedWords(coordinator)
        assertEquals(TerminalReason.COMPLETED, rig.endings.awaitOne())
        rig.host.awaitStopped()
        val row = theOnlyRow()
        assertEquals(listOf(row.id to "Keep these words."), rig.insertion.pastes.toList())
        assertEquals("promoted to ready after the scheduled handoff", TranscriptEntity.STATUS_READY_FOR_INSERTION, row.status)
        assertEquals("pending", row.insertionResult)
        assertEquals("ok", rig.endings.facts.single().historySave)
        assertTrue(toasts().isEmpty())
    }

    /**
     * Row 6. Two independent guards hold this row, the scope destroy cancels and the revoked commit, so no single
     * mutation turns it red. MUTATION (both): run the continuation on a scope destroy does not cancel, and
     * deliver after a revoked commit.
     */
    @Test fun aDestroyWhileTheSaveIsHeldDeliversNothing() {
        rig.dao.holdFinalize = CompletableDeferred()
        val coordinator = rig.coordinator(historySaveBoundMs = 5_000L)
        takeWithPolishedWords(coordinator)
        assertTrue(rig.dao.finalizeEntered.await(10, TimeUnit.SECONDS))
        rig.onMain { coordinator.destroy() }
        rig.dao.holdFinalize!!.complete(Unit)
        rig.awaitHistoryIdle()
        assertTrue(rig.insertion.pastes.isEmpty())
        assertTrue(rig.host.events.none { it.startsWith("clipboard:") })
        assertTrue(toasts().isEmpty())
    }

    /** Row 8. MUTATION: record `clipboard` unconditionally on the late row. */
    @Test fun aFailedCopyAfterATimeoutIsRecordedAsSuch() {
        rig.dao.holdFinalize = CompletableDeferred()
        rig.host.clipboardWorks = false
        val coordinator = rig.coordinator(historySaveBoundMs = 50L)
        takeWithPolishedWords(coordinator)
        rig.endings.awaitOne()
        rig.host.awaitServiceStopped()
        rig.dao.holdFinalize!!.complete(Unit)
        assertEquals(InsertionResults.INSERTION_FAILED, theOnlyRow().insertionResult)
    }

    /** Row 10. MUTATION: wait for the save after the timeout. */
    @Test fun aSaveThatNeverAnswersOwesNothingAfterTheCopy() {
        rig.dao.holdFinalize = CompletableDeferred()
        val coordinator = rig.coordinator(historySaveBoundMs = 50L)
        takeWithPolishedWords(coordinator)
        assertEquals(TerminalReason.COMPLETED, rig.endings.awaitOne())
        rig.host.awaitServiceStopped()
        assertTrue(rig.host.events.contains("clipboard:Keep these words."))
    }

    /** Row 11. MUTATION: await the promotion before the handoff. */
    @Test fun aStalledPromotionNeverHoldsTheWords() {
        rig.dao.holdPromotion = CompletableDeferred()
        val coordinator = rig.coordinator(historySaveBoundMs = 5_000L)
        takeWithPolishedWords(coordinator)
        assertEquals(TerminalReason.COMPLETED, rig.endings.awaitOne())
        rig.host.awaitServiceStopped()
        assertEquals("the words were handed off while the promotion is held", 1, rig.insertion.pastes.size)
        assertEquals(TranscriptEntity.STATUS_SAVED_UNROUTED, rig.dao.rows.values.single().status)
        rig.dao.holdPromotion!!.complete(Unit)
        assertEquals(TranscriptEntity.STATUS_READY_FOR_INSERTION, theOnlyRow().status)
    }

    /** Row 13. MUTATION: promote on every Saved, not only a scheduled handoff. */
    @Test fun aHandoffThatWasNotScheduledIsNeverPromoted() {
        rig.insertion.handoff = InsertionHandoff.SERVICE_NOT_RUNNING
        rig.dao.failOutcome = true
        val coordinator = rig.coordinator(historySaveBoundMs = 5_000L)
        takeWithPolishedWords(coordinator)
        assertEquals(TerminalReason.COMPLETED, rig.endings.awaitOne())
        rig.host.awaitStopped()
        assertEquals("a failed fallback outcome leaves the row neutral, never ready", TranscriptEntity.STATUS_SAVED_UNROUTED, theOnlyRow().status)
    }

    /** Row 5b. MUTATION: `mustPreventDataLoss = false` (the copy then follows the user's auto-copy setting). */
    @Test fun aTimedOutTakeCopiesEvenWithAutoCopyOff() {
        rig.preferenceStates.value = rig.preferenceStates.value.copy(autoCopyToClipboard = false)
        rig.dao.holdFinalize = CompletableDeferred()
        val coordinator = rig.coordinator(historySaveBoundMs = 50L)
        takeWithPolishedWords(coordinator)
        rig.endings.awaitOne()
        rig.host.awaitServiceStopped()
        assertTrue("the words reach the clipboard whatever the setting", rig.host.events.contains("clipboard:Keep these words."))
    }

    /** Row 9c. MUTATION: write the no-draft saved row as `ready_for_insertion`. */
    @Test fun aTakeWithNoDraftAlsoSavesANeutralRow() {
        rig.dao.failDraftInsert = true
        rig.dao.holdPromotion = CompletableDeferred()
        val coordinator = rig.coordinator(historySaveBoundMs = 5_000L)
        takeWithPolishedWords(coordinator)
        rig.endings.awaitOne()
        rig.host.awaitServiceStopped()
        assertEquals(TranscriptEntity.STATUS_SAVED_UNROUTED, rig.dao.rows.values.single().status)
    }

    /**
     * Row 14 (code review 1): recovery reads the late neutral row as delivery unknown before the measured copy
     * lands; the copy still replaces "unknown", because this process knows what the user got. MUTATION: reconcile
     * only a neutral/pending row (`finalizeInsertionOutcome` again).
     */
    @Test fun aKnownCopyReplacesADeliveryUnknownThatRecoveryWroteFirst() {
        rig.dao.holdFinalize = CompletableDeferred()
        val coordinator = rig.coordinator(historySaveBoundMs = 50L)
        takeWithPolishedWords(coordinator)
        rig.endings.awaitOne()
        rig.host.awaitServiceStopped()
        // Recovery runs between the late row's write and its reconciliation, with a cutoff past it.
        rig.dao.afterFinalize = {
            rig.dao.afterFinalize = null
            TranscriptRepository(rig.dao).recoverStaleOpenRows(nowMs = Long.MAX_VALUE, cutoffMs = Long.MAX_VALUE - 1)
        }
        rig.dao.holdFinalize!!.complete(Unit)
        val row = theOnlyRow()
        assertEquals(TranscriptEntity.STATUS_INSERTION_INTERRUPTED, row.status)
        assertEquals(InsertionResults.CLIPBOARD, row.insertionResult)
    }

    // Recovery (row 9): the rig's DAO mirrors the Room queries; the SQL itself is TranscriptRouteDaoTest's.

    private fun row(id: Long, status: String, result: String, changedAtMs: Long) = TranscriptEntity(
        id = id, originalText = "words", finalText = "Words.", createdAtMs = changedAtMs, durationMs = 1L,
        speechEngine = "Parakeet", polishEngine = "Deterministic fallback", polishLatencyMs = 0L,
        insertionResult = result, status = status, stateChangedAtMs = changedAtMs,
    )

    /** Row 9. MUTATION: write the saved row as `ready_for_insertion`. */
    @Test fun recoveryReadsANeutralRowAsDeliveryUnknownAndAReadyRowAsAnInterruptedPaste() = runBlocking {
        val dao = rig.dao
        dao.rows[1L] = row(1L, TranscriptEntity.STATUS_SAVED_UNROUTED, "pending", 1_000L)
        dao.rows[2L] = row(2L, TranscriptEntity.STATUS_READY_FOR_INSERTION, "pending", 1_000L)
        dao.rows[3L] = row(3L, TranscriptEntity.STATUS_INSERTION_INTERRUPTED, InsertionResults.CLIPBOARD, 1_000L)
        dao.rows[4L] = row(4L, TranscriptEntity.STATUS_SAVED_UNROUTED, "pending", 99_000L)
        val recovered = TranscriptRepository(dao).recoverStaleOpenRows(nowMs = 100_000L, cutoffMs = 50_000L)
        assertEquals(listOf(2L), recovered.readyRowIds)
        assertEquals("the delivery-unknown rows are counted, so they are reported (code review 1)", 1, recovered.unknownCount)
        assertEquals(TranscriptEntity.STATUS_COMPLETED to InsertionResults.DELIVERY_UNKNOWN, dao.rows.getValue(1L).let { it.status to it.insertionResult })
        assertEquals(InsertionResults.INSERTION_INTERRUPTED, dao.rows.getValue(2L).insertionResult)
        assertEquals("a reconciled copy row is left alone", InsertionResults.CLIPBOARD, dao.rows.getValue(3L).insertionResult)
        assertEquals("a row younger than the cutoff is never recovered", TranscriptEntity.STATUS_SAVED_UNROUTED, dao.rows.getValue(4L).status)
        // And the saved row of a real take is neutral before its route, so a death then reads as delivery unknown.
        rig.dao.holdPromotion = CompletableDeferred()
        val coordinator = rig.coordinator(historySaveBoundMs = 5_000L)
        takeWithPolishedWords(coordinator)
        rig.endings.awaitOne()
        rig.host.awaitServiceStopped()
        assertEquals(TranscriptEntity.STATUS_SAVED_UNROUTED, dao.rows.values.first { it.finalText == "Keep these words." }.status)
    }

    /** Row 12. MUTATION: scan ready rows before neutral rows in `recoverStaleOpenRows`. */
    @Test fun recoveryScansDraftsThenNeutralThenReady() = runBlocking {
        val order = mutableListOf<String>()
        val recording = object : com.envi.wispr.history.TranscriptDao by rig.dao {
            override suspend fun recoverStaleDrafts(cutoffMs: Long, nowMs: Long): Int { order += "drafts"; return 0 }
            override suspend fun recoverStaleUnroutedRows(cutoffMs: Long, nowMs: Long): Int { order += "neutral"; return 0 }
            override suspend fun recoverStaleReadyRowsReturningIds(cutoffMs: Long, nowMs: Long): List<Long> { order += "ready"; return emptyList() }
        }
        TranscriptRepository(recording).recoverStaleOpenRows(nowMs = 100_000L)
        assertEquals(listOf("drafts", "neutral", "ready"), order)
    }

    // The decision itself (row 7): the save and the bound compete once, in either order.

    /** Row 7. MUTATION: let `claimTimeout` succeed from any state. */
    @Test fun theSaveAndTheBoundCompeteOnceInEitherOrder() {
        val saveFirst = HistorySaveGate()
        assertTrue(saveFirst.saveAnswered(SaveOutcome.Saved(7L)))
        assertFalse("the bound lost to a save that answered an instant earlier", saveFirst.claimTimeout())
        assertEquals(SaveOutcome.Saved(7L), saveFirst.answered())

        val boundFirst = HistorySaveGate()
        assertTrue(boundFirst.claimTimeout())
        assertFalse("a late save never wins after the bound", boundFirst.saveAnswered(SaveOutcome.Saved(7L)))
        assertNull(boundFirst.answered())
        // The late row and the copy meet once, whichever comes second reconciles.
        assertNull("the row came first: no copy recorded yet", boundFirst.lateRow(7L))
        assertEquals("the copy came second: it reconciles the row", 7L, boundFirst.copied(ClipboardOutcome.COPIED))

        val copyFirst = HistorySaveGate()
        copyFirst.claimTimeout()
        assertNull(copyFirst.copied(ClipboardOutcome.COPIED))
        assertEquals(ClipboardOutcome.COPIED, copyFirst.lateRow(9L))
    }
}
