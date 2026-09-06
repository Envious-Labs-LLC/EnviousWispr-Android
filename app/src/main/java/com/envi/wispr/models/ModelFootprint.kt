package com.envi.wispr.models

import java.io.File
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes

/**
 * How much room a model actually takes on the phone.
 *
 * **Measured from disk, not from the manifest.** The manifest says what a model SHOULD weigh once it is
 * complete; the disk says what the user is paying for right now. The two differ in every case that
 * matters to someone trying to free space: a half-finished download, a quarantined file, a model left
 * behind by a version bump, and the hand-placed development model that no card knows about
 * ([issue #21](https://github.com/Envious-Labs-LLC/EnviousWispr-Android/issues/21)).
 *
 * The unit is BYTES, and formatting belongs to the UI. `ui/ModelCards.formatModelBytes` owns the
 * decimal-versus-binary decision and the reason for it.
 */
object ModelFootprint {

    /**
     * Total bytes of every regular file under [directory], including subdirectories.
     *
     * Returns 0 for a directory that does not exist, which is the right answer for a model that has
     * never been downloaded, and the same answer as an empty one. Nothing downstream needs to tell
     * "absent" from "empty" apart: both mean the user is paying nothing for it.
     *
     * **This touches the filesystem and must never run on the main thread.** Callers do the work on
     * `Dispatchers.IO`.
     *
     * A traversal failure, and an overflowing total, both THROW rather than returning a smaller number.
     * A storage figure that silently omits what it could not read is worse than no figure, because the
     * user acts on it. The caller catches and shows nothing.
     */
    fun bytesUnder(directory: File): Long {
        val root = directory.toPath()
        val attributes = try {
            Files.readAttributes(root, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        } catch (_: NoSuchFileException) {
            return 0L
        }
        if (attributes.isRegularFile) return attributes.size()
        if (!attributes.isDirectory) return 0L

        // `Files.walkFileTree` WITHOUT `FOLLOW_LINKS`, and that is the whole reason it is used here
        // rather than Kotlin's `walkTopDown`. An earlier revision used the Kotlin walker and carried a
        // comment saying it did not follow links. It does: the walker recurses into anything that
        // reports `isDirectory`, and a symbolic link to a directory reports exactly that. A link to a
        // file would have counted the same bytes twice, and a link to an ancestor would have counted
        // the tree over and over. Nothing in app-private storage creates one today, which is a reason
        // to be unworried and not a reason to keep a comment that was false.
        var total = 0L
        Files.walkFileTree(
            root,
            object : SimpleFileVisitor<Path>() {
                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    // Only regular files. A link visited here is reported as a link, not as its
                    // target, so it contributes nothing.
                    if (attrs.isRegularFile) {
                        // Throws on overflow rather than wrapping to a negative. The caller catches it
                        // and shows nothing, which is the right answer for a number nobody can believe.
                        total = Math.addExact(total, attrs.size())
                    }
                    return FileVisitResult.CONTINUE
                }
            },
        )
        return total
    }

    /** What the manifest says this model weighs once every file is present and verified. */
    fun expectedBytes(model: ModelDescriptor): Long = model.files.sumOf { it.expectedBytes }
}
