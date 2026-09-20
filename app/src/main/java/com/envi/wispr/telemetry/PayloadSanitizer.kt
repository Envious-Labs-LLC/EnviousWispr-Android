package com.envi.wispr.telemetry

/**
 * The one redactor for everything that leaves the phone, both vendors (issue #176, plan §3.7). The
 * third privacy enforcer beside `privacy/PrivacyDisclosure.kt` and `cpp/geniex_log_silencer.cpp`.
 *
 * Allowlist first, patterns second. A property leaves only if its NAME is one the schema declares
 * ([allowedKeys]) and its VALUE has an allowed kind: a number, a boolean, a closed token, or one of
 * the bounded dynamic strings. Every other string is DROPPED whatever its length, and URLs get no
 * exemption. Then the pattern rules run on what survived, as defence in depth: key prefixes, hex runs,
 * emails, Android private and shared storage paths, `content://` URIs, credentials inside URLs, and
 * anything over [MAX_STRING] characters.
 *
 * Exception MESSAGES are always dropped, including approved types and nested causes (G2 D7): a short
 * transcript can ride in any message. Only the exception TYPE and the frame locations pass.
 *
 * Pure: no SDK types, so `PayloadSanitizerTest` drives the exact bytes with literal witnesses.
 */
object PayloadSanitizer {
    const val REDACTED = "[REDACTED]"
    const val MAX_STRING = 100

    /**
     * The keys a PostHog row may carry: ours (from `AnalyticsEvent`), the stamps the bootstrap adds, and
     * the SDK context keys we keep. Anything else is removed. `$`-prefixed SDK keys the SDK needs for
     * its own bookkeeping are listed too, so filtering never breaks the SDK's contract.
     */
    val allowedKeys: Set<String> = setOf(
        // Stamps
        "app", "environment", "app_version", "app_build", "telemetry_policy_version", "process_run_id",
        // Identity and joins
        "take_id", "distinct_id",
        // dictation.terminal and friends
        "result", "reason", "asr_failure_reason", "trigger_source", "route_kind", "route_reason",
        "live_after_ms", "live_state", "start_failure", "capture_terminal", "silence_stop_status",
        "recording_s", "input_device", "asr_ms", "asr_chars", "asr_cold_start", "peak_amplitude",
        "polish_provider", "polish_reason", "polish_ms", "polish_status", "history_save", "stage",
        // insertion.terminal
        "handoff", "route", "target_app", "latency_ms", "clipboard_outcome", "recovered",
        // app.launched and settings
        "device_model", "os_version", "is_fresh_install", "models_ready", "accessibility_granted",
        "mic_granted", "onboarding_complete", "custom_words_count", "filler_removal", "emoji_formatter",
        "spoken_punctuation", "auto_copy_to_clipboard", "restore_clipboard_after_paste", "smart_insertion",
        "auto_stop_on_silence", "silence_pause_seconds", "show_bluetooth_tips", "keep_earbuds_ready",
        "dynamic_color", "bubble_look", "polish_policy", "setting", "from", "to",
        // onboarding, model delivery, providers
        "elapsed_s", "lesson", "outcome", "model", "source_host", "bytes_bucket", "duration_s", "first_run",
        "provider", "action",
        // PostHog SDK context we keep (the rest of the SDK's `$` keys are dropped below)
        "\$app_build", "\$app_version", "\$app_namespace", "\$device_manufacturer", "\$device_model",
        "\$device_type", "\$is_emulator", "\$lib", "\$lib_version", "\$locale", "\$os_name", "\$os_version",
        "\$timezone", "\$network_wifi", "\$network_cellular", "\$network_bluetooth",
        // PostHog SDK bookkeeping the SDK itself reads
        "\$session_id", "\$process_person_profile", "\$sample_type", "\$sample_threshold", "\$sampled_events",
    )

    /**
     * Keys whose value may be a bounded, externally supplied string rather than a closed token, each with
     * the SHAPE it must have. A value that does not fit its shape is dropped, so a short sentence can
     * never ride under `target_app` or `device_model` (G3 refutation: a length rule alone admits prose).
     */
    private val UUID_SHAPE = Regex("\\A[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\z")
    private val PACKAGE_SHAPE = Regex("\\A[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+\\z")
    /** Printable ASCII, no spaces: a model number, a version, a locale, a timezone id. */
    private val LABEL_SHAPE = Regex("\\A[\\x21-\\x7E]{1,64}\\z")
    /** Printable ASCII with single spaces: a manufacturer's model name ("Galaxy S26 Ultra"). */
    private val NAME_SHAPE = Regex("\\A[\\x21-\\x7E]+( [\\x21-\\x7E]+){0,4}\\z")
    private val boundedStringKeys: Map<String, Regex> = mapOf(
        "take_id" to UUID_SHAPE, "distinct_id" to UUID_SHAPE, "process_run_id" to UUID_SHAPE, "\$session_id" to UUID_SHAPE,
        "target_app" to PACKAGE_SHAPE, "\$app_namespace" to PACKAGE_SHAPE,
        "device_model" to NAME_SHAPE, "\$device_model" to NAME_SHAPE, "\$device_manufacturer" to NAME_SHAPE,
        "os_version" to LABEL_SHAPE, "\$os_version" to LABEL_SHAPE, "\$os_name" to LABEL_SHAPE,
        "\$app_version" to LABEL_SHAPE, "\$app_build" to LABEL_SHAPE, "\$locale" to LABEL_SHAPE,
        "\$timezone" to LABEL_SHAPE, "\$lib" to LABEL_SHAPE, "\$lib_version" to LABEL_SHAPE, "\$device_type" to LABEL_SHAPE,
        "app_version" to LABEL_SHAPE, "app" to LABEL_SHAPE, "environment" to LABEL_SHAPE,
    )

