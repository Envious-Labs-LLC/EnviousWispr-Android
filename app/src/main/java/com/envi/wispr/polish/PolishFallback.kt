package com.envi.wispr.polish

import com.envi.wispr.cleanup.CleanupOptions
import com.envi.wispr.cleanup.PolishPipeline

/**
 * The one text both sides fall back to: the deterministic pipeline with no model. The engine uses
 * it for every failure outcome and the session owner for every failure it handles itself, so the
 * words a user gets cannot depend on which side failed (issue #69).
 */
object PolishFallback {
    fun deterministic(rawText: String, options: CleanupOptions): String = PolishPipeline.run(rawText, options).text
}
