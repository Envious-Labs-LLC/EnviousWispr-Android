package com.envi.wispr.privacy

enum class PolishProvider { OFFLINE, OPENAI, GEMINI, CLAUDE, SELF_HOSTED }

data class PrivacyDisclosure(val provider: PolishProvider, val summary: String, val sendsText: Boolean)

object PrivacyDisclosures {
    /** The privacy policy page, one URL for both products. */
    const val POLICY_URL = "https://enviouswispr.com/privacy-policy/"

    /** What stays: the boundary is the network, and audio never crosses it on any path. */
    const val ON_DEVICE_SUMMARY = "Your voice and your words stay on this phone. Audio never leaves it. " +
        "Text leaves only if you connect your own AI provider for cloud polish, and then it goes straight to that provider under your key."

    /**
     * What leaves for telemetry (issue #176): the closed description of the two vendors' rows. Every
     * claim here is enforced by `telemetry/PayloadSanitizer.kt` (allowlist first: no words, no names, no
     * keys, no paths) and `telemetry/InstallIdentity.kt` (one random id per install, never a person).
     */
    const val TELEMETRY_SUMMARY = "EnviousWispr sends anonymous usage and crash reports so problems can be found and fixed: " +
        "whether a dictation finished, how long each step took, which settings are on, which app the words went to, " +
        "and a crash report when the app fails. It never sends audio, your words, a name, a file, or a key. " +
        "A random id tells one install from another and is not tied to you or your account."

    const val TELEMETRY_VENDORS = "These reports go to PostHog (usage) and Sentry (crashes), stored in the United States."

    fun forProvider(provider: PolishProvider): PrivacyDisclosure = when (provider) {
        PolishProvider.OFFLINE -> PrivacyDisclosure(provider, "Audio and text stay on this phone.", false)
        PolishProvider.SELF_HOSTED -> PrivacyDisclosure(provider, "Selected text is sent only to your configured endpoint.", true)
        PolishProvider.OPENAI, PolishProvider.GEMINI, PolishProvider.CLAUDE -> PrivacyDisclosure(provider, "Selected text is sent to the provider you chose, using your key.", true)
    }
}
