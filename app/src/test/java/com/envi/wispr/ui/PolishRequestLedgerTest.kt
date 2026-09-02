package com.envi.wispr.ui

import com.envi.wispr.polish.PolishRequestIdSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Product Outcome: when this fails, a late polish result from an abandoned dictation is inserted
 * over the current one, or a cancelled dictation's text is published anyway.
 */
class PolishRequestLedgerTest {

    private fun ledger(clock: () -> Long = { 1_000L }) = PolishRequestLedger(PolishRequestIdSource(clock))

    @Test fun acceptsOnlyTheOpenRequestAndOnlyOnce() {
        val ledger = ledger()
        val open = ledger.open()
        assertFalse("a stale id must be refused", ledger.claim(open + 1))
        assertTrue(ledger.claim(open))
        assertFalse("the same id must not be accepted twice", ledger.claim(open))
    }

    @Test fun openingAgainRetiresThePreviousRequest() {
        val ledger = ledger()
        val first = ledger.open()
        val second = ledger.open()
        assertNotEquals(first, second)
        assertFalse(ledger.claim(first))
        assertTrue(ledger.claim(second))
    }

    @Test fun closeReturnsTheOpenIdAndAnOutcomeArrivingAfterwardsIsRefused() {
        val ledger = ledger()
        val open = ledger.open()
        assertEquals(open, ledger.close())
        assertFalse("close won, so the outcome loses", ledger.claim(open))
        assertNull("nothing left to cancel", ledger.close())
    }

    @Test fun anOutcomeAcceptedFirstLeavesNothingForCloseToCancel() {
        val ledger = ledger()
        val open = ledger.open()
        assertTrue(ledger.claim(open))
        assertNull(ledger.close())
    }

    @Test fun twoLedgersOnOneSourceGivenTheSameClockReadingReceiveDistinctIds() {
        // Two session-owner instances can share one engine process and one clock tick; equal ids
        // would share one cancellation token in the engine's request map.
        val source = PolishRequestIdSource { 5_000L }
        val a = PolishRequestLedger(source).open()
        val b = PolishRequestLedger(source).open()
        assertNotEquals(a, b)
        assertTrue("ids keep increasing", b > a)
    }

    @Test fun idsNeverGoBackwardsWhenTheClockDoes() {
        var now = 9_000L
        val source = PolishRequestIdSource { now }
        val first = source.next()
        now = 100L
        val second = source.next()
        assertTrue("a clock that jumps back must not reuse an earlier id", second > first)
    }
}
