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
        /** Android refuses the read-back: a second staging must then be refused, never guessed. */
        var clipboardUnreadableAfterFirstStaging: Boolean = false,
        /** The caret moves between the composing read and the paste call, once. */
        var caretMovesBeforePasteOnce: Boolean = false,
        /** A standard EditText advertises paste only once the clipboard holds something. */
        var pasteAdvertisedOnlyAfterStaging: Boolean = false,
        var readThrowsAfterWrite: Boolean = false,
        var locateThrowsOnce: Boolean = false,
        var commitEligible: Boolean = false,
        var commitThrows: Boolean = false,
        /** The pipe answers its surrounding-text read; false is an editor like Chromium that gives null. */
        var surroundingAvailable: Boolean = true,
        /** Input finishes on the pipe between the eligibility check and the commit call, once. */
        var sessionChangesBeforeCommitOnce: Boolean = false,
        /** The editor applies the commit only when [applyLateCommit] is called, as a slow editor would. */
        var commitAppliesLate: Boolean = false,
        /** The captured input session is still the pipe's live one; false models focus leaving the field. */
        var commitSessionLive: Boolean = true,
        var readCostMs: Long = 0L,
    ) : EditorWrites {
        var clock: Long = 0L
        var pastes = 0
        var commits = 0
        var surroundingReads = 0
        private var lateCommit: String? = null

        fun applyLateCommit() {
            lateCommit?.let(::mutate)
            lateCommit = null
        }
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
            return TargetState(EditorRead(field, hint), selection, sensitive)
        }

        override fun commitEligible(): Boolean = commitEligible

        /**
         * The pipe reads the editor's TRUE caret, which in this fake is the end of the field even
         * while the node reports 0/0 (Gmail's shape).
         */
        override fun readSurrounding(beforeChars: Int, afterChars: Int): AccessibilityInsertionRules.SurroundingWindow? {
            clock += readCostMs
            surroundingReads += 1
            if (!surroundingAvailable || !present) return null
            val whole = field ?: ""
            val before = whole.takeLast(beforeChars)
            return AccessibilityInsertionRules.window(before, before.length, before.length, whole.length - before.length)
        }

        override fun stageClipboard(payload: String): Boolean {
            if (stageThrows) throw IllegalStateException("clipboard denied")
            stagings += 1
            if (!stageReturns) return false
            if (staged != null && clipboardUnreadableAfterFirstStaging) return false
            staged = payload
            return true
        }

        override fun paste(expectedBaseline: String?, expectedSelection: EditorSelection?): PasteOutcome {
            if (pasteTargetGoneOnce) {
                pasteTargetGoneOnce = false
                return PasteOutcome.TARGET_GONE
            }
            if (caretMovesBeforePasteOnce) {
                caretMovesBeforePasteOnce = false
                selection = EditorSelection(3, 3)
            }
            val now = AccessibilityInsertionRules.snapshot(field, hint, selection?.start ?: -1, selection?.end ?: -1)
            if (now.baseline != expectedBaseline || now.selection != expectedSelection) return PasteOutcome.CONTEXT_CHANGED
            val advertisesPaste = if (pasteAdvertisedOnlyAfterStaging) staged != null else canPaste
            if (!advertisesPaste) return PasteOutcome.REFUSED
            pastes += 1
            if (pasteThrows) throw IllegalStateException("binder died")
            if (!pasteReturns) return PasteOutcome.REFUSED
            if (pasteMutates) mutate(checkNotNull(staged))
            return PasteOutcome.ACCEPTED
        }

        override fun commit(payload: String): CommitOutcome {
            if (sessionChangesBeforeCommitOnce) {
                sessionChangesBeforeCommitOnce = false
                commitEligible = false
                return CommitOutcome.SESSION_CHANGED
            }
            commits += 1
            if (commitThrows) throw IllegalStateException("connection gone")
            if (commitAppliesLate) lateCommit = payload else mutate(payload)
            return CommitOutcome.SENT
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

        override fun commitSessionLive(): Boolean = commitSessionLive

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
        assertEquals(InsertionAttempt.Evidence.SURROUNDING, attempt.evidence)
        assertEquals(0, editor.stagings)
        assertEquals(1, attempt.writeCount)
        assertEquals("Hi team, and I will", editor.field)
    }

    @Test
    fun commitComposesTheSeamFromThePipeNotFromTheNodeCaret() {
        // The node says 0/0 (Gmail); the pipe says the caret is at the end after "Hi team, ". The
        // smart seam is repaired against the pipe's read: no leading space, one trailing space.
        val editor = FakeEditor(commitEligible = true)
        val attempt = InsertionAttempt(editor, "and I will", smartInsertion = true, deadlineMs = 2_500L)
        assertEquals(InsertionAttempt.Tick.Verified(InsertionRoute.COMMIT), attempt.tick())
        assertEquals("Hi team, and I will ", editor.field)
        assertEquals(2, editor.surroundingReads)
    }

    @Test
    fun commitWithoutASurroundingReadIsJudgedByTheNode() {
        val editor = FakeEditor(commitEligible = true, surroundingAvailable = false)
        val attempt = attempt(editor)
        assertEquals(InsertionAttempt.Tick.Verified(InsertionRoute.COMMIT), attempt.tick())
        assertEquals(InsertionAttempt.Evidence.NODE, attempt.evidence)
        assertEquals(1, editor.commits)
    }

    @Test
    fun aSessionThatMovesBeforeTheCommitWritesNothingAndTheNextTickPastes() {
        val editor = FakeEditor(commitEligible = true, sessionChangesBeforeCommitOnce = true)
        val attempt = attempt(editor)
        assertEquals(InsertionAttempt.Tick.Waiting, attempt.tick())
        assertEquals(0, attempt.writeCount)
        assertEquals(null, attempt.verification)
        assertEquals(0, editor.commits)
        assertEquals(InsertionAttempt.Tick.Verified(InsertionRoute.PASTE), attempt.tick())
        assertEquals(0, editor.commits)
        assertEquals(1, editor.pastes)
        assertEquals(1, attempt.writeCount)
    }

    @Test
    fun aCommitTheEditorHasNotAppliedYetKeepsJudgingWithoutASecondWrite() {
        val editor = FakeEditor(commitEligible = true, commitAppliesLate = true)
        val attempt = attempt(editor, deadline = 500L)
        assertEquals(InsertionAttempt.Tick.Waiting, attempt.tick())
        assertEquals(AccessibilityInsertionRules.Judgement.MISS, attempt.lastJudgement)
        assertEquals(1, editor.commits)
        assertEquals(InsertionAttempt.Tick.Waiting, attempt.tick())
        editor.applyLateCommit()
        assertEquals(InsertionAttempt.Tick.Verified(InsertionRoute.COMMIT), attempt.tick())
        assertEquals(1, editor.commits)
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
            override fun paste(expectedBaseline: String?, expectedSelection: EditorSelection?): PasteOutcome {
                editor.clock = 500L
                return editor.paste(expectedBaseline, expectedSelection)
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

    /**
     * Round 9: an editor that exposes null text and no hint (some web views) still gets its paste;
     * the boundary comparison is null-to-null, not null-to-empty. The fake derives its baseline the
     * way the service does, through AccessibilityInsertionRules.snapshot.
     */
    @Test
    fun nullTextEditorStillGetsThePaste() {
        val editor = FakeEditor(field = null, selection = EditorSelection(0, 0), pasteMutates = false)
        val attempt = attempt(editor)
        assertEquals(InsertionAttempt.Tick.Waiting, attempt.tick())
        assertEquals(1, editor.pastes)
        assertEquals(1, attempt.writeCount)
        assertEquals(Judgement.UNREADABLE, attempt.lastJudgement)
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
    fun aCleanCommitIntoAnUnreadableFieldIsTrustedOnExpiryNotDroppedToClipboard() {
        // Chrome's web input: the commit is accepted through the connection, but every read-back is
        // null (surrounding pipe gives nothing, the node throws), so the field stays UNREADABLE to the
        // deadline. The words are in the box; the app must trust the commit, not warn and copy (issue).
        val editor = FakeEditor(commitEligible = true, surroundingAvailable = false, readThrowsAfterWrite = true)
        val attempt = attempt(editor, deadline = 300L)
        assertEquals(InsertionAttempt.Tick.Waiting, attempt.tick())
        assertEquals(Judgement.UNREADABLE, attempt.lastJudgement)
        editor.clock = 300L
        assertEquals(InsertionAttempt.Tick.Verified(InsertionRoute.COMMIT), attempt.tick())
        assertEquals(InsertionAttempt.Evidence.COMMIT_TRUSTED, attempt.evidence)
        assertEquals(1, editor.commits)
        assertEquals(1, attempt.writeCount)
        assertEquals(0, editor.stagings)
    }

    @Test
    fun aCleanCommitIntoAnUnreadableFieldIsTrustedWellBeforeTheDeadline() {
        // The point of the whole change: Chrome's unreadable box is trusted after a short grace, not
        // after the full 2.5 s deadline, so the recorder does not stall. Deadline is the real 2500 ms.
        val editor = FakeEditor(commitEligible = true, surroundingAvailable = false, readThrowsAfterWrite = true)
        val attempt = attempt(editor, deadline = 2_500L)
        assertEquals(InsertionAttempt.Tick.Waiting, attempt.tick())
        assertEquals(Judgement.UNREADABLE, attempt.lastJudgement)
        editor.clock = 250L
        assertEquals(InsertionAttempt.Tick.Verified(InsertionRoute.COMMIT), attempt.tick())
        assertEquals(InsertionAttempt.Evidence.COMMIT_TRUSTED, attempt.evidence)
        assertTrue("trusted long before the 2500ms deadline", editor.clock < 2_500L)
    }

    @Test
    fun aCommitStillUnreadableButInsideTheGraceIsNotYetTrusted() {
        // Before the grace elapses, a slow editor still has the chance to reveal a readable text or a
        // MISS, so we keep waiting rather than trusting instantly.
        val editor = FakeEditor(commitEligible = true, surroundingAvailable = false, readThrowsAfterWrite = true)
        val attempt = attempt(editor, deadline = 2_500L)
        assertEquals(InsertionAttempt.Tick.Waiting, attempt.tick())
        editor.clock = 125L
        assertEquals(InsertionAttempt.Tick.Waiting, attempt.tick())
    }

    @Test
    fun aCommitThatEverReadBackAMissIsNeverTrustedOnExpiry() {
        // A MISS is real evidence the editor changed or dropped the payload, so a later unreadable tick
        // must not launder it into a trusted commit: the clipboard fallback stays.
        val editor = FakeEditor(commitEligible = true, surroundingAvailable = false, commitAppliesLate = true)
        val attempt = attempt(editor, deadline = 300L)
        assertEquals(InsertionAttempt.Tick.Waiting, attempt.tick())
        assertEquals(Judgement.MISS, attempt.lastJudgement)
        editor.clock = 300L
        assertEquals(InsertionAttempt.Tick.Expired(written = true), attempt.tick())
    }

    @Test
    fun anUnreadablePasteIsNeverTrustedOnExpiry() {
        // Only the COMMIT route (the input connection) is trusted; a clipboard PASTE that cannot be
        // read back keeps the clipboard fallback.
        val editor = FakeEditor(readThrowsAfterWrite = true)
        val attempt = attempt(editor, deadline = 300L)
        assertEquals(InsertionAttempt.Tick.Waiting, attempt.tick())
        assertEquals(InsertionRoute.PASTE, attempt.route)
        assertEquals(Judgement.UNREADABLE, attempt.lastJudgement)
        editor.clock = 300L
        assertEquals(InsertionAttempt.Tick.Expired(written = true), attempt.tick())
    }

    @Test
    fun aCommitIntoADeadSessionIsNeverTrustedEvenWhenUnreadable() {
        // The connection can be discarded server-side just after the local liveness check, so commit
        // still returns SENT into nothing (Codex review 2026-09-15). If the session is no longer live
        // at expiry, focus left the field and nothing vouches for delivery: the clipboard fallback stands.
        val editor = FakeEditor(
            commitEligible = true,
            surroundingAvailable = false,
            readThrowsAfterWrite = true,
            commitSessionLive = false,
        )
        val attempt = attempt(editor, deadline = 300L)
        assertEquals(InsertionAttempt.Tick.Waiting, attempt.tick())
        assertEquals(Judgement.UNREADABLE, attempt.lastJudgement)
        editor.clock = 300L
        assertEquals(InsertionAttempt.Tick.Expired(written = true), attempt.tick())
    }

    @Test
    fun aCommitThatThrewIsNeverTrustedOnExpiry() {
        // THREW means the connection was gone, so the write most likely never landed: never trust it.
        val editor = FakeEditor(commitEligible = true, surroundingAvailable = false, commitThrows = true)
        val attempt = attempt(editor, deadline = 300L)
        assertEquals(InsertionAttempt.Tick.Waiting, attempt.tick())
        assertEquals(InsertionAttempt.Returned.THREW, attempt.returned)
        editor.clock = 300L
        assertEquals(InsertionAttempt.Tick.Expired(written = true), attempt.tick())
    }

    /**
     * The caret moves between the composing read and the paste call. The write boundary sees it
     * (CONTEXT_CHANGED), nothing is written, and the next tick composes against the new caret and
     * stages the literal words, which then land. Round 8 of the code review: a stale smart payload
     * can drop a seam word, so it is never pasted at a caret it was not composed for.
     */
    @Test
    fun caretMovedAtTheWriteBoundaryIsNeverPastedWithTheStalePayload() {
        val editor = FakeEditor(field = "Hi team,", selection = EditorSelection(8, 8), caretMovesBeforePasteOnce = true)
        val attempt = InsertionAttempt(editor, "and I will", smartInsertion = true, deadlineMs = 2_500L)
        assertEquals(InsertionAttempt.Tick.Waiting, attempt.tick())
        assertEquals(0, editor.pastes)
        assertEquals(0, attempt.writeCount)
        assertNull(attempt.verification)
        assertEquals(InsertionAttempt.Tick.Verified(InsertionRoute.PASTE), attempt.tick())
        assertEquals(1, editor.pastes)
        // Composed for the NEW caret (3/3, after "Hi "): no leading space, a trailing one before "team,".
        assertEquals("and I will ", editor.staged)
        assertEquals("and I will ", attempt.verification?.insertedText)
        assertEquals(2, editor.stagings)

        // Two-way control: with the caret still, the smart payload (a space on each side, because the
        // caret sits after a comma at the end of the field) is what is staged and pasted.
        val still = FakeEditor(field = "Hi team,", selection = EditorSelection(8, 8))
        assertEquals(InsertionAttempt.Tick.Verified(InsertionRoute.PASTE), InsertionAttempt(still, "and I will", smartInsertion = true, deadlineMs = 2_500L).tick())
        assertEquals(" and I will ", still.staged)
        assertEquals(1, still.stagings)
    }

    /**
     * Round 8 of the code review: a paste on a later tick delivers whatever the clipboard holds
     * THEN, so it needs confirmed ownership. Android refuses the read-back on the founder's phone;
     * the second staging is refused and nothing is pasted rather than an unknown clip.
     */
    @Test
    fun retryWithAnUnreadableClipboardNeverPastes() {
        val editor = FakeEditor(pasteTargetGoneOnce = true, clipboardUnreadableAfterFirstStaging = true)
        val attempt = attempt(editor)
        assertEquals(InsertionAttempt.Tick.Waiting, attempt.tick())
        assertEquals(InsertionAttempt.Tick.StagingFailed, attempt.tick())
        assertEquals(0, editor.pastes)
        assertEquals(0, attempt.writeCount)
    }
}
