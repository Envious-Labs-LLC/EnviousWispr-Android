package com.envi.wispr.models

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelManifestTest {
    @Test fun productionDescriptorsCarryPinnedVerifiedReceipts() {
        assertTrue(ModelManifest.parakeet.isAvailable)
        assertTrue(ModelManifest.s1.isAvailable)
        assertEquals("9d104194420cfe48c3374385bb42b42a788b9225", ModelManifest.parakeet.pinnedRevision)
        assertEquals("34add00a48a2e5d24e5a4ee5405a99620a3a240c", ModelManifest.s1.pinnedRevision)
    }

    /**
     * #374: the speech model is the SmoothQuant set in the onnx-asr layout, under its own storage id, from our host.
     * MUTATIONS: reuse the retired `parakeet` id (it would share the sherpa model's folder); drop a file.
     */
    @Test fun theSpeechModelIsTheSmoothQuantSetUnderItsOwnId() {
        val speech = ModelManifest.parakeet
        assertEquals("parakeet-sq", speech.id)
        assertEquals("Parakeet", speech.displayName)
        assertEquals(listOf("encoder-model.int8.onnx", "decoder_joint-model.int8.onnx", "vocab.txt"), speech.files.map { it.name })
        assertEquals(667_821_431L, speech.files.sumOf { it.expectedBytes })
        assertTrue(speech.files.all { it.sourceUrl.startsWith("https://models.enviouslabs.co/parakeet-sq/9d104194420cfe48c3374385bb42b42a788b9225/") })
        assertFalse(ModelManifest.all.any { it.id in LegacyModelSweep.LEGACY })
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
        assertFalse("an escaped ordinary character", ok(own.replace("parakeet-onnx", "para%6beet-onnx")))
        assertFalse("a trailing slash", ok("$own/"))
        assertFalse("a doubled slash", ok(own.replace("/$rev/", "//$rev/")))
    }

    @Test fun theRevisionIsAFullCommitHashAndTheLastSegmentIsTheFile() {
        assertFalse("a blank revision", ok(own.replace(rev, ""), revision = ""))
        assertFalse("a branch", ok(own.replace(rev, "main"), revision = "main"))
        assertFalse("a short hash", ok(own.replace(rev, "2bda32ec"), revision = "2bda32ec"))
        assertFalse("an uppercase hash", ok(own.replace(rev, rev.uppercase()), revision = rev.uppercase()))
        assertFalse("another file", ok(own, file = "decoder.int8.onnx"))
    }

    /** Every shipped file has our host first, Hugging Face second, and the pinned revision in both paths. */
    /**
     * Our host first for every file; a Hugging Face fallback wherever one is admissible. The speech model's encoder and
     * decoder sit in an `int8/` folder on Hugging Face, a shape `validateModelSource` rejects, so those two have our
     * host alone (#374). MUTATION: give them a Hugging Face fallback (the model then becomes unavailable).
     */
    @Test fun everyShippedFileHasOurHostFirstAndHuggingFaceAsTheFallback() {
        ModelManifest.all.forEach { model ->
            model.files.forEach { file ->
                assertTrue(file.name, file.sourceUrl.startsWith("https://models.enviouslabs.co/"))
                assertTrue(file.name, validateModelSource(file.sourceUrl, model.pinnedRevision, file.name))
                file.fallbackUrl?.let { fallback ->
                    assertTrue(file.name, fallback.startsWith("https://huggingface.co/"))
                    assertTrue(file.name, validateModelSource(fallback, model.pinnedRevision, file.name))
                }
            }
        }
        val withoutFallback = ModelManifest.all.flatMap { m -> m.files.filter { it.fallbackUrl == null }.map { "${m.id}/${it.name}" } }
        assertEquals(listOf("parakeet-sq/encoder-model.int8.onnx", "parakeet-sq/decoder_joint-model.int8.onnx"), withoutFallback)
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
