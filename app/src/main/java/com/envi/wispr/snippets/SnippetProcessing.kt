package com.envi.wispr.snippets

import android.content.Context
import com.envi.wispr.cleanup.CleanupLanguage
import com.envi.wispr.cleanup.CleanupLanguagePolicy
import com.envi.wispr.cleanup.LanguageDetector
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/** A fired snippet uses the Mac's literal editor route with language-aware spacing. */
internal fun snippetWordSpacing(raw: String, detector: LanguageDetector): Boolean {
    val detected = runCatching { CleanupLanguagePolicy.resolve(detector.detect(raw)) }.getOrNull()
    return (detected as? CleanupLanguage.Known)?.code !in unsegmented
}

private val unsegmented = setOf("ja", "zh", "yue", "th", "lo", "my", "km")

internal suspend fun Context.matchSnippets(text: String): SnippetExpansion {
    return try {
        withTimeoutOrNull(1_000) { applicationContext.snippetStore.data.first().expand(text) { ensureActive() } }
            ?: SnippetExpansion.unchanged(text)
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        SnippetExpansion.unchanged(text)
    }
}

internal data class RecoveredSnippetText(val text: String, val wordSpacing: Boolean?)
