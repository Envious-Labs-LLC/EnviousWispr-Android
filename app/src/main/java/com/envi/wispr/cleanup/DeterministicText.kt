package com.envi.wispr.cleanup

import com.envi.wispr.polish.PolishFallback
import com.envi.wispr.vocabulary.CustomTerm
import com.envi.wispr.vocabulary.StructuredTermRestorer

/** The shared fail-open order; logging belongs only to the ordinary caller's collaborators. */
internal fun deterministicText(
    raw: String,
    restoreRaw: (String) -> String?,
    clean: (String) -> String?,
    restoreCleaned: (String) -> String?,
    finalizeCasual: (text: String, cleanupInput: String) -> String = { text, _ -> text },
): String {
    val prepared = restoreRaw(raw) ?: return finalizeCasual(raw, raw)
    val cleaned = clean(prepared) ?: return finalizeCasual(prepared, prepared)
    return finalizeCasual(restoreCleaned(cleaned) ?: cleaned, prepared)
}

/** One safety policy for ordinary and recovered vocabulary restoration. */
internal fun restoreVocabularySafely(
    text: String,
    matcher: StructuredTermRestorer.Matcher,
    restore: (String, StructuredTermRestorer.Matcher) -> String = { value, terms -> terms.restore(value) },
): String {
    val candidate = restore(text, matcher)
    return if (TextSafety.isSafe(text, candidate, checkNumbers = false)) candidate else text
}

/** Applies Casual mode once using exact cleanup provenance when cleanup ran, else fails open on [cleanupInput]. */
internal fun finalizeCasualFallback(
    text: String,
    cleanupInput: String,
    outcome: PolishFallback.Outcome?,
    options: CleanupOptions,
    detector: LanguageDetector,
): String = if (outcome != null) {
    CasualEnding.applyWithProvenance(text, options.casualMode, outcome.languageCode, outcome.spokenFinalFullStop)
} else {
    CasualEnding.applyFailOpen(text, cleanupInput, options, detector)
}

/** Recovery has no word-logging collaborator, settings reader, AI provider or insertion capability. */
internal fun recoverDeterministicText(raw: String, options: CleanupOptions, terms: List<CustomTerm>, detector: LanguageDetector): String {
    val matcher = try { StructuredTermRestorer.compile(terms) } catch (_: Exception) {
        return CasualEnding.applyFailOpen(raw, raw, options, detector)
    }
    fun restored(value: String): String? = try { restoreVocabularySafely(value, matcher) } catch (_: Exception) { null }
    var outcome: PolishFallback.Outcome? = null
    return deterministicText(
        raw,
        ::restored,
        clean = { value ->
            try {
                PolishFallback.deterministicOutcome(value, options.copy(casualMode = false), detector)
                    .also { outcome = it }
                    .text
            } catch (_: Exception) {
                null
            }
        },
        restoreCleaned = ::restored,
        finalizeCasual = { value, cleanupInput ->
            finalizeCasualFallback(value, cleanupInput, outcome, options, detector)
        },
    )
}
