package com.envi.wispr.polish

import android.content.Context
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
    fun installedDescriptor(context: Context): ModelDescriptor? = runCatching {
        val store = com.envi.wispr.models.ModelDeliveryStore(ModelStorage.root(context))
        (listOf(ModelManifest.s1) + ModelManifest.s1.previous).firstOrNull { store.installationStamp(it) != null }
    }.getOrNull()

    fun installationStamp(context: Context): String? = runCatching {
        installedDescriptor(context)?.let { com.envi.wispr.models.ModelDeliveryStore(ModelStorage.root(context)).installationStamp(it) }
            ?.let(com.envi.wispr.processing.ProcessingEnvironment::identity)
    }.getOrNull()

    fun resolve(context: Context): S1ModelSelection? {
        val before = installationStamp(context) ?: return null
        val descriptor = ModelStorage.usable(context, ModelManifest.s1) ?: return null
        val after = installationStamp(context)
        if (before != after) return null
        return S1ModelSelection(
            File(ModelStorage.directory(context, ModelManifest.s1), S1Config.MODEL_FILENAME),
            installationStamp = before,
            descriptor = descriptor,
        )
    }
}
