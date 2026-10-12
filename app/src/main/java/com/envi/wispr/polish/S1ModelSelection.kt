package com.envi.wispr.polish

import android.content.Context
import com.envi.wispr.models.ModelManifest
import com.envi.wispr.models.ModelStorage
import java.io.File

internal data class S1ModelSelection(
    val file: File,
    val installationStamp: String,
)

internal object S1ModelSelector {
    fun installationStamp(context: Context): String? = runCatching { com.envi.wispr.models.ModelDeliveryStore(ModelStorage.root(context))
        .installationStamp(ModelManifest.s1)?.let(com.envi.wispr.processing.ProcessingEnvironment::identity) }.getOrNull()
    fun resolve(context: Context): S1ModelSelection? {
        val before = installationStamp(context) ?: return null
        if (!ModelStorage.isReady(context, ModelManifest.s1)) return null
        val after = installationStamp(context)
        if (before != after) return null
        return S1ModelSelection(
            File(ModelStorage.directory(context, ModelManifest.s1), S1Config.MODEL_FILENAME),
            installationStamp = before,
        )
    }
}
