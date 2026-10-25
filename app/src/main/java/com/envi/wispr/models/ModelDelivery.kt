package com.envi.wispr.models

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.URI
import java.nio.file.Files
import java.nio.file.LinkOption
import java.security.MessageDigest

/** STAGED (#108): the new revision is complete and verified in staging, and applying it waits for an idle moment. */
internal enum class DownloadState { DOWNLOADING, PAUSED, VERIFYING, READY, FAILED, CANCELLED, REPAIR_NEEDED, STAGED }
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

    /**
     * #458: carries a user's Pause or Cancel from a retired model id over to [model], under the same lock as every
     * read and write. The retired value is inherited only when [model] has no record of its own (an explicit Resume,
     * Download or Update on the current card wins), and the retired record is deleted either way, so a run interrupted
     * between the two steps finishes on the next call and an obsolete stop can never come back after a Resume.
     */
    fun inheritRetired(retiredId: String, model: ModelDescriptor) = synchronized(MODEL_CONTROL_LOCK) {
        val retired = File(directory(), "$retiredId.state")
        if (!retired.exists()) return@synchronized
        val state = runCatching { ModelDeliveryControlState.valueOf(retired.readText().trim()) }.getOrNull()
        val stopped = state == ModelDeliveryControlState.PAUSED || state == ModelDeliveryControlState.CANCELLED
        if (stopped && !File(directory(), "${model.id}.state").exists()) write(model, state!!)
        retired.delete()
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
    fun isVerified(model: ModelDescriptor): Boolean = synchronized(lock(model)) { verifies(finalDirectory(model), model) }

    /**
     * Which pinned descriptor the installed files fully verify against, the current one first, then each of
     * [ModelDescriptor.previous] (#108); null when none does. Nothing is relaxed: the same names, receipt, sizes and
     * hashes, against a descriptor compiled into this build. "Can I dictate" reads this; download and update decisions
     * keep reading [isVerified], which means the CURRENT revision.
     */
    fun usableDescriptor(model: ModelDescriptor): ModelDescriptor? = synchronized(lock(model)) {
        (listOf(model) + model.previous).firstOrNull { verifies(finalDirectory(model), it) }
    }

    /** [directory] holds exactly [model]'s files plus its receipt, every size and hash matching. */
    private fun verifies(directory: File, model: ModelDescriptor): Boolean {
        val receipt = File(directory, RECEIPT)
        val names = directory.listFiles()?.map { it.name }?.toSet()
        // A linked receipt could borrow another folder's (#492 review round 2), so it is refused like a linked model file.
        return model.isAvailable && !Files.isSymbolicLink(directory.toPath()) && names == (model.files.map { it.name }.toSet() + RECEIPT) && receipt.isFile && !Files.isSymbolicLink(receipt.toPath()) && receipt.readText() == receiptText(model) && model.files.all { entry ->
            val file = File(directory, entry.name)
            file.isFile && !Files.isSymbolicLink(file.toPath()) && file.length() == entry.expectedBytes && sha256(file) == entry.sha256!!.lowercase()
        }
    }

    /**
     * Finishes or undoes an admission a process death interrupted (#492). Admission renames final to `.<id>.old`,
     * then staging to final, then deletes `.old`; a death between the renames left final absent with the previous
     * model in `.old` and the new one in staging, and the next download used to delete `.old` first and staging after
     * it, losing both. Main process only (app start, and before any download or adoption); returns whether it changed
     * anything.
     *
     * Final is judged without following links, and only a final that fully verifies counts as a finished admission:
     * - final verifies: a leftover `.old` is removed;
     * - final missing, or present but not verifying (a corrupt folder, a file, a dangling link): a staging or adoption
     *   folder that fully verifies against [model] replaces it, and `.old` then goes;
     * - final missing and nothing verified to replace it: `.old` is put back, unverified, since every reader verifies
     *   final before trusting it;
     * - anything else is left exactly as found: nothing here deletes the only copy of a model.
     *
     * #108: a final holding a previous revision is in use, never broken; its update waits in staging for the gated
     * admission. Only start-up recovery (before the swap gate opens, so before any engine binds) admits a finished
     * folder itself; a download or adoption leaves it for its own gated admission.
     */
    fun reconcile(model: ModelDescriptor): Boolean = synchronized(lock(model)) { reconcileLocked(model, admitFinished = true) }

    /** Whether start-up must recover [model] before the swap gate opens: an admission left `.old` behind (cheap, no hashing). */
    fun interruptedAdmission(model: ModelDescriptor): Boolean =
        Files.exists(File(root, ".${model.id}.old").toPath(), LinkOption.NOFOLLOW_LINKS)

    private fun reconcileLocked(model: ModelDescriptor, admitFinished: Boolean): Boolean {
        val final = finalDirectory(model)
        val old = File(root, ".${model.id}.old")
        val oldPresent = Files.exists(old.toPath(), LinkOption.NOFOLLOW_LINKS)
        val finalPresent = Files.exists(final.toPath(), LinkOption.NOFOLLOW_LINKS)
        if (finalPresent && (listOf(model) + model.previous).any { verifies(final, it) }) return oldPresent && ModelTree.delete(old)
        val finished = if (!admitFinished) null else listOf(File(root, ".${model.id}.download"), File(root, ".${model.id}.adopt"))
            .firstOrNull { verifies(it, model) }
        if (finished != null) {
            // The verified copy is the one kept; whatever sits at final (broken, or nothing) makes way for it.
            if (finalPresent && !ModelTree.delete(final)) return false
            if (!finished.renameTo(final)) return false
            if (oldPresent) ModelTree.delete(old)
            return true
        }
        if (finalPresent) return false
        return Files.isDirectory(old.toPath(), LinkOption.NOFOLLOW_LINKS) && old.renameTo(final)
    }

    /** Cheap installation identity for reusing an already verified native instance. Not first-load verification. */
    fun installationStamp(model: ModelDescriptor): String? = synchronized(lock(model)) {
        val directory = finalDirectory(model)
        val receipt = File(directory, RECEIPT)
        val names = directory.listFiles()?.map { it.name }?.toSet()
        if (!model.isAvailable || Files.isSymbolicLink(directory.toPath()) || names != model.files.map { it.name }.toSet() + RECEIPT ||
            !receipt.isFile || receipt.readText() != receiptText(model)) return null
        val parts = model.files.map { entry ->
            val file = File(directory, entry.name)
            if (!file.isFile || Files.isSymbolicLink(file.toPath()) || file.length() != entry.expectedBytes) return null
            val attributes = Files.readAttributes(file.toPath(), java.nio.file.attribute.BasicFileAttributes::class.java)
            "${entry.name}:${attributes.fileKey()}:${attributes.lastModifiedTime()}:${attributes.size()}"
        }
        receiptText(model) + "|" + parts.joinToString("|")
    }

    /** True when an admitted model belongs to an older pinned manifest revision. */
    fun needsUpdate(model: ModelDescriptor): Boolean = runCatching {
        val final = finalDirectory(model)
        val receipt = File(final, RECEIPT)
        final.isDirectory && !Files.isSymbolicLink(final.toPath()) && receipt.isFile &&
            runCatching { receipt.readText() != receiptText(model) }.getOrDefault(false)
    }.getOrDefault(false)

    /**
     * [admission] runs the final-folder renames: the worker passes `ModelSwap.exclusive` (#108), which pauses dictation
     * and stops the engines first, and answers null when that cannot happen now; the download then ends STAGED, with
     * the complete new revision kept in staging for the next attempt.
     */
    fun download(model: ModelDescriptor, transport: ModelTransport, control: DownloadControl = object : DownloadControl {}, now: () -> Long = { System.currentTimeMillis() }, onProgress: (DownloadStatus) -> Unit = {}, onSource: (file: String, host: String) -> Unit = { _, _ -> }, admission: (mutation: () -> Boolean) -> Boolean? = { it() }): DownloadStatus = synchronized(lock(model)) {
        if (!model.isAvailable) return DownloadStatus(DownloadState.FAILED, message = "model manifest is unavailable", reason = DeliveryFailureReason.MANIFEST_UNAVAILABLE)
        root.mkdirs()
        // An interrupted admission is settled before anything below deletes `.old` or the staging folder (#492);
        // when that completed the current model there is nothing left to fetch.
        if (reconcileLocked(model, admitFinished = false) && verifies(finalDirectory(model), model)) {
            val total = model.files.sumOf { it.expectedBytes }
            return DownloadStatus(DownloadState.READY, total, total)
        }
        val staging = File(root, ".${model.id}.download")
        // A revision an earlier attempt finished but could not apply (#108): nothing to fetch, only the admission.
        if (verifies(staging, model)) {
            return try { admitStaged(model, staging, admission) } catch (e: IOException) {
                DownloadStatus(DownloadState.FAILED, message = "model download interrupted", reason = DeliveryFailureReason.of(e))
            }
        }
        // A revision bump keeps the model id and every staging file name, so the PREVIOUS revision's
        // partials are still sitting here. Resuming them mixes two revisions' bytes into one file. The
        // stamp closes that window at the source rather than reasoning about each partial's length:
        // a partial longer than the new file wedges on an HTTP 416, and a SHORTER one is worse to reason
        // about because it looks resumable, downloads the new suffix onto old bytes, and only fails at the
        // hash, having spent the whole transfer (#36 review rounds 4 and 5, 2026-09-02).
        val stamp = File(staging, STAGING_REVISION)
        if (staging.exists() && runCatching { stamp.readText() }.getOrNull() != model.pinnedRevision) {
            ModelTree.delete(staging)
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
            admitStaged(model, staging, admission)
        } catch (e: IOException) {
            // Transport and storage interruptions keep the .part file for a later range resume. The
            // reason is read off the exception, which was typed where the cause was decided (#176).
            DownloadStatus(DownloadState.FAILED, message = "model download interrupted", reason = DeliveryFailureReason.of(e))
        }
    }

    /** The complete [staging] folder replaces final through [admission]; STAGED when the admission could not run now. */
    private fun admitStaged(model: ModelDescriptor, staging: File, admission: (mutation: () -> Boolean) -> Boolean?): DownloadStatus {
        val total = model.files.sumOf { it.expectedBytes }
        val admitted = admission {
            val final = finalDirectory(model)
            val old = File(root, ".${model.id}.old")
            ModelTree.delete(old)
            if (final.exists() && !final.renameTo(old)) throw ModelDeliveryException(DeliveryFailureReason.STAGING_FAILED, "could not stage existing model")
            if (!staging.renameTo(final)) {
                old.renameTo(final)
                throw ModelDeliveryException(DeliveryFailureReason.ADMISSION_FAILED, "could not admit model atomically")
            }
            ModelTree.delete(old)
            true
        } ?: return DownloadStatus(DownloadState.STAGED, total, total)
        return if (admitted) DownloadStatus(DownloadState.READY, total, total) else DownloadStatus(DownloadState.STAGED, total, total)
    }

    fun remove(model: ModelDescriptor): Boolean = synchronized(lock(model)) {
        val removedFinal = ModelTree.delete(finalDirectory(model))
        removedFinal && cleanupArtifacts(model)
    }
    fun repair(model: ModelDescriptor): Boolean = synchronized(lock(model)) {
        val removed = remove(model)
        val cleaned = cleanupArtifacts(model)
        removed && cleaned
    }

    /** Verifies and copies an existing legacy model without modifying its source. */
    fun adoptExisting(model: ModelDescriptor, legacyDirectory: File, now: () -> Long = { System.currentTimeMillis() }, admission: (mutation: () -> Boolean) -> Boolean? = { it() }): DownloadStatus = synchronized(lock(model)) {
        if (!model.isAvailable || !legacyDirectory.isDirectory || Files.isSymbolicLink(legacyDirectory.toPath())) {
            return DownloadStatus(DownloadState.REPAIR_NEEDED, message = "legacy model is unavailable")
        }
        val names = legacyDirectory.listFiles()?.map { it.name }?.toSet() ?: emptySet()
        if (names != model.files.map { it.name }.toSet()) return DownloadStatus(DownloadState.REPAIR_NEEDED, message = "legacy model file set is incomplete")
        reconcileLocked(model, admitFinished = false)
        val staging = File(root, ".${model.id}.adopt")
        ModelTree.delete(staging)
        try {
            staging.mkdirs()
            for (entry in model.files) {
                val source = File(legacyDirectory, entry.name)
                if (!source.isFile || Files.isSymbolicLink(source.toPath()) || source.length() != entry.expectedBytes || sha256(source) != entry.sha256!!.lowercase()) {
                    ModelTree.delete(staging)
                    return DownloadStatus(DownloadState.REPAIR_NEEDED, message = "legacy model integrity check failed")
                }
                Files.copy(source.toPath(), File(staging, entry.name).toPath())
            }
            File(staging, RECEIPT).writeText(receiptText(model))
            // The same gated admission as a download (#108); after STAGED, a later attempt copies again.
            admitStaged(model, staging, admission)
        } catch (_: IOException) {
            ModelTree.delete(staging)
            DownloadStatus(DownloadState.FAILED, message = "legacy model adoption failed")
        }
    }

    private fun cleanupArtifacts(model: ModelDescriptor): Boolean {
        val candidates = root.listFiles()?.filter { it.name.startsWith(".${model.id}.") } ?: return true
        return candidates.all { ModelTree.delete(it) }
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
internal const val MODEL_HOST_OWN = "models.enviouslabs.co"
internal const val MODEL_HOST_HUGGING_FACE = "huggingface.co"

/** A pinned revision: a full 40-character lowercase commit hash, never a branch, a tag or a short hash (#284). */
private val REVISION = Regex("\\A[0-9a-f]{40}\\z")

/** One path segment of a model address: no separator, no escape, no dot segment. */
private val SEGMENT = Regex("\\A[A-Za-z0-9][A-Za-z0-9._-]*\\z")

/**
 * [url] is a source for [fileName] at [revision] (#284): parsed ONCE, https on the default port with no user info or
 * fragment, on one of the two hosts, and in that host's exact path shape, where the revision is one whole segment in
 * its place and the last segment is the file's own name. Our bucket is `/<prefix>/<revision>/<file>` with no query;
 * Hugging Face is `/<owner>/<repo>/resolve/<revision>/<file>` with no query or `download=true`. A revision that is
 * not a full commit hash admits nothing. The byte count and SHA-256 checks still judge what arrives.
 */
internal fun validateModelSource(url: String, revision: String, fileName: String): Boolean = runCatching {
    if (!REVISION.matches(revision)) return@runCatching false
    val uri = URI(url)
    // The raw path equal to the decoded one: an escaped separator or dot cannot hide inside a segment.
    val rawPath = uri.rawPath ?: return@runCatching false
    if (rawPath != uri.path || !rawPath.startsWith("/")) return@runCatching false
    val segments = rawPath.removePrefix("/").split("/")
    if (segments.any { !SEGMENT.matches(it) }) return@runCatching false
    uri.scheme == "https" && uri.userInfo == null && uri.fragment == null && (uri.port == -1 || uri.port == 443) &&
        when (uri.host) {
            MODEL_HOST_OWN -> uri.rawQuery == null && segments.size == 3 && segments[1] == revision && segments[2] == fileName
            MODEL_HOST_HUGGING_FACE -> (uri.rawQuery == null || uri.rawQuery == "download=true") && segments.size == 5 &&
                segments[2] == "resolve" && segments[3] == revision && segments[4] == fileName
            else -> false
        }
}.getOrDefault(false)
