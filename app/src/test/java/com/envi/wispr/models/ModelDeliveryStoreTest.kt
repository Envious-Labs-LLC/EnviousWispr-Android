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
    @Test fun deletingTheReceiptOrReplacingAFileInvalidatesTheResidentInstallationStamp() {
        val bytes = "tiny verified model".toByteArray()
        val model = descriptor(bytes)
        val root = Files.createTempDirectory("installation-stamp").toFile()
        val store = ModelDeliveryStore(root)
        assertEquals(DownloadState.READY, store.download(model, ModelTransport { _, _ -> TransportResponse(ByteArrayInputStream(bytes), false) }).state)
        assertTrue(store.isVerified(model))
        val original = requireNotNull(store.installationStamp(model))
        val file = java.io.File(root, "demo/model.bin")
        assertTrue(file.delete()); file.writeBytes(bytes)
        assertFalse(original == store.installationStamp(model))
        assertTrue(java.io.File(root, "demo/.verified-receipt").delete())
        assertEquals(null, store.installationStamp(model))
    }
    @Test fun pauseCanBeWrittenWhileTheNetworkReadIsBlocked() {
        val payload = "model payload".toByteArray()
        val model = descriptor(payload)
        val root = Files.createTempDirectory("paused-transfer").toFile()
        val controls = ModelDeliveryControlStore(root)
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val paused = java.util.concurrent.CountDownLatch(1)
        val executor = java.util.concurrent.Executors.newFixedThreadPool(2)
        try {
            val download = executor.submit<DownloadStatus> {
                ModelDeliveryStore(root).download(model, ModelTransport { _, _ ->
                    TransportResponse(object : ByteArrayInputStream(payload) {
                        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                            entered.countDown()
                            check(release.await(10, java.util.concurrent.TimeUnit.SECONDS))
                            return super.read(buffer, offset, length)
                        }
                    }, false)
                }, object : DownloadControl { override fun isPaused() = controls.read(model) == ModelDeliveryControlState.PAUSED })
            }
            assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS))
            executor.submit { controls.write(model, ModelDeliveryControlState.PAUSED); paused.countDown() }
            assertTrue("Pause must not wait for the download to finish", paused.await(2, java.util.concurrent.TimeUnit.SECONDS))
            release.countDown()
            assertEquals(DownloadState.PAUSED, download.get(5, java.util.concurrent.TimeUnit.SECONDS).state)
            assertEquals(payload.size.toLong(), ModelDeliveryStore(root).stagedBytes(model))
            assertFalse(ModelDeliveryStore(root).isVerified(model))
        } finally { release.countDown(); executor.shutdown(); executor.awaitTermination(10, java.util.concurrent.TimeUnit.SECONDS); root.deleteRecursively() }
    }

    @Test fun anotherModelCanFinishWhileOneTransferIsBlocked() {
        val payload = "payload".toByteArray()
        val first = descriptor(payload)
        val second = first.copy(id = "second")
        val root = Files.createTempDirectory("independent-models").toFile()
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val executor = java.util.concurrent.Executors.newFixedThreadPool(2)
        try {
            executor.submit {
                ModelDeliveryStore(root).download(first, ModelTransport { _, _ ->
                    entered.countDown(); check(release.await(10, java.util.concurrent.TimeUnit.SECONDS))
                    TransportResponse(ByteArrayInputStream(payload), false)
                })
            }
            assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS))
            val other = executor.submit<DownloadStatus> { ModelDeliveryStore(root).download(second, ModelTransport { _, _ -> TransportResponse(ByteArrayInputStream(payload), false) }) }
            assertEquals(DownloadState.READY, other.get(2, java.util.concurrent.TimeUnit.SECONDS).state)
        } finally { release.countDown(); executor.shutdown(); executor.awaitTermination(10, java.util.concurrent.TimeUnit.SECONDS); root.deleteRecursively() }
    }

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
        // Stamped with THIS revision, which is what makes it resumable. An unstamped partial cannot be
        // attributed to any revision, so it is discarded rather than resumed: that is the one-time cost of
        // introducing the stamp, paid by anyone mid-download when it first ships.
        val partial = java.io.File(root, ".demo.download/model.bin.part").apply { parentFile.mkdirs(); writeBytes(bytes.copyOf(5)) }
        java.io.File(root, ".demo.download/.staging-revision").writeText(R1)
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
        val installed = descriptor(bytes, R1)
        val newer = descriptor(bytes, R2)
        val root = Files.createTempDirectory("models").toFile()
        val store = ModelDeliveryStore(root)
        store.download(installed, ModelTransport { _, _ -> TransportResponse(ByteArrayInputStream(bytes), false) })
        assertTrue(store.needsUpdate(newer))
        assertEquals("model payload", java.io.File(root, "demo/model.bin").readText())
        root.deleteRecursively()
    }

    @Test fun aCorruptUpdateLeavesTheAdmittedModelByteIdentical() {
        val installed = descriptor("first payload".toByteArray(), R1)
        val newer = descriptor("second payload".toByteArray(), R2)
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
        val installed = descriptor("first payload".toByteArray(), R1)
        val newer = descriptor("second payload".toByteArray(), R2)
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

    @Test fun aPartialLeftByAnOlderRevisionIsNeverResumedWhateverItsLength() {
        // BOTH lengths, because fixing only the longer one was the round-4 mistake. Longer wedges on an
        // HTTP 416 with the screen offering only Update, which repeats it. Shorter is quieter and costs
        // more: it looks resumable, so the new suffix lands on old bytes and the whole 670 MB transfer is
        // spent before the hash rejects it.
        listOf(
            "a much longer partial left by the previous revision" to "longer than the new file",
            "old" to "shorter than the new file",
        ).forEach { (stale, note) ->
            val bytes = "second payload".toByteArray()
            val newer = descriptor(bytes, R2)
            val root = Files.createTempDirectory("models").toFile()
            java.io.File(root, ".demo.download/model.bin.part").apply {
                parentFile.mkdirs()
                writeBytes(stale.toByteArray())
            }
            val offsets = mutableListOf<Long>()

            val status = ModelDeliveryStore(root).download(newer, ModelTransport { _, offset ->
                offsets += offset
                TransportResponse(ByteArrayInputStream(bytes), false)
            })

            // Asserting the offsets, not just the end state: an in-memory transport answers a bad resume
            // with resumed=false, which the existing fallback already survives, so only the offset list
            // separates "never asked to resume" from "asked, then recovered".
            assertEquals("resumed a stale partial $note", listOf(0L), offsets)
            assertEquals(DownloadState.READY, status.state)
            assertEquals("second payload", java.io.File(root, "demo/model.bin").readText())
            root.deleteRecursively()
        }
    }

    @Test fun theStagingRevisionStampIsNeverAdmittedIntoTheModelDirectory() {
        val bytes = "model payload".toByteArray()
        val model = descriptor(bytes)
        val root = Files.createTempDirectory("models").toFile()
        val store = ModelDeliveryStore(root)

        store.download(model, ModelTransport { _, _ -> TransportResponse(ByteArrayInputStream(bytes), false) })

        // isVerified demands EXACTLY the manifest's files plus the receipt, so a leftover stamp would make
        // a byte-perfect model read as not ready, which is the silent failure this whole layer exists to
        // avoid.
        assertEquals(
            setOf("model.bin", ".verified-receipt"),
            java.io.File(root, "demo").listFiles()!!.map { it.name }.toSet(),
        )
        assertTrue(store.isVerified(model))
        root.deleteRecursively()
    }

    /**
     * #461. MUTATION: put `File.deleteRecursively` back in `remove`; it walks through the link and empties the
     * folder outside the models root.
     */
    @Test fun removeDeletesALinkInsideTheModelFolderWithoutFollowingIt() {
        val bytes = "tiny verified model".toByteArray()
        val model = descriptor(bytes)
        val root = Files.createTempDirectory("remove-link").toFile()
        val outside = Files.createTempDirectory("outside-models").toFile()
        val precious = java.io.File(outside, "photo.jpg").apply { writeBytes(ByteArray(7)) }
        val store = ModelDeliveryStore(root)
        assertEquals(DownloadState.READY, store.download(model, ModelTransport { _, _ -> TransportResponse(ByteArrayInputStream(bytes), false) }).state)
        Files.createSymbolicLink(java.io.File(root, "demo/nested").toPath(), outside.toPath())

        assertTrue(store.remove(model))
        assertFalse(Files.exists(java.io.File(root, "demo").toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS))
        assertTrue("the file outside the models folder survives", precious.isFile)
    }

    private fun descriptor(bytes: ByteArray, revision: String = R1) = ModelDescriptor("demo", "test", "Demo", "Test", "Test", "", revision, listOf(ModelFile("model.bin", bytes.size.toLong(), hash(bytes), "https://huggingface.co/test/model/resolve/$revision/model.bin")))

    private fun twoHostDescriptor(bytes: ByteArray) = ModelDescriptor("demo", "test", "Demo", "Test", "Test", "", R1, listOf(
        ModelFile("model.bin", bytes.size.toLong(), hash(bytes), "https://models.enviouslabs.co/demo/$R1/model.bin", "https://huggingface.co/test/model/resolve/$R1/model.bin"),
    ))

    /** Our host refuses to open, Hugging Face serves, the file is admitted, and the log names the host (#168). */
    @Test fun theFallbackHostServesWhenOursCannotBeOpened() {
        val root = Files.createTempDirectory("fallback").toFile()
        val payload = "model payload".toByteArray()
        val opened = mutableListOf<String>()
        val served = mutableListOf<String>()
        val status = ModelDeliveryStore(root).download(twoHostDescriptor(payload), ModelTransport { url, _ ->
            opened += url
            if (url.startsWith("https://models.enviouslabs.co/")) throw java.io.IOException("model source returned HTTP 404")
            TransportResponse(ByteArrayInputStream(payload), false)
        }, onSource = { file, host -> served += "$file@$host" })
        assertEquals(DownloadState.READY, status.state)
        assertEquals(listOf("https://models.enviouslabs.co/demo/$R1/model.bin", "https://huggingface.co/test/model/resolve/$R1/model.bin"), opened)
        assertEquals(listOf("model.bin@huggingface.co"), served)
    }

    @Test fun ourHostIsTriedFirstAndTheFallbackIsNotTouchedWhenItServes() {
        val root = Files.createTempDirectory("primary").toFile()
        val payload = "model payload".toByteArray()
        val opened = mutableListOf<String>()
        val served = mutableListOf<String>()
        val status = ModelDeliveryStore(root).download(twoHostDescriptor(payload), ModelTransport { url, _ ->
            opened += url
            TransportResponse(ByteArrayInputStream(payload), false)
        }, onSource = { file, host -> served += "$file@$host" })
        assertEquals(DownloadState.READY, status.state)
        assertEquals(listOf("https://models.enviouslabs.co/demo/$R1/model.bin"), opened)
        assertEquals(listOf("model.bin@models.enviouslabs.co"), served)
    }

    @Test fun bothHostsRefusingFailsTheDownloadWithTheLastError() {
        val root = Files.createTempDirectory("both-fail").toFile()
        val payload = "model payload".toByteArray()
        val status = ModelDeliveryStore(root).download(twoHostDescriptor(payload), ModelTransport { url, _ -> throw java.io.IOException("model source returned HTTP 503 for $url") })
        assertEquals("both refusals collapse into the same interrupted state as one host did before", DownloadState.FAILED, status.state)
    }
    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    // ---- #492: an admission a process death interrupted between its two renames

    private val oldBytes = "previous model".toByteArray()
    private val newBytes = "current model".toByteArray()

    /** A death after `final → .demo.old` and before `staging → final`: final absent, both other folders present. */
    private fun interruptedAdmission(stagingComplete: Boolean): Pair<java.io.File, ModelDescriptor> {
        val root = Files.createTempDirectory("interrupted").toFile()
        val previous = descriptor(oldBytes, R1)
        val current = descriptor(newBytes, R2)
        val store = ModelDeliveryStore(root)
        assertEquals(DownloadState.READY, store.download(previous, ModelTransport { _, _ -> TransportResponse(ByteArrayInputStream(oldBytes), false) }).state)
        assertTrue(java.io.File(root, "demo").renameTo(java.io.File(root, ".demo.old")))
        val staging = java.io.File(root, ".demo.download")
        if (stagingComplete) {
            // A real admitted copy of the current model, moved into the staging name the way admission leaves it.
            val elsewhere = Files.createTempDirectory("current").toFile()
            assertEquals(DownloadState.READY, ModelDeliveryStore(elsewhere).download(current, ModelTransport { _, _ -> TransportResponse(ByteArrayInputStream(newBytes), false) }).state)
            assertTrue(java.io.File(elsewhere, "demo").renameTo(staging))
            elsewhere.deleteRecursively()
        } else {
            staging.mkdirs(); java.io.File(staging, "model.bin.part").writeBytes(newBytes.copyOf(4))
        }
        assertFalse(java.io.File(root, "demo").exists())
        return root to current
    }

    /** Staging finished verifying before the death: the admission completes and `.old` goes. REVERT: drop the staging branch. */
    @Test fun aCompleteStagedModelIsAdmittedAfterTheDeath() {
        val (root, current) = interruptedAdmission(stagingComplete = true)
        try {
            assertTrue(ModelDeliveryStore(root).reconcile(current))
            assertTrue(ModelDeliveryStore(root).isVerified(current))
            assertEquals("current model", java.io.File(root, "demo/model.bin").readText())
            assertFalse(java.io.File(root, ".demo.old").exists())
        } finally { root.deleteRecursively() }
    }

    /** Staging was not complete: the previous model is put back, never deleted. REVERT: drop the `.old` restore. */
    @Test fun anIncompleteStagedModelPutsThePreviousOneBack() {
        val (root, current) = interruptedAdmission(stagingComplete = false)
        try {
            assertTrue(ModelDeliveryStore(root).reconcile(current))
            assertEquals("previous model", java.io.File(root, "demo/model.bin").readText())
            assertTrue("the restored previous model is still verifiable as itself", ModelDeliveryStore(root).isVerified(descriptor(oldBytes, R1)))
            assertFalse(java.io.File(root, ".demo.old").exists())
        } finally { root.deleteRecursively() }
    }

    /**
     * The defect itself: a download started in the interrupted state used to delete `.old` first. Now the previous
     * model is back in place before the transfer, and a transfer that fails leaves it there.
     * REVERT: remove the `reconcileLocked` call at the top of `download`.
     */
    @Test fun aDownloadAfterTheDeathNeverLosesThePreviousModel() {
        val (root, current) = interruptedAdmission(stagingComplete = false)
        try {
            val status = ModelDeliveryStore(root).download(current, ModelTransport { _, _ -> throw java.io.IOException("network gone") })
            assertEquals(DownloadState.FAILED, status.state)
            assertEquals("previous model", java.io.File(root, "demo/model.bin").readText())
        } finally { root.deleteRecursively() }
    }

    /** A download that finds the admission already complete after reconciling fetches nothing. */
    @Test fun aDownloadAfterACompleteStagedModelFetchesNothing() {
        val (root, current) = interruptedAdmission(stagingComplete = true)
        try {
            val status = ModelDeliveryStore(root).download(current, ModelTransport { _, _ -> error("must not fetch") })
            assertEquals(DownloadState.READY, status.state)
            assertTrue(ModelDeliveryStore(root).isVerified(current))
        } finally { root.deleteRecursively() }
    }

    /**
     * Codex round 1 (P1): a final that exists but does not verify is not a finished admission, so a working `.old`
     * beside it must survive. REVERT: delete `.old` whenever final exists.
     */
    @Test fun aBrokenFinalNeverCostsTheWorkingOld() {
        val (root, current) = interruptedAdmission(stagingComplete = false)
        try {
            val final = java.io.File(root, "demo"); final.mkdirs(); java.io.File(final, "model.bin").writeText("garbage")
            assertFalse(ModelDeliveryStore(root).reconcile(current))
            assertEquals("previous model", java.io.File(root, ".demo.old/model.bin").readText())
        } finally { root.deleteRecursively() }
    }

    /** A dangling link at final does not block a complete staged model; the link makes way. REVERT: follow links. */
    @Test fun aDanglingLinkAtFinalMakesWayForTheVerifiedStagedModel() {
        val (root, current) = interruptedAdmission(stagingComplete = true)
        try {
            Files.createSymbolicLink(java.io.File(root, "demo").toPath(), java.io.File(root, "nowhere").toPath())
            assertTrue(ModelDeliveryStore(root).reconcile(current))
            assertTrue(ModelDeliveryStore(root).isVerified(current))
            assertFalse(Files.isSymbolicLink(java.io.File(root, "demo").toPath()))
        } finally { root.deleteRecursively() }
    }

    /** Admission finished but its `.old` delete was lost: only the leftover goes; nothing else is touched. */
    @Test fun aLeftoverOldBesideAFinalModelIsRemoved() {
        val root = Files.createTempDirectory("leftover").toFile()
        try {
            val model = descriptor(newBytes, R2)
            assertEquals(DownloadState.READY, ModelDeliveryStore(root).download(model, ModelTransport { _, _ -> TransportResponse(ByteArrayInputStream(newBytes), false) }).state)
            java.io.File(root, ".demo.old").mkdirs()
            assertTrue(ModelDeliveryStore(root).reconcile(model))
            assertFalse(java.io.File(root, ".demo.old").exists())
            assertTrue(ModelDeliveryStore(root).isVerified(model))
            assertFalse("nothing to do the second time", ModelDeliveryStore(root).reconcile(model))
        } finally { root.deleteRecursively() }
    }
}

/** Pinned revisions in the shape the manifest requires (#284): full 40-character commit hashes. */
private const val R1 = "1111111111111111111111111111111111111111"
private const val R2 = "2222222222222222222222222222222222222222"
