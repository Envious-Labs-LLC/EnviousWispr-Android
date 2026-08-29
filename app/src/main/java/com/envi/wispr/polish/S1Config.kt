package com.envi.wispr.polish

object S1Config {
    const val MODEL_NAME = "S1-mini"
    const val MODEL_CREATOR = "Superwhisper"
    const val MODEL_REPOSITORY = "superwhisper/s1-mini-GGUF"
    const val MODEL_REVISION = "34add00a48a2e5d24e5a4ee5405a99620a3a240c"
    const val MODEL_FILENAME = "s1-mini-q4_k_m.gguf"
    const val NPU_MODEL_FILENAME = "s1-mini-q4_0.gguf"
    const val NPU_MODEL_SHA256 = "9c6242d70bebbf3d92fbc070a51990d04253ebc494dca12cd9c4fd01b9e00461"
    const val NPU_MODEL_BYTES = 469_080_992L
    const val CONTEXT_SIZE = 2048
    const val THREAD_COUNT = 4
    const val BATCH_SIZE = 512
    const val UBATCH_SIZE = 512

    const val SYSTEM_PROMPT =
        "You are a text normalizer for speech-to-text transcripts. The input begins with a control line specifying the styling, structure, and context settings; clean the transcript to match those settings and output only the cleaned text."
}
