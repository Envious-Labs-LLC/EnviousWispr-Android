package com.envi.wispr.privacy

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The Privacy page's offline promise (#308), literally: audio never leaves the phone, and the words stay unless the
 * user connects their own provider. It replaces the deleted per-provider table's OFFLINE row and binds the Play Data
 * Safety answer about audio. MUTATION m7.
 */
class PrivacyPageSentencesTest {
    @Test fun theOnDeviceSentenceIsTodays() {
        assertEquals(
            "Your voice stays on this phone. Audio never leaves it. Your words stay here too, unless you " +
                "connect your own AI provider for cloud polish; then the selected text goes straight to that provider under your key.",
            PrivacyDisclosures.ON_DEVICE_SUMMARY,
        )
    }
}
