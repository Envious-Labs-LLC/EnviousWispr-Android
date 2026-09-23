package com.envi.wispr.paste

import java.io.File

/**
 * Harness Contract (#217): the paste service's source, as the rows that read it as text need it. The
 * service delegates to three collaborators, so a row that pins code reads the ONE file that code lives in,
 * and a row that says something is ABSENT, or counts something, reads [all]: a scan over the service
 * alone would pass the moment the code it forbids moved next door.
 */
internal object PasteSources {
    private fun read(name: String): String {
        val file = File("src/main/java/com/envi/wispr/paste/$name")
        check(file.isFile) { "the paste source ${file.path} must exist" }
        return file.readText()
    }

    val service: String get() = read("PasteAccessibilityService.kt")
    val tracker: String get() = read("EditorTargetTracker.kt")
    val runner: String get() = read("AccessibilityInsertionRunner.kt")
    val bubble: String get() = read("AccessibilityBubbleHost.kt")

    /** Every file of the paste service, for a scan that says something is absent or counts it. */
    val all: String get() = listOf(service, tracker, runner, bubble).joinToString("\n")

    /**
     * The text from [start] up to (not including) the first [end] after it. Fails when either marker is
     * missing: `substringAfter` and `substringBefore` return the whole text instead, which turns a moved
     * function into a scan of the whole file.
     */
    fun slice(text: String, start: String, end: String): String {
        val from = text.indexOf(start)
        check(from >= 0) { "start marker missing: $start" }
        val to = text.indexOf(end, from + start.length)
        check(to >= 0) { "end marker missing after $start: $end" }
        return text.substring(from, to)
    }
}
