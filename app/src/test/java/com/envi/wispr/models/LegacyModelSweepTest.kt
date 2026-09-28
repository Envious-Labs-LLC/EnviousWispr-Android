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
 * Product outcome (#374): when this fails, the replaced 670 MB speech model is left on the phone for good, or the NEW
 * model is deleted with it and dictation stops working.
 */
class LegacyModelSweepTest {
    @get:Rule val folder = TemporaryFolder()

    private fun write(path: String, bytes: Int) = File(folder.root, path).apply { parentFile!!.mkdirs(); writeBytes(ByteArray(bytes)) }

    /** MUTATION: sweep the wrong folder name, or sweep every folder (the new model and the polish model go too). */
    @Test fun removesOnlyTheReplacedModelAndItsHalfDownload() {
        write("parakeet/encoder.int8.onnx", 600)
        write("parakeet/.verified-receipt", 10)
        write(".parakeet.download/decoder.int8.onnx.part", 40)
        write("parakeet-sq/encoder-model.int8.onnx", 700)
        write("s1-mini/s1-mini-q4_k_m.gguf", 300)

        assertEquals(650L, LegacyModelSweep.sweep(folder.root))
        assertFalse(File(folder.root, "parakeet").exists())
        assertFalse(File(folder.root, ".parakeet.download").exists())
        assertTrue(File(folder.root, "parakeet-sq/encoder-model.int8.onnx").exists())
        assertTrue(File(folder.root, "s1-mini/s1-mini-q4_k_m.gguf").exists())
    }

    @Test fun nothingToSweepIsANoOp() {
        write("parakeet-sq/vocab.txt", 5)
        assertEquals(0L, LegacyModelSweep.sweep(folder.root))
        assertTrue(File(folder.root, "parakeet-sq/vocab.txt").exists())
    }

    /** The retired id must never be one a model in use still has, or the sweep would delete a live model. */
    @Test fun theRetiredIdsBelongToNoModelInUse() {
        assertTrue(ModelManifest.all.none { it.id in LegacyModelSweep.LEGACY || ".${it.id}.download" in LegacyModelSweep.LEGACY })
    }

    /** MUTATION: follow a link standing in the retired folder's place (the live model it points at is deleted). */
    @Test fun aLinkInTheRetiredPlaceIsRemovedWithoutTouchingItsTarget() {
        write("parakeet-sq/encoder-model.int8.onnx", 700)
        Files.createSymbolicLink(File(folder.root, "parakeet").toPath(), File(folder.root, "parakeet-sq").toPath())
        LegacyModelSweep.sweep(folder.root)
        assertFalse(Files.exists(File(folder.root, "parakeet").toPath(), LinkOption.NOFOLLOW_LINKS))
        assertTrue(File(folder.root, "parakeet-sq/encoder-model.int8.onnx").exists())
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
