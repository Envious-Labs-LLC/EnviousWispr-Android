package com.envi.wispr.ui

import com.envi.wispr.models.ModelDescriptor
import com.envi.wispr.models.ModelManifest
import com.envi.wispr.polish.LocalPolishModel
import com.envi.wispr.providers.PolishMode
import com.envi.wispr.providers.capabilities
import com.envi.wispr.providers.ui.ProviderSettingsUiState

/**
 * Pure readiness and troubleshooting summaries for the Permissions / Readiness page (#35).
 *
 * Keeps model, provider, and storage status logic out of Compose so every state and its plain-English
 * troubleshooting guidance are verified directly in unit tests.
 */
internal data class ReadinessRowState(
    val title: String,
    val subtitle: String,
    val ready: Boolean?,
)

internal sealed interface StorageReadiness {
    data object Checking : StorageReadiness
    data object Unavailable : StorageReadiness
    data class Measured(
        val availableBytes: Long,
        /** Remaining engine-cache bytes per model id, measured lock-free from `EngineCache`. */
        val remainingEngineCacheBytesByModelId: Map<String, Long> = emptyMap(),
    ) : StorageReadiness
}

internal object ReadinessDiagnostics {
    /** Matches `ModelDeliveryWorker`'s working headroom for downloads and model caches. */
    const val LOW_STORAGE_THRESHOLD_BYTES: Long = 128L * 1024L * 1024L

    fun localPolishModelReady(readiness: AppReadiness, model: LocalPolishModel): Boolean = when (model) {
        LocalPolishModel.S1_MINI -> readiness.onboardingPolishModelReady
        LocalPolishModel.EG1_MICRO -> readiness.eg1PolishModelReady
    }

    fun cloudProviderReady(providerSettings: ProviderSettingsUiState): Boolean {
        if (providerSettings.loading) return false
        val capabilities = providerSettings.provider.capabilities()
        return if (capabilities.requiresEndpoint) {
            providerSettings.configured && providerSettings.endpoint.isNotBlank()
        } else {
            providerSettings.credentialStored
        }
    }

    /** Whether the selected polish mode is ready (Off is always ready once loaded). */
    fun polishReadyForMode(
        readiness: AppReadiness,
        providerSettings: ProviderSettingsUiState,
    ): Boolean = when (providerSettings.mode) {
        PolishMode.OFF -> !providerSettings.loading
        PolishMode.OFFLINE_S1 -> !providerSettings.loading &&
            readiness.modelsChecked &&
            localPolishModelReady(readiness, providerSettings.localPolishModel)
        PolishMode.PROVIDER -> cloudProviderReady(providerSettings)
    }

    /**
     * Whether core dictation setup is satisfied for the active polish mode. Cloud polish is optional
     * enhancement (dictation still works with basic cleanup if a cloud key is missing), so only an
     * unready local polish model blocks core setup when on-device polish is selected.
     */
    fun coreReadyForMode(
        readiness: AppReadiness,
        providerSettings: ProviderSettingsUiState,
    ): Boolean {
        val localPolishSatisfied = when (providerSettings.mode) {
            PolishMode.OFF, PolishMode.PROVIDER -> true
            PolishMode.OFFLINE_S1 -> !providerSettings.loading &&
                readiness.modelsChecked &&
                localPolishModelReady(readiness, providerSettings.localPolishModel)
        }
        return readiness.microphoneGranted &&
            readiness.modelsChecked &&
            readiness.speechModelReady &&
            localPolishSatisfied
    }

    fun speechModelRow(readiness: AppReadiness): ReadinessRowState {
        val modelName = ModelManifest.parakeet.displayName
        return when {
            !readiness.modelsChecked -> ReadinessRowState(
                title = "Speech model",
                subtitle = "Checking downloaded speech model.",
                ready = null,
            )
            readiness.speechModelReady -> ReadinessRowState(
                title = "Speech model",
                subtitle = "$modelName is ready for offline dictation.",
                ready = true,
            )
            else -> ReadinessRowState(
                title = "Speech model",
                subtitle = "$modelName isn't ready yet. Tap to download or repair it in Transcription.",
                ready = false,
            )
        }
    }

    fun polishAndProviderRow(
        readiness: AppReadiness,
        providerSettings: ProviderSettingsUiState,
    ): ReadinessRowState = when (providerSettings.mode) {
        PolishMode.OFF -> if (providerSettings.loading) {
            ReadinessRowState(
                title = "Polish and providers",
                subtitle = "Checking polish and provider settings.",
                ready = null,
            )
        } else {
            ReadinessRowState(
                title = "Polish and providers",
                subtitle = "AI polish is off. Dictation uses basic cleanup without AI polish.",
                ready = true,
            )
        }

        PolishMode.OFFLINE_S1 -> {
            val modelName = providerSettings.localPolishModel.displayName
            when {
                providerSettings.loading || !readiness.modelsChecked -> ReadinessRowState(
                    title = "Polish and providers",
                    subtitle = "Checking on-device polish model.",
                    ready = null,
                )
                localPolishModelReady(readiness, providerSettings.localPolishModel) -> ReadinessRowState(
                    title = "Polish and providers",
                    subtitle = "On-device polish ($modelName) is ready.",
                    ready = true,
                )
                else -> ReadinessRowState(
                    title = "Polish and providers",
                    subtitle = "On-device polish ($modelName) isn't ready yet. Dictation will use basic cleanup without AI polish until you download or repair it in AI Polish.",
                    ready = false,
                )
            }
        }

        PolishMode.PROVIDER -> {
            val capabilities = providerSettings.provider.capabilities()
            when {
                providerSettings.loading -> ReadinessRowState(
                    title = "Polish and providers",
                    subtitle = "Checking cloud polish setup.",
                    ready = null,
                )
                cloudProviderReady(providerSettings) -> ReadinessRowState(
                    title = "Polish and providers",
                    subtitle = "Cloud polish with ${capabilities.displayName} is configured.",
                    ready = true,
                )
                !providerSettings.configured -> ReadinessRowState(
                    title = "Polish and providers",
                    subtitle = "Cloud polish is selected, but setup isn't finished. Finish cloud setup in AI Polish.",
                    ready = false,
                )
                else -> {
                    val missing = if (capabilities.requiresEndpoint) "endpoint" else "API key"
                    ReadinessRowState(
                        title = "Polish and providers",
                        subtitle = "Cloud polish is set to ${capabilities.displayName}, but no $missing is saved. Tap to finish setup in AI Polish.",
                        ready = false,
                    )
                }
            }
        }
    }

