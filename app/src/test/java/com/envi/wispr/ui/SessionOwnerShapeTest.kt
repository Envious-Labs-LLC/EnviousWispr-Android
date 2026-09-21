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
     * TOGGLE that STOPPED a take re-pinned the field the user had moved to. The property is the absence
     * of a pin call from those two sources and exactly one call in the owner, inside `beginSession`.
     * REVERT: restore `PasteAccessibilityService.pinTargetForDictation()` in the launcher, or
     * `pinTarget()` in `startDictationFromBubble`.
     */
    @Test
    fun onlyTheOwnerPinsTheTarget() {
        val launcher = File("src/main/java/com/envi/wispr/ui/VoiceInputActivity.kt").readText()
        assertFalse("the launcher pins nothing; the owner pins in beginSession", launcher.contains("pinTarget"))

        val paste = File("src/main/java/com/envi/wispr/paste/PasteAccessibilityService.kt").readText()
        val bubbleStart = paste.substring(paste.indexOf("fun startDictationFromBubble("))
            .let { it.substring(0, it.indexOf("\n    }\n")) }
        assertFalse("the bubble's direct start pins nothing", bubbleStart.contains("pinTarget"))

        val coordinator = File("src/main/java/com/envi/wispr/ui/DictationSessionCoordinator.kt").readText()
        assertEquals("the owner pins exactly once", 1, coordinator.split(".pinTargetForDictation()").size - 1)
        val beginSession = coordinator.substring(coordinator.indexOf("private fun beginSession("))
            .let { it.substring(0, it.indexOf("\n    private fun ")) }
        assertTrue("the one pin is inside beginSession", beginSession.contains(".pinTargetForDictation()"))
    }

    @Test
    fun serviceLineCountIsReported() {
        // A metric for the reader of the test output, not a threshold: 1,937 lines before #186.
        println("DictationSessionService.kt: ${service.lines().size} lines")
    }
}
