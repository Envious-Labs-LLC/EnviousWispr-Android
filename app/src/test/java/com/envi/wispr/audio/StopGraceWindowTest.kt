package com.envi.wispr.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Behaviour of the stop grace window (#419), on a fake clock: the values, not the wiring. */
class StopGraceWindowTest {

    @Test
    fun aWindowThatWasNeverArmedIsClosedAndNotArmed() {
        val window = StopGraceWindow()
        assertFalse(window.isOpen(0L))
        assertFalse(window.isOpen(1_000_000L))
        assertFalse(window.wasArmed)
    }

    @Test
    fun theWindowIsOpenUntilTheDeadlineAndClosedFromIt() {
        val window = StopGraceWindow()
        window.arm(nowMs = 10_000L, graceMs = 250L)
        assertTrue(window.wasArmed)
        assertTrue(window.isOpen(10_000L))
        assertTrue(window.isOpen(10_249L))
        assertFalse("closed exactly at the deadline", window.isOpen(10_250L))
        assertFalse(window.isOpen(20_000L))
        assertTrue("armed stays true after it closes: the take ended by a stop request", window.wasArmed)
    }

    @Test
    fun aZeroGraceNeverArms() {
        val window = StopGraceWindow()
        window.arm(nowMs = 10_000L, graceMs = 0L)
        assertFalse(window.wasArmed)
        assertFalse(window.isOpen(10_000L))
    }

    @Test
    fun aSecondArmKeepsTheFirstDeadline() {
        val window = StopGraceWindow()
        window.arm(nowMs = 10_000L, graceMs = 250L)
        window.arm(nowMs = 10_200L, graceMs = 250L)
        assertFalse("a repeated stop must not extend the window", window.isOpen(10_250L))
    }

    @Test
    fun cancelClosesAnOpenWindowAndALaterArmCannotReopenIt() {
        val window = StopGraceWindow()
        window.arm(nowMs = 10_000L, graceMs = 250L)
        window.cancel()
        assertFalse(window.isOpen(10_001L))
        window.arm(nowMs = 10_002L, graceMs = 250L)
        assertFalse(window.isOpen(10_003L))
        assertEquals(true, window.wasArmed)
    }
}
