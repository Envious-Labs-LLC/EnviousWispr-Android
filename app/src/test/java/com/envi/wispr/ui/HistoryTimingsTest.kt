package com.envi.wispr.ui

import com.envi.wispr.history.TranscriptEntity
import com.envi.wispr.paste.InsertionHandoff
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Product Outcome (#378 D8): a History row keeps its take's timings, so the founder can read how long each step took
 * on the Developer-unlocked card, and an unmeasured step reads as absent, never as zero. Each row names the compiling
 * mutation that turns it red.
 */
class HistoryTimingsTest {
    private val rig = DictationSessionRig()

    @After fun tearDown() = rig.close()

    private fun row(
        liveAfterMs: Long? = null,
        asrMs: Long? = null,
        insertionMs: Long? = null,
        endToEndMs: Long? = null,
    ) = TranscriptEntity(
        originalText = "words", finalText = "Words.", createdAtMs = 0L, durationMs = 1L, speechEngine = "Parakeet",
        polishEngine = "Off", polishLatencyMs = 0L, insertionResult = "pending",
        liveAfterMs = liveAfterMs, asrMs = asrMs, insertionMs = insertionMs, endToEndMs = endToEndMs,
    )

    private fun completedTake(coordinator: DictationSessionCoordinator) {
        coordinator.onCreated()
        rig.command(coordinator, DictationSessionService.ACTION_START)
        rig.surface.awaitShown()
        rig.command(coordinator, DictationSessionService.ACTION_STOP)
        rig.speech.awaitRequest().onResult("keep these words")
        rig.polish.awaitRequest().onOutcome(rig.polish.outcome("Keep these words."))
        rig.endings.awaitOne()
        rig.host.awaitServiceStopped()
    }

    /** MUTATION: print a missing step as `0 ms`. */
    @Test fun theLineNamesOnlyTheMeasuredSteps() {
        assertEquals("Timings: live 120 ms · speech 702 ms · insert 140 ms · total 4.4 s", HistoryTimings.line(row(120L, 702L, 140L, 4_400L)))
        assertEquals("Timings: speech 702 ms · total 1.0 s", HistoryTimings.line(row(asrMs = 702L, endToEndMs = 1_000L)))
        assertEquals("an older row shows no timings line", "", HistoryTimings.line(row()))
    }

    /** MUTATION: read the timings from anywhere but the take's facts, or drop them from the save. */
    @Test fun theSavedRowKeepsTheTakesLiveAndSpeechTimes() {
        completedTake(rig.coordinator(historySaveBoundMs = 5_000L))
        val saved = rig.awaitHistoryIdle().let { rig.dao.rows.values.single() }
        assertEquals("the capture's live-after, as the take's facts recorded it", 120L, saved.liveAfterMs)
        assertNotNull("the speech time the take recorded", saved.asrMs)
    }

    /** MUTATION: hand insertion a zero acceptance time. The insertion service measures end to end from it. */
    @Test fun aScheduledHandoffCarriesTheTakesAcceptanceTime() {
        completedTake(rig.coordinator(historySaveBoundMs = 5_000L))
        assertTrue("the handoff carries when the take was accepted", rig.insertion.acceptedAtHandoff.single() > 0L)
    }

    /** Codex round 1. MUTATION: stamp the end to end before the copy. A slow copy is inside the take's total. */
    @Test fun aClipboardTakeEndsAfterItsCopy() {
        val now = java.util.concurrent.atomic.AtomicLong(1_000_000L)
        rig.host.clock = { now.get() }
        rig.host.duringCopy = { now.addAndGet(10_000L) }
        rig.insertion.handoff = InsertionHandoff.SERVICE_NOT_RUNNING
        completedTake(rig.coordinator(historySaveBoundMs = 5_000L))
        val saved = rig.awaitHistoryIdle().let { rig.dao.rows.values.single() }
        assertTrue("the total includes the copy: ${saved.endToEndMs}", saved.endToEndMs!! >= 10_000L)
    }

    /** MUTATION: skip the end-to-end on a route with no insertion. It ends at the delivery, with no insertion time. */
    @Test fun aTakeWithNoInsertionEndsAtItsDelivery() {
        rig.insertion.handoff = InsertionHandoff.SERVICE_NOT_RUNNING
        completedTake(rig.coordinator(historySaveBoundMs = 5_000L))
        val saved = rig.awaitHistoryIdle().let { rig.dao.rows.values.single() }
        assertNotNull("the take's end to end, to its delivery", saved.endToEndMs)
        assertTrue(saved.endToEndMs!! >= 0L)
        assertNull("nothing was handed to insertion, so no insertion time", saved.insertionMs)
    }
}
