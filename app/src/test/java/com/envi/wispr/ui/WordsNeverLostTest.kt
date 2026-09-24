package com.envi.wispr.ui

import com.envi.wispr.history.TranscriptEntity
import com.envi.wispr.paste.InsertionHandoff
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Product Outcome (#288): the customer always gets their words. A take whose words could not go into the field, whose
 * clipboard copy failed, and whose History save failed keeps them on the phone, tells the user so, and has them in
 * History after the next recovery. Each row waits on a subject-fired signal and names the mutation that turns it red.
 */
class WordsNeverLostTest {
    private val rig = DictationSessionRig()

    @After fun tearDown() {
        rig.dao.holdFinalize?.complete(Unit)
        rig.close()
        rig.rescueDir.deleteRecursively()
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

    private fun rescued() = rig.rescueDir.listFiles().orEmpty().filter { it.name.endsWith(".words") }

    private fun awaitUntil(what: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (!condition()) {
            check(System.nanoTime() < deadline) { "never: $what; toasts ${toasts()}" }
            Thread.sleep(5)
        }
    }

    /**
     * Row 1 (the issue's done-when; RED on main): no handoff, a failed copy, a failed save. The words are kept, the line
     * says so, and a recovery once the save has failed writes them into History and deletes the file.
     * MUTATIONS m1 (no write-ahead) and m7 (the kept line before the durable write).
     */
    @Test fun aTripleFailureKeepsTheWordsAndRecoversThemIntoHistory() {
        // A write slower than a race but well inside the owner's bound: only the owner's wait makes the line KEPT.
        rig.rescueWriteDelayMs = 60L
        rig.insertion.handoff = InsertionHandoff.SERVICE_NOT_RUNNING
        rig.host.clipboardWorks = false
        rig.dao.failInserts = true
        val coordinator = rig.coordinator(historySaveBoundMs = 5_000L)
        takeWithPolishedWords(coordinator)
        rig.endings.awaitOne()
        rig.host.awaitServiceStopped()
        awaitUntil("the fallback line") { toasts().isNotEmpty() }
        assertEquals(listOf("toast:Kept on this phone. Open EnviousWispr to find them."), toasts())
        assertEquals(1, rescued().size)
        assertEquals("1000\nKeep these words.", rescued().single().readText())
        assertTrue("never the words in a log", rig.log.lines.none { it.contains("Keep these words") })
        rig.dao.failInserts = false
        awaitUntil("recovered into History") { runBlocking { rig.rescuedWords.recover(rig.transcripts) } == 1 }
        val row = rig.dao.rows.values.single()
        assertEquals("Keep these words.", row.finalText)
        assertEquals(TranscriptEntity.STATUS_INSERTION_INTERRUPTED, row.status)
        assertEquals(emptyList<File>(), rescued())
    }

    /** Row 2: an ordinary take's save answers SAVED and deletes the words; nothing is left on the phone. MUTATION m2/m3. */
    @Test fun anOrdinaryTakeLeavesNothingBehind() {
        val coordinator = rig.coordinator(historySaveBoundMs = 5_000L)
        takeWithPolishedWords(coordinator)
        rig.endings.awaitOne()
        rig.host.awaitServiceStopped()
        awaitUntil("the settled file is gone") { rescued().isEmpty() }
        assertEquals("Keep these words.", rig.awaitHistoryIdle().let { rig.dao.rows.values.single { it.finalText.isNotBlank() } }.finalText)
    }

    /**
     * Row 3: a scheduled handoff whose save fails later keeps the words, though delivery looked successful at the time;
     * the next recovery writes them into History.
     */
    @Test fun aHandedOffTakeWhoseSaveFailsStillKeepsTheWords() {
        rig.dao.failInserts = true
        val coordinator = rig.coordinator(historySaveBoundMs = 5_000L)
        takeWithPolishedWords(coordinator)
        rig.endings.awaitOne()
        rig.host.awaitServiceStopped()
        assertEquals(1, rescued().size)
        rig.dao.failInserts = false
        awaitUntil("recovered into History") { runBlocking { rig.rescuedWords.recover(rig.transcripts) } == 1 }
        assertEquals("Keep these words.", rig.dao.rows.values.single().finalText)
    }

    /**
     * Row 3b (review round 1): a recovery that wrote the take's row before the take's own late save leaves one row, and
     * the save completes it instead of colliding with it. MUTATION m10: the save ignores a row the take already has.
     */
    @Test fun aLateSaveCompletesTheRowARecoveryWroteFirst() {
        // No draft, and a recovery writes the take's row before its save is even queued.
        rig.dao.failDraftInsert = true
        rig.dao.onDraftInsert = { takeId -> rig.transcripts.keepRescuedWords(takeId, "Keep these words.", 1_000L) }
        val coordinator = rig.coordinator(historySaveBoundMs = 5_000L)
        takeWithPolishedWords(coordinator)
        rig.endings.awaitOne()
        rig.host.awaitServiceStopped()
        val row = rig.awaitHistoryIdle().let { rig.dao.rows.values.single() }
        // Saved by the take itself, then promoted by its handoff, as an ordinary take's row is; never the recovery's status.
        assertEquals("the take's own save completed the row", TranscriptEntity.STATUS_READY_FOR_INSERTION, row.status)
        assertEquals("Keep these words.", row.finalText)
        awaitUntil("the settled file is gone") { rescued().isEmpty() }
    }

    /** Row 4: with the rescue failed and the save still pending, the line never says the words are lost. */
    @Test fun aPendingSaveIsNeverToldItsWordsAreLost() {
        rig.rescueDir.deleteRecursively()
        rig.rescueDir.writeText("not a directory")
        rig.insertion.handoff = InsertionHandoff.SERVICE_NOT_RUNNING
        rig.host.clipboardWorks = false
        rig.dao.holdFinalize = CompletableDeferred()
        val coordinator = rig.coordinator(historySaveBoundMs = 5_000L)
        takeWithPolishedWords(coordinator)
        rig.endings.awaitOne()
        awaitUntil("the fallback line") { toasts().isNotEmpty() }
        assertEquals(listOf("toast:Saving your words. Open EnviousWispr to check."), toasts())
        rig.rescueDir.delete()
    }

    /** Row 5: only a failed save AND a failed rescue say the words could not be saved. */
    @Test fun onlyTwoFailuresSayTheWordsAreLost() {
        rig.rescueDir.deleteRecursively()
        rig.rescueDir.writeText("not a directory")
        rig.insertion.handoff = InsertionHandoff.SERVICE_NOT_RUNNING
        rig.host.clipboardWorks = false
        rig.dao.failInserts = true
        // The save answers FAILED before the words are handed on, so "lost" is measured, never raced.
        rig.insertion.beforeHandoff = { rig.log.awaitLine("Unable to save transcript history") }
        val coordinator = rig.coordinator(historySaveBoundMs = 5_000L)
        takeWithPolishedWords(coordinator)
        rig.endings.awaitOne()
        awaitUntil("the fallback line") { toasts().isNotEmpty() }
        assertEquals(listOf("toast:Your words could not be saved. Please dictate again."), toasts())
        rig.rescueDir.delete()
    }
}
