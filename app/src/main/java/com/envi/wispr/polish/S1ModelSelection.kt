package com.envi.wispr.polish

import android.content.Context
import android.content.pm.ApplicationInfo
import com.envi.wispr.models.ModelManifest
import com.envi.wispr.models.ModelStorage
import java.io.File
import java.security.MessageDigest

internal data class S1ModelSelection(
    val file: File,
    val computeUnits: List<String>,
    val npuOptimized: Boolean,
)

internal object S1ModelSelector {
    fun resolve(context: Context): S1ModelSelection? {
        if (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) {
            val override = File(File(context.noBackupFilesDir, "development-models"), S1Config.NPU_MODEL_FILENAME)
            if (override.isFile &&
                override.length() == S1Config.NPU_MODEL_BYTES &&
                sha256(override) == S1Config.NPU_MODEL_SHA256
            ) {
                return S1ModelSelection(override, listOf("npu", "gpu", "cpu"), npuOptimized = true)
            }
        }

        if (!ModelStorage.isReady(context, ModelManifest.s1)) return null
        return S1ModelSelection(
            File(ModelStorage.directory(context, ModelManifest.s1), S1Config.MODEL_FILENAME),
            listOf("gpu", "cpu"),
            npuOptimized = false,
        )
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
