package com.envi.wispr.models

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelManifestTest {
    /**
     * Drift guard (#108): every previous revision a model keeps loadable is the same model at a different pin, with
     * the same file names the engine opens, itself pinned and verifiable, and no deeper chain.
     * REVERT: list a previous revision with a different id, a renamed file, or the current revision.
     */
    @Test fun previousRevisionsAreTheSameModelAtAnotherPin() {
        ModelManifest.all.forEach { model ->
            model.previous.forEach { old ->
                assertEquals("${model.id}: id", model.id, old.id)
                assertEquals("${model.id}: engine", model.engineId, old.engineId)
                assertEquals("${model.id}: file names", model.files.map { it.name }.toSet(), old.files.map { it.name }.toSet())
                assertFalse("${model.id}: a previous revision is not the current one", old.pinnedRevision == model.pinnedRevision)
                assertTrue("${model.id}: pinned and verifiable", old.isAvailable)
                assertTrue("${model.id}: no nesting", old.previous.isEmpty())
            }
        }
    }

    @Test fun productionDescriptorsCarryPinnedVerifiedReceipts() {
        assertTrue(ModelManifest.parakeet.isAvailable)
        assertTrue(ModelManifest.s1.isAvailable)
        assertEquals("3b80a92332b5d34c364a35a880ef049c7fede9f2", ModelManifest.parakeet.pinnedRevision)
        assertEquals("34add00a48a2e5d24e5a4ee5405a99620a3a240c", ModelManifest.s1.pinnedRevision)
    }

    /**
     * #458: the speech model is Parakeet Ultra in the onnx-asr layout, under its own storage id, from our host. The
     * pins are the files the endings gate measured (0 of 288 lost endings) and the founder approved.
     * MUTATIONS: reuse a retired id (`parakeet`, `parakeet-sq`: it would share a swept folder); change a byte count or
     * hash; drop a file.
     */
    @Test fun theSpeechModelIsParakeetUltraUnderItsOwnId() {
        val speech = ModelManifest.parakeet
        assertEquals("parakeet-ultra", speech.id)
        assertEquals("Parakeet Ultra", speech.displayName)
        assertEquals(listOf("encoder-model.int8.onnx", "decoder_joint-model.int8.onnx", "vocab.txt"), speech.files.map { it.name })
        assertEquals(listOf(649_537_325L, 18_203_490L, 93_939L), speech.files.map { it.expectedBytes })
        assertEquals(
            listOf(
                "8a2b47169cf3f1b114010e12c0221bc6f07203289477a65a44847b9e3f1e01ed",
                "667ffde2242a560923cba6bc7f69ef03d736f8a36ca9588f2e6f9fa818cf2fc1",
                "d58544679ea4bc6ac563d1f545eb7d474bd6cfa467f0a6e2c1dc1c7d37e3c35d",
            ),
            speech.files.map { it.sha256 },
        )
        assertTrue(speech.files.all { it.sourceUrl.startsWith("https://models.enviouslabs.co/parakeet-ultra/3b80a92332b5d34c364a35a880ef049c7fede9f2/") })
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
        assertEquals(listOf("parakeet-ultra/encoder-model.int8.onnx", "parakeet-ultra/decoder_joint-model.int8.onnx"), withoutFallback)
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
