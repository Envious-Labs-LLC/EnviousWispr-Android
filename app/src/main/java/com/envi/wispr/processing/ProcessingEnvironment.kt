package com.envi.wispr.processing

import android.os.Build
import com.envi.wispr.models.ModelManifest
import com.envi.wispr.polish.S1Config
import com.envi.wispr.polish.S1RuntimeKind
import java.security.MessageDigest

/** Model/runtime/platform changes invalidate earlier canaries. No identifier leaves the device. */
internal object ProcessingEnvironment {
    /**
     * Every backend a standard S1 runtime can have. Qualification bookkeeping records a runtime failure against all of
     * them, whichever runtime this phone has, exactly as before #449.
     */
    val allStandardPolishBackends = setOf(ProcessingBackend.GPU, ProcessingBackend.CPU)
    /** The S1 backends this phone's runtime can run (#449): graphics processor and processor on GenieX, processor only otherwise. */
    val standardPolishBackends: Set<ProcessingBackend> get() = S1RuntimeKind.current.backends
    val speechBackends = setOf(ProcessingBackend.CPU)
    /**
     * The identity qualification evidence is recorded under. [model] is the pinned S1 revision actually loaded (#108):
     * evidence gathered on a previous revision carries that revision's identity, so the main process, which accepts
     * only the current identity, never files it under the current model.
     */
    fun s1ContextId(model: com.envi.wispr.models.ModelDescriptor = ModelManifest.s1): String = identity(
        listOf(
            model.pinnedRevision,
            model.files.joinToString { checkNotNull(it.sha256) },
            S1RuntimeKind.current.identity, Build.FINGERPRINT, Build.VERSION.SDK_INT.toString(),
            "${S1Config.CONTEXT_SIZE}:${S1Config.THREAD_COUNT}:${S1Config.BATCH_SIZE}:${S1Config.UBATCH_SIZE}",
        ).joinToString("|"),
    )
    internal fun identity(value: String): String = MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
        .joinToString("") { "%02x".format(it) }
}
