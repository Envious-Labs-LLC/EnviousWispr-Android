package com.envi.wispr.settings

import android.content.Context
import com.envi.wispr.polish.S1PromptBuilder

class CustomWordsStore(context: Context) {
    companion object {
        private const val PREFERENCES = "envious_wispr_settings"
        private const val CUSTOM_WORDS = "custom_words"
    }

    private val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    fun load(): List<String> {
        val saved = preferences.getString(CUSTOM_WORDS, "").orEmpty()
        return parse(saved)
    }

    fun save(words: List<String>) {
        val sanitized = S1PromptBuilder.sanitizeCustomWords(words)
        preferences.edit().putString(CUSTOM_WORDS, sanitized.joinToString("\n")).apply()
    }

    fun parse(draft: String): List<String> = S1PromptBuilder.sanitizeCustomWords(
        draft.lines().flatMap { line -> line.split(",") }
    )
}
