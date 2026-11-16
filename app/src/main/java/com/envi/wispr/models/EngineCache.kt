package com.envi.wispr.models

import android.content.Context
import java.io.File
import java.nio.file.Files

/**
 * Where an engine keeps files it derives from a model, outside the verified model folder (#531): verification
 * refuses any extra file next to the model (`ModelDeliveryStore.verifies`). One folder per model, holding one
 * subfolder per (revision, engine build), because MNN's weight cache is not keyed by model content
 * (`third_party/MNN/source/backend/cpu/CPUBackend.cpp` cache prefix): a cache from another revision or build must
 * never be read. Every removal here deletes entry by entry, never recursively (`tools-and-apps.md`
 * RULE: never-unattended-recursive-delete).
 */
internal object EngineCache {
    /** The one subfolder a cache folder may hold: MNN's saved system-prompt states (#531 plan §3.6), files only. */
    const val PREFIX_FOLDER = "prefix"

    /** MNN's per-run KV scratch files: 16 hex digits plus `.k`/`.v`, named by object address, never reused. */
    private val SCRATCH = Regex("[0-9A-Fa-f]{16}\\.[kv]")

    fun root(context: Context): File = File(context.applicationContext.noBackupFilesDir, "model-cache")
    fun modelFolder(context: Context, model: ModelDescriptor): File = modelFolder(root(context), model)
    fun folder(context: Context, model: ModelDescriptor, engineBuild: String): File = folder(root(context), model, engineBuild)
    fun bytes(context: Context, model: ModelDescriptor): Long = bytes(root(context), model)
    fun sweepExcept(context: Context, model: ModelDescriptor, keep: File?): Boolean = sweepExcept(root(context), model, keep)
    fun removeAll(context: Context, model: ModelDescriptor): Boolean = removeAll(root(context), model)

    internal fun modelFolder(root: File, model: ModelDescriptor): File = File(root, model.id)
    internal fun folder(root: File, model: ModelDescriptor, engineBuild: String): File =
        File(modelFolder(root, model), "${model.pinnedRevision}-$engineBuild")

    /** Bytes the model's cache folders hold now (their files' lengths, including the prefix subfolder), for storage lines. */
    internal fun bytes(root: File, model: ModelDescriptor): Long =
        modelFolder(root, model).listFiles().orEmpty().sumOf { sub -> filesIn(sub).sumOf { it.length() } + filesIn(File(sub, PREFIX_FOLDER)).sumOf { it.length() } }

    /** Deletes KV scratch files a killed process left in [cacheFolder]; returns how many were removed. */
    fun sweepScratch(cacheFolder: File): Int =
        filesIn(cacheFolder).count { it.name.matches(SCRATCH) && it.delete() }

    private fun filesIn(folder: File): List<File> =
        folder.listFiles().orEmpty().filter { it.isFile && !Files.isSymbolicLink(it.toPath()) }

    /** Deletes every cache subfolder of [model] except [keep]; false if anything could not be removed. */
    internal fun sweepExcept(root: File, model: ModelDescriptor, keep: File?): Boolean {
        var clean = true
        modelFolder(root, model).listFiles().orEmpty().filter { it != keep }.forEach { sub -> clean = removeFolder(sub) && clean }
        return clean
    }

    /** Deletes all of [model]'s cache folders and the model's own cache folder, for model removal. */
    internal fun removeAll(root: File, model: ModelDescriptor): Boolean {
        val clean = sweepExcept(root, model, keep = null)
        val folder = modelFolder(root, model)
        return clean && (!folder.exists() || folder.delete())
    }

    /**
     * A cache folder holds files and at most the [PREFIX_FOLDER] subfolder of files. Anything else (another
     * subfolder, a link) is left in place and reported.
     */
    private fun removeFolder(folder: File, allowPrefix: Boolean = true): Boolean {
        if (Files.isSymbolicLink(folder.toPath())) return false
        if (!folder.isDirectory) return folder.delete()
        var clean = true
        folder.listFiles().orEmpty().forEach { entry ->
            clean = when {
                Files.isSymbolicLink(entry.toPath()) -> false
                entry.isFile -> entry.delete() && clean
                allowPrefix && entry.isDirectory && entry.name == PREFIX_FOLDER -> removeFolder(entry, allowPrefix = false) && clean
                else -> false
            }
        }
        return clean && folder.delete()
    }
}
