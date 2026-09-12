package com.envi.wispr.shortcuts

import com.envi.wispr.shortcuts.BubbleRequestLedger.CommandDecision
import com.envi.wispr.shortcuts.BubbleRequestLedger.StartDecision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * PRODUCT OUTCOME. When these fail, the user sees a short hold throw the words away, a hold that keeps
 * recording after the finger lifts, or a late tap ending a take it did not belong to.
 *
 * Every sequence here is one the issue #135 review rounds named. The ledger is driven with literal
 * tokens; the real one is minted per process.
 */
class BubbleRequestsTest {

    private class Ledger : BubbleRequestLedger("epoch")

    private fun token(seq: Long, epoch: String = "epoch") = BubbleRequestToken(epoch, seq)

    @Test
    fun aStopForTheAdmittedTokenAppliesToTheLiveTake() {
        val l = Ledger()
        assertEquals(StartDecision.Admitted(stopAfterRecording = false, cancelAtOnce = false), l.resolveStart(token(1), ownerIdle = true))
        assertEquals(CommandDecision.ApplyToAdmitted, l.resolveCommand(token(1), cancel = false, admittedSeq = 1))
    }

    @Test
    fun aStopArrivingBeforeItsStartMakesTheAdmittedTakeStopAfterRecording() {
        val l = Ledger()
        assertEquals(CommandDecision.Noted, l.resolveCommand(token(1), cancel = false, admittedSeq = null))
        assertEquals(StartDecision.Admitted(stopAfterRecording = true, cancelAtOnce = false), l.resolveStart(token(1), ownerIdle = true))
    }

    @Test
    fun aCancelNotedWinsOverAReleaseForTheSameSeq() {
        val l = Ledger()
        l.resolveCommand(token(1), cancel = false, admittedSeq = null)
        l.resolveCommand(token(1), cancel = true, admittedSeq = null)
        assertEquals(StartDecision.Admitted(stopAfterRecording = false, cancelAtOnce = true), l.resolveStart(token(1), ownerIdle = true))
    }

    @Test
    fun aDelayedStartAfterANewerAdmissionIsRefusedStale() {
        // STOP(A) noted, START(B) admitted, B finishes, delayed START(A): A must not record unreleased.
        val l = Ledger()
        assertEquals(CommandDecision.Noted, l.resolveCommand(token(1), cancel = false, admittedSeq = null))
        assertEquals(StartDecision.Admitted(false, false), l.resolveStart(token(2), ownerIdle = true))
        assertEquals(StartDecision.Stale, l.resolveStart(token(1), ownerIdle = true))
    }

    @Test
    fun aStartAtOrBelowTheMarkOrFromAnotherEpochIsStale() {
        val l = Ledger()
        l.resolveStart(token(3), ownerIdle = true)
        assertEquals(StartDecision.Stale, l.resolveStart(token(3), ownerIdle = true))
        assertEquals(StartDecision.Stale, l.resolveStart(token(2), ownerIdle = true))
        assertEquals(StartDecision.Stale, l.resolveStart(token(9, epoch = "dead"), ownerIdle = true))
    }

    @Test
    fun aStartAboveTheMarkWhileBusyIsRefusedAndRetired() {
        val l = Ledger()
        l.resolveStart(token(1), ownerIdle = true)
        assertEquals(StartDecision.RefusedBusy, l.resolveStart(token(2), ownerIdle = false))
        // A release for the refused start is ignored later, and the admitted take's own stop still applies.
        assertEquals(CommandDecision.Ignored, l.resolveCommand(token(2), cancel = false, admittedSeq = 1))
        assertEquals(CommandDecision.ApplyToAdmitted, l.resolveCommand(token(1), cancel = false, admittedSeq = 1))
    }

    @Test
    fun aStopAtOrBelowTheMarkThatIsNotTheAdmittedTokenIsIgnored() {
        val l = Ledger()
        l.resolveStart(token(1), ownerIdle = true)
        l.resolveStart(token(2), ownerIdle = true)
        assertEquals(CommandDecision.Ignored, l.resolveCommand(token(1), cancel = false, admittedSeq = 2))
    }

    @Test
    fun twoEarlyReleasesInAscendingOrderRetireTheLowerOne() {
        // STOP(A) then STOP(B) before either START: A is displaced and retired; B keeps its release.
        val l = Ledger()
        assertEquals(CommandDecision.Noted, l.resolveCommand(token(1), cancel = false, admittedSeq = null))
        assertEquals(CommandDecision.Noted, l.resolveCommand(token(2), cancel = false, admittedSeq = null))
        assertEquals(StartDecision.Stale, l.resolveStart(token(1), ownerIdle = true))
        assertEquals(StartDecision.Admitted(stopAfterRecording = true, cancelAtOnce = false), l.resolveStart(token(2), ownerIdle = true))
    }

    @Test
    fun twoEarlyReleasesInDescendingOrderRetireTheLowerOneToo() {
        // STOP(B) then STOP(A): A retires itself; B's note is kept, whichever START comes first.
        val l = Ledger()
        assertEquals(CommandDecision.Noted, l.resolveCommand(token(2), cancel = false, admittedSeq = null))
        assertEquals(CommandDecision.Ignored, l.resolveCommand(token(1), cancel = false, admittedSeq = null))
        assertEquals(StartDecision.Stale, l.resolveStart(token(1), ownerIdle = true))
        assertEquals(StartDecision.Admitted(stopAfterRecording = true, cancelAtOnce = false), l.resolveStart(token(2), ownerIdle = true))
    }

    @Test
    fun aStopOrCancelFromAnotherEpochTouchesNothing() {
        val l = Ledger()
        assertEquals(CommandDecision.Rejected, l.resolveCommand(token(1, epoch = "dead"), cancel = true, admittedSeq = null))
        // The same seq from this epoch then starts clean: no note was created.
        assertEquals(StartDecision.Admitted(false, false), l.resolveStart(token(1), ownerIdle = true))
    }

    @Test
    fun mintedTokensRiseAndRoundTripThroughTheirStringForm() {
        val l = Ledger()
        val a = l.mint()
        val b = l.mint()
        assertEquals(a.seq + 1, b.seq)
        assertEquals(a, BubbleRequestToken.parse(a.encode()))
        assertNull(BubbleRequestToken.parse(null))
        assertNull(BubbleRequestToken.parse("epoch:"))
        assertNull(BubbleRequestToken.parse("epoch:0"))
        assertNull(BubbleRequestToken.parse("nocolon"))
    }
}
