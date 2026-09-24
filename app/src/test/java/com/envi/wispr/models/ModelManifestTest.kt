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

    private val rev = "2bda32ec70b097a55adaa07d9a7173915b43cc78"
    private val own = "https://models.enviouslabs.co/parakeet-onnx/$rev/encoder.int8.onnx"
    private val hf = "https://huggingface.co/csukuangfj/parakeet/resolve/$rev/encoder.int8.onnx?download=true"
    private fun ok(url: String, revision: String = rev, file: String = "encoder.int8.onnx") = validateModelSource(url, revision, file)

    @Test fun sourceRequiresHttps() {
        assertTrue(ok(own))
        assertFalse(ok(own.replace("https://", "http://")))
        assertFalse(ok("https:///parakeet-onnx/$rev/encoder.int8.onnx"))
    }

    /** Exactly two hosts, both ways: our own bucket domain and Hugging Face, nothing that looks like either (#168). */
    @Test fun sourceAcceptsExactlyTheTwoHosts() {
        assertTrue(ok(own))
        assertTrue(ok(hf))
        assertTrue("Hugging Face with no query", ok(hf.removeSuffix("?download=true")))
        assertFalse("a bare object at the root is not a revisioned path", ok("https://models.enviouslabs.co/encoder.int8.onnx"))
        assertFalse("a look-alike host", ok(own.replace("models.enviouslabs.co", "models.enviouslabs.co.evil.example")))
        assertFalse("a look-alike host", ok(own.replace("models.enviouslabs.co", "evil-models.enviouslabs.co")))
        assertFalse("Hugging Face without the resolve path", ok(hf.replace("/resolve/", "/blob/")))
        assertFalse("user info", ok(own.replace("https://", "https://a@")))
        assertFalse("a port", ok(own.replace("enviouslabs.co/", "enviouslabs.co:8443/")))
    }

    /**
     * Product Outcome (#284): the pinned revision is one whole path segment in its host's place, never a substring,
     * and the revision itself is a full commit hash. MUTATIONS: substring containment; any revision shape; no file check.
     */
    @Test fun theRevisionIsOneWholeSegmentInItsPlace() {
        assertFalse("the revision inside a longer segment", ok("https://models.enviouslabs.co/parakeet-onnx/${rev}x/encoder.int8.onnx"))
        assertFalse("the revision in the wrong place", ok("https://models.enviouslabs.co/$rev/parakeet-onnx/encoder.int8.onnx"))
        assertFalse("an extra segment", ok("https://models.enviouslabs.co/parakeet-onnx/$rev/x/encoder.int8.onnx"))
        assertFalse("Hugging Face revision in the repo place", ok("https://huggingface.co/csukuangfj/$rev/resolve/main/encoder.int8.onnx"))
        assertFalse("an escaped separator", ok("https://models.enviouslabs.co/parakeet-onnx/$rev%2Fx/encoder.int8.onnx"))
        assertFalse("a dot segment", ok("https://models.enviouslabs.co/parakeet-onnx/../$rev/encoder.int8.onnx"))
        assertFalse("a query on our host", ok("$own?v=1"))
        assertFalse("another query on Hugging Face", ok(hf.replace("download=true", "download=true&x=1")))
        assertFalse("a fragment", ok("$own#x"))
    }

    @Test fun theRevisionIsAFullCommitHashAndTheLastSegmentIsTheFile() {
        assertFalse("a blank revision", ok(own.replace(rev, ""), revision = ""))
        assertFalse("a branch", ok(own.replace(rev, "main"), revision = "main"))
        assertFalse("a short hash", ok(own.replace(rev, "2bda32ec"), revision = "2bda32ec"))
        assertFalse("an uppercase hash", ok(own.replace(rev, rev.uppercase()), revision = rev.uppercase()))
        assertFalse("another file", ok(own, file = "decoder.int8.onnx"))
    }

    /** Every shipped file has our host first, Hugging Face second, and the pinned revision in both paths. */
    @Test fun everyShippedFileHasOurHostFirstAndHuggingFaceAsTheFallback() {
        ModelManifest.all.forEach { model ->
            model.files.forEach { file ->
                assertTrue(file.name, file.sourceUrl.startsWith("https://models.enviouslabs.co/"))
                assertTrue(file.name, file.fallbackUrl!!.startsWith("https://huggingface.co/"))
                assertTrue(file.name, validateModelSource(file.sourceUrl, model.pinnedRevision, file.name))
                assertTrue(file.name, validateModelSource(file.fallbackUrl!!, model.pinnedRevision, file.name))
            }
        }
    }

    /** #284: a source that carries the revision in the wrong place, or a revision that is not a full hash, makes the model unavailable. */
    @Test fun aRevisionOutOfPlaceOrOfTheWrongShapeMakesTheModelUnavailable() {
        val good = ModelManifest.s1
        val misplaced = good.copy(files = good.files.map { it.copy(sourceUrl = "https://models.enviouslabs.co/${good.pinnedRevision}/s1/${it.name}") })
        assertFalse(misplaced.isAvailable)
        val short = good.pinnedRevision.take(8)
        val shortRevision = good.copy(
            pinnedRevision = short,
            files = good.files.map { it.copy(sourceUrl = it.sourceUrl.replace(good.pinnedRevision, short), fallbackUrl = it.fallbackUrl!!.replace(good.pinnedRevision, short)) },
        )
        assertFalse(shortRevision.isAvailable)
    }

    @Test fun aFallbackWithoutThePinnedRevisionMakesTheModelUnavailable() {
        val good = ModelManifest.s1
        val bad = good.copy(files = good.files.map { it.copy(fallbackUrl = "https://huggingface.co/x/y/resolve/other/f") })
        assertTrue(good.isAvailable)
        assertFalse(bad.isAvailable)
    }
}
