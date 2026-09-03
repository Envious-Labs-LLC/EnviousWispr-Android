package com.envi.wispr.vad

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest

class SileroVadSessionTest {

    private val model = File("src/main/assets/${SileroVadSession.ASSET_NAME}")

    @Test
    fun theShippedModelIsExactlyTheOneTheCodeExpects() {
        // The constants in the source are what the detector refuses to load without. If the asset and
        // the constants ever disagree, auto-stop is dead on every phone and the only symptom is that it
        // quietly never fires, so this is checked here rather than discovered there.
        assertTrue("the model asset must ship", model.isFile)
        assertEquals(SileroVadSession.EXPECTED_BYTES, model.length())

        val digest = MessageDigest.getInstance("SHA-256")
        model.inputStream().buffered().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        assertEquals(
            SileroVadSession.EXPECTED_SHA256,
            digest.digest().joinToString("") { "%02x".format(it) },
        )
    }

    @Test
    fun theShippedModelIsTheKtwoFsaVfourExportAndNotSomethingElse() {
        // Its graph decides everything downstream. Three inputs named x, h and c with outputs prob,
        // new_h and new_c is what sherpa recognises as its own v4 export, which is the branch that needs
        // no rolling overlap buffer. A v5 file would have a single state tensor and a different contract.
        val bytes = model.readBytes()
        listOf("silero-vad v4 exported to onnx by k2-fsa", "new_h", "new_c", "prob").forEach {
            assertTrue("the model must contain $it", bytes.toString(Charsets.ISO_8859_1).contains(it))
        }
    }

    @Test
    fun theLicenceTheModelRequiresIsShippedWithIt() {
        // MIT requires the notice travel with the software, and this file is what the app shows on its
        // own licences screen.
        val notices = File("src/main/assets/THIRD_PARTY_NOTICES.txt").readText()
        assertTrue(notices.contains("Silero VAD"))
        assertTrue(notices.contains("Copyright (c) 2020-present Silero Team"))
        assertTrue(notices.contains("MIT License"))
    }

    @Test
    fun decodingPcmMatchesTheSharedDecoderExactly() {
        // Two decoders for one wire format is how the detector and the speech engine end up hearing
        // different audio. Compared against the one the capture path already uses.
        val pcm = ByteArray(1024) { ((it * 37) % 251 - 125).toByte() }
        val expected = com.envi.wispr.audio.PcmAudio.toFloatSamples(pcm)

        val actual = FloatArray(SilenceStopDetector.SAMPLES_PER_BLOCK)
        val written = SileroVadSession.decodeInto(pcm, actual)

        assertEquals(expected.size, written)
        for (i in expected.indices) {
            assertEquals("sample $i", expected[i], actual[i], 0f)
        }
    }

    @Test
    fun decodingNeverWritesPastTheBufferItWasGiven() {
        val oversized = ByteArray(SilenceStopDetector.SAMPLES_PER_BLOCK * 2 + 4096)
        val out = FloatArray(SilenceStopDetector.SAMPLES_PER_BLOCK)
        assertEquals(SilenceStopDetector.SAMPLES_PER_BLOCK, SileroVadSession.decodeInto(oversized, out))

        val odd = ByteArray(1023)
        assertEquals("a trailing half sample is ignored", 511, SileroVadSession.decodeInto(odd, out))
    }

    @Test
    fun theCallDeadlineMatchesTheRingItIsSizedAgainst() {
        // Eight blocks of 256 ms is 2.048 s. A deadline longer than the ring would let the caller run
        // out of slots before the callee gave up.
        assertEquals(2_000L, SilenceVadService.CALL_DEADLINE_MS)
        assertTrue(
            SilenceVadService.CALL_DEADLINE_MS <=
                (8 * SilenceStopDetector.BLOCK_SECONDS * 1000).toLong(),
        )
    }
}
