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

    /** Exactly two hosts, both ways: our own bucket domain and Hugging Face, nothing that looks like either (#168). */
    @Test fun sourceAcceptsExactlyTheTwoHosts() {
        assertTrue(validateModelSource("https://models.enviouslabs.co/parakeet-onnx/2bda32ec/encoder.int8.onnx"))
        assertTrue(validateModelSource("https://models.enviouslabs.co/s1/34add00a/s1-mini-q4_k_m.gguf"))
        assertFalse("a bare object at the root is not a revisioned path", validateModelSource("https://models.enviouslabs.co/encoder.int8.onnx"))
        assertFalse("a look-alike host", validateModelSource("https://models.enviouslabs.co.evil.example/s1/r/x"))
        assertFalse("a look-alike host", validateModelSource("https://evil-models.enviouslabs.co/s1/r/x"))
        assertFalse("Hugging Face without the resolve path", validateModelSource("https://huggingface.co/test/model/blob/r1/model"))
        assertFalse("user info", validateModelSource("https://a@models.enviouslabs.co/s1/r/x"))
        assertFalse("a port", validateModelSource("https://models.enviouslabs.co:8443/s1/r/x"))
        assertFalse("plain http", validateModelSource("http://models.enviouslabs.co/s1/r/x"))
    }

    /** Every shipped file has our host first, Hugging Face second, and the pinned revision in both paths. */
    @Test fun everyShippedFileHasOurHostFirstAndHuggingFaceAsTheFallback() {
        ModelManifest.all.forEach { model ->
            model.files.forEach { file ->
                assertTrue(file.name, file.sourceUrl.startsWith("https://models.enviouslabs.co/"))
                assertTrue(file.name, file.fallbackUrl!!.startsWith("https://huggingface.co/"))
                assertTrue(file.name, file.sourceUrl.contains("/${model.pinnedRevision}/${file.name}"))
                assertTrue(file.name, file.fallbackUrl!!.contains("/resolve/${model.pinnedRevision}/${file.name}"))
            }
        }
    }

    @Test fun aFallbackWithoutThePinnedRevisionMakesTheModelUnavailable() {
        val good = ModelManifest.s1
        val bad = good.copy(files = good.files.map { it.copy(fallbackUrl = "https://huggingface.co/x/y/resolve/other/f") })
        assertTrue(good.isAvailable)
        assertFalse(bad.isAvailable)
    }
}
