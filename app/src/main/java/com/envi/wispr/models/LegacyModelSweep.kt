package com.envi.wispr.models

import java.io.File
import java.nio.file.Files

/**
 * Removes the sherpa-onnx speech model the #374 engine swap replaced: its folder (`models/parakeet`) and any
 * half-finished download of it (`models/.parakeet.download`), about 670 MB.
 *
 * Called by `AsrService` only after the NEW engine delivered its first successful result, so a phone is never left
 * with neither model. The two names are literals on purpose: they are the retired storage id, which no manifest entry
 * uses any more. Never throws; the files are walked and removed one by one, then the absence is checked.
 */
internal object LegacyModelSweep {
    /** The retired storage id's folders, relative to the models root. */
    val LEGACY = listOf("parakeet", ".parakeet.download")

    /** Returns the bytes removed; zero when there was nothing, or when removal failed (logged by the caller). */
    fun sweep(root: File): Long = runCatching {
        var removed = 0L
        for (name in LEGACY) {
            val folder = File(root, name)
            if (!folder.exists() && !Files.isSymbolicLink(folder.toPath())) continue
            // A link in the retired name's place is removed as a link: walking it could reach a live model.
            if (Files.isSymbolicLink(folder.toPath())) {
                folder.delete()
                continue
            }
            // Bottom-up, one entry at a time, never following a link out of the folder.
            folder.walkBottomUp().onEnter { !Files.isSymbolicLink(it.toPath()) }.forEach { entry ->
                val size = if (entry.isFile) entry.length() else 0L
                if (entry.delete()) removed += size
            }
        }
        if (LEGACY.any { File(root, it).exists() }) 0L else removed
    }.getOrDefault(0L)
}
