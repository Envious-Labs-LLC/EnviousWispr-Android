package com.envi.wispr.mnn

/**
 * Small synchronous JNI surface over the pinned Alibaba MNN runtime, for EG-1 Micro (#531).
 * Calls are serialized by PolishService, which runs in its own process; [abort] and [resetAbort] are the
 * exceptions, safe from any thread while a generation runs. Failures return "ERROR: ..." like `S1Native`.
 */
object MnnNative {
    init {
        System.loadLibrary("enviouswispr-mnn")
    }

    /**
     * Loads the model whose folder holds [configPath] (must be that folder's `config.json`) on [backend] ("cpu" or
     * "opencl"), keeping the weight cache in [cacheDir], which must exist outside the model folder. A non-empty
     * [prefixDir] (an existing folder) turns on prefix reuse: every request then runs as its own prefix session and
     * reuses the saved system-prompt state (#531 plan §3.6). Returns "" or an error.
     */
    external fun loadModel(configPath: String, backend: String, threads: Int, cacheDir: String, prefixDir: String): String

    /** Builds or confirms the saved prefix state for [systemPrompt]; false when prefix reuse is off or it failed. */
    external fun warmPrefix(systemPrompt: String): Boolean

    /** Generates up to [maxTokens], refusing a prompt whose tokens plus [maxTokens] exceed [contextLimit]. */
    external fun generate(systemPrompt: String, userPrompt: String, maxTokens: Int, contextLimit: Int): String

    /**
     * How the last [generate] ended: [end code (0 stop token, 1 token cap, 2 aborted, 3 engine error, 4 refused),
     * generated tokens, prompt tokens, prefill ms, decode ms].
     */
    external fun lastEnd(): IntArray

    /** Clears the abort flag; call before arming a deadline. */
    external fun resetAbort()

    /** Asks the running (or next) generation to stop before its next token. */
    external fun abort()
    external fun unload()

    /** Debug libraries only: raises the abort flag right after the next prefix call (between the two prefills). */
    external fun testAbortAfterPrefixCall()

    /**
     * Debug libraries only: MNN's own one-call generation, the oracle for the step loop, on a model loaded without
     * prefix reuse. Throws in release.
     */
    external fun referenceResponse(systemPrompt: String, userPrompt: String, maxTokens: Int): String
}
