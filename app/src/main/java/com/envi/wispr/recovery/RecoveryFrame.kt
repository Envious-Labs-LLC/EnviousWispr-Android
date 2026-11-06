package com.envi.wispr.recovery

import com.envi.wispr.audio.PcmAudio
import com.envi.wispr.audio.RecordingLimits
import java.io.IOException
import java.util.UUID

/** Recovery-only format budgets. None changes ordinary capture or model admission. */
internal object RecoveryFrameLimits {
    const val VERSION = 1
    const val PAYLOAD_BYTES = 32_768
    const val CONTEXT_BYTES = 16 * 1_024 * 1_024
    const val IV_BYTES = 12
    const val TAG_BITS = 128
    const val TAG_BYTES = TAG_BITS / 8
    const val AAD_BYTES = 93
    const val OVERHEAD_BYTES = AAD_BYTES + IV_BYTES + TAG_BYTES
    const val MAX_FRAME_BYTES = CONTEXT_BYTES + OVERHEAD_BYTES
    const val MAX_SAMPLES = RecordingLimits.MAX_AUDIO_BYTES / PcmAudio.BYTES_PER_SAMPLE
    const val MAX_AUDIO_FRAMES = (RecordingLimits.MAX_AUDIO_BYTES + PAYLOAD_BYTES - 1) / PAYLOAD_BYTES
}

internal enum class RecoveryFrameKind(val wireTag: Int) {
    CONTEXT(1), AUDIO(2), COMPLETED_CAPTURE(3),
}

/** Immutable digest identity. Callers cannot change a frame's authenticated context after admission. */
internal class RecoveryContextDigest private constructor(private val bytes: ByteArray) {
    fun copyBytes(): ByteArray = bytes.copyOf()

    override fun equals(other: Any?): Boolean =
        other is RecoveryContextDigest && bytes.contentEquals(other.bytes)

    override fun hashCode(): Int = bytes.contentHashCode()

    companion object {
        const val BYTES = 32

        fun of(bytes: ByteArray): RecoveryContextDigest {
            if (bytes.size != BYTES) throw RecoveryFrameException(RecoveryFrameFailure.MALFORMED)
            return RecoveryContextDigest(bytes.copyOf())
        }
    }
}

internal data class RecoveryRecordIdentity(
    val takeId: UUID,
    val generation: Long,
    val context: RecoveryContextDigest,
)

internal data class RecoveryFrameHeader(
    val identity: RecoveryRecordIdentity,
    val kind: RecoveryFrameKind,
    val sequence: Long,
    val startSample: Long,
    val sampleCount: Int,
)

internal enum class RecoveryFrameFailure { MALFORMED, WRONG_RECORD, AUTHENTICATION_FAILED, CRYPTO_UNAVAILABLE }

/** Carries a closed reason only, never input bytes, a path, or a vendor exception message/cause. */
internal class RecoveryFrameException(val reason: RecoveryFrameFailure) : IOException("Protected recording unavailable")
