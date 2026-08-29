package com.envi.wispr.privacy

enum class PolishProvider { OFFLINE, OPENAI, GEMINI, CLAUDE, SELF_HOSTED }

data class PrivacyDisclosure(val provider: PolishProvider, val summary: String, val sendsText: Boolean)

object PrivacyDisclosures {
    fun forProvider(provider: PolishProvider): PrivacyDisclosure = when (provider) {
        PolishProvider.OFFLINE -> PrivacyDisclosure(provider, "Audio and text stay on this phone.", false)
        PolishProvider.SELF_HOSTED -> PrivacyDisclosure(provider, "Selected text is sent only to your configured endpoint.", true)
        PolishProvider.OPENAI, PolishProvider.GEMINI, PolishProvider.CLAUDE -> PrivacyDisclosure(provider, "Selected text is sent to the provider you chose, using your key.", true)
    }
}
