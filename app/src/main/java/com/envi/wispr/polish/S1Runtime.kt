package com.envi.wispr.polish

import android.content.Context

/**
 * The one surface `PolishService` drives for local S1 inference (#449). Two implementations, chosen once per
 * process by [S1RuntimeKind.current]: [S1GenieXRuntime] and [S1PortableRuntime]. PolishService serializes every
 * call onto one worker thread.
 */
internal interface S1Runtime {
    /** Comma-separated compute units that failed to construct during the last [load]. */
    val failedComputeUnits: String
    /** True when the last [load] failed before any compute unit could be tried. */
    val initializationFailed: Boolean
    /** The compute unit actually serving, `cpu`/`gpu`, or `unloaded`. */
    val activeComputeUnit: String
    /** How the last completed [generate] ended; null until one completes. */
    val lastGeneration: GenerationEnd?

    /** Loads the model on the first unit in [computeUnits] that constructs; returns a content-free description. */
    fun load(modelPath: String, computeUnits: List<String>): String

    /** The generated text, or null when [timeoutMs] expired first (treated as a hard expiry, #75). */
    fun generate(systemPrompt: String, userPrompt: String, maxTokens: Int, timeoutMs: Long): String?

    fun close()

    companion object {
        fun create(kind: S1RuntimeKind, context: Context): S1Runtime = when (kind) {
            S1RuntimeKind.GENIEX -> S1GenieXRuntime(context)
            S1RuntimeKind.PORTABLE_CPU -> S1PortableRuntime(context)
        }
    }
}
