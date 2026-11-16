package com.envi.wispr.polish

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Product Outcome (decision level) for #531's per-phone thread count: when it fails, an S26 user waits about 1.9 s for
 * EG-1 instead of about 0.6 s, or a mid-range phone runs 25-40% slower and its long takes hit the 10 s timeout.
 * Core layouts are the ones read on the phones on 2026-11-15.
 */
class MnnCpuThreadsTest {
    // Galaxy S26: cores 0-5 top out at 3,628,800 kHz, cores 6-7 at 4,742,400 kHz.
    private val s26 = (0..5).associateWith { 3_628_800L } + (6..7).associateWith { 4_742_400L }

    @Test fun theS26OutsideItsFastestCoresGetsOneThread() {
        assertEquals("foreground cpuset during a take", MnnCpuThreads.FEW, MnnCpuThreads.choose(setOf(0, 1, 2, 3, 4, 5), s26))
        assertEquals("background cpuset", MnnCpuThreads.FEW, MnnCpuThreads.choose(setOf(0, 1, 4, 5), s26))
        assertEquals("one of the two fastest cores missing", MnnCpuThreads.FEW, MnnCpuThreads.choose((0..6).toSet(), s26))
    }

    @Test fun aProcessThatCanUseTheFastestCoresGetsFourThreads() {
        assertEquals("S26 with every core", MnnCpuThreads.MANY, MnnCpuThreads.choose((0..7).toSet(), s26))
        // A mid-range layout: four slower and four faster cores, all allowed.
        val midRange = (0..3).associateWith { 2_000_000L } + (4..7).associateWith { 2_400_000L }
        assertEquals(MnnCpuThreads.MANY, MnnCpuThreads.choose((0..7).toSet(), midRange))
        assertEquals("only the slow cores missing", MnnCpuThreads.MANY, MnnCpuThreads.choose((4..7).toSet(), midRange))
    }

    @Test fun anUnreadableFactKeepsTheEarlierFourThreads() {
        assertEquals("no allowed set", MnnCpuThreads.MANY, MnnCpuThreads.choose(null, s26))
        assertEquals("empty allowed set", MnnCpuThreads.MANY, MnnCpuThreads.choose(emptySet(), s26))
        assertEquals("no frequencies", MnnCpuThreads.MANY, MnnCpuThreads.choose(setOf(0, 1), emptyMap()))
    }

    @Test fun theLoadLogLineNamesWhatWasRead() {
        assertEquals("MNN threads=1 allowed=[0, 1, 2, 3, 4, 5] fastest=[6, 7]",
            MnnCpuThreads.Decision(1, (0..5).toSet(), s26).logLine())
        assertEquals("MNN threads=4 allowed=unread fastest=unread", MnnCpuThreads.Decision(4, null, emptyMap()).logLine())
    }

    @Test fun theKernelCpuListParsesAndRefusesMalformedText() {
        assertEquals(setOf(0, 1, 4, 5), MnnCpuThreads.parseCpuList("0-1,4-5"))
        assertEquals(setOf(0, 1, 2, 3, 4, 5), MnnCpuThreads.parseCpuList("0-5"))
        assertEquals(setOf(7), MnnCpuThreads.parseCpuList("7"))
        assertNull(MnnCpuThreads.parseCpuList(""))
        assertNull(MnnCpuThreads.parseCpuList("0-"))
        assertNull(MnnCpuThreads.parseCpuList("5-2"))
        assertNull(MnnCpuThreads.parseCpuList("a,b"))
        assertNull(MnnCpuThreads.parseCpuList("0-1-2"))
    }
}