    /**
     * Bytes of free storage needed for working headroom, any missing required on-device model
     * downloads, and any unbuilt engine cache for the selected local model (even after the model
     * files themselves are verified). Returns null while model or provider readiness is checking.
     */
    fun requiredStorageBytes(
        readiness: AppReadiness,
        providerSettings: ProviderSettingsUiState,
        remainingEngineCacheBytesByModelId: Map<String, Long> = emptyMap(),
    ): Long? {
        if (!readiness.modelsChecked || providerSettings.loading) return null
        fun remainingCache(model: ModelDescriptor): Long =
            (remainingEngineCacheBytesByModelId[model.id] ?: model.engineCacheBytes).coerceAtLeast(0L)

        var needed = LOW_STORAGE_THRESHOLD_BYTES
        if (!readiness.speechModelReady) {
            needed += ModelManifest.parakeet.files.sumOf { it.expectedBytes }
        }
        needed += remainingCache(ModelManifest.parakeet)

        if (providerSettings.mode == PolishMode.OFFLINE_S1) {
            val selectedPolish = providerSettings.localPolishModel.descriptor()
            if (!localPolishModelReady(readiness, providerSettings.localPolishModel)) {
                needed += selectedPolish.files.sumOf { it.expectedBytes }
            }
            needed += remainingCache(selectedPolish)
        }
        return needed
    }

    /**
     * Bytes of free storage needed right now for working headroom plus any unbuilt engine cache of
     * already-installed active models (independent of missing downloads).
     */
    fun requiredWorkingBytes(
        readiness: AppReadiness,
        providerSettings: ProviderSettingsUiState,
        remainingEngineCacheBytesByModelId: Map<String, Long> = emptyMap(),
    ): Long {
        fun remainingCache(model: ModelDescriptor): Long =
            (remainingEngineCacheBytesByModelId[model.id] ?: model.engineCacheBytes).coerceAtLeast(0L)

        var needed = LOW_STORAGE_THRESHOLD_BYTES
        if (readiness.modelsChecked && readiness.speechModelReady) {
            needed += remainingCache(ModelManifest.parakeet)
        }
        if (!providerSettings.loading &&
            readiness.modelsChecked &&
            providerSettings.mode == PolishMode.OFFLINE_S1 &&
            localPolishModelReady(readiness, providerSettings.localPolishModel)
        ) {
            needed += remainingCache(providerSettings.localPolishModel.descriptor())
        }
        return needed
    }

    fun storageRow(
        storage: StorageReadiness,
        readiness: AppReadiness,
        providerSettings: ProviderSettingsUiState,
    ): ReadinessRowState = when (storage) {
        StorageReadiness.Checking -> ReadinessRowState(
            title = "Storage",
            subtitle = "Checking free storage on this phone.",
            ready = null,
        )
        StorageReadiness.Unavailable -> ReadinessRowState(
            title = "Storage",
            subtitle = "Couldn't check free storage. Tap to inspect model storage.",
            ready = null,
        )
        is StorageReadiness.Measured -> {
            val available = storage.availableBytes.coerceAtLeast(0L)
            val freeText = formatModelBytes(available)
            val workingRequired = requiredWorkingBytes(readiness, providerSettings, storage.remainingEngineCacheBytesByModelId)
            val totalRequired = requiredStorageBytes(readiness, providerSettings, storage.remainingEngineCacheBytesByModelId)
            when {
                available < LOW_STORAGE_THRESHOLD_BYTES -> ReadinessRowState(
                    title = "Storage",
                    subtitle = "Only $freeText free. Low storage can block model downloads or recordings. Tap to review model storage.",
                    ready = false,
                )
                available < workingRequired -> ReadinessRowState(
                    title = "Storage",
                    subtitle = "Only $freeText free. At least ${formatModelBytes(workingRequired)} is needed for working files. Tap to review model storage.",
                    ready = false,
                )
                totalRequired != null && available < totalRequired -> ReadinessRowState(
                    title = "Storage",
                    subtitle = "$freeText free on this phone. A fresh download needs about ${formatModelBytes(totalRequired)}; paused downloads need less. Tap to review model storage.",
                    ready = null,
                )
                totalRequired == null -> ReadinessRowState(
                    title = "Storage",
                    subtitle = "$freeText free on this phone. Tap to see space used by models.",
                    ready = null,
                )
                else -> ReadinessRowState(
                    title = "Storage",
                    subtitle = "$freeText free on this phone. Tap to see space used by models.",
                    ready = true,
                )
            }
        }
    }
}
