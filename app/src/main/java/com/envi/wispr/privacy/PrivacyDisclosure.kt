package com.envi.wispr.privacy

/**
 * The Privacy page's sentences (#308): what stays on the phone, and what leaves for telemetry. The per-provider
 * disclosure a user reads before their text leaves is `providers/Provider.disclosure()`, the one table the setup
 * screen shows.
 */
internal object PrivacyDisclosures {
    /** The privacy policy page, one URL for both products. */
    const val POLICY_URL = "https://enviouswispr.com/privacy-policy/"

    /** What stays: the boundary is the network, and audio never crosses it on any path. */
    const val ON_DEVICE_SUMMARY = "Your voice stays on this phone. Audio never leaves it. Your words stay here too, unless you " +
        "connect your own AI provider for cloud polish; then the selected text goes straight to that provider under your key."

    /**
     * What leaves for telemetry (issue #176): the closed description of the two vendors' rows. Every
     * claim here is enforced by `telemetry/PayloadSanitizer.kt` (allowlist first: no words, no names, no
     * keys, no paths) and `telemetry/InstallIdentity.kt` (one random id per install, never a person).
     */
    const val TELEMETRY_SUMMARY = "EnviousWispr sends usage and crash reports with no personal details, so problems can be found and fixed: " +
        "whether a dictation finished, how long each step took, which settings are on, which app the words went to, " +
        "and a crash report when the app fails. It never sends audio, your dictated words, names you enter, file contents, or API keys. " +
        "A crash report can include the technical location in the code where it failed. " +
        "A random id tells one install from another; it is not tied to you or an account."

    const val TELEMETRY_VENDORS = "These reports go to PostHog (usage) and Sentry (crashes), stored in the United States. " +
        "PostHog may work out an approximate city or region from the connection; EnviousWispr never reads your phone's location."
}
