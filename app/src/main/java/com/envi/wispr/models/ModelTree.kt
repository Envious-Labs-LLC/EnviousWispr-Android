package com.envi.wispr.models

import java.io.File
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes

/**
 * The one way a folder under the models root is deleted (#461). `File.deleteRecursively` follows a link to a
 * directory and empties whatever it points at; this walk never follows a link: a link, nested or the target
 * itself, is unlinked as an entry and what it points at is untouched. Every entry is removed on its own, a
 * failure is skipped rather than ending the walk, and the answer is the target's absence afterwards, never
 * an exit code (`tools-and-apps.md` RULE: never-unattended-recursive-delete).
 */
internal object ModelTree {
    /** True when nothing is left at [target], including when nothing was there. [onDeleted] gets each removed file's size. */
    fun delete(target: File, onDeleted: (bytes: Long) -> Unit = {}): Boolean {
        val path = target.toPath()
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return true
        runCatching {
            Files.walkFileTree(path, object : SimpleFileVisitor<Path>() {
                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    // A link to a directory arrives here as an entry, because the walk does not follow links.
                    val size = if (attrs.isRegularFile) attrs.size() else 0L
                    if (runCatching { Files.deleteIfExists(file) }.getOrDefault(false)) onDeleted(size)
                    return FileVisitResult.CONTINUE
                }

                override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult = FileVisitResult.CONTINUE

                override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult {
                    runCatching { Files.deleteIfExists(dir) }
                    return FileVisitResult.CONTINUE
                }
            })
        }
        return !Files.exists(path, LinkOption.NOFOLLOW_LINKS)
    }
}
