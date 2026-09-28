package com.envi.wispr.asr

/**
 * The model half of the Parakeet TDT decode (#374): everything that touches ONNX Runtime, behind a seam, so that
 * [TdtRecipe] (the macOS batch recipe) is pure Kotlin and testable with a fake.
 */
internal interface TdtRunner : AutoCloseable {
    /** Vocabulary pieces by token id, as written in `vocab.txt` (word starts carry U+2581 or a leading space). */
    val vocab: List<String>

    /** The blank token id: the last vocabulary entry. */
    val blankId: Int get() = vocab.size - 1

    /**
     * Preprocess and encode one window. [samples] is already zero-padded to the model's fixed window; [length] is the
     * real sample count the preprocessor must use for its features (the macOS layout: padded audio, real length).
     */
    fun encode(samples: FloatArray, length: Int): Encoded

    /** The zero decoder state every window starts from (a FRESH state per window, as macOS does). */
    fun initialState(): DecoderState

    /** One joint step on encoder frame [frame] given the previous token: the argmax token and duration bin. */
    fun step(encoded: Encoded, frame: Int, previousToken: Int, state: DecoderState): Step
}

/** One encoded window: [frames] frames the encoder says are valid. */
internal interface Encoded : AutoCloseable {
    val frames: Int
}

/** The prediction network's recurrent state; opaque to the recipe. */
internal class DecoderState(val first: FloatArray, val second: FloatArray)

/** One joint decision: the argmax [token], the argmax [durationBin] (0..4), and the state after emitting the token. */
internal class Step(val token: Int, val durationBin: Int, val next: DecoderState)
