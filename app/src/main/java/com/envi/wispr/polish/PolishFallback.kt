package com.envi.wispr.polish

import com.envi.wispr.cleanup.CleanupLanguagePolicy
import com.envi.wispr.cleanup.CleanupOptions
import com.envi.wispr.cleanup.LanguageDetector
import com.envi.wispr.cleanup.PolishPipeline

/**
 * The one text both sides fall back to: the deterministic pipeline with no model. The engine uses
 * it for every failure outcome and the session owner for every failure it handles itself, so the
 * words a user gets cannot depend on which side failed (issue #69).
 *
 * It takes the DETECTOR, not an already-resolved language. There are exactly two cleanup terminals, and
 * a `CleanupLanguage` parameter let a caller hand over a constant abstention while still looking wired —
 * review round 2 showed the source-text drift guard stayed green against exactly that edit.
 *
 * **What this signature actually removes is the constant `CleanupLanguage` argument. It does not make
 * abstention unreachable**, because `LanguageDetector { null }` is still a legal argument. Nothing in the
 * type system can tell a real detector from an abstaining one, so the production wiring is guarded
 * separately and by a smoke test that only checks the current call shape. A default is absent for the
 * same reason the parameter exists (`validation-discipline.md` FACT: silent-empty-traps).
 *
 * Resolving here rather than at each caller applies the same confidence POLICY at both terminals. It does
 * not make their answers identical: each terminal owns its own detector instance in its own process, so
 * one can succeed while the other times out or hits a recoverable vendor error on the same words.
 */
object PolishFallback {
    fun deterministic(rawText: String, options: CleanupOptions, detector: LanguageDetector): String =
        PolishPipeline.run(rawText, options, CleanupLanguagePolicy.resolve(detector.detect(rawText))).text
}
