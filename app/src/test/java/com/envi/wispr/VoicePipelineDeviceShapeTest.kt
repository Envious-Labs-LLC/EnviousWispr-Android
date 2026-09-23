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
 * the repository alone, before built-ins are merged. Read off the source because the row runs on a device,
 * never in the JVM suite.
 *
 * Code review kept finding "text that is not code standing for code" (rounds 1 to 4). A lexer ([classify])
 * closes it for calls and predicates: a call is a CODE identifier followed by `(`, and a predicate counts
 * only when the second argument, comments removed, IS that predicate. Assertion MESSAGES are not checked
 * here: a message is a runtime value (templates, branches), so no reading of the source can pin it. The
 * exact `Prerequisite:` prefix the harness classifies is pinned by the harness rows in
 * `scripts/uat/test_wispr_eyes.py`.
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

    /**
     * The start of every CODE call of [name] in [text]: the identifier, whole (no identifier character on
     * either side), followed by optional whitespace or comments and then `(`.
     */
    private fun callStarts(text: String, name: String): List<Pair<Int, Int>> {
        val code = codeMask(text)
        fun identifier(c: Char) = c.isLetterOrDigit() || c == '_'
        val starts = mutableListOf<Pair<Int, Int>>()
        var at = code.indexOf(name)
        while (at >= 0) {
            val end = at + name.length
            val before = at == 0 || !identifier(code[at - 1])
            var open = end
            while (open < code.length && code[open].isWhitespace()) open++
            if (before && (end == code.length || !identifier(code[end])) && open < code.length && code[open] == '(') {
                starts += at to open
            }
            at = code.indexOf(name, at + 1)
        }
        return starts
    }

    /** Every CODE call of [name] in [text]: its start and its argument texts, split at top-level code commas. */
    private fun calls(text: String, name: String): List<Pair<Int, List<String>>> {
        val code = codeMask(text)
        return callStarts(text, name).map { (from, open) ->
            var depth = 0
            var argStart = open + 1
            val args = mutableListOf<String>()
            for (i in open until code.length) {
                when (code[i]) {
                    '(', '{', '[' -> depth++
                    ')', '}', ']' -> {
                        depth--
                        if (depth == 0) { args += text.substring(argStart, i); break }
                    }
                    ',' -> if (depth == 1) { args += text.substring(argStart, i); argStart = i + 1 }
                }
            }
            from to args
        }
    }

    /**
     * The start of the CODE `assertTrue` call with exactly two arguments whose second argument, comments
     * removed, IS [predicate]; -1 when none.
     */
    private fun assertsPredicate(text: String, predicate: String): Int =
        calls(text, "assertTrue").firstOrNull { (_, rawArgs) ->
            val args = rawArgs.map { normalized(view(it, Kind.CODE, Kind.STRING)) }.filter { it.isNotEmpty() }
            args.size == 2 && args[1] == normalized(predicate)
        }?.first ?: -1

    private fun hasCodeCall(text: String, name: String): Boolean = callStarts(text, name).isNotEmpty()

    // REVERT: put back `assumeTrue(... File(fixturePath).isFile)` in the row.
    @Test
    fun theRealBoundaryRowAssertsItsFixture() {
        val row = body(source, "fun transcribesThenPolishesWithSavedCustomWords()")
        assertFalse("no assumption in the real-boundary row", hasCodeCall(row, "assumeTrue"))
        assertTrue(
            "ONE assertion asserts the fixture predicate",
            assertsPredicate(row, "File(fixturePath).isFile") >= 0,
        )
    }

    // REVERT: remove the saved-user-term assertion, or check the merged vocabulary instead.
    @Test
    fun theSavedNameIsAPrerequisiteReadFromTheUserTermsAlone() {
        val row = body(source, "fun transcribesThenPolishesWithSavedCustomWords()")
        val code = codeMask(row)
        val read = code.indexOf("val userTerms = runBlocking { CustomTermRepository(context).list() }.map(CustomTermRecord::term)")
        val check = assertsPredicate(row, "userTerms.any { it.spelling == \"Saurabh\" }")
        val merge = code.indexOf("BuiltinVocabulary.withUserTerms(userTerms)")
        assertTrue("the user terms are read on their own, in code", read >= 0)
        assertTrue("ONE assertion asserts the user-term predicate, after the read and before the merge", check in (read + 1) until merge)
    }

    // REVERT: put back the assumption in `SpeakerAudio.prepare`.
    @Test
    fun theSpeakerSourceAssertsTheSameFixture() {
        val speaker = body(source, "private inner class SpeakerAudio")
        val prepare = body(speaker, "override fun prepare()")
        assertFalse(hasCodeCall(prepare, "assumeTrue"))
        assertTrue(
            "ONE assertion asserts the fixture predicate",
            assertsPredicate(prepare, "File(fixturePath).isFile") >= 0,
        )
    }

    // One control per way non-code text could stand for code, outside a call and inside a real one, and one
    // per legal spelling of a call.
    @Test
    fun onlyCodeCanSatisfyARow() {
        val predicate = "File(fixturePath).isFile"
        val real = "fun f() {\n    assertTrue(\"The fixture is missing\", File(fixturePath).isFile)\n}\n"
        assertTrue("a real assertion is found", assertsPredicate(real, predicate) >= 0)
        val spellings = listOf(
            "a space before the parenthesis" to "assertTrue (\"m\", File(fixturePath).isFile)",
            "a comment before the parenthesis" to "assertTrue/* c */(\"m\", File(fixturePath).isFile)",
            "a templated message" to "assertTrue(\"\"\"${'$'}{if (false) \"a\" else \"b\"}\"\"\", File(fixturePath).isFile)",
            "a trailing comma" to "assertTrue(\n    \"m\",\n    File(fixturePath).isFile,\n)",
        )
        for ((label, text) in spellings) {
            assertTrue("$label is a match", assertsPredicate(text, predicate) >= 0)
        }
        val hidden = listOf(
            "the whole call in a line comment" to "// assertTrue(\"m\", File(fixturePath).isFile)\n",
            "the whole call in a block comment" to "/* assertTrue(\"m\", File(fixturePath).isFile) */",
            "the whole call in a nested block comment" to "/* a /* b */ assertTrue(\"m\", File(fixturePath).isFile) */",
            "the whole call in a string" to "val s = \"assertTrue(m, File(fixturePath).isFile)\"",
            "the whole call in a raw string" to "val s = \"\"\"assertTrue(\"m\", File(fixturePath).isFile)\"\"\"",
            "the predicate only in a line comment" to "assertTrue(\"m\", true // File(fixturePath).isFile\n)",
            "the predicate only in a block comment" to "assertTrue(\"m\", true /* File(fixturePath).isFile */)",
            "the predicate only in the message" to "assertTrue(\"m File(fixturePath).isFile\", true)",
            "a predicate that merely contains the expected one" to "assertTrue(\"m\", File(fixturePath).isFile || true)",
            "a longer name ending in assertTrue" to "myassertTrue(\"m\", File(fixturePath).isFile)",
            "a longer name starting with assertTrue" to "assertTrueX(\"m\", File(fixturePath).isFile)",
            "the predicate as the only argument" to "assertTrue(File(fixturePath).isFile)",
        )
        for ((label, text) in hidden) {
            assertEquals("$label is not a match", -1, assertsPredicate(text, predicate))
        }
        for (call in listOf("assumeTrue(x)\n", "assumeTrue (x)\n", "assumeTrue/* c */(x)\n", "assumeTrue\n    (x)\n")) {
            assertTrue("a real assumeTrue is a call: $call", hasCodeCall(call, "assumeTrue"))
        }
        for (text in listOf("// assumeTrue(x)\n", "/* assumeTrue(x) */", "\"assumeTrue(x)\"", "notassumeTrue(x)", "assumeTrueNot(x)")) {
            assertFalse("not a call: $text", hasCodeCall(text, "assumeTrue"))
        }
        assertEquals("the views keep every index", real.length, codeMask(real).length)
    }
}
