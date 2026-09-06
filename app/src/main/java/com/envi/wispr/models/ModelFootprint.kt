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

    /**
     * Every model's bytes AND the folder's bytes, from ONE traversal.
     *
     * **The single walk is the point, not an optimisation.** Walking each model and then walking the
     * folder is N+1 measurements taken at N+1 instants, and a model that grows between two of them
     * makes the folder look larger than its parts, which this page would report as space "no model
     * claims" that nobody is using. A model deleted between them does the reverse. Every file here is
     * counted exactly once, into exactly one bucket, from the length observed on that one visit, so the
     * parts always add up to the whole.
     *
     * A file is attributed to a model when it sits under that model's own directory. Anything else
     * under the root goes to [ModelFolderFootprint.unclaimed]: a half-finished download, a file a
     * version bump left behind, or a hand-placed model no card knows about.
     *
     * Same contract as [bytesUnder], and for the same reason: a traversal failure or an overflowing
     * total THROWS rather than returning a smaller number, because a storage figure that silently omits
     * what it could not read is worse than no figure. **Never call this on the main thread.**
     */
    fun measureFolder(root: File, models: List<ModelDescriptor>): ModelFolderFootprint {
        val rootPath = root.toPath()
        val owners = models.associateWith { File(root, it.id).toPath() }
        val perModel = models.associateWith { 0L }.toMutableMap()
        var total = 0L

        val attributes = try {
            Files.readAttributes(rootPath, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        } catch (_: NoSuchFileException) {
            // Nothing downloaded yet. Every model is zero and so is the folder, which is the truth.
            return ModelFolderFootprint(perModel, 0L)
        }
        if (!attributes.isDirectory) return ModelFolderFootprint(perModel, 0L)

        Files.walkFileTree(
            rootPath,
            object : SimpleFileVisitor<Path>() {
                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    if (!attrs.isRegularFile) return FileVisitResult.CONTINUE
                    val size = attrs.size()
                    total = Math.addExact(total, size)
                    val owner = owners.entries.firstOrNull { (_, directory) -> file.startsWith(directory) }
                    if (owner != null) {
                        perModel[owner.key] = Math.addExact(perModel.getValue(owner.key), size)
                    }
                    return FileVisitResult.CONTINUE
                }
            },
        )
        return ModelFolderFootprint(perModel, total)
    }
}

/**
 * One reading of the models folder: what each model holds, and what the folder holds in total.
 *
 * [unclaimed] is derived rather than stored, so it cannot disagree with the two numbers it comes from,
 * and it cannot be negative because both came from the same traversal.
 */
data class ModelFolderFootprint(
    val perModel: Map<ModelDescriptor, Long>,
    val total: Long,
) {
    /** Bytes under the root that no model's own directory accounts for. */
    val unclaimed: Long get() = total - perModel.values.sum()
}
