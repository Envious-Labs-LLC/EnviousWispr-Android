package com.envi.wispr.recovery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections
import java.util.Enumeration
import java.util.UUID

/** Product Outcome: incomplete key scans must retain positive evidence that prevents unsafe allocation. */
class RecoveryKeyAliasesTest {
    private val rogue = "enviouswispr.recovery.v1.unowned"

    @Test fun throwingEnumerationKeepsThePositivePrefixAndDiscardsItsPrivateFailure() {
        var position = 0
        val enumeration = object : Enumeration<String> {
            override fun hasMoreElements(): Boolean {
                if (position == 1) throw IllegalStateException("synthetic-query-canary")
                return true
            }
            override fun nextElement(): String { position++; return rogue }
        }
        val observation = RecoveryKeyAliases.observe(enumeration)
        assertEquals(RecoveryKeyAliases.Scan.FAILED, observation.scan)
        assertEquals(setOf(rogue), observation.names)
        assertFalse(observation.oversizedRecoveryAlias)
    }

    @Test fun cappedEnumerationRetainsEarlyAnomalyWithoutPullingTheExtraElement() {
        val input = listOf(rogue) + List(256) { "other.$it" }
        val observation = RecoveryKeyAliases.observe(Collections.enumeration(input))
        assertEquals(RecoveryKeyAliases.Scan.CAPPED, observation.scan)
        assertEquals(setOf(rogue), observation.names)
        assertThrows(UnsupportedOperationException::class.java) { (observation.names as MutableSet).clear() }
    }

    @Test fun anEnumerationErrorAlsoKeepsPositiveEvidence() {
        var delivered = false
        val input = object : Enumeration<String> {
            override fun hasMoreElements(): Boolean {
                if (delivered) throw AssertionError("synthetic-query-error-canary")
                return true
            }
            override fun nextElement(): String { delivered = true; return rogue }
        }
        val observation = RecoveryKeyAliases.observe(input)
        assertEquals(RecoveryKeyAliases.Scan.FAILED, observation.scan)
        assertEquals(setOf(rogue), observation.names)
    }

    @Test fun oversizedRecoveryNameRetainsAnAnomalyWithoutKeepingItsBody() {
        val observation = RecoveryKeyAliases.observe(Collections.enumeration(listOf("enviouswispr.recovery." + "x".repeat(10_000))))
        assertEquals(RecoveryKeyAliases.Scan.ENDED, observation.scan)
        assertTrue(observation.oversizedRecoveryAlias)
        assertEquals(emptySet<String>(), observation.names)
    }

    @Test fun canonicalOpaqueNameAndEmptyScanAreValuesNotProviderHealthProof() {
        val ref = RecoveryRecordRef(UUID(1L, 2L), 0, RecoveryRecordIdentity(UUID(3L, 4L), Long.MAX_VALUE, RecoveryContextDigest.of(ByteArray(32))))
        val name = RecoveryKeyAliases.of(ref)
        assertEquals("enviouswispr.recovery.v1.00000000-0000-0001-0000-000000000002.9223372036854775807.00000000-0000-0003-0000-000000000004", name)
        val known = RecoveryKeyAliases.observe(Collections.enumeration(listOf(name)))
        assertEquals(setOf(name), known.names)
        assertFalse(known.oversizedRecoveryAlias)
        val empty = RecoveryKeyAliases.observe(Collections.emptyEnumeration())
        assertEquals(RecoveryKeyAliases.Scan.ENDED, empty.scan)
        assertEquals(emptySet<String>(), empty.names)
        assertEquals(RecoveryKeyAliases.Scan.FAILED, RecoveryKeyAliases.failed().scan)
    }
}
