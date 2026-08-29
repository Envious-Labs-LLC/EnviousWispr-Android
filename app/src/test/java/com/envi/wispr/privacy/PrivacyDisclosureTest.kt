package com.envi.wispr.privacy

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PrivacyDisclosureTest {
    @Test fun offlineDisclosurePromisesNoTextEgress() {
        val disclosure = PrivacyDisclosures.forProvider(PolishProvider.OFFLINE)
        assertFalse(disclosure.sendsText)
        assertTrue(disclosure.summary.contains("stay on this phone"))
    }

    @Test fun cloudDisclosureNamesUserChoiceBoundary() {
        assertTrue(PrivacyDisclosures.forProvider(PolishProvider.OPENAI).sendsText)
        assertTrue(PrivacyDisclosures.forProvider(PolishProvider.SELF_HOSTED).summary.contains("configured endpoint"))
    }
}
