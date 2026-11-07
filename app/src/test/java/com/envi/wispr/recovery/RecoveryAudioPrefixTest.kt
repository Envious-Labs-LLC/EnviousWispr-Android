package com.envi.wispr.recovery

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.util.UUID
import java.util.concurrent.CancellationException
import javax.crypto.KeyGenerator

/** Product Outcome: retain earlier authenticated speech after a torn suffix, with honest completeness. */
class RecoveryAudioPrefixTest {
    private val identity = RecoveryRecordIdentity(
        UUID.fromString("72035082-cfb6-4442-846c-98af22c32bd4"), 1L,
        RecoveryContextDigest.of(ByteArray(32) { 7 }),
    )
    private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private val first = byteArrayOf(1, 0, 2, 0)
    private val second = byteArrayOf(3, 0, 4, 0)

    @Test fun completeFramesAndMatchingFooterPreserveTheExactSamples() {
        read(audio(0, 0, first) + audio(1, 2, second) + footer(2, 4, 4)).use {
            assertArrayEquals(byteArrayOf(1, 0, 2, 0, 3, 0, 4, 0), it.pcm.copyOf(it.byteCount))
            assertEquals(8, it.byteCount)
            assertEquals(4, it.sampleCount)
            assertEquals(0L, it.firstSample)
            assertTrue(it.completedCapture)
            assertFalse(it.partial)
        }
    }

    @Test fun processDeathDuringTheNextFrameKeepsTheEarlierSpeech() {
        val torn = audio(1, 2, second).let { it.copyOf(it.size - 5) }
        read(audio(0, 0, first) + torn).use {
            assertArrayEquals(byteArrayOf(1, 0, 2, 0), it.pcm.copyOf(it.byteCount))
            assertTrue(it.partial)
        }
    }

    @Test fun aDamagedTagCannotPublishItsSamplesOrEraseTheEarlierSpeech() {
        val damaged = audio(1, 2, second).also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
        read(audio(0, 0, first) + damaged + footer(2, 4, 4)).use {
            assertArrayEquals(byteArrayOf(1, 0, 2, 0), it.pcm.copyOf(it.byteCount))
            assertFalse(it.completedCapture)
        }
    }

    @Test fun aMissingSequenceNeverJoinsSpeechAcrossTheGap() {
        read(audio(0, 0, first) + audio(2, 2, second)).use {
            assertArrayEquals(byteArrayOf(1, 0, 2, 0), it.pcm.copyOf(it.byteCount))
            assertTrue(it.partial)
        }
    }

    @Test fun lateAttachmentKeepsItsActualStartEvenWithACompleteFooter() {
        read(audio(0, 320, first) + footer(1, 322, 2)).use {
            assertEquals(320L, it.firstSample)
            assertEquals(2, it.sampleCount)
            assertTrue(it.completedCapture)
            assertTrue(it.partial)
        }
    }

    @Test fun trailingDataOrAnInconsistentFooterCannotClaimCompleteRecovery() {
        val base = audio(0, 0, first)
        read(base + footer(1, 2, 2) + byteArrayOf(1)).use {
            assertArrayEquals(byteArrayOf(1, 0, 2, 0), it.pcm.copyOf(it.byteCount))
            assertFalse(it.completedCapture)
        }
        read(base + footer(1, 2, 1)).use {
            assertArrayEquals(byteArrayOf(1, 0, 2, 0), it.pcm.copyOf(it.byteCount))
            assertFalse(it.completedCapture)
        }
    }

    @Test fun cancellationCannotReturnAnApparentlySuccessfulPartialInput() {
        val cancelled = CancellationException("synthetic cancellation")
        var checkpoints = 0
        val thrown = assertThrows(CancellationException::class.java) {
            RecoveryAudioPrefix.read(ByteArrayInputStream(audio(0, 0, first) + audio(1, 2, second)), identity, key) {
                if (++checkpoints == 2) throw cancelled
            }
        }
        assertSame(cancelled, thrown)
    }

    private fun read(bytes: ByteArray) = RecoveryAudioPrefix.read(ByteArrayInputStream(bytes), identity, key) {}

    private fun audio(sequence: Long, start: Long, pcm: ByteArray) = RecoveryFrameCodec.seal(
        RecoveryFrameHeader(identity, RecoveryFrameKind.AUDIO, sequence, start, pcm.size / 2), pcm, key,
    )

    private fun footer(frames: Long, end: Long, samples: Int) = RecoveryFrameCodec.seal(
        RecoveryFrameHeader(identity, RecoveryFrameKind.COMPLETED_CAPTURE, frames, end, samples), byteArrayOf(), key,
    )
}
