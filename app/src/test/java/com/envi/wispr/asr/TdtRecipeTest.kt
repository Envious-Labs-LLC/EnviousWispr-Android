package com.envi.wispr.asr

import com.envi.wispr.asr.TdtRecipe.Token
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Product outcome (#374): when these fail, the user sees missing or repeated words where a long take was split, or
 * a final clause lost. Each case asserts a literal output of one branch of the macOS batch recipe (FluidAudio fork
 * `b29591ad`, cited in `TdtRecipe`), driven by a fake model.
 */
class TdtRecipeTest {
    /** Vocabulary: 0 "▁the", 1 "▁The", 2 "▁cat", 3 "s", 4 ".", 5 "▁sat", 6 "▁on", 7 "▁mat", 8 "?", 9 blank. */
    private val vocab = listOf("▁the", "▁The", "▁cat", "s", ".", "▁sat", "▁on", "▁mat", "?", "<blk>")
    private val blank = 9

    /**
     * A scripted model: `script[localFrame]` is the (token, durationBin) the joint answers at that frame, else blank
     * with duration 1. Records every encode call and the state each window's FIRST step received.
     */
    private inner class FakeRunner(val script: (window: Int, frame: Int, previous: Int) -> Pair<Int, Int> = { _, _, _ -> blank to 1 }) : TdtRunner {
        override val vocab: List<String> = this@TdtRecipeTest.vocab
        val encodes = ArrayList<Pair<Int, Int>>() // (padded size, real length)
        val firstStepStates = ArrayList<DecoderState>()
        val zeroStates = ArrayList<DecoderState>()
        private var window = -1
        private var stepped = false

        override fun encode(samples: FloatArray, length: Int): Encoded {
            encodes += samples.size to length
            window++
            stepped = false
            val frames = (length + 1279) / 1280
            return object : Encoded { override val frames = frames; override fun close() = Unit }
        }

        override fun initialState() = DecoderState(FloatArray(1), FloatArray(1)).also { zeroStates += it }

        override fun step(encoded: Encoded, frame: Int, previousToken: Int, state: DecoderState): Step {
            if (!stepped) { firstStepStates += state; stepped = true }
            val (token, bin) = script(window, frame, previousToken)
            return Step(token, bin, DecoderState(FloatArray(1) { frame.toFloat() }, FloatArray(1)))
        }

        override fun close() = Unit
    }

    private fun silence(seconds: Double) = FloatArray((seconds * 16_000).toInt())
    private fun loud(seconds: Double) = FloatArray((seconds * 16_000).toInt()) { if (it % 2 == 0) 0.1f else -0.1f }

    @Test fun aTakeUnderTheMinimumIsEmptyAndDecodesNothing() {
        val runner = FakeRunner()
        assertEquals("", TdtRecipe(runner).transcribe(FloatArray(4_799)).text)
        assertTrue(runner.encodes.isEmpty())
    }

    /** One window up to 15 s: frame-aligned real length, padded to the model window (AsrManager+Transcription :15-37). */
    @Test fun aShortTakeIsOneFrameAlignedPaddedWindow() {
        val runner = FakeRunner()
        TdtRecipe(runner).transcribe(loud(3.0) + FloatArray(100))
        // 48,100 samples round up to 38 whole frames = 48,640.
        assertEquals(listOf(240_000 to 48_640), runner.encodes)
    }

    /**
     * Fixed-stride layout (ChunkProcessor :505-635): chunks start every 206,080 samples; chunks after the first carry
     * 1,280 samples of mel context; the short last chunk is backfilled with real audio to a full chunk.
     */
    @Test fun aLongTakeUsesTheFixedStrideLayoutWithABackfilledLastChunk() {
        val runner = FakeRunner()
        val take = loud(40.0)
        val result = TdtRecipe(runner).transcribe(take)
        assertEquals(3, result.windows)
        assertEquals(listOf(238_080, 238_080 + 1_280, 238_080), runner.encodes.map { it.second })
    }

    /** MUTATION: carry the decoder state across windows (the fake then sees a non-initial state). */
    @Test fun everyWindowStartsFromAFreshDecoderState() {
        val runner = FakeRunner()
        TdtRecipe(runner).transcribe(loud(40.0))
        assertEquals(3, runner.firstStepStates.size)
        runner.firstStepStates.forEach { first -> assertTrue(runner.zeroStates.any { it === first }) }
    }

    /** A non-blank whose duration jumps past the real frames is not emitted (TdtDecoderV3 :409). */
    @Test fun aTokenWhoseDurationLeavesTheAudioIsNotEmitted() {
        // 1 s = 13 frames; at frame 12 the joint says "cat" with duration 4, which lands past the last frame.
        val runner = FakeRunner { _, frame, _ -> if (frame == 12) 2 to 4 else blank to 1 }
        assertEquals("", TdtRecipe(runner).transcribe(loud(1.0)).text)
        val kept = FakeRunner { _, frame, _ -> if (frame == 5) 2 to 1 else blank to 1 }
        assertEquals("cat", TdtRecipe(kept).transcribe(loud(1.0)).text)
    }

    /** Last-window finalisation emits punctuation only (fork carry #1792): a word there is suppressed. */
    @Test fun finalisationAddsPunctuationButNeverAWord() {
        val punctuation = FakeRunner { _, frame, previous -> if (frame == 3) 2 to 1 else if (previous == 2 && frame >= 12) 4 to 1 else blank to 1 }
        assertEquals("cat.", TdtRecipe(punctuation).transcribe(loud(1.0)).text)
        val word = FakeRunner { _, frame, previous -> if (frame == 3) 2 to 1 else if (previous == 2 && frame >= 12) 5 to 1 else blank to 1 }
        assertEquals("cat", TdtRecipe(word).transcribe(loud(1.0)).text)
    }

    // ---- merge branches, on literal token streams (frames are 80 ms)

    private val recipe = TdtRecipe(FakeRunner())

    /** Contiguous overlap match: the right side's copy of the shared words is dropped (mergeChunks :1076-1153). */
    @Test fun aContiguousOverlapIsSplicedOnce() {
        val left = listOf(Token(0, 100, 1), Token(2, 104, 1), Token(5, 108, 1), Token(6, 112, 1))
        val right = listOf(Token(5, 108, 1), Token(6, 112, 1), Token(0, 116, 1), Token(7, 120, 1))
        assertEquals("the cat sat on the mat", recipe.text(recipe.merge(left, right)))
    }

    /** A case-only variant counts as the same token when matching the overlap. */
    @Test fun aCaseVariantMatchesInTheOverlap() {
        val left = listOf(Token(2, 100, 1), Token(1, 104, 1), Token(5, 108, 1))
        val right = listOf(Token(0, 104, 1), Token(5, 108, 1), Token(7, 112, 1))
        assertEquals("cat The sat mat", recipe.text(recipe.merge(left, right)))
    }

    /** No shared tokens: the streams are cut at the time midpoint, on word starts (mergeByMidpoint). */
    @Test fun noMatchSplitsAtTheMidpoint() {
        val left = listOf(Token(2, 100, 1), Token(5, 110, 1), Token(6, 120, 1))
        val right = listOf(Token(0, 105, 1), Token(7, 115, 1), Token(8, 125, 1))
        // leftEnd 9.76 s, rightStart 8.4 s, midpoint 9.08 s = frame 113.5: left keeps frames 100 and 110, right from 115.
        assertEquals("cat sat mat?", recipe.text(recipe.merge(left, right)))
    }

    /** Timestamps never go backwards, and token order is never re-sorted (ChunkProcessor :674-685). */
    @Test fun timestampsAreClampedNotResorted() {
        val out = recipe.monotonic(listOf(Token(2, 10, 1), Token(3, 8, 1), Token(5, 12, 1)))
        assertEquals(listOf(2, 3, 5), out.map { it.id })
        assertEquals(listOf(10, 10, 12), out.map { it.frame })
    }

    /**
     * A case-only duplicate at a seam keeps one copy (:857-938): the lower-case copy wins over a capitalised earlier one,
     * otherwise the earlier one stays; a sentence end before the second protects both.
     */
    @Test fun aCaseOnlyDuplicateWordAtASeamCollapses() {
        assertEquals("the cat", recipe.text(recipe.collapseSeamDuplicates(listOf(Token(0, 10, 1), Token(1, 12, 1), Token(2, 14, 1)))))
        assertEquals("the cat", recipe.text(recipe.collapseSeamDuplicates(listOf(Token(1, 10, 1), Token(0, 12, 1), Token(2, 14, 1)))))
        assertEquals("the. The", recipe.text(recipe.collapseSeamDuplicates(listOf(Token(0, 10, 1), Token(4, 11, 1), Token(1, 12, 1)))))
    }

    /**
     * Seam-gap repair re-decodes a gap with speech in it and splices only what lies strictly inside the gap
     * (:1394-1514). MUTATION: skip the repair (the gap stays empty).
     */
    @Test fun aSilentMergeGapWithSpeechInItIsRepaired() {
        // 3 s of speech: tokens at 0.0 s and 2.8 s, nothing between; the repair window finds "sat" at frame 17.
        val samples = loud(3.0)
        val runner = FakeRunner { _, frame, _ -> if (frame == 17) 5 to 1 else blank to 1 }
        val repaired = TdtRecipe(runner).repairSeamGaps(samples, listOf(Token(2, 0, 1), Token(7, 35, 1)))
        assertEquals("cat sat mat", TdtRecipe(runner).text(repaired))
        val quiet = FakeRunner { _, frame, _ -> if (frame == 17) 5 to 1 else blank to 1 }
        val untouched = TdtRecipe(quiet).repairSeamGaps(silence(3.0), listOf(Token(2, 0, 1), Token(7, 35, 1)))
        assertEquals("a gap with no speech is left alone", "cat mat", TdtRecipe(quiet).text(untouched))
    }
}
