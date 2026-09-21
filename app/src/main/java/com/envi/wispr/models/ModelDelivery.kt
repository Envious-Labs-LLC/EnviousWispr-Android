package com.envi.wispr.models

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.URI
import java.nio.file.Files
import java.security.MessageDigest

internal enum class DownloadState { DOWNLOADING, PAUSED, VERIFYING, READY, FAILED, CANCELLED, REPAIR_NEEDED }
internal data class DownloadStatus(
    val state: DownloadState,
    val bytes: Long = 0,
    val total: Long = 0,
    val message: String? = null,
    /** Why the attempt did not end READY, decided where the cause is known (issue #176); null on READY and progress. */
    val reason: DeliveryFailureReason? = null,
)
private val MODEL_CONTROL_LOCK = Any()
private val MODEL_OPERATION_LOCKS = java.util.concurrent.ConcurrentHashMap<String, Any>()

internal enum class ModelDeliveryControlState { ACTIVE, PAUSED, CANCELLED }

/** Small app-private control record shared by the UI, WorkManager, and the downloader. */
internal class ModelDeliveryControlStore(private val root: File) {
    fun read(model: ModelDescriptor): ModelDeliveryControlState = synchronized(MODEL_CONTROL_LOCK) {
        runCatching { File(directory(), "${model.id}.state").readText().trim() }
            .mapCatching { ModelDeliveryControlState.valueOf(it) }
            .getOrDefault(ModelDeliveryControlState.ACTIVE)
    }

    fun write(model: ModelDescriptor, state: ModelDeliveryControlState) = synchronized(MODEL_CONTROL_LOCK) {
        directory().mkdirs()
        val target = File(directory(), "${model.id}.state")
        val temporary = File(directory(), ".${model.id}.state.tmp")
        temporary.writeText(state.name)
        if (!temporary.renameTo(target)) {
            temporary.delete()
            throw IOException("could not persist model control state")
        }
    }

    fun clear(model: ModelDescriptor) = synchronized(MODEL_CONTROL_LOCK) {
        File(directory(), "${model.id}.state").delete()
    }

    private fun directory() = File(root, ".model-controls")
}

internal interface DownloadControl {
    fun isPaused(): Boolean = false
    fun isCancelled(): Boolean = false
    fun isStopped(): Boolean = false
}

internal data class TransportResponse(val stream: InputStream, val resumed: Boolean)
internal fun interface ModelTransport { fun open(url: String, offset: Long): TransportResponse }

internal class ModelDeliveryStore(private val root: File) {
    // Mutations of the same canonical model directory serialize across store instances.
    // Control records have a separate short lock so Pause can interrupt a blocked transfer.
    private fun lock(model: ModelDescriptor): Any = MODEL_OPERATION_LOCKS.computeIfAbsent(
        File(root.canonicalFile, model.id).path,
    ) { Any() }

    fun finalDirectory(model: ModelDescriptor) = File(root, model.id)

    /** Saved transfer progress only. Never a readiness or integrity claim; no mutation lock needed. */
    fun stagedBytes(model: ModelDescriptor): Long = runCatching {
        if (!model.isAvailable) return@runCatching 0L
        val staging = File(root, ".${model.id}.download")
        if (File(staging, STAGING_REVISION).readText() != model.pinnedRevision) return@runCatching 0L
        model.files.sumOf { entry ->
            val verifiedPart = File(staging, entry.name)
            val partial = File(staging, entry.name + ".part")
            (if (verifiedPart.isFile) verifiedPart.length() else partial.length()).coerceIn(0L, entry.expectedBytes)
        }
    }.getOrDefault(0L)

