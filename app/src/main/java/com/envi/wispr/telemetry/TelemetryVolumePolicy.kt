package com.envi.wispr.telemetry

/**
 * The ONE entry point that decides whether a PostHog row leaves the phone and stamps it (issue #176;
 * the Mac's `TelemetryVolumePolicy`, #2958). Runs from the PostHog `beforeSend`, before the sanitizer.
 *
 * Today every declared event is kept unsampled, and no hash bucket, sampling stamps or sampled fixture
 * ship until an approved event is actually sampled (G2 proportionality); when that day comes the
 * contract is PostHog's own vocabulary (`$sample_type`, `$sample_threshold`, `$sampled_events`) so
 * `sum(1 / $sample_threshold)` reads both platforms. What it DOES do now: stamp `telemetry_policy_version`
 * on every kept row so a reader can floor by policy, and reduce the SDK-added context to the keys the
 * sanitizer's allowlist names, so `$user_agent`, `$network_carrier`, `$device_name` and the screen
 * geometry never leave.
 *
 * Pure: no SDK types, no clock, no random source.
 */
internal object TelemetryVolumePolicy {
    /** Bumped whenever a rule changes what leaves. 1: #176, nothing sampled, SDK context reduced. */
    const val POLICY_VERSION = 1
    const val POLICY_VERSION_KEY = "telemetry_policy_version"

    sealed class Decision {
        data class Keep(val properties: Map<String, Any?>) : Decision()
        object Drop : Decision()
    }

    fun decide(event: String, properties: Map<String, Any?>): Decision {
        // The SDK's own lifecycle rows are off at the source (`PostHogBootstrap`); should one ever
        // arrive, it is not ours and does not leave.
        if (event.startsWith("$") || event.startsWith("Application ")) return Decision.Drop
        val out = LinkedHashMap<String, Any?>()
        for ((key, value) in properties) {
            if (key.startsWith("$") && key !in PayloadSanitizer.allowedKeys) continue
            out[key] = value
        }
        out[POLICY_VERSION_KEY] = POLICY_VERSION
        return Decision.Keep(out)
    }
}
