package com.envi.wispr.models

import java.io.IOException

/**
 * Why a model delivery attempt did not end READY, chosen at the catch site that KNOWS (issue #176,
 * plan §3.1). Until this existed every `IOException` collapsed to one sentence, so a bad mirror, a
 * full disk and a corrupt download were one bar on a chart. Closed and pinned: the wire value is the
 * lowercase name, and a reader groups by it.
 */
internal enum class DeliveryFailureReason {
    /** The manifest says the model is not available on this build. */
    MANIFEST_UNAVAILABLE,
    /** The connection could not be opened, or died mid-stream: the network, not the server's answer. */
    TRANSPORT,
    /** The source answered with a status outside 2xx/3xx. */
    HTTP_STATUS,
    /** A redirect was missing, unsafe, or one too many. */
    REDIRECT_REFUSED,
    /** The source answered a whole-file request with a partial body. */
    PARTIAL_RESPONSE,
    /** The bytes arrived but the size or hash did not match the manifest; the staging was quarantined. */
    INTEGRITY_MISMATCH,
    /** The phone has no room for the model. */
    DISK_FULL,
    /** A finished file or the previous model could not be moved into place. */
    STAGING_FAILED,
    /** The verified staging directory could not become the model directory atomically. */
    ADMISSION_FAILED,
    CANCELLED,
    PAUSED,
    ;

    val wire: String get() = name.lowercase()

    companion object {
        /**
         * A storage or transport exception's reason: a typed [ModelDeliveryException] names its own; a
         * bare `IOException` whose message names the no-space errno is the disk; anything else is the
         * network. Never inferred from prose beyond that one errno.
         */
        fun of(error: IOException): DeliveryFailureReason = when {
            error is ModelDeliveryException -> error.reason
            error.message?.contains("ENOSPC") == true -> DISK_FULL
            else -> TRANSPORT
        }
    }
}

/** An `IOException` that already knows why: raised where the cause is decided, read once at the catch. */
internal class ModelDeliveryException(val reason: DeliveryFailureReason, message: String) : IOException(message)

/**
 * Which roof served the bytes, as a closed token for the `source_host` property: the host string
 * itself stays in the log. Decided from the manifest's two hosts; anything else is `unknown`.
 */
internal enum class ModelSourceHost(val wire: String) {
    MIRROR("mirror"),
    HUGGING_FACE("huggingface"),
    UNKNOWN("unknown"),
    ;

    companion object {
        const val MIRROR_HOST = "models.enviouslabs.co"
        const val HUGGING_FACE_HOST = "huggingface.co"

        fun of(host: String?): ModelSourceHost = when {
            host == null -> UNKNOWN
            host == MIRROR_HOST -> MIRROR
            host == HUGGING_FACE_HOST || host.endsWith(".$HUGGING_FACE_HOST") -> HUGGING_FACE
            else -> UNKNOWN
        }
    }
}
