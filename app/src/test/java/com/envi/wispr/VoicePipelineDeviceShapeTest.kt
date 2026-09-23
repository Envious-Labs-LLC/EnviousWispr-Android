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
 * Every search runs on CODE only: [codeMask] blanks line and block comments, string and raw-string literals
 * and char literals (keeping positions), so text inside any of them can never satisfy a row. Code review
 * rounds 1 and 2 each found one way non-code text did; the class is "text that is not code", enumerated
 * here by the lexer's five states, each with a control row below.
 */
class VoicePipelineDeviceShapeTest {

    private val source = File("src/androidTest/java/com/envi/wispr/VoicePipelineDeviceTest.kt").readText()

    /**
     * [text] with every non-code character replaced by a space and newlines kept, so indices match the
     * original: `//` line comments, nested `/* */` block comments, `"..."` strings (with escapes and `${}`
     * templates treated as string content), `"""..."""` raw strings, and `'.'` char literals.
     */
    private fun codeMask(text: String): String {
        val out = StringBuilder(text.length)
        var i = 0
        fun blank(until: Int) {
            while (i < until) { out.append(if (text[i] == '\n') '\n' else ' '); i++ }
        }
        while (i < text.length) {
            when {
                text.startsWith("//", i) -> blank(text.indexOf('\n', i).let { if (it < 0) text.length else it })
                text.startsWith("/*", i) -> {
                    var depth = 0
                    var j = i
                    while (j < text.length) {
                        if (text.startsWith("/*", j)) { depth++; j += 2 }
                        else if (text.startsWith("*/", j)) { depth--; j += 2; if (depth == 0) break }
                        else j++
                    }
                    blank(j)
                }
                text.startsWith("\"\"\"", i) -> {
                    val end = text.indexOf("\"\"\"", i + 3).let { if (it < 0) text.length else it + 3 }
                    blank(end)
                }
                text[i] == '"' -> {
                    var j = i + 1
                    while (j < text.length && text[j] != '"') { if (text[j] == '\\') j++; j++ }
                    blank(minOf(j + 1, text.length))
                }
                text[i] == '\'' -> {
                    var j = i + 1
                    while (j < text.length && text[j] != '\'') { if (text[j] == '\\') j++; j++ }
                    blank(minOf(j + 1, text.length))
                }
                else -> { out.append(text[i]); i++ }
            }
        }
        return out.toString()
    }

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

    /** Every CODE `assertTrue(` call in [text]: its start index and its original argument text. */
    private fun assertCalls(text: String): List<Pair<Int, String>> {
        val code = codeMask(text)
        val calls = mutableListOf<Pair<Int, String>>()
        var from = code.indexOf("assertTrue(")
        while (from >= 0) {
            val open = from + "assertTrue".length
            var depth = 0
            for (i in open until code.length) {
                when (code[i]) {
                    '(' -> depth++
                    ')' -> if (--depth == 0) { calls += from to text.substring(open + 1, i); break }
                }
            }
            from = code.indexOf("assertTrue(", from + 1)
        }
        return calls
    }

    private fun oneAssertionCarries(text: String, message: String, predicate: String): Int =
        assertCalls(text).firstOrNull { (_, args) -> args.contains(message) && args.contains(predicate) }?.first ?: -1

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

    // The lexer's controls: each non-code state hides an assertion; only real code is found.
    @Test
    fun onlyCodeCanSatisfyARow() {
        val real = "fun f() {\n    assertTrue(\"The fixture is missing\", File(fixturePath).isFile)\n}\n"
        val hidden = listOf(
            "line comment" to "fun f() {\n    // assertTrue(\"The fixture is missing\", File(fixturePath).isFile)\n}\n",
            "block comment" to "fun f() {\n    /* assertTrue(\"The fixture is missing\", File(fixturePath).isFile) */\n}\n",
            "nested block comment" to "fun f() {\n    /* a /* b */ assertTrue(\"The fixture is missing\", File(fixturePath).isFile) */\n}\n",
            "string" to "fun f() {\n    val s = \"assertTrue(The fixture is missing, File(fixturePath).isFile)\"\n}\n",
            "raw string" to "fun f() {\n    val s = \"\"\"assertTrue(\"The fixture is missing\", File(fixturePath).isFile)\"\"\"\n}\n",
        )
        assertTrue("a real assertion is found", oneAssertionCarries(real, "The fixture is missing", "File(fixturePath).isFile") >= 0)
        for ((label, text) in hidden) {
            assertEquals("an assertion inside a $label is not code", -1, oneAssertionCarries(text, "The fixture is missing", "File(fixturePath).isFile"))
        }
        assertFalse("an assumeTrue inside a comment is not a call", hasCodeCall("// assumeTrue(x)\n", "assumeTrue"))
        assertTrue("a real assumeTrue is a call", hasCodeCall("assumeTrue(x)\n", "assumeTrue"))
        assertEquals("the mask keeps every index", real.length, codeMask(real).length)
    }
}
