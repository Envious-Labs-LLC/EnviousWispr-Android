package com.envi.wispr.cleanup

import java.util.regex.Pattern

/** Android always uses Unicode classes; the desktop JVM needs its supported flag for the same rules. */
private val unicodeClassFlag: Int = try {
    Pattern.compile("", Pattern.UNICODE_CHARACTER_CLASS)
    Pattern.UNICODE_CHARACTER_CLASS
} catch (_: IllegalArgumentException) {
    // Android's documented implementation rejects this flag because Unicode classes are always on.
    0
}

internal fun cleaningRegex(pattern: String, option: RegexOption? = null): Regex {
    val flags = unicodeClassFlag or (option?.value ?: 0) or if (option == RegexOption.IGNORE_CASE) Pattern.UNICODE_CASE else 0
    return Pattern.compile(pattern, flags).toRegex()
}
