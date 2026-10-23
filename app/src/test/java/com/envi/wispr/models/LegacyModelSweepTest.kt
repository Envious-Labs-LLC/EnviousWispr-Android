package com.envi.wispr.models

import android.os.IBinder
import com.envi.wispr.asr.AsrService
import com.envi.wispr.asr.IAsrCallback
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Product outcome (#374, #458): when this fails, a replaced 670 MB speech model is left on the phone for good, or the
 * CURRENT model is deleted with it and dictation stops working.
 */
class LegacyModelSweepTest {
    @get:Rule val folder = TemporaryFolder()

    private fun write(path: String, bytes: Int) = File(folder.root, path).apply { parentFile!!.mkdirs(); writeBytes(ByteArray(bytes)) }

    /**
     * #458: every path the two retired ids can own goes, and the live models stay.
     * MUTATION: sweep the wrong name, drop a retired path, or sweep every folder (Ultra and the polish model go too).
     */
    @Test fun removesEveryRetiredPathAndOnlyThose() {
        write("parakeet/encoder.int8.onnx", 600)
        write(".parakeet.download/decoder.int8.onnx.part", 40)
        write("parakeet-sq/encoder-model.int8.onnx", 700)
        write("parakeet-sq/.verified-receipt", 10)
        write(".parakeet-sq.download/encoder-model.int8.onnx.part", 30)
        write(".parakeet-sq.old/vocab.txt", 3)
        write(".parakeet-sq.adopt/vocab.txt", 2)
        write(".parakeet-sq.download.quarantine-1760000000000/encoder-model.int8.onnx", 20)
        write(".model-controls/parakeet-sq.state", 6)
        write(".model-controls/.parakeet-sq.state.tmp", 6)
        write("parakeet-ultra/encoder-model.int8.onnx", 800)
        write(".parakeet-ultra.download/vocab.txt.part", 4)
        write(".model-controls/parakeet-ultra.state", 6)
        write("s1-mini/s1-mini-q4_k_m.gguf", 300)

        assertEquals(1417L, LegacyModelSweep.sweep(folder.root))
        listOf("parakeet", ".parakeet.download", "parakeet-sq", ".parakeet-sq.download", ".parakeet-sq.old", ".parakeet-sq.adopt",
            ".parakeet-sq.download.quarantine-1760000000000", ".model-controls/parakeet-sq.state", ".model-controls/.parakeet-sq.state.tmp",
        ).forEach { assertFalse(it, File(folder.root, it).exists()) }
        listOf("parakeet-ultra/encoder-model.int8.onnx", ".parakeet-ultra.download/vocab.txt.part", ".model-controls/parakeet-ultra.state",
            "s1-mini/s1-mini-q4_k_m.gguf",
        ).forEach { assertTrue(it, File(folder.root, it).exists()) }
    }

    @Test fun nothingToSweepIsANoOp() {
        write("parakeet-ultra/vocab.txt", 5)
        assertEquals(0L, LegacyModelSweep.sweep(folder.root))
        assertTrue(File(folder.root, "parakeet-ultra/vocab.txt").exists())
    }

    /** A retired path must never be one a model in use can own, or the sweep would delete a live model. */
    @Test fun theRetiredPathsBelongToNoModelInUse() {
        ModelManifest.all.forEach { model ->
            LegacyModelSweep.LEGACY.forEach { path ->
                val name = path.substringAfterLast('/')
                assertFalse("$path vs ${model.id}", name == model.id || name.startsWith(".${model.id}.") || name == "${model.id}.state")
            }
            assertFalse(LegacyModelSweep.QUARANTINE_PREFIX.startsWith(".${model.id}."))
        }
    }

    /** MUTATION: follow a link standing in a retired folder's place (the live model it points at is deleted). */
    @Test fun aLinkInTheRetiredPlaceIsRemovedWithoutTouchingItsTarget() {
        write("parakeet-ultra/encoder-model.int8.onnx", 800)
        Files.createSymbolicLink(File(folder.root, "parakeet-sq").toPath(), File(folder.root, "parakeet-ultra").toPath())
        LegacyModelSweep.sweep(folder.root)
        assertFalse(Files.exists(File(folder.root, "parakeet-sq").toPath(), LinkOption.NOFOLLOW_LINKS))
        assertTrue(File(folder.root, "parakeet-ultra/encoder-model.int8.onnx").exists())
    }

    /**
     * #461: a link NESTED inside a retired folder goes as a link, and the folder goes with it. Before, the walk
     * skipped the link, so the folder could never be emptied and stayed.
     * MUTATION: follow the link (the live model it points at is emptied), or skip it (the folder stays).
     */
    @Test fun aLinkInsideARetiredFolderIsRemovedWithoutTouchingItsTarget() {
        write("parakeet-ultra/encoder-model.int8.onnx", 800)
        write("parakeet-sq/encoder-model.int8.onnx", 700)
        Files.createSymbolicLink(File(folder.root, "parakeet-sq/nested").toPath(), File(folder.root, "parakeet-ultra").toPath())

        assertEquals(700L, LegacyModelSweep.sweep(folder.root))
        assertFalse(Files.exists(File(folder.root, "parakeet-sq").toPath(), LinkOption.NOFOLLOW_LINKS))
        assertTrue(File(folder.root, "parakeet-ultra/encoder-model.int8.onnx").exists())
    }

    private class Callback(val throws: Boolean) : IAsrCallback {
        val results = ArrayList<String>()
        override fun onResult(text: String) { if (throws) throw IllegalStateException("binder died"); results += text }
        override fun onError(message: String) = Unit
        override fun onFailure(reason: Int, detail: String) = Unit
        override fun asBinder(): IBinder? = null
    }

    /** MUTATION: sweep after a null or throwing callback (the old model goes for a result nobody received). */
    @Test fun theSweepFollowsOnlyADeliveredResult() {
        var swept = 0
        var threw = 0
        val ok = Callback(throws = false)
        AsrService.deliverResult(ok, "hello", onDelivered = { swept++ }) { threw++ }
        assertEquals(listOf("hello"), ok.results)
        assertEquals(1, swept)
        AsrService.deliverResult(Callback(throws = true), "hello", onDelivered = { swept++ }) { threw++ }
        AsrService.deliverResult(null, "hello", onDelivered = { swept++ }) { threw++ }
        assertEquals("no sweep after a throwing or missing callback", 1, swept)
        assertEquals(1, threw)
    }

    /** Harness contract on the source: the service routes its result through [AsrService.deliverResult] with the sweep. */
    @Test fun theServiceSweepsThroughTheDeliveryRule() {
        val service = File("src/main/java/com/envi/wispr/asr/AsrService.kt").readText()
        assertEquals(1, Regex("""deliverResult\(callback, rawText, onDelivered = ::sweepLegacyModelOnce\)""").findAll(service).count())
        assertEquals("called from exactly one place", 1, Regex("""sweepLegacyModelOnce(\(\)|\b)""").findAll(service).count() - 1)
        assertFalse("no direct onResult outside the rule", Regex("""callback\??\.onResult""").containsMatchIn(service.replace("callback.onResult(text)", "")))
    }
}
