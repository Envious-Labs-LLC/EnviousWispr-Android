package com.envi.wispr.models

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Product Outcome: real Android filesystem cleanup receipts retain interrupted repair progress.
 * Tiny synthetic model only; this does not qualify speech or polish inference on the device.
 */
@RunWith(AndroidJUnit4::class)
class ModelRepairDeviceTest {
    @Test fun firstUseSyncAndRestartRetainThePartialAndTheAdmittedModel() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val parent = Files.createTempDirectory(context.cacheDir.toPath(), "repair-device-").toFile()
        val root = File(parent, "models")
        val bytes = "model payload".toByteArray()
        val revision = "1111111111111111111111111111111111111111"
        val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        val model = ModelDescriptor("demo", "test", "Demo", "Test", "Test", "", revision,
            listOf(ModelFile("model.bin", 13L, hash, "https://huggingface.co/test/model/resolve/$revision/model.bin")))
        val request = UUID.randomUUID()
        try {
            assertFalse("fixture starts before first-use directory creation", root.exists())
            assertEquals(true, ModelDeliveryStore(root).repair(model, request))
            val part = File(root, ".demo.download/model.bin.part").apply { parentFile.mkdirs(); writeBytes(bytes.copyOf(5)) }
            File(part.parentFile, ".staging-revision").writeText(revision)
            val restarted = ModelDeliveryStore(root)
            assertEquals(true, restarted.repair(model, request, exclusive = { error("cleanup must not repeat") }))
            assertEquals(5L, part.length())
            val status = restarted.download(model, ModelTransport { _, offset ->
                assertEquals("resume the actual saved prefix", 5L, offset)
                TransportResponse(ByteArrayInputStream(bytes.copyOfRange(5, bytes.size)), true)
            })
            assertEquals(DownloadState.READY, status.state)
            assertTrue(restarted.isVerified(model))
            assertEquals("model payload", File(root, "demo/model.bin").readText())
            assertEquals(true, ModelDeliveryStore(root).repair(model, request))
            assertTrue("a replay after admission must not remove the model", ModelDeliveryStore(root).isVerified(model))
        } finally { assertTrue("the disposable model fixture was not removed", ModelTree.delete(parent)) }
    }
}
