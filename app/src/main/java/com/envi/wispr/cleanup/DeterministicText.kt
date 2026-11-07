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
): String {
    val prepared = restoreRaw(raw) ?: return raw
    val cleaned = clean(prepared) ?: return prepared
    return restoreCleaned(cleaned) ?: cleaned
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

/** Recovery has no word-logging collaborator, settings reader, AI provider or insertion capability. */
internal fun recoverDeterministicText(raw: String, options: CleanupOptions, terms: List<CustomTerm>, detector: LanguageDetector): String {
    val matcher = try { StructuredTermRestorer.compile(terms) } catch (_: Exception) { return raw }
    fun restored(value: String): String? = try { restoreVocabularySafely(value, matcher) } catch (_: Exception) { null }
    return deterministicText(raw, ::restored,
        clean = { value -> try { PolishFallback.deterministic(value, options, detector) } catch (_: Exception) { null } },
        restoreCleaned = ::restored)
}
