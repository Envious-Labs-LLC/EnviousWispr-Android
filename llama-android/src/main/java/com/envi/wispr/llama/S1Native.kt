package com.envi.wispr.llama

/**
 * Small synchronous JNI surface over the pinned llama.cpp runtime.
 * Calls are serialized by PolishService, which runs in its own process; [abort] and [resetAbort] are the
 * exceptions, safe from any thread while a generation runs.
 */
object S1Native {
    init {
        System.loadLibrary("s1-polish")
    }

    /** Loads the best CPU variant for this processor; returns "<variant> | <system info>" or "ERROR: ...". */
    external fun initialize(nativeLibraryDir: String): String
    external fun loadModel(modelPath: String, contextSize: Int, threadCount: Int): String
    external fun warmPrefix(systemPrompt: String): Boolean
    external fun generate(systemPrompt: String, userPrompt: String, maxTokens: Int): String

    /** How the last [generate] ended: [end code (0 eos, 1 length, 2 aborted), generated tokens, prompt tokens, seq-0 prefix cache used (1 or 0)]. */
    external fun lastEnd(): IntArray

    /** Copy-ahead counts of the last [generate] (#510): [decode calls, guessed tokens, accepted guesses, recoveries]. */
    external fun lastCopyAheadStats(): IntArray

    /** Clears the abort flag; call before arming a deadline. */
    external fun resetAbort()

    /** Asks the running (or next) generation to stop at its next step or inside the current decode. */
    external fun abort()
    external fun unload()

    /** Debug libraries only: the bridge's text conversion Java -> UTF-8 -> Java. */
    external fun testTextRoundTrip(value: String): String

    /** Debug libraries only: the UTF-8 bytes the bridge passes to llama.cpp, as lowercase hex. */
    external fun testUtf8Hex(value: String): String

    /** Debug libraries only: decode arbitrary native bytes through toJava for invalid-UTF-8 checks. */
    external fun testInvalidUtf8ToJava(bytes: ByteArray): String
}
