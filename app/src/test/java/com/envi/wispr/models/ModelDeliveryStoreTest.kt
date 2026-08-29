package com.envi.wispr.models

import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelDeliveryStoreTest {
    @Test fun downloadsVerifiesAndAtomicallyPromotes() {
        val bytes = "model payload".toByteArray()
        val model = descriptor(bytes)
        val root = Files.createTempDirectory("models").toFile()
        val status = ModelDeliveryStore(root).download(model, ModelTransport { _, _ -> TransportResponse(ByteArrayInputStream(bytes), false) })
        assertEquals(DownloadState.READY, status.state)
        assertEquals("model payload", java.io.File(root, "demo/model.bin").readText())
        assertFalse(java.io.File(root, ".demo.download").exists())
        root.deleteRecursively()
    }

    @Test fun resumesExistingPartialWhenTransportHonorsRange() {
        val bytes = "model payload".toByteArray()
        val model = descriptor(bytes)
        val root = Files.createTempDirectory("models").toFile()
        val partial = java.io.File(root, ".demo.download/model.bin.part").apply { parentFile.mkdirs(); writeBytes(bytes.copyOf(5)) }
        val status = ModelDeliveryStore(root).download(model, ModelTransport { _, offset ->
            assertEquals(5, offset)
            TransportResponse(ByteArrayInputStream(bytes.copyOfRange(offset.toInt(), bytes.size)), true)
        })
        assertEquals(DownloadState.READY, status.state)
        assertTrue(java.io.File(root, "demo/model.bin").exists())
        partial.delete()
        root.deleteRecursively()
    }

    @Test fun badChecksumQuarantinesAndNeverPromotes() {
        val bytes = "bad payload".toByteArray()
        val model = descriptor("correct".toByteArray())
        val root = Files.createTempDirectory("models").toFile()
        val status = ModelDeliveryStore(root).download(model, ModelTransport { _, _ -> TransportResponse(ByteArrayInputStream(bytes), false) })
        assertEquals(DownloadState.REPAIR_NEEDED, status.state)
        assertFalse(java.io.File(root, "demo").exists())
        assertTrue(root.listFiles()!!.any { it.name.contains("quarantine") })
        root.deleteRecursively()
    }

    @Test fun pauseAndCancelKeepPartialAndDoNotAdmit() {
        val bytes = "model payload".toByteArray()
        val model = descriptor(bytes)
        val root = Files.createTempDirectory("models").toFile()
        val paused = ModelDeliveryStore(root).download(model, ModelTransport { _, _ -> TransportResponse(ByteArrayInputStream(bytes), false) }, object : DownloadControl { override fun isPaused() = true })
        assertEquals(DownloadState.PAUSED, paused.state)
        assertFalse(java.io.File(root, "demo").exists())
        val cancelled = ModelDeliveryStore(root).download(model, ModelTransport { _, _ -> TransportResponse(ByteArrayInputStream(bytes), false) }, object : DownloadControl { override fun isCancelled() = true })
        assertEquals(DownloadState.CANCELLED, cancelled.state)
        root.deleteRecursively()
    }

    @Test fun removeAndRepairDeleteAdmittedModel() {
        val bytes = "model payload".toByteArray(); val model = descriptor(bytes)
        val root = Files.createTempDirectory("models").toFile(); val store = ModelDeliveryStore(root)
        store.download(model, ModelTransport { _, _ -> TransportResponse(ByteArrayInputStream(bytes), false) })
        assertTrue(store.repair(model)); assertFalse(store.finalDirectory(model).exists())
        root.deleteRecursively()
    }

    @Test fun controlStatePersistsInAppPrivateControlDirectory() {
        val root = Files.createTempDirectory("models").toFile()
        val model = descriptor("payload".toByteArray())
        ModelDeliveryControlStore(root).write(model, ModelDeliveryControlState.PAUSED)
        assertEquals(ModelDeliveryControlState.PAUSED, ModelDeliveryControlStore(root).read(model))
        ModelDeliveryControlStore(root).clear(model)
        assertEquals(ModelDeliveryControlState.ACTIVE, ModelDeliveryControlStore(root).read(model))
        root.deleteRecursively()
    }

    @Test fun admittedOlderReceiptReportsUpdateWithoutReplacingModel() {
        val bytes = "model payload".toByteArray()
        val installed = descriptor(bytes, "r1")
        val newer = descriptor(bytes, "r2")
        val root = Files.createTempDirectory("models").toFile()
        val store = ModelDeliveryStore(root)
        store.download(installed, ModelTransport { _, _ -> TransportResponse(ByteArrayInputStream(bytes), false) })
        assertTrue(store.needsUpdate(newer))
        assertEquals("model payload", java.io.File(root, "demo/model.bin").readText())
        root.deleteRecursively()
    }

    private fun descriptor(bytes: ByteArray, revision: String = "r1") = ModelDescriptor("demo", "test", "Demo", "Test", "Test", "", revision, listOf(ModelFile("model.bin", bytes.size.toLong(), hash(bytes), "https://huggingface.co/test/model/resolve/$revision/model.bin")))
    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