    /** Full receipt verification. Call from a worker or service executor, never the UI thread. */
    fun isVerified(model: ModelDescriptor): Boolean = synchronized(lock(model)) {
        val receipt = File(finalDirectory(model), RECEIPT)
        val names = finalDirectory(model).listFiles()?.map { it.name }?.toSet()
        model.isAvailable && !Files.isSymbolicLink(finalDirectory(model).toPath()) && names == (model.files.map { it.name }.toSet() + RECEIPT) && receipt.isFile && receipt.readText() == receiptText(model) && model.files.all { entry ->
            val file = File(finalDirectory(model), entry.name)
            file.isFile && !Files.isSymbolicLink(file.toPath()) && file.length() == entry.expectedBytes && sha256(file) == entry.sha256!!.lowercase()
        }
    }

    /** True when an admitted model belongs to an older pinned manifest revision. */
    fun needsUpdate(model: ModelDescriptor): Boolean = runCatching {
        val final = finalDirectory(model)
        val receipt = File(final, RECEIPT)
        final.isDirectory && !Files.isSymbolicLink(final.toPath()) && receipt.isFile &&
            runCatching { receipt.readText() != receiptText(model) }.getOrDefault(false)
    }.getOrDefault(false)

    fun download(model: ModelDescriptor, transport: ModelTransport, control: DownloadControl = object : DownloadControl {}, now: () -> Long = { System.currentTimeMillis() }, onProgress: (DownloadStatus) -> Unit = {}, onSource: (file: String, host: String) -> Unit = { _, _ -> }): DownloadStatus = synchronized(lock(model)) {
        if (!model.isAvailable) return DownloadStatus(DownloadState.FAILED, message = "model manifest is unavailable", reason = DeliveryFailureReason.MANIFEST_UNAVAILABLE)
        root.mkdirs()
        val staging = File(root, ".${model.id}.download")
        // A revision bump keeps the model id and every staging file name, so the PREVIOUS revision's
        // partials are still sitting here. Resuming them mixes two revisions' bytes into one file. The
        // stamp closes that window at the source rather than reasoning about each partial's length:
        // a partial longer than the new file wedges on an HTTP 416, and a SHORTER one is worse to reason
        // about because it looks resumable, downloads the new suffix onto old bytes, and only fails at the
        // hash, having spent the whole transfer (#36 review rounds 4 and 5, 2026-09-02).
        val stamp = File(staging, STAGING_REVISION)
        if (staging.exists() && runCatching { stamp.readText() }.getOrNull() != model.pinnedRevision) {
            staging.deleteRecursively()
        }
        try {
            staging.mkdirs()
            stamp.writeText(model.pinnedRevision)
            for (entry in model.files) {
                if (control.isCancelled()) {
                    val bytes = File(staging, entry.name + ".part").length()
                    val status = DownloadStatus(DownloadState.CANCELLED, bytes, entry.expectedBytes, reason = DeliveryFailureReason.CANCELLED)
                    onProgress(status)
                    return status
                }
                if (control.isPaused()) {
                    val bytes = File(staging, entry.name + ".part").length()
                    val status = DownloadStatus(DownloadState.PAUSED, bytes, entry.expectedBytes, reason = DeliveryFailureReason.PAUSED)
                    onProgress(status)
                    return status
                }
                val part = File(staging, entry.name + ".part")
                part.parentFile?.mkdirs()
                var offset = part.length()
                // Belt to the stamp's braces, and a DIFFERENT question: the stamp knows which revision a
                // partial belongs to, this knows whether it is a valid prefix at all. Within one revision
                // an oversized partial means local corruption, and resuming it asks for
                // `Range: bytes=<size>-`, which is an HTTP 416 that throws before verification can
                // quarantine anything. Cost: a transfer that finished but died before the rename starts
                // over, which is rare and recoverable.
                if (offset >= entry.expectedBytes) {
                    part.delete()
                    offset = 0
                }
                var opened = openFirstSource(entry, transport, offset)
                var response = opened.response
                if (offset > 0 && !response.resumed) {
                    response.stream.close()
                    part.delete()
                    offset = 0
                    opened = openFirstSource(entry, transport, 0)
                    response = opened.response
                }
                onSource(entry.name, opened.host)
                onProgress(DownloadStatus(DownloadState.DOWNLOADING, offset, entry.expectedBytes))
                response.stream.use { input ->
                    FileOutputStream(part, offset > 0).use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            if (control.isStopped() || control.isCancelled()) {
                                val status = DownloadStatus(DownloadState.CANCELLED, offset, entry.expectedBytes, reason = DeliveryFailureReason.CANCELLED)
                                onProgress(status)
                                return status
                            }
                            if (control.isPaused()) {
                                val status = DownloadStatus(DownloadState.PAUSED, offset, entry.expectedBytes, reason = DeliveryFailureReason.PAUSED)
                                onProgress(status)
                                return status
                            }
                            val count = input.read(buffer)
                            if (count < 0) break
                            output.write(buffer, 0, count)
                            offset += count
                            onProgress(DownloadStatus(DownloadState.DOWNLOADING, offset, entry.expectedBytes))
                        }
                    }
                }
                onProgress(DownloadStatus(DownloadState.VERIFYING, offset, entry.expectedBytes))
                if (offset != entry.expectedBytes || sha256(part) != entry.sha256!!.lowercase()) {
                    quarantine(staging, now())
                    return DownloadStatus(DownloadState.REPAIR_NEEDED, offset, entry.expectedBytes, "model integrity check failed", DeliveryFailureReason.INTEGRITY_MISMATCH)
                }
                val admittedFile = File(staging, entry.name)
                if (!part.renameTo(admittedFile)) throw ModelDeliveryException(DeliveryFailureReason.STAGING_FAILED, "could not finalize model file")
            }
            // The stamp is staging-only bookkeeping and must not be admitted: `isVerified` requires the
            // directory to hold EXACTLY the manifest's files plus the receipt, so one extra file makes an
            // otherwise perfect model read as not ready.
            stamp.delete()
            File(staging, RECEIPT).writeText(receiptText(model))
            val final = finalDirectory(model)
            val old = File(root, ".${model.id}.old")
            if (old.exists()) old.deleteRecursively()
            if (final.exists() && !final.renameTo(old)) throw ModelDeliveryException(DeliveryFailureReason.STAGING_FAILED, "could not stage existing model")
            if (!staging.renameTo(final)) {
                old.renameTo(final)
                throw ModelDeliveryException(DeliveryFailureReason.ADMISSION_FAILED, "could not admit model atomically")
            }
            old.deleteRecursively()
            DownloadStatus(DownloadState.READY, model.files.sumOf { it.expectedBytes }, model.files.sumOf { it.expectedBytes })
        } catch (e: IOException) {
            // Transport and storage interruptions keep the .part file for a later range resume. The
            // reason is read off the exception, which was typed where the cause was decided (#176).
            DownloadStatus(DownloadState.FAILED, message = "model download interrupted", reason = DeliveryFailureReason.of(e))
        }
    }

    fun remove(model: ModelDescriptor): Boolean = synchronized(lock(model)) {
        val removedFinal = !finalDirectory(model).exists() || finalDirectory(model).deleteRecursively()
        removedFinal && cleanupArtifacts(model)
    }
    fun repair(model: ModelDescriptor): Boolean = synchronized(lock(model)) {
        val removed = remove(model)
        val cleaned = cleanupArtifacts(model)
        removed && cleaned
    }

    /** Verifies and copies an existing legacy model without modifying its source. */
    fun adoptExisting(model: ModelDescriptor, legacyDirectory: File, now: () -> Long = { System.currentTimeMillis() }): DownloadStatus = synchronized(lock(model)) {
        if (!model.isAvailable || !legacyDirectory.isDirectory || Files.isSymbolicLink(legacyDirectory.toPath())) {
            return DownloadStatus(DownloadState.REPAIR_NEEDED, message = "legacy model is unavailable")
        }
        val names = legacyDirectory.listFiles()?.map { it.name }?.toSet() ?: emptySet()
        if (names != model.files.map { it.name }.toSet()) return DownloadStatus(DownloadState.REPAIR_NEEDED, message = "legacy model file set is incomplete")
        val staging = File(root, ".${model.id}.adopt")
        if (staging.exists()) staging.deleteRecursively()
        try {
            staging.mkdirs()
            for (entry in model.files) {
                val source = File(legacyDirectory, entry.name)
                if (!source.isFile || Files.isSymbolicLink(source.toPath()) || source.length() != entry.expectedBytes || sha256(source) != entry.sha256!!.lowercase()) {
                    staging.deleteRecursively()
                    return DownloadStatus(DownloadState.REPAIR_NEEDED, message = "legacy model integrity check failed")
                }
                Files.copy(source.toPath(), File(staging, entry.name).toPath())
            }
            File(staging, RECEIPT).writeText(receiptText(model))
            val final = finalDirectory(model); val old = File(root, ".${model.id}.old")
            if (old.exists()) old.deleteRecursively()
            if (final.exists() && !final.renameTo(old)) throw IOException("could not stage existing model")
            if (!staging.renameTo(final)) { old.renameTo(final); throw IOException("could not admit model atomically") }
            old.deleteRecursively()
            DownloadStatus(DownloadState.READY, model.files.sumOf { it.expectedBytes }, model.files.sumOf { it.expectedBytes })
        } catch (_: IOException) {
            staging.deleteRecursively()
            DownloadStatus(DownloadState.FAILED, message = "legacy model adoption failed")
        }
    }

    private fun cleanupArtifacts(model: ModelDescriptor): Boolean {
        val candidates = root.listFiles()?.filter { it.name.startsWith(".${model.id}.") } ?: return true
        return candidates.all { it.deleteRecursively() }
    }

    private fun receiptText(model: ModelDescriptor) = model.pinnedRevision + "\n" + model.files.joinToString("\n") { "${it.name}=${it.expectedBytes}:${it.sha256}" }

    private companion object {
        const val RECEIPT = ".verified-receipt"
        /** Which manifest revision the staging partials belong to. Staging-only; never admitted. */
        const val STAGING_REVISION = ".staging-revision"
    }

    private fun quarantine(staging: File, stamp: Long) {
        if (!staging.exists()) return
        staging.renameTo(File(root, "${staging.name}.quarantine-$stamp"))
    }
    private class OpenedSource(val response: TransportResponse, val host: String)

    /**
     * Our host first, Hugging Face second. The fallback is tried only when the primary cannot be
     * OPENED at all; once bytes flow, a failure mid-stream surfaces as before, and the next attempt
     * resumes from the partial against the primary again. The same file lives on both hosts, so a
     * partial written from one and resumed from the other is still one file; the byte count and hash
     * check admits or quarantines it regardless of which host served (#168).
     */
    private fun openFirstSource(entry: ModelFile, transport: ModelTransport, offset: Long): OpenedSource {
        var last: IOException? = null
        for (url in entry.sources) {
            try {
                return OpenedSource(transport.open(url, offset), URI(url).host ?: url)
            } catch (error: IOException) {
                last = error
            }
        }
        throw last ?: IOException("model has no source")
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) { val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}

/** The two hosts a model may come from, and nothing else; a redirect may not leave its host either. */
const val MODEL_HOST_OWN = "models.enviouslabs.co"
const val MODEL_HOST_HUGGING_FACE = "huggingface.co"

internal fun validateModelSource(url: String): Boolean = runCatching {
    val uri = URI(url)
    uri.scheme == "https" && uri.host?.isNotBlank() == true && uri.userInfo == null && uri.fragment == null &&
        (uri.port == -1 || uri.port == 443) && when (uri.host) {
            MODEL_HOST_OWN -> uri.path.count { it == '/' } >= 3
            MODEL_HOST_HUGGING_FACE -> uri.path.contains("/resolve/")
            else -> false
        }
}.getOrDefault(false)
