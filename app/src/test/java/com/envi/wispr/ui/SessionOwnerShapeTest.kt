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
     *
     * Calls are read from CODE only, through [codeOnly]: rounds 2 to 4 each found a comment-or-string
     * shape the previous text cut misread (a KDoc naming the call; a block-comment opener inside a string swallowing the
     * code after it; a `//` inside a URL string truncating the line), so the cut is replaced by the
     * closed list of Kotlin lexical states rather than a fourth patch.
     * REVERT: restore `PasteAccessibilityService.pinTargetForDictation()` in the launcher, or
     * `pinTarget()` in `startDictationFromBubble`; receipts R5 and R6 do so behind a block-comment-opener string and a
     * `//` string and the row stays red.
     */
    @Test
    fun onlyTheOwnerPinsTheTarget() {
        val callSites = File("src/main/java").walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .flatMap { file ->
                codeOnly(file.readText()).lines().asSequence().mapIndexedNotNull { index, line ->
                    // A declaration is not a call; the gateway declares AND calls on one line, so the
                    // declaration is cut out and whatever call remains counts.
                    val code = line.replace(Regex("""\bfun\s+pinTarget(ForDictation)?\([^)]*\)"""), "")
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

        val coordinator = codeOnly(File("src/main/java/com/envi/wispr/ui/DictationSessionCoordinator.kt").readText())
        val beginSession = coordinator.substring(coordinator.indexOf("private fun beginSession("))
            .let { it.substring(0, it.indexOf("\n    private fun ")) }
        assertTrue("the owner's one call is inside beginSession", beginSession.contains(".pinTargetForDictation()"))
    }

    /**
     * Drift Guard on [codeOnly] itself, with literal expectations: each lexical state blanked, code kept,
     * newlines kept so line numbers hold. REVERT: drop the `str` state's `//` handling; the URL row fails.
     */
    @Test
    fun codeOnlyBlanksEveryNonCodeState() {
        val blank = { n: Int -> " ".repeat(n) }
        assertEquals("val a = 1 " + blank(7), codeOnly("val a = 1 // pin("))
        assertEquals("val b = " + blank(10) + " 2", codeOnly("val b = /* pin( */ 2"))
        assertEquals("val c = " + blank(20) + " 3", codeOnly("val c = /* a /* pin( */ b */ 3"))
        assertEquals("val d = \"" + blank(17) + "\"", codeOnly("val d = \"https://x/pin( /*\""))
        assertEquals("val e = \"" + blank(4) + "\" + f", codeOnly("val e = \"a\\\"b\" + f"))
        assertEquals("val g = \"\"\"" + blank(7) + "\"\"\"", codeOnly("val g = \"\"\"// pin(\"\"\""))
        assertEquals("val h = '" + blank(2) + "'", codeOnly("val h = '\\''"))
        assertEquals("val i = \"" + blank(2) + "\${pin()}" + blank(2) + "\"", codeOnly("val i = \"a \${pin()} b\""))
        assertEquals("a\n" + blank(6) + "\nb", codeOnly("a\n// pin\nb"))
    }

    /**
     * Kotlin's lexical states, the closed list `scripts/check-visibility.py` (`code_mask`) implements:
     * code, line comment, nesting block comment, string with escapes and `${ }` templates, raw string
     * ending at the last three quotes of a run, character literal. Every non-code character becomes a
     * space; newlines stay.
     */
    private fun codeOnly(text: String): String {
        val out = StringBuilder(text.length)
        // ("code", brace depth at entry) | "line" | ("block", nesting) | "str" | "raw" | "chr"
        val stack = ArrayDeque<Pair<String, Int>>().apply { addLast("code" to 0) }
        var depth = 0
        var i = 0
        fun blank(count: Int) { repeat(count) { k -> out.append(if (text[i + k] == '\n') '\n' else ' ') }; i += count }
        while (i < text.length) {
            val (kind, entry) = stack.last()
            val c = text[i]
            when (kind) {
                "code" -> when {
                    text.startsWith("//", i) -> { stack.addLast("line" to 0); blank(2) }
                    text.startsWith("/*", i) -> { stack.addLast("block" to 1); blank(2) }
                    text.startsWith("\"\"\"", i) -> { stack.addLast("raw" to 0); out.append("\"\"\""); i += 3 }
                    c == '"' -> { stack.addLast("str" to 0); out.append(c); i += 1 }
                    c == '\'' -> { stack.addLast("chr" to 0); out.append(c); i += 1 }
                    c == '}' && stack.size > 1 && depth == entry -> { stack.removeLast(); out.append(c); i += 1 }
                    else -> {
                        if (c == '{') depth += 1 else if (c == '}') depth -= 1
                        out.append(c); i += 1
                    }
                }
                "line" -> { if (c == '\n') stack.removeLast(); blank(1) }
                "block" -> when {
                    text.startsWith("/*", i) -> { stack[stack.lastIndex] = "block" to entry + 1; blank(2) }
                    text.startsWith("*/", i) -> { if (entry == 1) stack.removeLast() else stack[stack.lastIndex] = "block" to entry - 1; blank(2) }
                    else -> blank(1)
                }
                "str" -> when {
                    c == '\\' -> blank(if (text.startsWith("\\u", i)) 6 else 2)
                    text.startsWith("\${", i) -> { stack.addLast("code" to depth); out.append("\${"); i += 2 }
                    c == '"' -> { stack.removeLast(); out.append(c); i += 1 }
                    else -> blank(1)
                }
                "raw" -> when {
                    text.startsWith("\${", i) -> { stack.addLast("code" to depth); out.append("\${"); i += 2 }
                    text.startsWith("\"\"\"", i) -> {
                        var j = i
                        while (j < text.length && text[j] == '"') j += 1
                        // a run of quotes ends the literal at its last three
                        blank(j - 3 - i); out.append("\"\"\""); i += 3
                        stack.removeLast()
                    }
                    else -> blank(1)
                }
                "chr" -> when {
                    c == '\\' -> blank(if (text.startsWith("\\u", i)) 6 else 2)
                    c == '\'' -> { stack.removeLast(); out.append(c); i += 1 }
                    else -> blank(1)
                }
                else -> error(kind)
            }
        }
        return out.toString()
    }

    @Test
    fun serviceLineCountIsReported() {
        // A metric for the reader of the test output, not a threshold: 1,937 lines before #186.
        println("DictationSessionService.kt: ${service.lines().size} lines")
    }
}
