package com.envi.wispr.recovery

import com.envi.wispr.audio.PcmAudio
import com.envi.wispr.audio.RecordingLimits
import java.io.EOFException
import java.io.InputStream
import javax.crypto.SecretKey

/** One bounded PCM allocation. Only [byteCount] bytes are audio; the remainder is unused capacity. */
internal class RecoveryPcmPrefix(
    val pcm: ByteArray,
    val byteCount: Int,
    val firstSample: Long,
    val completedCapture: Boolean,
) : AutoCloseable {
    val sampleCount: Int get() = byteCount / PcmAudio.BYTES_PER_SAMPLE
    val partial: Boolean get() = firstSample != 0L || !completedCapture

    override fun close() { pcm.fill(0) }
}

/** Worker-only reader. A torn or unauthenticated suffix cannot erase an earlier verified prefix. */
internal object RecoveryAudioPrefix {
    fun read(
        input: InputStream,
        identity: RecoveryRecordIdentity,
        key: SecretKey,
        checkpoint: () -> Unit,
    ): RecoveryPcmPrefix {
        val pcm = ByteArray(RecordingLimits.MAX_AUDIO_BYTES.toInt())
        var bytes = 0
        var frames = 0L
        var firstSample = 0L
        var completed = false
        try {
            while (true) {
                checkpoint()
                val frame = try {
                    RecoveryFrameCodec.readAudioFrame(input, identity, key)
                } catch (_: EOFException) {
                    break
                } catch (failure: RecoveryFrameException) {
                    when (failure.reason) {
                        RecoveryFrameFailure.MALFORMED, RecoveryFrameFailure.WRONG_RECORD,
                        RecoveryFrameFailure.AUTHENTICATION_FAILED -> break
                        RecoveryFrameFailure.CRYPTO_UNAVAILABLE -> throw failure
                    }
                } ?: break
                try {
                    val header = frame.header
                    when (header.kind) {
                        RecoveryFrameKind.CONTEXT -> break
                        RecoveryFrameKind.AUDIO -> {
                            if (header.sequence != frames) break
                            if (frames == 0L) firstSample = header.startSample
                            if (header.startSample != firstSample + bytes / PcmAudio.BYTES_PER_SAMPLE) break
                            if (frame.payloadBytes > pcm.size - bytes) break
                            frame.copyPayloadInto(pcm, bytes)
                            bytes += frame.payloadBytes
                            frames++
                        }
                        RecoveryFrameKind.COMPLETED_CAPTURE -> {
                            completed = frames > 0L && header.sequence == frames &&
                                header.sampleCount == bytes / PcmAudio.BYTES_PER_SAMPLE &&
                                header.startSample == firstSample + header.sampleCount && input.read() == -1
                            break
                        }
                    }
                } finally {
                    frame.clearPayload()
                }
            }
            checkpoint()
            return RecoveryPcmPrefix(pcm, bytes, firstSample, completed)
        } catch (failure: Throwable) {
            pcm.fill(0)
            throw failure
        }
    }
}
