package com.envi.wispr.telemetry

import java.net.URI
import java.net.URLDecoder

/** Publisher-authored campaign labels only. The original Play query never reaches storage or telemetry. */
internal data class InstallReferral(
    val status: Status,
    val source: String? = null,
    val medium: String? = null,
    val campaign: String? = null,
    val content: String? = null,
) {
    enum class Status { TAGGED, UNATTRIBUTED, INVALID;
        val wire: String get() = name.lowercase()
    }

    companion object {
        /** Shared with the existing outbound schema, so a producer cannot broaden the accepted label shape. */
        val LABEL = Regex("\\A[A-Za-z0-9][A-Za-z0-9._-]{0,63}\\z")
        private val KEYS = setOf("utm_source", "utm_medium", "utm_campaign", "utm_content")
        private const val MAX_QUERY = 4096

        fun parse(raw: String): InstallReferral {
            if (raw.isEmpty()) return InstallReferral(Status.UNATTRIBUTED)
            if (raw.length > MAX_QUERY) return InstallReferral(Status.INVALID)
            return try {
                // URI validates escaping/structure; URLDecoder decodes individual form-query components once.
                val uri = URI("https://referrer.invalid/?$raw")
                if (uri.rawFragment != null) return InstallReferral(Status.INVALID)
                val labels = linkedMapOf<String, String>()
                for (pair in uri.rawQuery.orEmpty().split('&')) {
                    val key = URLDecoder.decode(pair.substringBefore('='), Charsets.UTF_8)
                    if (key !in KEYS) continue
                    val value = URLDecoder.decode(pair.substringAfter('=', ""), Charsets.UTF_8)
                    if (key in labels || !LABEL.matches(value) || PayloadSanitizer.redactPatterns(value) != value) {
                        return InstallReferral(Status.INVALID)
                    }
                    labels[key] = value
                }
                InstallReferral(
                    if (labels.isEmpty()) Status.UNATTRIBUTED else Status.TAGGED,
                    labels["utm_source"], labels["utm_medium"], labels["utm_campaign"], labels["utm_content"],
                )
            } catch (_: IllegalArgumentException) {
                InstallReferral(Status.INVALID)
            } catch (_: java.net.URISyntaxException) {
                InstallReferral(Status.INVALID)
            }
        }
    }
}
