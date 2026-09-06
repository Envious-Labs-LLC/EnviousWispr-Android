package com.envi.wispr.polish

import android.content.Context
import android.content.pm.ApplicationInfo
import com.envi.wispr.models.ModelFootprint
import java.io.File
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest

/**
 * The hand-placed development polish model: where it lives, whether it is there, and how to be rid of it.
 *
 * One owner for a path that used to be written inline in `S1ModelSelector`, because two things need it
 * now and they ask DIFFERENT questions about the same file.
 *
 * - **The polish path asks whether it QUALIFIES**: debuggable build, exact byte count, exact hash. Only
 *   then is it loaded, and `architecture-rules.md` FACT: npu-polish-is-a-development-override is why.
 * - **The Models screen asks whether it EXISTS**, and nothing more. A file that fails the hash still
 *   takes 469 MB, and a user cannot free space they are not shown. Surfacing only qualifying files would
 *   hide exactly the case where the file is useless AND large.
 *
 * Conflating those two is how 469 MB stayed invisible: the only code that knew the path cared only
 * whether it was loadable ([issue #21](https://github.com/Envious-Labs-LLC/EnviousWispr-Android/issues/21)).
 */
internal object DevelopmentPolishModel {

    /** The directory a development model is placed in by hand. */
    fun directory(context: Context): File =
        File(context.noBackupFilesDir, "development-models")

    /** The file itself, whether or not it is there. */
    fun file(context: Context): File =
        File(directory(context), S1Config.NPU_MODEL_FILENAME)

    /**
     * Whether this build is even allowed to use a hand-placed model.
     *
     * A release build must not offer to manage one, because it would never load it: the screen would be
     * naming a capability that build does not have.
     */
    fun isSupported(context: Context): Boolean =
        context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0

    /**
     * How much room everything in the development-models directory takes.
     *
     * The whole DIRECTORY, not only the file whose name we know. Anything else left there by hand is
     * space the user is paying for and no other screen counts it.
     *
     * **Touches the filesystem; never call it on the main thread.**
     */
    fun bytesOnDisk(context: Context): Long = ModelFootprint.bytesUnder(directory(context))

    /**
     * Whether the file present would be SELECTED by the polish path.
     *
     * **This is not a claim about what is loaded.** It inspects a file; the model actually in memory
     * lives in the `:polish` process and nothing here can see it. A card that said "polish is running
     * from this file" would be asserting something this check cannot know, and would be wrong for the
     * whole window before the first polish request. Word it as eligibility.
     *
     * Hashes 469 MB, so this is IO and belongs off the main thread with everything else here.
     */
    fun qualifies(context: Context): Boolean {
        val candidate = file(context)
        if (!candidate.isFile) return false
        if (candidate.length() != S1Config.NPU_MODEL_BYTES) return false
        return sha256(candidate) == S1Config.NPU_MODEL_SHA256
    }

    /**
     * Remove the development model directory and everything in it.
     *
     * **What it removes must match what [bytesOnDisk] counted**, or the card offers to free a number it
     * cannot free. An earlier version deleted only top-level files while the size counted the whole
     * tree, so a hand-made subfolder would have been counted and then left behind.
     *
     * Entries are visited and deleted INDIVIDUALLY rather than through an unattended recursive sweep of
     * a path a person could have touched (`tools-and-apps.md` RULE: never-unattended-recursive-delete),
     * links are never followed, and the answer comes from measuring the world afterwards rather than
     * from any exit code along the way.
     *
     * A root that is itself a link is refused outright. [bytesOnDisk] would not follow it, so deleting
     * through it would destroy files it never counted, somewhere else entirely.
     */
    fun delete(context: Context): Boolean {
        val root = directory(context)
        if (!root.exists()) return true
        val rootPath = root.toPath()
        if (Files.isSymbolicLink(rootPath)) return false

        runCatching {
            Files.walkFileTree(
                rootPath,
                object : SimpleFileVisitor<Path>() {
                    override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                        // A link is unlinked, never followed. Deleting the link removes the name here
                        // and leaves whatever it pointed at alone, which is the only safe reading.
                        Files.deleteIfExists(file)
                        return FileVisitResult.CONTINUE
                    }

                    override fun postVisitDirectory(dir: Path, exc: java.io.IOException?): FileVisitResult {
                        // Bottom-up, so a directory is empty by the time it is removed.
                        Files.deleteIfExists(dir)
                        return FileVisitResult.CONTINUE
                    }
                },
            )
        }

        // The claim is "nothing is left", and it is measured rather than assumed. A failure part way
        // through leaves this false and the caller says so.
        return !root.exists() && bytesOnDisk(context) == 0L
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
