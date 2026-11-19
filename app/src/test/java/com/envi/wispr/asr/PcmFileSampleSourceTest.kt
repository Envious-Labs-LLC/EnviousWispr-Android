package com.envi.wispr.asr

import com.envi.wispr.audio.PcmAudio
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.EOFException
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sin

class PcmFileSampleSourceTest {

    private fun pcmBytes(samples: ShortArray): ByteArray {
        val buf = ByteBuffer.allocate(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (s in samples) buf.putShort(s)
        return buf.array()
    }

    @Test fun streamsBitForBitIdenticalSamplesAcrossMultipleChunksAndEdges() {
        // 80,000 samples (> 2x 32,768 scratch buffer) with edge PCM16 values.
        val count = 80_000
        val rawShorts = ShortArray(count) { i ->
            when (i) {
                0 -> Short.MIN_VALUE
                1 -> Short.MAX_VALUE
                2 -> 0
                3 -> -1
                else -> ((i * 7919) % 65536 - 32768).toShort()
            }
        }
        val bytes = pcmBytes(rawShorts)
        val expectedAll = PcmAudio.toFloatSamples(bytes) {}

        val file = File.createTempFile("pcm-source-test", ".pcm")
        try {
            file.writeBytes(bytes + byteArrayOf(0x7F)) // trailing odd byte ignored
            var checkpoints = 0
            RandomAccessFile(file, "r").use { raf ->
                PcmFileSampleSource(raf, count) { checkpoints++ }.use { source ->
                    assertEquals(count, source.size)
                    assertEquals(0, source.copyRange(10, 10).size)
                    assertArrayEquals(
                        expectedAll.map(Float::toRawBits).toIntArray(),
                        source.copyRange(0, count).map(Float::toRawBits).toIntArray(),
                    )
                    assertArrayEquals(
                        expectedAll.copyOfRange(12_345, 75_432).map(Float::toRawBits).toIntArray(),
                        source.copyRange(12_345, 75_432).map(Float::toRawBits).toIntArray(),
                    )
                    assertTrue("checkpoints fired across chunks", checkpoints >= 4)
                }
            }
        } finally {
            file.delete()
        }
    }

    @Test fun throwsEofWhenFileIsTruncatedAndPropagatesCancellation() {
        val file = File.createTempFile("pcm-trunc-test", ".pcm")
        try {
            file.writeBytes(ByteArray(100))
            RandomAccessFile(file, "r").use { raf ->
                PcmFileSampleSource(raf, 100).use { source ->
                    try {
                        source.copyRange(0, 100)
                        fail("expected EOFException on truncated PCM file")
                    } catch (_: EOFException) {
                        // expected
                    }
                }
            }
            RandomAccessFile(file, "r").use { raf ->
                PcmFileSampleSource(raf, 50) { throw AsrCancelled() }.use { source ->
                    try {
                        source.copyRange(0, 10)
                        fail("expected AsrCancelled")
                    } catch (_: AsrCancelled) {
                        // expected
                    }
                }
            }
        } finally {
            file.delete()
        }
    }

    @Test fun tdtRecipeMatchesFloatArrayExactlyOnMultiWindowTakeWithTrailingSilence() {
        // 35 seconds total (560,000 samples > ONE_PASS_SAMPLES 480,000): 32 s speech tone + 3 s silence.
        val totalSamples = 35 * TdtRecipe.SAMPLE_RATE
        val speechSamples = 32 * TdtRecipe.SAMPLE_RATE
        val rawShorts = ShortArray(totalSamples) { i ->
            if (i < speechSamples) (sin(i * 0.05) * 12_000.0).toInt().toShort() else 0
        }
        val bytes = pcmBytes(rawShorts)
        val arraySamples = PcmAudio.toFloatSamples(bytes) {}

        val vocab = listOf("▁hello", "▁world", "<blk>")
        val blank = 2
        fun makeRunner(): Triple<TdtRunner, MutableList<Pair<Int, IntArray>>, MutableList<Pair<AsrObserver.Phase, Int>>> {
            val encodedWindows = ArrayList<Pair<Int, IntArray>>()
            val phases = ArrayList<Pair<AsrObserver.Phase, Int>>()
            val runner = object : TdtRunner {
                override val cancellation = AsrCancellation()
                override var observer: AsrObserver = object : AsrObserver {
                    override fun phase(phase: AsrObserver.Phase, index: Int) {
                        phases += phase to index
                    }
                }
                override val vocab: List<String> = vocab
                private var win = -1
                override fun encode(samples: FloatArray, length: Int): Encoded {
                    win++
                    encodedWindows += length to samples.map(Float::toRawBits).toIntArray()
                    val frames = (length + 1279) / 1280
                    return object : Encoded { override val frames = frames; override fun close() = Unit }
                }
                override fun initialState() = DecoderState(FloatArray(1), FloatArray(1))
                override fun step(encoded: Encoded, frame: Int, previousToken: Int, state: DecoderState): Step {
                    val tok = if (frame == 5) (if (win == 0) 0 else 1) else blank
                    return Step(tok, 1, state)
                }
                override fun close() = Unit
            }
            return Triple(runner, encodedWindows, phases)
        }

        val (arrayRunner, arrayWindows, arrayPhases) = makeRunner()
        val fromArray = TdtRecipe(arrayRunner).transcribe(arraySamples)
        val file = File.createTempFile("pcm-tdt-test", ".pcm")
        try {
            file.writeBytes(bytes)
            val (fileRunner, fileWindows, filePhases) = makeRunner()
            val fromFile = RandomAccessFile(file, "r").use { raf ->
                PcmFileSampleSource(raf, totalSamples).use { source ->
                    TdtRecipe(fileRunner).transcribe(source)
                }
            }
            assertEquals(fromArray, fromFile)
            assertEquals(arrayWindows.size, fileWindows.size)
            for (w in arrayWindows.indices) {
                assertEquals("window $w length", arrayWindows[w].first, fileWindows[w].first)
                assertArrayEquals("window $w raw sample bits", arrayWindows[w].second, fileWindows[w].second)
            }
            assertEquals("checkpoint phases and global indices match identically", arrayPhases, filePhases)
            assertEquals(
                "first speech-end checkpoint reports global sample index (560_000 - 1_280)",
                558_720,
                filePhases.first { it.first == AsrObserver.Phase.SPEECH_END }.second,
            )
            assertTrue(
                "second repair RMS block starts at global sample index 81_920 (64 * 1_280)",
                filePhases.any { it == (AsrObserver.Phase.REPAIR to 81_920) },
            )
            assertTrue("multi-window path exercised", fromFile.windows >= 2)
        } finally {
            file.delete()
        }
    }
}
