package com.envi.wispr.polish

import android.content.Context
import com.envi.wispr.models.ModelManifest
import com.envi.wispr.models.ModelStorage
import java.io.File

internal data class S1ModelSelection(
    val file: File,
    val computeUnits: List<String>,
    val npuOptimized: Boolean,
)

internal object S1ModelSelector {
    fun resolve(context: Context): S1ModelSelection? {
        // Same three conditions as before, now asked of `DevelopmentPolishModel` so the path and the
        // qualification test have ONE owner. The Models screen needs the same path to show what the
        // file costs, and a second copy of it is how the file stayed invisible.
        if (DevelopmentPolishModel.isSupported(context) && DevelopmentPolishModel.qualifies(context)) {
            return S1ModelSelection(
                DevelopmentPolishModel.file(context),
                listOf("npu", "gpu", "cpu"),
                npuOptimized = true,
            )
        }

        if (!ModelStorage.isReady(context, ModelManifest.s1)) return null
        return S1ModelSelection(
            File(ModelStorage.directory(context, ModelManifest.s1), S1Config.MODEL_FILENAME),
            listOf("gpu", "cpu"),
            npuOptimized = false,
        )
    }
}
