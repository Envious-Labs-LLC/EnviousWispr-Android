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
    fun s1ContextId(): String = identity(
        listOf(
            ModelManifest.s1.pinnedRevision,
            ModelManifest.s1.files.joinToString { checkNotNull(it.sha256) },
            S1RuntimeKind.current.identity, Build.FINGERPRINT, Build.VERSION.SDK_INT.toString(),
            "${S1Config.CONTEXT_SIZE}:${S1Config.THREAD_COUNT}:${S1Config.BATCH_SIZE}:${S1Config.UBATCH_SIZE}",
        ).joinToString("|"),
    )
    internal fun identity(value: String): String = MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
        .joinToString("") { "%02x".format(it) }
}
