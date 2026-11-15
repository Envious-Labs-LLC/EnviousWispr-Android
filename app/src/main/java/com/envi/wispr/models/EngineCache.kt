package com.envi.wispr.models

import android.content.Context
import java.io.File

/**
 * Where an engine keeps files it derives from a model, outside the verified model folder (#531): verification
 * refuses any extra file next to the model (`ModelDeliveryStore.verifies`). One folder per model, holding one
 * subfolder per (revision, engine build), because MNN's weight cache is not keyed by model content
 * (`third_party/MNN/source/backend/cpu/CPUBackend.cpp` cache prefix): a cache from another revision or build must
 * never be read. Every removal here deletes entry by entry, never recursively (`tools-and-apps.md`
 * RULE: never-unattended-recursive-delete).
 */
internal object EngineCache {
    fun root(context: Context): File = File(context.applicationContext.noBackupFilesDir, "model-cache")
    fun modelFolder(context: Context, model: ModelDescriptor): File = File(root(context), model.id)
    fun folder(context: Context, model: ModelDescriptor, engineBuild: String): File =
        File(modelFolder(context, model), "${model.pinnedRevision}-$engineBuild")

    /** Bytes the model's cache folders hold now, for storage lines. */
    fun bytes(context: Context, model: ModelDescriptor): Long =
        modelFolder(context, model).listFiles().orEmpty().sumOf { sub -> sub.listFiles().orEmpty().filter { it.isFile }.sumOf { it.length() } }

    /** Deletes every cache subfolder of [model] except [keep]; returns false if anything could not be removed. */
    fun sweepExcept(context: Context, model: ModelDescriptor, keep: File?): Boolean {
        var clean = true
        modelFolder(context, model).listFiles().orEmpty().filter { it != keep }.forEach { sub -> clean = removeFolder(sub) && clean }
        return clean
    }

    /** Deletes all of [model]'s cache folders, for model removal. */
    fun removeAll(context: Context, model: ModelDescriptor): Boolean {
        val clean = sweepExcept(context, model, keep = null)
        val folder = modelFolder(context, model)
        return clean && (!folder.exists() || folder.delete())
    }

    /** A flat cache folder: files only. Anything else (a subfolder, a link) is left in place and reported. */
    private fun removeFolder(folder: File): Boolean {
        if (!folder.isDirectory) return folder.delete()
        var clean = true
        folder.listFiles().orEmpty().forEach { entry ->
            clean = if (entry.isFile && !java.nio.file.Files.isSymbolicLink(entry.toPath())) entry.delete() && clean else false
        }
        return clean && folder.delete()
    }
}
