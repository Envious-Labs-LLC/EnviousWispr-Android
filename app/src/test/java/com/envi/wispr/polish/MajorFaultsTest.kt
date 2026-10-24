package com.envi.wispr.polish

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Observability Contract (#465). When this fails, the polish load line reports the wrong major-fault count, and a slow
 * reload cannot be told apart as page-cache eviction or something else. Inputs are `/proc/self/stat` lines; the
 * expected values are the literal 12th fields.
 */
class MajorFaultsTest {
    @Test fun theTwelfthFieldIsRead() {
        // pid (comm) state ppid pgrp session tty tpgid flags minflt cminflt majflt ...
        val stat = "14703 (com.envi.wispr:polish) S 653 653 0 0 -1 1077952832 41250 0 18342 0 920 311 0 0 10 -10 52 0"
        assertEquals(18342L, MajorFaults.parse(stat))
    }

    @Test fun aNameWithSpacesAndParenthesesDoesNotShiftTheFields() {
        val stat = "99 (odd ) name (x)) R 1 1 0 0 -1 0 7 0 5 0 0 0"
        assertEquals(5L, MajorFaults.parse(stat))
    }

    @Test fun anUnreadableLineIsUnknownNeverZero() {
        assertNull(MajorFaults.parse(""))
        assertNull(MajorFaults.parse("14703 com.envi.wispr S 1 2 3"))
        assertNull(MajorFaults.parse("1 (x) S 1 1"))
        assertNull(MajorFaults.parse("1 (x) S 1 1 0 0 -1 0 7 0 notanumber 0"))
    }
}
