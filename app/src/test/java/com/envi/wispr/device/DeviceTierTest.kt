package com.envi.wispr.device

import com.envi.wispr.ui.DictationSessionService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Product Outcome (#449 chunk 2): when this fails, a phone measured at 7 to 8 s per sentence is offered setup and a
 * 0.5 GB download, or a phone that works is shut out. The `CPU part` lists are the ones Test Lab logged for each phone
 * (2026-10-19/20); the implementers are not in those logs and are written here as each core's designer (Arm 0x41,
 * Qualcomm 0x51 for Kryo-numbered parts).
 * REVERT: make `classify` ignore `OLD_CORES` (A05s and Moto g04 rows fail) or return BLOCKED always (A15 rows fail).
 */
class DeviceTierTest {
    private fun cpuinfo(vararg cores: Pair<String, String>): String = cores.withIndex().joinToString("\n\n") { (i, core) ->
        "processor\t: $i\nBogoMIPS\t: 38.40\nCPU implementer\t: ${core.first}\nCPU architecture: 8\nCPU variant\t: 0x0\nCPU part\t: ${core.second}\nCPU revision\t: 4"
    } + "\n\nHardware\t: test\n"

    private fun eight(little: Pair<String, String>, big: Pair<String, String>, bigCount: Int = 2) =
        cpuinfo(*Array(8 - bigCount) { little }, *Array(bigCount) { big })

    private val a55 = "0x41" to "0xd05"

    @Test fun theA23ChipIsBlocked() =
        assertEquals(DeviceTier.BLOCKED, DeviceTier.classify("0-7", eight("0x51" to "0x801", "0x41" to "0xd09", 4), hasI8mm = false))

    @Test fun theUnisocT606IsBlocked() =
        assertEquals(DeviceTier.BLOCKED, DeviceTier.classify("0-7", eight(a55, "0x41" to "0xd0a"), hasI8mm = false))

    @Test fun theHelioG99IsMidTier() =
        assertEquals(DeviceTier.MID, DeviceTier.classify("0-7", eight(a55, "0x41" to "0xd0b"), hasI8mm = false))

    @Test fun theDimensity1080AndExynos1380AreMidTier() =
        assertEquals(DeviceTier.MID, DeviceTier.classify("0-7", eight(a55, "0x41" to "0xd41"), hasI8mm = false))

    @Test fun anArmv9PhoneIsStandard() = assertEquals(
        DeviceTier.STANDARD,
        DeviceTier.classify("0-8", cpuinfo(*Array(4) { "0x41" to "0xd46" }, *Array(4) { "0x41" to "0xd4d" }, "0x41" to "0xd4e"), hasI8mm = true),
    )

    @Test fun aKryoA76ClassGoldCoreIsNotOld() =
        assertEquals(DeviceTier.MID, DeviceTier.classify("0-7", eight("0x51" to "0x805", "0x51" to "0x804"), hasI8mm = false))

    @Test fun aSamsungCustomCoreIsNeverBlocked() =
        assertEquals(DeviceTier.MID, DeviceTier.classify("0-7", eight(a55, "0x53" to "0x004"), hasI8mm = false))

    @Test fun anOfflineCoreMissingFromCpuinfoNeverBlocks() {
        // Possible CPUs 0-7 but only the six little cores are online and listed.
        val partial = cpuinfo(*Array(6) { a55 })
        assertEquals(DeviceTier.MID, DeviceTier.classify("0-7", partial, hasI8mm = false))
    }

    @Test fun unreadableOrMalformedReportsNeverBlock() {
        val old = eight(a55, "0x41" to "0xd0a")
        assertEquals(DeviceTier.STANDARD, DeviceTier.classify(null, old, hasI8mm = null))
        assertEquals(DeviceTier.STANDARD, DeviceTier.classify("0-7", null, hasI8mm = null))
        assertEquals(DeviceTier.STANDARD, DeviceTier.classify("", old, hasI8mm = null))
        assertEquals(DeviceTier.STANDARD, DeviceTier.classify("0-x", old, hasI8mm = null))
        assertEquals(DeviceTier.STANDARD, DeviceTier.classify("0-7", old.replace("CPU part\t: 0xd0a", "CPU part\t: zz"), hasI8mm = null))
        assertEquals(DeviceTier.STANDARD, DeviceTier.classify("0-7", old.replace("processor\t: 7", "processor\t: 6"), hasI8mm = null))
    }

    @Test fun cpuListsParseRangesAndSingles() {
        assertEquals((0..7).toSet(), DeviceTier.parseCpuList("0-3,4-7\n"))
        assertEquals(setOf(0, 2), DeviceTier.parseCpuList("0,2"))
        assertNull(DeviceTier.parseCpuList("3-1"))
        assertNull(DeviceTier.parseCpuList("0,,1"))
    }

    @Test fun onlyAStartOrToggleOnABlockedPhoneIsRefused() {
        assertTrue(DictationSessionService.startRefused(DictationSessionService.ACTION_START, DeviceTier.BLOCKED))
        assertTrue(DictationSessionService.startRefused(DictationSessionService.ACTION_TOGGLE, DeviceTier.BLOCKED))
        assertFalse(DictationSessionService.startRefused(DictationSessionService.ACTION_STOP, DeviceTier.BLOCKED))
        assertFalse(DictationSessionService.startRefused(DictationSessionService.ACTION_CANCEL, DeviceTier.BLOCKED))
        assertFalse(DictationSessionService.startRefused(DictationSessionService.ACTION_START, DeviceTier.MID))
        assertFalse(DictationSessionService.startRefused(DictationSessionService.ACTION_START, DeviceTier.STANDARD))
    }
}