    /** Filters a PostHog property bag: unknown keys dropped, values judged, patterns applied. */
    fun sanitizeProperties(properties: Map<String, Any?>): Map<String, Any> {
        val out = LinkedHashMap<String, Any>()
        for ((key, value) in properties) {
            if (key !in allowedKeys) continue
            val kept = sanitizeValue(key, value) ?: continue
            out[key] = kept
        }
        return out
    }

    /**
     * A single value under a known key. Numbers and booleans pass. A string passes only as a closed token
     * (`[a-z0-9_.:-]{1,64}`, the shape of every enum name, wire result and version we emit) or, under a
     * bounded key, as a scrubbed string of at most [MAX_STRING] characters. Lists of strings are filtered
     * element-wise. Anything else is dropped.
     */
    fun sanitizeValue(key: String, value: Any?): Any? = when (value) {
        null -> null
        is Boolean, is Int, is Long, is Float, is Double -> value
        is String -> sanitizeString(key, value)
        is List<*> -> value.mapNotNull { element -> (element as? String)?.let { sanitizeString(key, it) } }
        else -> null
    }

    private fun sanitizeString(key: String, value: String): String? {
        // A key with its own shape is judged by that shape alone: "chrome" is a fine token but not a
        // package name, so under `target_app` it is a bug or a word, and either way it stays.
        val shape = boundedStringKeys[key]
        if (shape != null) {
            if (value.length > MAX_STRING || !shape.matches(value)) return null
            return redactPatterns(value)
        }
        return if (TOKEN.matches(value)) value else null
    }

    /**
     * Sentry surfaces carry free text the SDK wrote (a breadcrumb message, a tag, an extra). This is the
     * rule for such a string: scrubbed, or [REDACTED] when a pattern matched, or [REDACTED] when it is
     * over [MAX_STRING]. Never null: Sentry fields are rewritten, not removed.
     */
    fun sanitizeFreeText(value: String): String {
        if (value.length > MAX_STRING) return REDACTED
        return redactPatterns(value)
    }

    /** Recursively sanitizes a Sentry map (extras, contexts, breadcrumb data). */
    fun sanitizeFreeMap(map: Map<String, Any?>): Map<String, Any?> = map.mapValues { (_, v) -> sanitizeFreeAny(v) }

    private fun sanitizeFreeAny(value: Any?): Any? = when (value) {
        is String -> sanitizeFreeText(value)
        is Map<*, *> -> value.entries.associate { (k, v) -> k.toString() to sanitizeFreeAny(v) }
        is List<*> -> value.map { sanitizeFreeAny(it) }
        else -> value
    }

    /** The pattern pass. Whole-value [REDACTED] when any rule matches; a path rule rewrites in place. */
    fun redactPatterns(raw: String): String {
        var s = raw
        for (rule in PATH_RULES) s = rule.replace(s, "[PATH]")
        if (DENY_RULES.any { it.containsMatchIn(s) }) return REDACTED
        return s
    }

    private val TOKEN = Regex("\\A[A-Za-z0-9_.:-]{1,64}\\z")

    private val PATH_RULES = listOf(
        Regex("/data/user/\\d+/[^\\s]*"),
        Regex("/data/data/[^\\s]*"),
        Regex("/storage/emulated/\\d+/[^\\s]*"),
        Regex("/sdcard/[^\\s]*"),
        Regex("content://[^\\s]*"),
    )

    private val DENY_RULES = listOf(
        // Key-shaped prefixes: OpenAI, PostHog, Sentry, generic, Google.
        Regex("(?i)\\b(sk-|phc_|sntrys_|key_|AIza)[A-Za-z0-9_\\-]{16,}"),
        // 32+ contiguous hex: tokens, hashes, compact UUIDs.
        Regex("[0-9a-fA-F]{32,}"),
        // Emails.
        Regex("[A-Za-z0-9._%+\\-]+@[A-Za-z0-9.\\-]+\\.[A-Za-z]{2,}"),
        // Credentials, queries or fragments inside a URL: a URL passes only bare.
        Regex("(?i)https?://[^\\s]*[@?#][^\\s]*"),
    )
}
