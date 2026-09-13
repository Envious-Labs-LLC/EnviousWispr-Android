package com.envi.wispr.paste

import com.envi.wispr.paste.AccessibilityInsertionRules.EditorRead
import com.envi.wispr.paste.AccessibilityInsertionRules.EditorSelection
import com.envi.wispr.paste.AccessibilityInsertionRules.Judgement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PRODUCT OUTCOME tests on the one-write loop (#141). When one of these fails the user sees their
 * words twice, or a write is sent after the deadline, or an older clipboard comes back over a newer one.
 */
class InsertionAttemptTest {

    /** A scripted editor. Every call is counted; the field is a real string the paste mutates. */
    private class FakeEditor(
        var field: String? = "Hi team, ",
        var hint: Boolean = false,
        var selection: EditorSelection? = EditorSelection(0, 0),
        var canPaste: Boolean = true,
        var sensitive: Boolean = false,
        var present: Boolean = true,
        var pasteReturns: Boolean = true,
        var pasteMutates: Boolean = true,
        var pasteThrows: Boolean = false,
        var pasteTargetGoneOnce: Boolean = false,
        var stageReturns: Boolean = true,
        var stageThrows: Boolean = false,
        var clipboardUnreadableAfterFirstStaging: Boolean = false,
        /** A standard EditText advertises paste only once the clipboard holds something. */
        var pasteAdvertisedOnlyAfterStaging: Boolean = false,
        var readThrowsAfterWrite: Boolean = false,
        var locateThrowsOnce: Boolean = false,
        var commitEligible: Boolean = false,
        var commitThrows: Boolean = false,
        var readCostMs: Long = 0L,
    ) : EditorWrites {
        var clock: Long = 0L
        var pastes = 0
        var commits = 0
        var stagings = 0
        var staged: String? = null
        var written = false

        override fun locateTarget(): TargetState? {
            clock += readCostMs
            if (locateThrowsOnce) {
                locateThrowsOnce = false
                throw IllegalStateException("node went away")
            }
            if (!present) return null
            val advertisesPaste = if (pasteAdvertisedOnlyAfterStaging) staged != null else canPaste
            return TargetState(EditorRead(field, hint), selection, sensitive, advertisesPaste)
        }

        override fun commitEligible(): Boolean = commitEligible

        override fun stageClipboard(payload: String): String? {
            if (stageThrows) throw IllegalStateException("clipboard denied")
            stagings += 1
            if (!stageReturns) return null
            // A refused read-back after the first staging keeps what is already there.
            if (staged != null && clipboardUnreadableAfterFirstStaging) return staged
            staged = payload
            return payload
        }

        override fun paste(): PasteOutcome {
            if (pasteTargetGoneOnce) {
                pasteTargetGoneOnce = false
                return PasteOutcome.TARGET_GONE
            }
            pastes += 1
            if (pasteThrows) throw IllegalStateException("binder died")
            if (!pasteReturns) return PasteOutcome.REFUSED
            if (pasteMutates) mutate(checkNotNull(staged))
            return PasteOutcome.ACCEPTED
        }

        override fun commit(payload: String) {
            commits += 1
            if (commitThrows) throw IllegalStateException("connection gone")
            mutate(payload)
        }

        private fun mutate(payload: String) {
            written = true
            // The real editor inserts at its true caret, which Gmail reports as 0/0: put it at the END.
            field = (field ?: "") + payload
        }

        override fun readTarget(): EditorRead? {
            clock += readCostMs
            if (readThrowsAfterWrite && written) throw IllegalStateException("stale node")
            if (!present) return null
            return EditorRead(field, hint)
        }

        override fun now(): Long = clock
    }

    private fun attempt(editor: FakeEditor, text: String = "and I will", deadline: Long = 2_500L) =
        InsertionAttempt(editor, text, smartInsertion = false, deadlineMs = deadline)

    @Test
    fun pasteThatLandsIsVerifiedOnTheFirstTickWithOneWrite() {
        val editor = FakeEditor()
        val attempt = attempt(editor)
        val tick = attempt.tick()
        assertEquals(InsertionAttempt.Tick.Verified(InsertionRoute.PASTE), tick)
        assertEquals(1, editor.pastes)
        assertEquals(1, attempt.writeCount)
        assertEquals(InsertionAttempt.Returned.TRUE, attempt.returned)
        assertEquals("Hi team, and I will", editor.field)
    }

    @Test
    fun pasteRefusedByTheEditorEndsTheAttemptWithNoFurtherWrite() {
        val editor = FakeEditor(pasteReturns = false)
        val attempt = attempt(editor)
        assertEquals(InsertionAttempt.Tick.Rejected, attempt.tick())
        assertEquals(InsertionAttempt.Returned.FALSE, attempt.returned)
        assertFalse(attempt.written)
        assertEquals(1, attempt.writeCount)
        assertEquals("and I will", editor.staged)
    }

    @Test
    fun pasteThatThrowsIsTreatedAsWrittenAndNeverRetried() {
        val editor = FakeEditor(pasteThrows = true)
        val attempt = attempt(editor)
        assertEquals(InsertionAttempt.Tick.Waiting, attempt.tick())
        assertNotNull(attempt.verification)
        assertEquals(InsertionAttempt.Returned.THREW, attempt.returned)
        assertTrue(attempt.written)
        repeat(5) { attempt.tick() }
        assertEquals(1, editor.pastes)
        assertEquals(1, attempt.writeCount)
    }

    @Test
    fun pasteAcceptedButNotYetVisibleKeepsJudgingWithoutAnotherWrite() {
        val editor = FakeEditor(pasteMutates = false)
        val attempt = attempt(editor)
        assertEquals(InsertionAttempt.Tick.Waiting, attempt.tick())
        assertEquals(Judgement.MISS, attempt.lastJudgement)
        repeat(3) { assertEquals(InsertionAttempt.Tick.Waiting, attempt.tick()) }
        assertEquals(1, editor.pastes)
        // The words arrive late; the next judge sees them.
        editor.field = "Hi team, and I will"
        assertEquals(InsertionAttempt.Tick.Verified(InsertionRoute.PASTE), attempt.tick())
        assertEquals(1, attempt.writeCount)
    }

    @Test
    fun readThrowingAfterTheWriteIsUnreadableAndNeverWritesAgain() {
        val editor = FakeEditor(readThrowsAfterWrite = true)
        val attempt = attempt(editor)
        assertEquals(InsertionAttempt.Tick.Waiting, attempt.tick())
        assertEquals(Judgement.UNREADABLE, attempt.lastJudgement)
        repeat(3) { assertEquals(InsertionAttempt.Tick.Waiting, attempt.tick()) }
        assertEquals(1, editor.pastes)
        assertEquals(1, attempt.writeCount)
        assertTrue(attempt.written)
    }

    @Test
    fun readThrowingBeforeTheWriteRetriesPreparationThenWritesOnce() {
        val editor = FakeEditor(locateThrowsOnce = true)
        val attempt = attempt(editor)
        assertEquals(InsertionAttempt.Tick.Waiting, attempt.tick())
        assertEquals(0, attempt.writeCount)
        assertNull(attempt.verification)
        assertEquals(InsertionAttempt.Tick.Verified(InsertionRoute.PASTE), attempt.tick())
        assertEquals(1, attempt.writeCount)
    }

    @Test
    fun commitThatThrowsIsWrittenAndNeverRetried() {
        val editor = FakeEditor(commitEligible = true, commitThrows = true)
        val attempt = attempt(editor)
        assertEquals(InsertionAttempt.Tick.Waiting, attempt.tick())
        assertEquals(InsertionRoute.COMMIT, attempt.route)
        assertEquals(InsertionAttempt.Returned.THREW, attempt.returned)
        repeat(4) { attempt.tick() }
        assertEquals(1, editor.commits)
        assertEquals(0, editor.pastes)
        assertEquals(0, editor.stagings)
    }

    @Test
    fun commitThatLandsIsVerifiedWithoutTouchingTheClipboard() {
        val editor = FakeEditor(commitEligible = true)
        val attempt = attempt(editor)
        assertEquals(InsertionAttempt.Tick.Verified(InsertionRoute.COMMIT), attempt.tick())
        assertEquals(InsertionAttempt.Returned.VOID, attempt.returned)
        assertEquals(0, editor.stagings)
        assertEquals(1, attempt.writeCount)
    }

    @Test
    fun targetNotPresentWaitsWithoutWritingUntilTheDeadline() {
        val editor = FakeEditor(present = false)
        val attempt = attempt(editor, deadline = 300L)
        assertEquals(InsertionAttempt.Tick.Waiting, attempt.tick())
        editor.clock = 300L
        assertEquals(InsertionAttempt.Tick.Expired(written = false), attempt.tick())
        assertEquals(0, attempt.writeCount)
    }

    @Test
    fun aReadThatConsumesTheBudgetForbidsTheWrite() {
        val editor = FakeEditor(readCostMs = 400L)
        val attempt = attempt(editor, deadline = 300L)
        assertEquals(InsertionAttempt.Tick.Expired(written = false), attempt.tick())
        assertEquals(0, editor.pastes)
        assertEquals(0, attempt.writeCount)
    }

    @Test
    fun aWriteThatOverrunsTheDeadlineIsStillJudgedAndFlagged() {
        val editor = FakeEditor()
        // The paste itself is slow: the clock passes the deadline inside the write.
        val slow = object : EditorWrites by editor {
            override fun paste(): PasteOutcome {
                editor.clock = 500L
                return editor.paste()
            }
        }
        val slowAttempt = InsertionAttempt(slow, "and I will", smartInsertion = false, deadlineMs = 100L)
        assertEquals(InsertionAttempt.Tick.Verified(InsertionRoute.PASTE), slowAttempt.tick())
        assertTrue(slowAttempt.overrun)
        assertEquals(1, slowAttempt.writeCount)
        // Two-way control: an ordinary paste inside the budget is not flagged.
        val prompt = attempt(FakeEditor())
        prompt.tick()
        assertFalse(prompt.overrun)
    }

    @Test
    fun stagingFailureWritesNothing() {
        val editor = FakeEditor(stageReturns = false)
        val attempt = attempt(editor)
        assertEquals(InsertionAttempt.Tick.StagingFailed, attempt.tick())
        assertEquals(0, editor.pastes)
        val throwing = FakeEditor(stageThrows = true)
        assertEquals(InsertionAttempt.Tick.StagingFailed, attempt(throwing).tick())
        assertEquals(0, throwing.pastes)
    }

    @Test
    fun sensitiveFieldIsRefusedBeforeAnyStagingOrWrite() {
        val editor = FakeEditor(sensitive = true)
        assertEquals(InsertionAttempt.Tick.Sensitive, attempt(editor).tick())
        assertEquals(0, editor.stagings)
        assertEquals(0, editor.pastes)
    }

    /**
     * REPRODUCIBLE (Codex code review, round 1): a standard EditText reports ACTION_PASTE only while
     * the clipboard holds something, so on an empty clipboard the first read says "cannot paste" and
     * the read after staging says "can". Revert that turns this red: checking the pre-staging read.
     */
    @Test
    fun pasteAdvertisedOnlyAfterStagingStillLands() {
        val editor = FakeEditor(pasteAdvertisedOnlyAfterStaging = true)
        val attempt = attempt(editor)
        assertEquals(InsertionAttempt.Tick.Verified(InsertionRoute.PASTE), attempt.tick())
        assertEquals(1, editor.pastes)
        assertEquals(1, attempt.writeCount)
    }

    /**
     * HYPOTHETICAL (Codex code review, round 2): the node vanishes between the read and the paste
     * call. ACTION_PASTE was never invoked, so nothing was mutated and the next tick may prepare
     * again. Revert that turns this red: treating a missing node as the editor's own "false".
     */
    @Test
    fun targetGoneAtThePasteCallRetriesPreparationWithoutCountingAWrite() {
        val editor = FakeEditor(pasteTargetGoneOnce = true)
        val attempt = attempt(editor)
        assertEquals(InsertionAttempt.Tick.Waiting, attempt.tick())
        assertEquals(0, attempt.writeCount)
        assertNull(attempt.verification)
        assertFalse(attempt.written)
        assertEquals(InsertionAttempt.Tick.Verified(InsertionRoute.PASTE), attempt.tick())
        assertEquals(1, attempt.writeCount)
        assertEquals(1, editor.pastes)
    }

    @Test
    fun pasteNotAdvertisedIsRejectedWithTheWordsStaged() {
        val editor = FakeEditor(canPaste = false)
        assertEquals(InsertionAttempt.Tick.Rejected, attempt(editor).tick())
        assertEquals("and I will", editor.staged)
        assertEquals(0, editor.pastes)
    }

    @Test
    fun deadlineAfterAWrittenButUnconfirmedPasteReportsWritten() {
        val editor = FakeEditor(pasteMutates = false)
        val attempt = attempt(editor, deadline = 300L)
        assertEquals(InsertionAttempt.Tick.Waiting, attempt.tick())
        editor.clock = 300L
        assertEquals(InsertionAttempt.Tick.Expired(written = true), attempt.tick())
        assertEquals(1, attempt.writeCount)
    }

    @Test
    fun smartPayloadIsReplacedByTheLiteralWordsWhenTheCaretMovedBeforeThePaste() {
        // Smart insertion adds a leading space after "team,"; the user then moves the caret, so the
        // literal words are staged instead and the record describes the field at paste time.
        val editor = FakeEditor(field = "Hi team,", selection = EditorSelection(8, 8))
        val moving = object : EditorWrites by editor {
            var locates = 0
            override fun locateTarget(): TargetState? {
                locates += 1
                if (locates == 2) editor.selection = EditorSelection(3, 3)
                return editor.locateTarget()
            }
        }
        val movingAttempt = InsertionAttempt(moving, "and I will", smartInsertion = true, deadlineMs = 2_500L)
        movingAttempt.tick()
        assertEquals("and I will", editor.staged)
        assertEquals(2, editor.stagings)
        assertEquals("and I will", movingAttempt.verification?.insertedText)
        // Codex code review round 7: a refused clipboard read is never a licence to write over it. With
        // the caret moved AND the clipboard unreadable, the smart payload already staged is what gets
        // pasted, and the record names it so the judge looks for the right text.
        val unreadable = FakeEditor(field = "Hi team,", selection = EditorSelection(8, 8), clipboardUnreadableAfterFirstStaging = true)
        val movingUnreadable = object : EditorWrites by unreadable {
            var locates = 0
            override fun locateTarget(): TargetState? {
                locates += 1
                if (locates == 2) unreadable.selection = EditorSelection(3, 3)
                return unreadable.locateTarget()
            }
        }
        val kept = InsertionAttempt(movingUnreadable, "and I will", smartInsertion = true, deadlineMs = 2_500L)
        kept.tick()
        assertEquals(" and I will ", unreadable.staged)
        assertEquals(" and I will ", kept.verification?.insertedText)
        assertEquals(1, unreadable.pastes)
        // Two-way control: with the caret still, the smart payload (a space on each side, because the
        // caret sits after a comma at the end of the field) is what is staged.
        val still = FakeEditor(field = "Hi team,", selection = EditorSelection(8, 8))
        InsertionAttempt(still, "and I will", smartInsertion = true, deadlineMs = 2_500L).tick()
        assertEquals(" and I will ", still.staged)
        assertEquals(1, still.stagings)
    }
}
