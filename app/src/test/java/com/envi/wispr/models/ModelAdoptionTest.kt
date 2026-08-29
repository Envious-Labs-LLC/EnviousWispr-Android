package com.envi.wispr.models

import java.nio.file.Files
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelAdoptionTest {
    @Test fun verifiedAdoptionPromotesAndPreservesLegacySource() {
        val bytes = "verified".toByteArray(); val legacy = Files.createTempDirectory("legacy").toFile()
        java.io.File(legacy, "model.bin").writeBytes(bytes)
        val root = Files.createTempDirectory("models").toFile(); val store = ModelDeliveryStore(root)
        val status = store.adoptExisting(descriptor(bytes), legacy)
        assertEquals(DownloadState.READY, status.state)
        assertTrue(java.io.File(root, "demo/model.bin").exists())
        assertEquals("verified", java.io.File(legacy, "model.bin").readText())
        root.deleteRecursively(); legacy.deleteRecursively()
    }

    @Test fun tamperAndPartialSetsAreRejectedWithoutPromotion() {
        val legacy = Files.createTempDirectory("legacy").toFile(); java.io.File(legacy, "model.bin").writeText("tampered")
        val root = Files.createTempDirectory("models").toFile(); val store = ModelDeliveryStore(root)
        assertEquals(DownloadState.REPAIR_NEEDED, store.adoptExisting(descriptor("verified".toByteArray()), legacy).state)
        java.io.File(legacy, "model.bin").delete()
        assertEquals(DownloadState.REPAIR_NEEDED, store.adoptExisting(descriptor("verified".toByteArray()), legacy).state)
        assertFalse(java.io.File(root, "demo").exists())
        root.deleteRecursively(); legacy.deleteRecursively()
    }

    @Test fun symlinkIsRejected() {
        val legacy = Files.createTempDirectory("legacy").toFile(); val outside = Files.createTempFile("outside", ".bin")
        Files.createSymbolicLink(java.io.File(legacy, "model.bin").toPath(), outside)
        val root = Files.createTempDirectory("models").toFile()
        assertEquals(DownloadState.REPAIR_NEEDED, ModelDeliveryStore(root).adoptExisting(descriptor("verified".toByteArray()), legacy).state)
        root.deleteRecursively(); legacy.deleteRecursively(); Files.deleteIfExists(outside)
    }

    private fun descriptor(bytes: ByteArray) = ModelDescriptor("demo", "test", "Demo", "Test", "Test", "", "r1", listOf(ModelFile("model.bin", bytes.size.toLong(), hash(bytes), "https://huggingface.co/test/model/resolve/r1/model.bin")))
    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
