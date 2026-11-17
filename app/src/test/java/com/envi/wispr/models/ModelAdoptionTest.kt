package com.envi.wispr.models

import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.Callable
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Product Outcome (#284, #502): verified legacy models are promoted cleanly, tampered or linked files are rejected,
 * and concurrent first-launch workers racing to create the model root all succeed without spurious storage errors.
 * `modelDeliveryWorkerUsesRaceFreeDirectoryCheck` is a Drift Guard pinning `ModelDeliveryWorker` to that helper.
 */
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

    /**
     * Product Outcome (#502): concurrent first-launch workers racing to create a missing model root all succeed.
     * MUTATION: replace `ensureDirectory`'s body with `!((!directory.exists() && !directory.mkdirs()) || !directory.isDirectory)`
     * and the barrier inside `exists()` makes losing callers see `exists() == false` and `mkdirs() == false`.
     */
    @Test fun concurrentEnsureDirectorySucceedsWhenTwoWorkersCreateMissingRootTogether() {
        val tempRoot = Files.createTempDirectory("model-root-race").toFile()
        val threads = 8
        val pool = Executors.newFixedThreadPool(threads)
        try {
            repeat(50) { round ->
                val startBarrier = CyclicBarrier(threads)
                val existsBarrier = CyclicBarrier(threads)
                val tripped = ThreadLocal.withInitial { false }
                val target = object : File(tempRoot, "round-$round/nested/models") {
                    override fun exists(): Boolean {
                        val seen = super.exists()
                        if (tripped.get() != true) {
                            tripped.set(true)
                            existsBarrier.await(5, TimeUnit.SECONDS)
                        }
                        return seen
                    }
                }
                val tasks = (0 until threads).map {
                    Callable {
                        tripped.set(false)
                        startBarrier.await(5, TimeUnit.SECONDS)
                        ModelStorage.ensureDirectory(target)
                    }
                }
                val results = pool.invokeAll(tasks).map { it.get() }
                assertTrue("all concurrent callers must observe created directory in round $round", results.all { it })
                assertTrue(target.isDirectory)
            }
        } finally {
            pool.shutdownNow()
            tempRoot.deleteRecursively()
        }
    }

    @Test fun ensureDirectoryFailsClosedWhenBlockedByRegularFile() {
        val tempRoot = Files.createTempDirectory("model-root-file").toFile()
        try {
            val file = File(tempRoot, "models").apply { writeText("not-a-directory") }
            assertFalse(ModelStorage.ensureDirectory(file))
        } finally {
            tempRoot.deleteRecursively()
        }
    }

    /** Drift Guard: pins `ModelDeliveryWorker.doWork` to the race-free helper in `ModelStorage`. */
    @Test fun modelDeliveryWorkerUsesRaceFreeDirectoryCheck() {
        val worker = (File("src/main/java/com/envi/wispr/models/ModelDeliveryWorker.kt").takeIf { it.exists() }
            ?: File("app/src/main/java/com/envi/wispr/models/ModelDeliveryWorker.kt")).readText()
        assertTrue(worker.contains("ModelStorage.ensureDirectory(root)"))
        assertFalse(worker.contains("!root.exists() && !root.mkdirs()"))
    }

    private fun descriptor(bytes: ByteArray) = ModelDescriptor("demo", "test", "Demo", "Test", "Test", "", R1, listOf(ModelFile("model.bin", bytes.size.toLong(), hash(bytes), "https://huggingface.co/test/model/resolve/$R1/model.bin")))
    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}

/** Pinned revisions in the shape the manifest requires (#284): full 40-character commit hashes. */
private const val R1 = "1111111111111111111111111111111111111111"
private const val R2 = "2222222222222222222222222222222222222222"
