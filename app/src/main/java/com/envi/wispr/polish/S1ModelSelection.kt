package com.envi.wispr.polish

import android.content.Context
import com.envi.wispr.models.ModelDeliveryStore
import com.envi.wispr.models.ModelDescriptor
import com.envi.wispr.models.ModelManifest
import com.envi.wispr.models.ModelStorage
import java.io.File

internal data class S1ModelSelection(
    val file: File,
    val installationStamp: String,
    /** The pinned revision the installed files verified against: the current one, or a previous one (#108). */
    val descriptor: ModelDescriptor,
)

internal object S1ModelSelector {
    /**
     * Which pinned S1 revision is installed, by its receipt alone (no hashing), current first, then a previous one this
     * build loads (#108). It names identity for resident reuse and qualification records; it never authorizes a first
     * load, which `resolve` verifies in full.
     */
    fun installedDescriptor(context: Context): ModelDescriptor? = installedDescriptor(ModelStorage.root(context))

    fun installationStamp(context: Context): String? = installationStamp(ModelStorage.root(context))

    fun resolve(context: Context): S1ModelSelection? = resolve(ModelStorage.root(context))

    internal fun installedDescriptor(root: File, model: ModelDescriptor = ModelManifest.s1): ModelDescriptor? = runCatching {
        val store = ModelDeliveryStore(root)
        (listOf(model) + model.previous).firstOrNull { store.installationStamp(it) != null }
    }.getOrNull()

    internal fun installationStamp(root: File, model: ModelDescriptor = ModelManifest.s1): String? = runCatching {
        installedDescriptor(root, model)?.let { ModelDeliveryStore(root).installationStamp(it) }
            ?.let(com.envi.wispr.processing.ProcessingEnvironment::identity)
    }.getOrNull()

    internal fun resolve(root: File, model: ModelDescriptor = ModelManifest.s1): S1ModelSelection? {
        val before = installationStamp(root, model) ?: return null
        val descriptor = ModelDeliveryStore(root).usableDescriptor(model) ?: return null
        val after = installationStamp(root, model)
        if (before != after) return null
        return S1ModelSelection(File(File(root, model.id), S1Config.MODEL_FILENAME), installationStamp = before, descriptor = descriptor)
    }
}
