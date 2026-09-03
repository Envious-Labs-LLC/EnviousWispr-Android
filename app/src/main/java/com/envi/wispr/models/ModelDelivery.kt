package com.envi.wispr.models

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.URI
import java.nio.file.Files
import java.security.MessageDigest

enum class DownloadState { DOWNLOADING, PAUSED, VERIFYING, READY, FAILED, CANCELLED, REPAIR_NEEDED }
data class DownloadStatus(val state: DownloadState, val bytes: Long = 0, val total: Long = 0, val message: String? = null)
private val MODEL_DELIVERY_PROCESS_LOCK = Any()

enum class ModelDeliveryControlState { ACTIVE, PAUSED, CANCELLED }

/** Small app-private control record shared by the UI, WorkManager, and the downloader. */
class ModelDeliveryControlStore(private val root: File) {
    fun read(model: ModelDescriptor): ModelDeliveryControlState = synchronized(MODEL_DELIVERY_PROCESS_LOCK) {
        runCatching { File(directory(), "${model.id}.state").readText().trim() }
            .mapCatching { ModelDeliveryControlState.valueOf(it) }
            .getOrDefault(ModelDeliveryControlState.ACTIVE)
    }

    fun write(model: ModelDescriptor, state: ModelDeliveryControlState) = synchronized(MODEL_DELIVERY_PROCESS_LOCK) {
        directory().mkdirs()
        val target = File(directory(), "${model.id}.state")
        val temporary = File(directory(), ".${model.id}.state.tmp")
        temporary.writeText(state.name)
        if (!temporary.renameTo(target)) {
            temporary.delete()
            throw IOException("could not persist model control state")
        }
    }

    fun clear(model: ModelDescriptor) = synchronized(MODEL_DELIVERY_PROCESS_LOCK) {
        File(directory(), "${model.id}.state").delete()
    }

    private fun directory() = File(root, ".model-controls")
}

interface DownloadControl {
    fun isPaused(): Boolean = false
    fun isCancelled(): Boolean = false
    fun isStopped(): Boolean = false
}

data class TransportResponse(val stream: InputStream, val resumed: Boolean)
fun interface ModelTransport { fun open(url: String, offset: Long): TransportResponse }

class ModelDeliveryStore(private val root: File) {
    // WorkManager may create separate worker instances in the same process. Use one
    // process-wide lock so remove/repair cannot overlap a download from another instance.
    private val lock = MODEL_DELIVERY_PROCESS_LOCK

    fun finalDirectory(model: ModelDescriptor) = File(root, model.id)

    /** Full receipt verification. Call from a worker or service executor, never the UI thread. */
    fun isVerified(model: ModelDescriptor): Boolean = synchronized(lock) {
        val receipt = File(finalDirectory(model), RECEIPT)
        val names = finalDirectory(model).listFiles()?.map { it.name }?.toSet()
        model.isAvailable && !Files.isSymbolicLink(finalDirectory(model).toPath()) && names == (model.files.map { it.name }.toSet() + RECEIPT) && receipt.isFile && receipt.readText() == receiptText(model) && model.files.all { entry ->
            val file = File(finalDirectory(model), entry.name)
            file.isFile && !Files.isSymbolicLink(file.toPath()) && file.length() == entry.expectedBytes && sha256(file) == entry.sha256!!.lowercase()
        }
    }

    /** True when an admitted model belongs to an older pinned manifest revision. */
    fun needsUpdate(model: ModelDescriptor): Boolean = synchronized(lock) {
        val final = finalDirectory(model)
        val receipt = File(final, RECEIPT)
        final.isDirectory && !Files.isSymbolicLink(final.toPath()) && receipt.isFile &&
            runCatching { receipt.readText() != receiptText(model) }.getOrDefault(false)
    }

