package com.envi.wispr.ui

import com.envi.wispr.providers.Provider
import com.envi.wispr.providers.capabilities

/**
 * A page opened from the AI Polish tab (#67): a provider's setup, or the local model's management. Saved
 * across recreation as one string beside the drawer page's name; an unknown saved string falls back to the
 * tab rather than throwing (`PolishSubpageTest`).
 */
internal sealed interface PolishSubpage {
    data class ProviderSetup(val provider: Provider) : PolishSubpage
    data object LocalModel : PolishSubpage

    /** The top-bar title; [savedProvider] is the configured provider, if any, so setup reads Edit or Set up. */
    fun title(savedProvider: Provider?): String = when (this) {
        is ProviderSetup -> "${if (provider == savedProvider) "Edit" else "Set up"} ${provider.capabilities().displayName}"
        LocalModel -> "S1-mini"
    }

    fun toSaved(): String = when (this) {
        is ProviderSetup -> "setup:${provider.name}"
        LocalModel -> "model"
    }

    companion object {
        fun fromSaved(value: String): PolishSubpage? = when {
            value == "model" -> LocalModel
            value.startsWith("setup:") -> Provider.entries.firstOrNull { it.name == value.removePrefix("setup:") }?.let(::ProviderSetup)
            else -> null
        }
    }
}
