package com.envi.wispr.paste

/**
 * The one log line every insertion attempt ends with, so a field report from any phone is diagnosable
 * without the transcript. Its parameters are enums, numbers and the target package: there is no
 * String payload parameter, which is how the line is kept content-free by construction
 * (`kotlin-patterns.md` RULE: no-content-in-diagnostics).
 */
internal object InsertionOutcomeLine {
    enum class Outcome {
        VERIFIED,
        UNVERIFIED,
        REJECTED,
        NEVER_RETURNED,
        SENSITIVE,
        STAGING_FAILED,
        INTERRUPTED,
        DESTROYED,
    }

    fun format(
        api: Int,
        route: InsertionRoute?,
        written: Boolean,
        returned: InsertionAttempt.Returned,
        evidence: InsertionAttempt.Evidence,
        outcome: Outcome,
        attempts: Int,
        elapsedMs: Long,
        overrun: Boolean,
        targetPackage: String?,
    ): String =
        "insertion api=$api route=${route?.name ?: "NONE"} written=$written returned=${returned.name} " +
            "evidence=${evidence.name} outcome=${outcome.name} attempts=$attempts ms=$elapsedMs " +
            "overrun=$overrun target=${targetPackage ?: "none"}"
}