    fun download(model: ModelDescriptor, transport: ModelTransport, control: DownloadControl = object : DownloadControl {}, now: () -> Long = { System.currentTimeMillis() }, onProgress: (DownloadStatus) -> Unit = {}): DownloadStatus = synchronized(lock) {
        if (!model.isAvailable) return DownloadStatus(DownloadState.FAILED, message = "model manifest is unavailable")
        root.mkdirs()
        val staging = File(root, ".${model.id}.download")
        try {
            for (entry in model.files) {
                if (control.isCancelled()) {
                    val bytes = File(staging, entry.name + ".part").length()
                    val status = DownloadStatus(DownloadState.CANCELLED, bytes, entry.expectedBytes)
                    onProgress(status)
                    return status
                }
                if (control.isPaused()) {
                    val bytes = File(staging, entry.name + ".part").length()
                    val status = DownloadStatus(DownloadState.PAUSED, bytes, entry.expectedBytes)
                    onProgress(status)
                    return status
                }
                val part = File(staging, entry.name + ".part")
                part.parentFile?.mkdirs()
                var offset = part.length()
                // A partial at or past the expected size is not a resumable PREFIX of this file, so it
                // belongs to a different manifest revision. The model id and the staging names do not
                // change across a revision bump, so a v2 partial is still sitting here after a v3 bump:
                // asking for `Range: bytes=<size>-` returns 416, which throws before any verification can
                // quarantine it, and every Retry or Update reuses the same partial forever. The user is
                // never offered Repair from that state, so nothing clears it (#36 review, 2026-09-02).
                // Cost of the simple rule: a download that finished but died before the rename starts
                // over. That is rare and recoverable; the wedge was neither.
                if (offset >= entry.expectedBytes) {
                    part.delete()
                    offset = 0
                }
                var response = transport.open(entry.sourceUrl, offset)
                if (offset > 0 && !response.resumed) {
                    response.stream.close()
                    part.delete()
                    offset = 0
                    response = transport.open(entry.sourceUrl, 0)
                }
                onProgress(DownloadStatus(DownloadState.DOWNLOADING, offset, entry.expectedBytes))
                response.stream.use { input ->
                    FileOutputStream(part, offset > 0).use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            if (control.isStopped() || control.isCancelled()) {
                                val status = DownloadStatus(DownloadState.CANCELLED, offset, entry.expectedBytes)
                                onProgress(status)
                                return status
                            }
                            if (control.isPaused()) {
                                val status = DownloadStatus(DownloadState.PAUSED, offset, entry.expectedBytes)
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
                    return DownloadStatus(DownloadState.REPAIR_NEEDED, offset, entry.expectedBytes, "model integrity check failed")
                }
                val admittedFile = File(staging, entry.name)
                if (!part.renameTo(admittedFile)) throw IOException("could not finalize model file")
            }
            File(staging, RECEIPT).writeText(receiptText(model))
            val final = finalDirectory(model)
            val old = File(root, ".${model.id}.old")
            if (old.exists()) old.deleteRecursively()
            if (final.exists() && !final.renameTo(old)) throw IOException("could not stage existing model")
            if (!staging.renameTo(final)) {
                old.renameTo(final)
                throw IOException("could not admit model atomically")
            }
            old.deleteRecursively()
            DownloadStatus(DownloadState.READY, model.files.sumOf { it.expectedBytes }, model.files.sumOf { it.expectedBytes })
        } catch (e: IOException) {
            // Transport and storage interruptions keep the .part file for a later range resume.
            DownloadStatus(DownloadState.FAILED, message = "model download interrupted")
        }
    }

    fun remove(model: ModelDescriptor): Boolean = synchronized(lock) {
        val removedFinal = !finalDirectory(model).exists() || finalDirectory(model).deleteRecursively()
        removedFinal && cleanupArtifacts(model)
    }
    fun repair(model: ModelDescriptor): Boolean = synchronized(lock) {
        val removed = remove(model)
        val cleaned = cleanupArtifacts(model)
        removed && cleaned
    }

    /** Verifies and copies an existing legacy model without modifying its source. */
    fun adoptExisting(model: ModelDescriptor, legacyDirectory: File, now: () -> Long = { System.currentTimeMillis() }): DownloadStatus = synchronized(lock) {
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
    }

    private fun quarantine(staging: File, stamp: Long) {
        if (!staging.exists()) return
        staging.renameTo(File(root, "${staging.name}.quarantine-$stamp"))
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

fun validateModelSource(url: String): Boolean = runCatching {
    val uri = URI(url)
    uri.scheme == "https" && uri.host?.isNotBlank() == true && uri.userInfo == null && uri.fragment == null &&
        (uri.port == -1 || uri.port == 443) && uri.host == "huggingface.co" && uri.path.contains("/resolve/")
}.getOrDefault(false)
