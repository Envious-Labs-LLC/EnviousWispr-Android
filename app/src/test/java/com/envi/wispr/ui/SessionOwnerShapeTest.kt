package com.envi.wispr.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Drift Guard (#186): the Service is the Android shell and nothing else. A state machine cannot exist
 * without a lock, a compare-and-set, an arbiter, a ledger or a terminal reason, so their absence from the
 * Service source is the property; the field set pins what the shell is allowed to hold. A token check on
 * one name alone is evaded by a rename, and a line ceiling fails on comments (Codex review G1, 2026-09-20),
 * so the line count below is REPORTED, never gated.
 *
 * REVERT: paste one `state.compareAndSet` or one `synchronized(` back into the Service, or add a field.
 */
class SessionOwnerShapeTest {
    private val service = File("src/main/java/com/envi/wispr/ui/DictationSessionService.kt").readText()

    @Test
    fun serviceOwnsOnlyItsAdapters() {
        // Instance fields, read off the source: every member declared at class indentation with val/var.
        // Any run of annotations (with or without arguments and spaces) and any modifiers before val/var,
        // so `@Volatile private var` and `@Suppress("x y") private val` are enumerated too (Codex reviews
        // C1 and C2, 2026-09-20).
        val fields = Regex("""(?m)^ {4}(?:(?:@[^\r\n]*?)\s+|(?:[A-Za-z_]\w*)\s+)*(?:val|var)\s+(\w+)\b""")
            .findAll(service).map { it.groupValues[1] }.toSet()
        assertEquals(
            "the Service holds exactly its adapters; a take's state lives in the coordinator",
            setOf("mainHandler", "languageDetector", "preferences", "bindings", "coordinator"),
            fields,
        )
        listOf("synchronized(", "compareAndSet(", "TakeArbiter", "PolishRequestLedger", "TerminalReason", "SessionState").forEach { token ->
            assertFalse("the Service source carries '$token', which only a state machine needs", service.contains(token))
        }
    }

    /**
     * Drift Guard (#192): the session owner is the only component that pins the field a take aims at,
     * and it pins once, at admission. The launcher and the bubble's direct start each pinned too, so a
     * TOGGLE that STOPPED a take re-pinned the field the user had moved to. The property is the whole
     * inventory of pin CALLS across production source (Codex code review round 1, 2026-09-21: a check on
     * three named regions stays green when a fourth file reaches the pin through a helper): the owner's
     * one call inside `beginSession`, the gateway's delegation, and the companion's call into the
     * private pin. Any other file, or a second call in these, fails.
     * REVERT: restore `PasteAccessibilityService.pinTargetForDictation()` in the launcher, or
     * `pinTarget()` in `startDictationFromBubble`.
     */
    @Test
    fun onlyTheOwnerPinsTheTarget() {
        val callSites = File("src/main/java").walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .flatMap { file ->
                // Block comments are cut first (a KDoc naming the call is prose, not a call; Codex code
                // review round 2), then each line's `//` tail.
                file.readText().replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), "")
                    .lines().asSequence().mapIndexedNotNull { index, line ->
                    // A declaration is not a call; the gateway declares AND calls on one line, so the
                    // declaration is cut out and whatever call remains counts.
                    val code = line.substringBefore("//").replace(Regex("""\bfun\s+pinTarget(ForDictation)?\([^)]*\)"""), "")
                    val isCall = code.contains("pinTargetForDictation(") || code.contains("pinTarget(")
                    if (isCall) "${file.name}:${index + 1}" else null
                }
            }
            .toList()
        assertEquals(
            "the owner, the gateway and the companion are the only pin callers; the line numbers move, the file set does not",
            listOf("DictationSessionCoordinator.kt", "InsertionGateway.kt", "PasteAccessibilityService.kt"),
            callSites.map { it.substringBefore(":") }.sorted(),
        )

        val coordinator = File("src/main/java/com/envi/wispr/ui/DictationSessionCoordinator.kt").readText()
        val beginSession = coordinator.substring(coordinator.indexOf("private fun beginSession("))
            .let { it.substring(0, it.indexOf("\n    private fun ")) }
        assertTrue("the owner's one call is inside beginSession", beginSession.contains(".pinTargetForDictation()"))
    }

    @Test
    fun serviceLineCountIsReported() {
        // A metric for the reader of the test output, not a threshold: 1,937 lines before #186.
        println("DictationSessionService.kt: ${service.lines().size} lines")
    }
}
