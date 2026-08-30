package com.envi.wispr.paste

import com.envi.wispr.insertion.ClipboardOutcome
import com.envi.wispr.insertion.FallbackAnnouncement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The reconciliation between what a dictation's START saw and what INSERTION found.
 *
 * Product Outcome. When this fails the user dictates into an editor, the words land on the
 * clipboard, and nothing on the phone says so.
 *
 * The matrix is enumerated from `values()` on both enums rather than from a list of interesting
 * pairs, so a new member of either is a red row here instead of a silent hole
 * (`workflow-process.md` RULE: enumerate-from-the-producer-not-from-the-findings).
 */
class InsertionJudgementTest {

    @Test
    fun `every handoff other than NO_PINNED_TARGET is returned unchanged`() {
        for (pin in DictationTargetPin.values()) {
            for (handoff in InsertionHandoff.values()) {
                if (handoff == InsertionHandoff.NO_PINNED_TARGET) continue
                assertEquals(
                    "$pin at start must not rewrite $handoff",
                    handoff,
                    InsertionJudgement.handoffToJudge(pin, handoff),
                )
            }
        }
    }

    @Test
    fun `a live service with nothing to pin stays the designed clipboard case`() {
        assertEquals(
            InsertionHandoff.NO_PINNED_TARGET,
            InsertionJudgement.handoffToJudge(
                DictationTargetPin.NO_TARGET,
                InsertionHandoff.NO_PINNED_TARGET,
            ),
        )
    }

    @Test
    fun `a service that was dead at the start and rebound before insertion is still a fault`() {
        assertEquals(
            InsertionHandoff.SERVICE_NOT_RUNNING,
            InsertionJudgement.handoffToJudge(
                DictationTargetPin.SERVICE_NOT_RUNNING,
                InsertionHandoff.NO_PINNED_TARGET,
            ),
        )
    }

    @Test
    fun `a target pinned at the start and gone by insertion is a fault`() {
        assertEquals(
            InsertionHandoff.SERVICE_NOT_RUNNING,
            InsertionJudgement.handoffToJudge(
                DictationTargetPin.PINNED,
                InsertionHandoff.NO_PINNED_TARGET,
            ),
        )
    }

    @Test
    fun `a start the service never answered keeps its own diagnosis`() {
        assertEquals(
            InsertionHandoff.SERVICE_DID_NOT_ANSWER,
            InsertionJudgement.handoffToJudge(
                DictationTargetPin.SERVICE_DID_NOT_ANSWER,
                InsertionHandoff.NO_PINNED_TARGET,
            ),
        )
    }

    @Test
    fun `exactly one start state leaves a missing pin unannounced`() {
        val unannounced = DictationTargetPin.values().filter { pin ->
            InsertionJudgement.handoffToJudge(pin, InsertionHandoff.NO_PINNED_TARGET) ==
                InsertionHandoff.NO_PINNED_TARGET
        }
        assertEquals(listOf(DictationTargetPin.NO_TARGET), unannounced)
    }

    /**
     * The outcome the user actually gets, not just the value the rule returns. A judgement that is
     * right and never reaches a surface is the defect this whole change exists to end.
     */
    @Test
    fun `the reclassified outcome reaches the user and the designed one does not`() {
        val spokenTo = FallbackAnnouncement.fallbackAnnouncement(
            autoPaste = AutoPasteAvailability.LIVE,
            handoff = InsertionJudgement.handoffToJudge(
                DictationTargetPin.SERVICE_NOT_RUNNING,
                InsertionHandoff.NO_PINNED_TARGET,
            ),
            clipboard = ClipboardOutcome.COPIED,
            savedInHistory = true,
        )
        assertNotNull("A dead service at the start must still be announced", spokenTo)

        val silent = FallbackAnnouncement.fallbackAnnouncement(
            autoPaste = AutoPasteAvailability.LIVE,
            handoff = InsertionJudgement.handoffToJudge(
                DictationTargetPin.NO_TARGET,
                InsertionHandoff.NO_PINNED_TARGET,
            ),
            clipboard = ClipboardOutcome.COPIED,
            savedInHistory = true,
        )
        assertNull("An ordinary tile dictation must not be announced as a failure", silent)
    }
}
