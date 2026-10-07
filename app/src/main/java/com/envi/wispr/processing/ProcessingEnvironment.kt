package com.envi.wispr.processing

import android.os.Build
import com.envi.wispr.models.ModelManifest
import com.envi.wispr.polish.S1Config
import java.security.MessageDigest

/** Model/runtime/platform changes invalidate earlier canaries. No identifier leaves the device. */
internal object ProcessingEnvironment {
    val standardPolishBackends = setOf(ProcessingBackend.GPU, ProcessingBackend.CPU)
    val speechBackends = setOf(ProcessingBackend.CPU)
    fun s1ContextId(): String = identity(
        listOf(
            ModelManifest.s1.pinnedRevision,
            ModelManifest.s1.files.joinToString { checkNotNull(it.sha256) },
            "geniex-0.4.0-llama_cpp", Build.FINGERPRINT, Build.VERSION.SDK_INT.toString(),
            "${S1Config.CONTEXT_SIZE}:${S1Config.THREAD_COUNT}:${S1Config.BATCH_SIZE}:${S1Config.UBATCH_SIZE}",
        ).joinToString("|"),
    )
    internal fun identity(value: String): String = MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
        .joinToString("") { "%02x".format(it) }
}
