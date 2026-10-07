package com.envi.wispr.processing

import com.envi.wispr.polish.PolishReason
import org.junit.Assert.*
import org.junit.Test

/** Product Outcome: requested hardware and older successes must never rename the latest dictation. */
class ProcessingObservationTest {
    private val usage = ProcessingUsage(ProcessingBackend.CPU, "a".repeat(64), ProcessingPreference.DEFAULT.retry(), true, "gpu", artifactStamp = "receipt")
    private fun observation(reason: PolishReason = PolishReason.POLISHED, context: String = "local", local: ProcessingUsage? = usage) =
        ProcessingObservation("take|with delimiters", 12, 100000, context, reason, ProcessingPreference.DEFAULT, local, 500)
    @Test fun structuredRecordRoundTripsIdentityAndFallbackWithoutParsingEngineLabels() {
        val before = observation()
        assertEquals(before, ProcessingObservation.decode(before.encode()))
        assertTrue(before.displayLine().contains("used CPU after the preferred option failed"))
        assertFalse(before.displayLine().contains("used GPU"))
    }
    @Test fun rejectedLocalTextDoesNotClaimAUsedLocalResult() {
        val rejected = observation(PolishReason.OUTPUT_REJECTED, local = usage.copy(acceptedLocalText = false))
        assertTrue(rejected.displayLine().contains("No local model result used"))
        assertFalse(rejected.displayLine().contains("used CPU"))
    }
    @Test fun offAndCloudReplaceThePreviousLocalFact() {
        assertTrue(observation(PolishReason.OFF, "off", null).displayLine().contains("AI polish off"))
        assertFalse(observation(PolishReason.POLISHED, "cloud:OPENAI", null).displayLine().contains("S1-mini"))
    }
}
