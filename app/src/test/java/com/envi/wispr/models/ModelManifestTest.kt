package com.envi.wispr.models

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelManifestTest {
    @Test fun productionDescriptorsCarryPinnedVerifiedReceipts() {
        assertTrue(ModelManifest.parakeet.isAvailable)
        assertTrue(ModelManifest.s1.isAvailable)
        assertEquals("2bda32ec70b097a55adaa07d9a7173915b43cc78", ModelManifest.parakeet.pinnedRevision)
        assertEquals("34add00a48a2e5d24e5a4ee5405a99620a3a240c", ModelManifest.s1.pinnedRevision)
    }

    @Test fun sourceRequiresHttps() {
        assertTrue(validateModelSource("https://huggingface.co/test/model/resolve/r1/model"))
        assertFalse(validateModelSource("http://models.example/model"))
        assertFalse(validateModelSource("https:///model"))
    }
}
