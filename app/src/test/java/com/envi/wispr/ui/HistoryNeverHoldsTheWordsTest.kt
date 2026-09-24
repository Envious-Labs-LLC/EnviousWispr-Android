package com.envi.wispr.ui

import com.envi.wispr.history.TranscriptEntity
import com.envi.wispr.history.TranscriptRepository
import com.envi.wispr.insertion.InsertionResults
import com.envi.wispr.paste.InsertionHandoff
import com.envi.wispr.paste.recordInsertionOutcome
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Product Outcome (#277, superseding #235's clipboard route): insertion is the heart and History a limb
 * (`architecture-rules.md` FACT: heart-and-limbs), so the words are handed to insertion as soon as they are final,
 * whatever the History save is doing. Every outcome write resolves the take's row on the History queue, behind the
 * save; a slow or failed save is a defect, never a lost or diverted paste. The #235 rows that still hold (promotion,
 * no draft, recovery) are kept here.
 *
 * A held save is staged with the rig's gates, never a race against a fast timer. Each row names the compiling
 * mutation that turns it red (recorded in the PR's receipts).
 */
class HistoryNeverHoldsTheWordsTest {
    private val rig = DictationSessionRig()

    @After fun tearDown() {
        rig.dao.holdFinalize?.complete(Unit)
        rig.dao.holdPromotion?.complete(Unit)
        rig.dao.holdSavedInsert?.complete(Unit)
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

    /** Waits for [condition], which a worker thread makes true, instead of sleeping for it. */
    private fun awaitUntil(what: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10)
        while (!condition()) {
            check(System.nanoTime() < deadline) { "never: $what" }
            Thread.sleep(5)
        }
    }

    /**
     * Row 1. A held save: the words are handed to insertion BEFORE the save is released, the take's fact reads
     * `pending`, the bound raises one defect, and the outcome written through the handle lands on the finalized row
     * once the save lands. MUTATION m1: `publishResult` awaits the save before `deliver` again.
     */
    @Test fun aHeldSaveNeverHoldsTheWords() {
        rig.dao.holdFinalize = CompletableDeferred()
        rig.insertion.outcomeQueue = rig.historyWrites
        val coordinator = rig.coordinator(historySaveBoundMs = 50L)
        takeWithPolishedWords(coordinator)
        assertEquals(TerminalReason.COMPLETED, rig.endings.awaitOne())
        rig.host.awaitServiceStopped()
        assertEquals("handed to insertion while the save is held", listOf("Keep these words."), rig.insertion.requests.map { it.second })
        assertEquals("the save had not answered at the handoff", listOf(false), rig.insertion.savedAtHandoff.toList())
        assertEquals("pending", rig.endings.facts.single().historySave)
        awaitUntil("the bound's defect") { rig.defects.any { it.first == "history_save_timed_out" } }
        assertEquals(listOf("history_save_timed_out"), rig.defects.map { it.first }.filter { it.startsWith("history") })
        assertTrue("nothing went to the clipboard", rig.host.events.none { it.startsWith("clipboard:") })
        assertTrue(toasts().isEmpty())
        rig.dao.holdFinalize!!.complete(Unit)
        val row = theOnlyRow()
        assertEquals(InsertionResults.PASTED, row.insertionResult)
        assertEquals("the handoff resolves to the finalized row", listOf(row.id to "Keep these words."), rig.insertion.pastes)
    }

    /**
     * Row 2. A failed save after its draft exists: the words are still handed to insertion; the draft stays
     * unfinalized; the handle resolves 0, so no outcome is written to the draft; the failure is diagnosed once.
     */
    @Test fun aFailedSaveStillHandsTheWordsToInsertion() {
        rig.dao.failFinalize = true
        val coordinator = rig.coordinator(historySaveBoundMs = 5_000L)
        takeWithPolishedWords(coordinator)
        assertEquals(TerminalReason.COMPLETED, rig.endings.awaitOne())
        rig.host.awaitStopped()
        val draft = theOnlyRow()
        assertEquals("the draft stays unfinalized: never saved, never routed", TranscriptEntity.STATUS_PROCESSING, draft.status)
        assertEquals("pending", draft.insertionResult)
        assertEquals("handed to insertion, resolving to no saved row", listOf(0L to "Keep these words."), rig.insertion.pastes)
        rig.log.awaitLine("Unable to save transcript history")
        assertTrue(rig.host.events.none { it.startsWith("clipboard:") })
    }

    /** Row 2 (the runner's half): no saved row emits exactly once without a write. MUTATION m2: the zero branch skips the emit. */
    @Test fun anOutcomeWithNoSavedRowEmitsOnceAndWritesNothing() = runBlocking {
        var emits = 0
        var writes = 0
        recordInsertionOutcome(0L, write = { writes += 1; 1 }, emit = { emits += 1 })
        assertEquals(1, emits)
        assertEquals(0, writes)
        recordInsertionOutcome(7L, write = { 0 }, emit = { emits += 1 })
        assertEquals("a row another writer already won emits nothing", 1, emits)
        recordInsertionOutcome(7L, write = { 1 }, emit = { emits += 1 })
        assertEquals(2, emits)
    }

    /**
     * Row 3. The draft insert fails and the publication's own saved-row insert is held: the words are handed to
     * insertion first, and the outcome, resolved on the queue, lands on the new row once it exists. MUTATION m3:
     * `TakeHistory.resolveOnQueue` answers the id captured when the handoff was requested.
     */
    @Test fun theOutcomeResolvesToARowSavedAfterTheHandoff() {
        rig.dao.failDraftInsert = true
        rig.dao.holdSavedInsert = CompletableDeferred()
        rig.insertion.outcomeQueue = rig.historyWrites
        val coordinator = rig.coordinator(historySaveBoundMs = 5_000L)
        takeWithPolishedWords(coordinator)
        assertEquals(TerminalReason.COMPLETED, rig.endings.awaitOne())
        rig.host.awaitServiceStopped()
        assertEquals("handed over before the row existed", 1, rig.insertion.requests.size)
        assertTrue(rig.dao.rows.isEmpty())
        rig.dao.holdSavedInsert!!.complete(Unit)
        val row = theOnlyRow()
        assertEquals(InsertionResults.PASTED, row.insertionResult)
        assertEquals(listOf(row.id to "Keep these words."), rig.insertion.pastes)
    }

    /** Row 4. A failed handoff with auto-copy off and the save still pending copies. MUTATION m4: the copy reads only the setting. */
    @Test fun aFailedHandoffBeforeTheSaveAnswersCopiesEvenWithAutoCopyOff() {
        rig.preferenceStates.value = rig.preferenceStates.value.copy(autoCopyToClipboard = false)
        rig.insertion.handoff = InsertionHandoff.SERVICE_NOT_RUNNING
        rig.dao.holdFinalize = CompletableDeferred()
        val coordinator = rig.coordinator(historySaveBoundMs = 5_000L)
        takeWithPolishedWords(coordinator)
        rig.endings.awaitOne()
        rig.host.awaitServiceStopped()
        assertTrue("the words reach the clipboard whatever the setting", rig.host.events.contains("clipboard:Keep these words."))
        rig.dao.holdFinalize!!.complete(Unit)
        assertEquals("the owner's outcome lands on the row once it is saved", InsertionResults.CLIPBOARD, theOnlyRow().insertionResult)
    }

    /** A failed copy on that route is recorded as such once the row lands. */
    @Test fun aFailedCopyBeforeTheSaveAnswersIsRecordedAsSuch() {
        rig.insertion.handoff = InsertionHandoff.SERVICE_NOT_RUNNING
        rig.host.clipboardWorks = false
        rig.dao.holdFinalize = CompletableDeferred()
        val coordinator = rig.coordinator(historySaveBoundMs = 5_000L)
        takeWithPolishedWords(coordinator)
        rig.endings.awaitOne()
        rig.host.awaitServiceStopped()
        rig.dao.holdFinalize!!.complete(Unit)
        assertEquals(InsertionResults.INSERTION_FAILED, theOnlyRow().insertionResult)
    }

    /**
     * Row 5. The promotion and the insertion outcome, each gated, in both orders, end in the same row. Outcome first:
     * the promotion finds no neutral row. Promotion first: the outcome updates the ready row.
     */
    @Test fun promotionAndOutcomeEndTheSameInEitherOrder() {
        // Outcome first: the fake writes the outcome at the handoff, and the promotion is held behind it.
        rig.insertion.outcomeQueue = rig.historyWrites
        rig.dao.holdPromotion = CompletableDeferred()
        val first = rig.coordinator(historySaveBoundMs = 5_000L)
        takeWithPolishedWords(first)
        rig.endings.awaitOne()
        rig.host.awaitServiceStopped()
        rig.dao.holdPromotion!!.complete(Unit)
        val outcomeFirst = theOnlyRow()
        assertEquals(listOf("outcome", "promotion"), rig.dao.routeWrites.toList())
        assertEquals(InsertionResults.PASTED, outcomeFirst.insertionResult)
        assertEquals(TranscriptEntity.STATUS_COMPLETED, outcomeFirst.status)

        // Promotion first: the outcome is released only after the promotion landed.
        DictationSessionRig().let { take ->
            try {
                take.insertion.outcomeQueue = take.historyWrites
                take.insertion.deferOutcome = true
                val coordinator = take.coordinator(historySaveBoundMs = 5_000L)
                coordinator.onCreated()
                take.command(coordinator, DictationSessionService.ACTION_START)
                take.surface.awaitShown()
                take.command(coordinator, DictationSessionService.ACTION_STOP)
                take.speech.awaitRequest().onResult("keep these words")
                take.polish.awaitRequest().onOutcome(take.polish.outcome("Keep these words."))
                take.endings.awaitOne()
                take.host.awaitStopped()
                take.awaitHistoryIdle()
                take.insertion.releaseOutcome()
                val promotionFirst = take.awaitHistoryIdle().let { take.dao.rows.values.single() }
                assertEquals(listOf("promotion", "outcome"), take.dao.routeWrites.toList())
                assertEquals(outcomeFirst.status to outcomeFirst.insertionResult, promotionFirst.status to promotionFirst.insertionResult)
            } finally {
                take.close()
            }
        }
    }

    /** A save that never answers owes nothing: the words were handed over and the take ended. */
    @Test fun aSaveThatNeverAnswersNeverHoldsTheTake() {
        rig.dao.holdFinalize = CompletableDeferred()
        val coordinator = rig.coordinator(historySaveBoundMs = 50L)
        takeWithPolishedWords(coordinator)
        assertEquals(TerminalReason.COMPLETED, rig.endings.awaitOne())
        rig.host.awaitServiceStopped()
        assertEquals(1, rig.insertion.requests.size)
    }

    // #235 rows that still hold.

    /** #235 row 4, adapted: a save that answers is promoted after the scheduled handoff. MUTATION: promote before the handoff. */
    @Test fun aScheduledHandoffIsPromotedAfterTheHandoff() {
        val coordinator = rig.coordinator(historySaveBoundMs = 5_000L)
        takeWithPolishedWords(coordinator)
        assertEquals(TerminalReason.COMPLETED, rig.endings.awaitOne())
        rig.host.awaitStopped()
        val row = theOnlyRow()
        assertEquals(listOf(row.id to "Keep these words."), rig.insertion.pastes)
        assertEquals("promoted to ready after the scheduled handoff", TranscriptEntity.STATUS_READY_FOR_INSERTION, row.status)
        assertEquals("pending", row.insertionResult)
        assertTrue(toasts().isEmpty())
    }

    /** #235 row 11. MUTATION: await the promotion before the handoff. */
    @Test fun aStalledPromotionNeverHoldsTheWords() {
        rig.dao.holdPromotion = CompletableDeferred()
        val coordinator = rig.coordinator(historySaveBoundMs = 5_000L)
        takeWithPolishedWords(coordinator)
        assertEquals(TerminalReason.COMPLETED, rig.endings.awaitOne())
        rig.host.awaitServiceStopped()
        assertEquals("the words were handed off while the promotion is held", 1, rig.insertion.requests.size)
        rig.dao.holdPromotion!!.complete(Unit)
        assertEquals(TranscriptEntity.STATUS_READY_FOR_INSERTION, theOnlyRow().status)
    }

    /** #235 row 13. MUTATION: promote on every handoff, not only a scheduled one. */
    @Test fun aHandoffThatWasNotScheduledIsNeverPromoted() {
        rig.insertion.handoff = InsertionHandoff.SERVICE_NOT_RUNNING
        rig.dao.failOutcome = true
        val coordinator = rig.coordinator(historySaveBoundMs = 5_000L)
        takeWithPolishedWords(coordinator)
        assertEquals(TerminalReason.COMPLETED, rig.endings.awaitOne())
        rig.host.awaitStopped()
        assertEquals("a failed fallback outcome leaves the row neutral, never ready", TranscriptEntity.STATUS_SAVED_UNROUTED, theOnlyRow().status)
    }

    /** #235 row 9c. MUTATION: write the no-draft saved row as `ready_for_insertion`. */
    @Test fun aTakeWithNoDraftAlsoSavesANeutralRow() {
        rig.dao.failDraftInsert = true
        rig.dao.holdPromotion = CompletableDeferred()
        val coordinator = rig.coordinator(historySaveBoundMs = 5_000L)
        takeWithPolishedWords(coordinator)
        rig.endings.awaitOne()
        rig.host.awaitServiceStopped()
        awaitUntil("the saved row") { rig.dao.rows.isNotEmpty() }
        assertEquals(TranscriptEntity.STATUS_SAVED_UNROUTED, rig.dao.rows.values.single().status)
    }

    /** A destroy after the reservation, while the save is held: the revoked commit hands nothing over (G2 D2). */
    @Test fun aDestroyBeforeTheCommitDeliversNothing() {
        rig.dao.holdFinalize = CompletableDeferred()
        val coordinator = rig.coordinator(historySaveBoundMs = 5_000L)
        coordinator.onCreated()
        rig.command(coordinator, DictationSessionService.ACTION_START)
        rig.surface.awaitShown()
        rig.command(coordinator, DictationSessionService.ACTION_STOP)
        rig.speech.awaitRequest().onResult("keep these words")
        val polish = rig.polish.awaitRequest()
        rig.onMain { coordinator.destroy() }
        polish.onOutcome(rig.polish.outcome("Keep these words."))
        rig.dao.holdFinalize!!.complete(Unit)
        rig.awaitHistoryIdle()
        assertTrue(rig.insertion.requests.isEmpty())
        assertFalse(rig.host.events.any { it.startsWith("clipboard:") })
    }

    // Recovery: the rig's DAO mirrors the Room queries; the SQL itself is TranscriptRouteDaoTest's.

    private fun row(id: Long, status: String, result: String, changedAtMs: Long) = TranscriptEntity(
        id = id, originalText = "words", finalText = "Words.", createdAtMs = changedAtMs, durationMs = 1L,
        speechEngine = "Parakeet", polishEngine = "Deterministic fallback", polishLatencyMs = 0L,
        insertionResult = result, status = status, stateChangedAtMs = changedAtMs,
    )

    /** #235 row 9. MUTATION: write the saved row as `ready_for_insertion`. */
    @Test fun recoveryReadsANeutralRowAsDeliveryUnknownAndAReadyRowAsAnInterruptedPaste() = runBlocking {
        val dao = rig.dao
        dao.rows[1L] = row(1L, TranscriptEntity.STATUS_SAVED_UNROUTED, "pending", 1_000L)
        dao.rows[2L] = row(2L, TranscriptEntity.STATUS_READY_FOR_INSERTION, "pending", 1_000L)
        dao.rows[3L] = row(3L, TranscriptEntity.STATUS_INSERTION_INTERRUPTED, InsertionResults.CLIPBOARD, 1_000L)
        dao.rows[4L] = row(4L, TranscriptEntity.STATUS_SAVED_UNROUTED, "pending", 99_000L)
        val recovered = TranscriptRepository(dao).recoverStaleOpenRows(nowMs = 100_000L, cutoffMs = 50_000L)
        assertEquals(listOf(2L), recovered.readyRowIds)
        assertEquals("the delivery-unknown rows are counted, so they are reported", 1, recovered.unknownCount)
        assertEquals(TranscriptEntity.STATUS_COMPLETED to InsertionResults.DELIVERY_UNKNOWN, dao.rows.getValue(1L).let { it.status to it.insertionResult })
        assertEquals(InsertionResults.INSERTION_INTERRUPTED, dao.rows.getValue(2L).insertionResult)
        assertEquals("a routed row is left alone", InsertionResults.CLIPBOARD, dao.rows.getValue(3L).insertionResult)
        assertEquals("a row younger than the cutoff is never recovered", TranscriptEntity.STATUS_SAVED_UNROUTED, dao.rows.getValue(4L).status)
        // And the saved row of a real take is neutral before its route, so a death then reads as delivery unknown.
        rig.dao.holdPromotion = CompletableDeferred()
        val coordinator = rig.coordinator(historySaveBoundMs = 5_000L)
        takeWithPolishedWords(coordinator)
        rig.endings.awaitOne()
        rig.host.awaitServiceStopped()
        assertEquals(TranscriptEntity.STATUS_SAVED_UNROUTED, dao.rows.values.first { it.finalText == "Keep these words." }.status)
    }

    /** #235 row 12. MUTATION: scan ready rows before neutral rows in `recoverStaleOpenRows`. */
    @Test fun recoveryScansDraftsThenNeutralThenReady() = runBlocking {
        val order = mutableListOf<String>()
        val recording = object : com.envi.wispr.history.TranscriptDao by rig.dao {
            override suspend fun recoverStaleDrafts(cutoffMs: Long, nowMs: Long): Int { order += "drafts"; return 0 }
            override suspend fun recoverStaleProcessingRows(cutoffMs: Long, nowMs: Long): Int { order += "processing"; return 0 }
            override suspend fun recoverStaleUnroutedRows(cutoffMs: Long, nowMs: Long): Int { order += "neutral"; return 0 }
            override suspend fun recoverStaleReadyRowsReturningIds(cutoffMs: Long, nowMs: Long): List<Long> { order += "ready"; return emptyList() }
        }
        TranscriptRepository(recording).recoverStaleOpenRows(nowMs = 100_000L)
        assertEquals(listOf("drafts", "processing", "neutral", "ready"), order)
    }

    /**
     * Code review round 1: a take killed after its words were handed over and before its save landed leaves a
     * processing row; recovery reads it as delivery unknown, never "not attempted". A draft (still recording) stays
     * "not attempted". MUTATION m6: `recoverStaleOpenRows` skips the processing scan (the draft scan then no longer
     * matches it, and the row stays processing).
     */
    @Test fun recoveryReadsAProcessingRowAsDeliveryUnknownAndADraftAsNotAttempted() = runBlocking {
        val dao = rig.dao
        dao.rows[1L] = row(1L, TranscriptEntity.STATUS_PROCESSING, "pending", 1_000L)
        dao.rows[2L] = row(2L, TranscriptEntity.STATUS_DRAFT, "pending", 1_000L)
        val recovered = TranscriptRepository(dao).recoverStaleOpenRows(nowMs = 100_000L, cutoffMs = 50_000L)
        assertEquals(InsertionResults.DELIVERY_UNKNOWN, dao.rows.getValue(1L).insertionResult)
        assertEquals(TranscriptEntity.STATUS_INTERRUPTED, dao.rows.getValue(1L).status)
        assertEquals("not_attempted", dao.rows.getValue(2L).insertionResult)
        assertEquals("the unknown row is counted, so it is reported", 1, recovered.unknownCount)
    }

    /**
     * Code review round 1: the bound is measured from the save's enqueue, not from when the observer started. The
     * clock jumps past the bound the moment the save lands; the observer, still inside its real wait, must report it.
     * MUTATION m5: drop the elapsed-since-enqueue check after the answer.
     */
    @Test fun aSaveAnsweredPastTheBoundIsReportedEvenIfTheObserverSawItInTime() {
        val bound = 1_000L
        var now = 50_000L
        rig.host.clock = { now }
        rig.dao.afterFinalize = { now += bound + 1 }
        val coordinator = rig.coordinator(historySaveBoundMs = bound)
        takeWithPolishedWords(coordinator)
        assertEquals(TerminalReason.COMPLETED, rig.endings.awaitOne())
        rig.host.awaitStopped()
        awaitUntil("the late save's defect") { rig.defects.any { it.first == "history_save_timed_out" } }
    }
}
