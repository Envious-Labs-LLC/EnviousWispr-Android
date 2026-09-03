package com.envi.wispr.models

import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Product Outcome. When these fail the user loses a working speech engine: a model that never admits, one
 * that admits without verifying, or a failed update that takes the working model down with it.
 */
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

    @Test fun aCorruptUpdateLeavesTheAdmittedModelByteIdentical() {
        val installed = descriptor("first payload".toByteArray(), "r1")
        val newer = descriptor("second payload".toByteArray(), "r2")
        val root = Files.createTempDirectory("models").toFile()
        val store = ModelDeliveryStore(root)
        store.download(installed, ModelTransport { _, _ -> TransportResponse(ByteArrayInputStream("first payload".toByteArray()), false) })

        val status = store.download(newer, ModelTransport { _, _ -> TransportResponse(ByteArrayInputStream("truncated".toByteArray()), false) })

        // The admitted directory is never touched: a hash mismatch quarantines the staging copy and returns
        // before the promotion rename. Moving the promotion ahead of verification turns this red.
        assertEquals(DownloadState.REPAIR_NEEDED, status.state)
        assertEquals("first payload", java.io.File(root, "demo/model.bin").readText())
        // But it is NOT a fallback. Production asks isVerified with the NEW descriptor, which these bytes fail,
        // so the surviving files shorten the recovery and cannot serve a dictation.
        assertFalse(store.isVerified(newer))
        assertTrue(store.isVerified(installed))
        root.deleteRecursively()
    }

    @Test fun aValidUpdateReplacesTheAdmittedModelAndItsReceipt() {
        val installed = descriptor("first payload".toByteArray(), "r1")
        val newer = descriptor("second payload".toByteArray(), "r2")
        val root = Files.createTempDirectory("models").toFile()
        val store = ModelDeliveryStore(root)
        store.download(installed, ModelTransport { _, _ -> TransportResponse(ByteArrayInputStream("first payload".toByteArray()), false) })

        val status = store.download(newer, ModelTransport { _, _ -> TransportResponse(ByteArrayInputStream("second payload".toByteArray()), false) })

        assertEquals(DownloadState.READY, status.state)
        assertEquals("second payload", java.io.File(root, "demo/model.bin").readText())
        // The receipt moved with the bytes, so the old revision no longer reads as installed and nothing
        // still offers an update. Skipping the receipt rewrite turns this red.
        assertTrue(store.isVerified(newer))
        assertFalse(store.needsUpdate(newer))
        assertFalse(store.isVerified(installed))
        root.deleteRecursively()
    }

    @Test fun aPartialLeftByAnOlderRevisionIsDiscardedRatherThanResumed() {
        val bytes = "second payload".toByteArray()
        val newer = descriptor(bytes, "r2")
        val root = Files.createTempDirectory("models").toFile()
        // What a revision bump really leaves behind: the model id and the staging names do not change, so
        // the previous revision's partial is still here and it is LONGER than the new file.
        java.io.File(root, ".demo.download/model.bin.part").apply {
            parentFile.mkdirs()
            writeBytes("a much longer payload from the previous revision".toByteArray())
        }
        val offsets = mutableListOf<Long>()

        val status = ModelDeliveryStore(root).download(newer, ModelTransport { _, offset ->
            offsets += offset
            TransportResponse(ByteArrayInputStream(bytes), false)
        })

        // Resuming would ask for a range at or past the end of the file, which is HTTP 416. That throws
        // before verification can quarantine anything, and the state it leaves offers Update or Retry but
        // never Repair, so every press reuses the same partial.
        assertEquals(listOf(0L), offsets)
        assertEquals(DownloadState.READY, status.state)
        assertEquals("second payload", java.io.File(root, "demo/model.bin").readText())
        root.deleteRecursively()
    }

    private fun descriptor(bytes: ByteArray, revision: String = "r1") = ModelDescriptor("demo", "test", "Demo", "Test", "Test", "", revision, listOf(ModelFile("model.bin", bytes.size.toLong(), hash(bytes), "https://huggingface.co/test/model/resolve/$revision/model.bin")))
    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
