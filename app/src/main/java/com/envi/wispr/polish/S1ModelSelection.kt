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

    /**
     * The configuration a load or a resident check asks for: the identity of the revision INSTALLED (#108), so a
     * resident engine on a previous revision keeps matching until the swap, then reloads once.
     */
    internal fun loadConfiguration(root: File, policy: PolishPolicy.LocalS1, model: ModelDescriptor = ModelManifest.s1) = S1LoadConfiguration(
        com.envi.wispr.processing.ProcessingEnvironment.s1ContextId(installedDescriptor(root, model) ?: model),
        com.envi.wispr.processing.ProcessingEnvironment.standardPolishBackends, policy.processing, policy.qualification,
        installationStamp(root, model).orEmpty(),
    )

    /** What a load that verified [selection] records: that revision's stamp and identity. */
    internal fun resolvedConfiguration(requested: S1LoadConfiguration, selection: S1ModelSelection) = requested.copy(
        artifactStamp = selection.installationStamp,
        contextId = com.envi.wispr.processing.ProcessingEnvironment.s1ContextId(selection.descriptor),
    )

    /** The identity a qualification result is filed under: the revision it loaded, the current one before selection. */
    internal fun qualificationContextId(selection: S1ModelSelection?): String =
        com.envi.wispr.processing.ProcessingEnvironment.s1ContextId(selection?.descriptor ?: ModelManifest.s1)

    internal fun resolve(root: File, model: ModelDescriptor = ModelManifest.s1): S1ModelSelection? {
        val before = installationStamp(root, model) ?: return null
        val descriptor = ModelDeliveryStore(root).usableDescriptor(model) ?: return null
        val after = installationStamp(root, model)
        if (before != after) return null
        return S1ModelSelection(File(File(root, model.id), S1Config.MODEL_FILENAME), installationStamp = before, descriptor = descriptor)
    }
}
