package com.envi.wispr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Drift Guard (#215), not product coverage: the heart's one real ASR-and-local-polish row, and the speaker
 * source that plays the same fixture, ASSERT their stageable fixture instead of assuming it (a bare
 * instrumentation run reports an assumption as a pass), and the row checks the founder's saved term from
 * the repository alone, before built-ins are merged, with the exact `Prerequisite:` message the harness
 * door classifies. Read off the source because the row runs on a device, never in the JVM suite.
 *
 * The class code review kept finding (rounds 1 to 3) is "text that is not code standing for code". It is
 * closed by a lexer: every character is CODE, COMMENT or STRING ([classify]); a call is found only in code;
 * a required message only counts as string content of the call's first argument, and a required predicate
 * only counts when the second argument, comments removed, IS that predicate. [onlyCodeCanSatisfyARow]
 * holds one control per way non-code text can appear, outside and inside a real call.
 */
class VoicePipelineDeviceShapeTest {

    private val source = File("src/androidTest/java/com/envi/wispr/VoicePipelineDeviceTest.kt").readText()

    private enum class Kind { CODE, COMMENT, STRING }

    /**
     * The class of every character of [text]: `//` line comments and nested `/* */` block comments are
     * COMMENT; `"..."` strings (escapes and templates read as string content), `"""..."""` raw strings and
     * `'.'` char literals, delimiters included, are STRING; everything else is CODE.
     */
    private fun classify(text: String): Array<Kind> {
        val kinds = Array(text.length) { Kind.CODE }
        var i = 0
        fun mark(until: Int, kind: Kind) {
            while (i < until) { kinds[i] = kind; i++ }
        }
        while (i < text.length) {
            when {
                text.startsWith("//", i) -> mark(text.indexOf('\n', i).let { if (it < 0) text.length else it }, Kind.COMMENT)
                text.startsWith("/*", i) -> {
                    var depth = 0
                    var j = i
                    while (j < text.length) {
                        if (text.startsWith("/*", j)) { depth++; j += 2 }
                        else if (text.startsWith("*/", j)) { depth--; j += 2; if (depth == 0) break }
                        else j++
                    }
                    mark(j, Kind.COMMENT)
                }
                text.startsWith("\"\"\"", i) ->
                    mark(text.indexOf("\"\"\"", i + 3).let { if (it < 0) text.length else it + 3 }, Kind.STRING)
                text[i] == '"' || text[i] == '\'' -> {
                    val quote = text[i]
                    var j = i + 1
                    while (j < text.length && text[j] != quote) { if (text[j] == '\\') j++; j++ }
                    mark(minOf(j + 1, text.length), Kind.STRING)
                }
                else -> i++
            }
        }
        return kinds
    }

    /** [text] keeping only characters of [keep] (others become spaces, newlines stay): same length, same indices. */
    private fun view(text: String, vararg keep: Kind): String {
        val kinds = classify(text)
        return String(CharArray(text.length) { i -> if (kinds[i] in keep || text[i] == '\n') text[i] else ' ' })
    }

    private fun codeMask(text: String) = view(text, Kind.CODE)

    private fun normalized(value: String) = value.replace(Regex("\\s+"), " ").trim()

    /** The original text between the `{` after the CODE occurrence of [signature] and its matching `}`. */
    private fun body(text: String, signature: String): String {
        val code = codeMask(text)
        val start = code.indexOf(signature)
        assertTrue("$signature must exist in code", start >= 0)
        val open = code.indexOf('{', start)
        var depth = 0
        for (i in open until code.length) {
            when (code[i]) {
                '{' -> depth++
                '}' -> if (--depth == 0) return text.substring(open + 1, i)
            }
        }
        error("unbalanced $signature")
    }

    /** Every CODE `assertTrue(` call in [text]: its start and its argument texts, split at top-level code commas. */
    private fun assertCalls(text: String): List<Pair<Int, List<String>>> {
        val code = codeMask(text)
        val calls = mutableListOf<Pair<Int, List<String>>>()
        var from = code.indexOf("assertTrue(")
        while (from >= 0) {
            val open = from + "assertTrue".length
            var depth = 0
            var argStart = open + 1
            val args = mutableListOf<String>()
            for (i in open until code.length) {
                when (code[i]) {
                    '(', '{', '[' -> depth++
                    ')', '}', ']' -> {
                        depth--
                        if (depth == 0) { args += text.substring(argStart, i); calls += from to args; break }
                    }
                    ',' -> if (depth == 1) { args += text.substring(argStart, i); argStart = i + 1 }
                }
            }
            from = code.indexOf("assertTrue(", from + 1)
        }
        return calls
    }

    /**
     * The start of the CODE `assertTrue(` whose first argument is string literals only (joined by `+`)
     * containing [message], and whose second argument, comments removed, IS [predicate]; -1 when none.
     */
    private fun oneAssertionCarries(text: String, message: String, predicate: String): Int =
        assertCalls(text).firstOrNull { (_, rawArgs) ->
            val args = rawArgs.filter { normalized(view(it, Kind.CODE, Kind.STRING)).isNotEmpty() }
            if (args.size != 2) return@firstOrNull false
            val (first, second) = args
            val firstIsLiteralsOnly = normalized(codeMask(first)).replace("+", "").isBlank()
            firstIsLiteralsOnly && view(first, Kind.STRING).contains(message) &&
                normalized(view(second, Kind.CODE, Kind.STRING)) == normalized(predicate)
        }?.first ?: -1

    private fun hasCodeCall(text: String, name: String): Boolean = codeMask(text).contains("$name(")

    // REVERT: put back `assumeTrue(... File(fixturePath).isFile)` in the row.
    @Test
    fun theRealBoundaryRowAssertsItsFixture() {
        val row = body(source, "fun transcribesThenPolishesWithSavedCustomWords()")
        assertFalse("no assumption in the real-boundary row", hasCodeCall(row, "assumeTrue"))
        assertTrue(
            "ONE assertion carries the fixture message and the fixture predicate",
            oneAssertionCarries(row, "The real-model fixture is missing", "File(fixturePath).isFile") >= 0,
        )
    }

    // REVERT: remove the saved-user-term assertion, or check the merged vocabulary instead.
    @Test
    fun theSavedNameIsAPrerequisiteReadFromTheUserTermsAlone() {
        val row = body(source, "fun transcribesThenPolishesWithSavedCustomWords()")
        val code = codeMask(row)
        val read = code.indexOf("val userTerms = runBlocking { CustomTermRepository(context).list() }.map(CustomTermRecord::term)")
        val check = oneAssertionCarries(row, "\"Prerequisite: the saved custom name 'Saurabh'", "userTerms.any { it.spelling == \"Saurabh\" }")
        val merge = code.indexOf("BuiltinVocabulary.withUserTerms(userTerms)")
        assertTrue("the user terms are read on their own, in code", read >= 0)
        assertTrue("ONE assertion carries the exact prefix and the user-term predicate, after the read and before the merge", check in (read + 1) until merge)
    }

    // REVERT: put back the assumption in `SpeakerAudio.prepare`.
    @Test
    fun theSpeakerSourceAssertsTheSameFixture() {
        val speaker = body(source, "private inner class SpeakerAudio")
        val prepare = body(speaker, "override fun prepare()")
        assertFalse(hasCodeCall(prepare, "assumeTrue"))
        assertTrue(
            "ONE assertion carries the fixture message and predicate",
            oneAssertionCarries(prepare, "The fixture is missing", "File(fixturePath).isFile") >= 0,
        )
    }

    // One control per way non-code text could stand for code, outside a call and inside a real one.
    @Test
    fun onlyCodeCanSatisfyARow() {
        val message = "The fixture is missing"
        val predicate = "File(fixturePath).isFile"
        val real = "fun f() {\n    assertTrue(\"The fixture is missing\", File(fixturePath).isFile)\n}\n"
        assertTrue("a real assertion is found", oneAssertionCarries(real, message, predicate) >= 0)
        assertTrue(
            "a real message joined with + and a real predicate still match",
            oneAssertionCarries("assertTrue(\n    \"The fixture \" +\n        \"is missing\",\n    File(fixturePath).isFile,\n)", "is missing", predicate) >= 0,
        )
        val hidden = listOf(
            "the whole call in a line comment" to "// assertTrue(\"The fixture is missing\", File(fixturePath).isFile)\n",
            "the whole call in a block comment" to "/* assertTrue(\"The fixture is missing\", File(fixturePath).isFile) */",
            "the whole call in a nested block comment" to "/* a /* b */ assertTrue(\"The fixture is missing\", File(fixturePath).isFile) */",
            "the whole call in a string" to "val s = \"assertTrue(The fixture is missing, File(fixturePath).isFile)\"",
            "the whole call in a raw string" to "val s = \"\"\"assertTrue(\"The fixture is missing\", File(fixturePath).isFile)\"\"\"",
            "the predicate only in a line comment" to "assertTrue(\"The fixture is missing\", true // File(fixturePath).isFile\n)",
            "the predicate only in a block comment" to "assertTrue(\"The fixture is missing\", true /* File(fixturePath).isFile */)",
            "the predicate only in the message" to "assertTrue(\"The fixture is missing File(fixturePath).isFile\", true)",
            "the message only in a comment" to "assertTrue(/* The fixture is missing */ \"x\", File(fixturePath).isFile)",
            "the message joined to code" to "assertTrue(prefix + \"The fixture is missing\", File(fixturePath).isFile)",
            "a predicate that merely contains the expected one" to "assertTrue(\"The fixture is missing\", File(fixturePath).isFile || true)",
        )
        for ((label, text) in hidden) {
            assertEquals("$label is not a match", -1, oneAssertionCarries(text, message, predicate))
        }
        assertFalse("an assumeTrue inside a comment is not a call", hasCodeCall("// assumeTrue(x)\n", "assumeTrue"))
        assertTrue("a real assumeTrue is a call", hasCodeCall("assumeTrue(x)\n", "assumeTrue"))
        assertEquals("the views keep every index", real.length, codeMask(real).length)
    }
}
