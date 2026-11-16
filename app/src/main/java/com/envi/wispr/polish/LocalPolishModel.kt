package com.envi.wispr.polish

import com.envi.wispr.models.ModelDescriptor
import com.envi.wispr.models.ModelManifest

/**
 * Closed set of on-device polish models a user can select (#528).
 * [wire] is the stable persisted and binder token; [descriptorId] links to [ModelManifest].
 */
internal enum class LocalPolishModel(val wire: String, val descriptorId: String) {
    S1_MINI("s1-mini", ModelManifest.s1.id),
    EG1_MICRO("eg1-micro", ModelManifest.eg1Micro.id);

    fun descriptor(): ModelDescriptor = ModelManifest.localPolish(descriptorId)

    val displayName: String get() = descriptor().displayName

    companion object {
        fun fromWireOrNull(value: String?): LocalPolishModel? =
            entries.firstOrNull { it.wire == value }

        fun fromWire(value: String?): LocalPolishModel =
            fromWireOrNull(value) ?: S1_MINI
    }
}
