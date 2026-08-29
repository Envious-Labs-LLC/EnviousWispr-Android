package com.envi.wispr.llama

/**
 * Small synchronous JNI surface over the pinned llama.cpp runtime.
 * Calls are serialized by PolishService, which runs in its own process.
 */
object S1Native {
    init {
        System.loadLibrary("s1-polish")
    }

    external fun initialize(nativeLibraryDir: String): String
    external fun loadModel(modelPath: String, contextSize: Int, threadCount: Int): String
    external fun generate(systemPrompt: String, userPrompt: String, maxTokens: Int): String
    external fun unload()
}
