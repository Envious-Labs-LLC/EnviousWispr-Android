package com.envi.wispr.polish

import com.envi.wispr.processing.ProcessingBackend
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Product Outcome (#449): when this fails, a phone whose processor or vendor libraries cannot run GenieX loads it
 * anyway and its polish process dies (Galaxy A05s and A34 crashed, A35 never loaded, Test Lab 2026-10-19), or a
 * phone that can run it loses the graphics route. Fixtures are the `/proc/cpuinfo` Features lines those phones
 * reported, turned into bits with the Linux arm64 HWCAP numbering written out here by hand.
 * REVERT: make `decide` return GENIEX unconditionally; the three older-phone rows fail.
 */
class S1RuntimeKindTest {
    // Linux arch/arm64 uapi hwcap.h numbering, independent of the production constants.
    private val hwcapBit = mapOf(
        "fp" to 0, "asimd" to 1, "evtstrm" to 2, "aes" to 3, "pmull" to 4, "sha1" to 5, "sha2" to 6, "crc32" to 7,
        "atomics" to 8, "fphp" to 9, "asimdhp" to 10, "cpuid" to 11, "asimdrdm" to 12, "jscvt" to 13, "fcma" to 14,
        "lrcpc" to 15, "dcpop" to 16, "sha3" to 17, "sm3" to 18, "sm4" to 19, "asimddp" to 20, "sha512" to 21,
        "sve" to 22, "asimdfhm" to 23, "dit" to 24, "uscat" to 25, "ilrcpc" to 26, "flagm" to 27, "ssbs" to 28,
        "sb" to 29, "paca" to 30, "pacg" to 31,
    )
    private val hwcap2Bit = mapOf(
        "dcpodp" to 0, "sve2" to 1, "sveaes" to 2, "svepmull" to 3, "svebitperm" to 4, "svesha3" to 5, "svesm4" to 6,
        "flagm2" to 7, "frint" to 8, "svei8mm" to 9, "svef32mm" to 10, "svef64mm" to 11, "svebf16" to 12, "i8mm" to 13,
        "bf16" to 14, "dgh" to 15, "rng" to 16, "bti" to 17,
    )

    private fun features(line: String): CpuFeatures {
        val names = line.trim().split(Regex("\\s+"))
        var hwcap = 0L; var hwcap2 = 0L
        for (name in names) {
            hwcapBit[name]?.let { hwcap = hwcap or (1L shl it) }
            hwcap2Bit[name]?.let { hwcap2 = hwcap2 or (1L shl it) }
        }
        return CpuFeatures(hwcap, hwcap2)
    }

    private val a05s = "fp asimd evtstrm aes pmull sha1 sha2 crc32 cpuid"
    private val a34AndA35 = "fp asimd evtstrm aes pmull sha1 sha2 crc32 atomics fphp asimdhp cpuid asimdrdm lrcpc dcpop asimddp"
    private val pixel8 = "fp asimd evtstrm aes pmull sha1 sha2 crc32 atomics fphp asimdhp cpuid asimdrdm jscvt fcma lrcpc dcpop " +
        "sha3 sm3 sm4 asimddp sha512 sve asimdfhm dit uscat ilrcpc flagm ssbs sb paca pacg dcpodp sve2 sveaes svepmull " +
        "svebitperm svesha3 svesm4 flagm2 frint svei8mm svebf16 i8mm bti"

    @Test fun anArmv8Point0PhoneLikeTheA23RunsThePortableRuntime() =
        assertEquals(S1RuntimeKind.PORTABLE_CPU, S1RuntimeKind.decide(features(a05s)) { true })

    @Test fun anArmv8Point2PhoneWithOpenClStillRunsThePortableRuntime() =
        assertEquals(S1RuntimeKind.PORTABLE_CPU, S1RuntimeKind.decide(features(a34AndA35)) { true })

    @Test fun aCapableProcessorWithoutAppLoadableOpenClRunsThePortableRuntime() =
        assertEquals(S1RuntimeKind.PORTABLE_CPU, S1RuntimeKind.decide(features(pixel8)) { false })

    @Test fun aCapableProcessorWithOpenClKeepsGenieX() =
        assertEquals(S1RuntimeKind.GENIEX, S1RuntimeKind.decide(features(pixel8)) { true })

    @Test fun eachRequiredFeatureAloneMissingFallsBackToPortable() {
        for (missing in listOf("atomics", "fphp", "asimdhp", "fcma", "lrcpc", "asimddp", "ilrcpc", "i8mm")) {
            val reduced = pixel8.split(" ").filter { it != missing }.joinToString(" ")
            assertEquals(missing, S1RuntimeKind.PORTABLE_CPU, S1RuntimeKind.decide(features(reduced)) { true })
        }
    }

    @Test fun unknownFeaturesFallBackToPortableWithoutProbingOpenCl() {
        var probed = false
        assertEquals(S1RuntimeKind.PORTABLE_CPU, S1RuntimeKind.decide(null) { probed = true; true })
        assertFalse(probed)
    }

    @Test fun thePortableRuntimeOffersOnlyTheProcessorAndADistinctIdentity() {
        assertEquals(setOf(ProcessingBackend.CPU), S1RuntimeKind.PORTABLE_CPU.backends)
        assertEquals(setOf(ProcessingBackend.GPU, ProcessingBackend.CPU), S1RuntimeKind.GENIEX.backends)
        // The GenieX identity is the literal earlier builds stored, so existing qualifications on those phones carry over.
        assertEquals("geniex-0.4.0-llama_cpp", S1RuntimeKind.GENIEX.identity)
        assertFalse(S1RuntimeKind.PORTABLE_CPU.identity == S1RuntimeKind.GENIEX.identity)
    }

    /** Drift Guard: a GenieX upgrade must re-run the instruction census before these masks are trusted. */
    @Test fun theMasksAreRecordedAgainstTheGenieXVersionGradlePackages() {
        val gradle = File("build.gradle.kts").readText()
        val version = Regex("com\\.qualcomm\\.qti:geniex-android:([0-9.]+)").find(gradle)!!.groupValues[1]
        assertEquals("GenieX moved; re-run the #449 census and update GENIEX_VERSION and the masks", GENIEX_VERSION, version)
    }

    @Test fun theAuxiliaryVectorParserReadsBothWordsAndStopsAtTheEnd() {
        val bytes = ByteBuffer.allocate(16 * 4).order(ByteOrder.LITTLE_ENDIAN)
            .putLong(6).putLong(4096) // AT_PAGESZ
            .putLong(16).putLong(0x0dL) // AT_HWCAP
            .putLong(26).putLong(0x2000L) // AT_HWCAP2
            .putLong(0).putLong(0).array()
        assertEquals(CpuFeatures(0x0dL, 0x2000L), CpuFeatures.parse(bytes))
    }

    @Test fun anAuxiliaryVectorWithoutHwcapOrWithATornLengthIsUnknown() {
        val noHwcap = ByteBuffer.allocate(32).order(ByteOrder.LITTLE_ENDIAN).putLong(6).putLong(4096).putLong(0).putLong(0).array()
        assertNull(CpuFeatures.parse(noHwcap))
        assertNull(CpuFeatures.parse(ByteArray(15)))
    }
}
