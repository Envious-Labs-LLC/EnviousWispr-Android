package com.envi.wispr.models

import java.io.File
import java.nio.file.Files

/**
 * Removes the speech models later swaps replaced, each about 670 MB: the sherpa-onnx set (#374, storage id `parakeet`)
 * and the SmoothQuant set (#458, storage id `parakeet-sq`), with every path either id can own under the models root:
 * the folder, its half-finished download, replace and adoption folders, its delivery control record, and any download
 * quarantined after an integrity mismatch.
 *
 * Called by `AsrService` only after the CURRENT model delivered its first successful result, so a phone is never left
 * with neither, and by the speech model's Remove. The names are literals on purpose: they are retired storage ids,
 * which no manifest entry uses any more. Never throws; the files are walked and removed one by one, then the absence
 * is checked.
 */
internal object LegacyModelSweep {
    /** Retired paths, relative to the models root. */
    val LEGACY = listOf(
        "parakeet", ".parakeet.download",
        "parakeet-sq", ".parakeet-sq.download", ".parakeet-sq.old", ".parakeet-sq.adopt",
        ".model-controls/parakeet-sq.state", ".model-controls/.parakeet-sq.state.tmp",
    )

    /** Direct children of the models root starting with this are quarantined `parakeet-sq` downloads (`ModelDelivery.quarantine`). */
    const val QUARANTINE_PREFIX = ".parakeet-sq.download.quarantine-"

    /** Returns the bytes removed; zero when there was nothing, or when removal failed (logged by the caller). */
    fun sweep(root: File): Long = runCatching {
        val targets = LEGACY.map { File(root, it) } +
            (root.listFiles()?.filter { it.name.startsWith(QUARANTINE_PREFIX) } ?: emptyList())
        var removed = 0L
        for (target in targets) {
            if (!target.exists() && !Files.isSymbolicLink(target.toPath())) continue
            // A link in a retired name's place is removed as a link: walking it could reach a live model.
            if (Files.isSymbolicLink(target.toPath())) {
                target.delete()
                continue
            }
            // Bottom-up, one entry at a time, never following a link out of the folder.
            target.walkBottomUp().onEnter { !Files.isSymbolicLink(it.toPath()) }.forEach { entry ->
                val size = if (entry.isFile) entry.length() else 0L
                if (entry.delete()) removed += size
            }
        }
        if (targets.any { it.exists() }) 0L else removed
    }.getOrDefault(0L)
}
