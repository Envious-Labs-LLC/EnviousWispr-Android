package com.envi.wispr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import com.envi.wispr.KotlinSourceLexer.Kind
import com.envi.wispr.KotlinSourceLexer.body
import com.envi.wispr.KotlinSourceLexer.calls
import com.envi.wispr.KotlinSourceLexer.normalized
import com.envi.wispr.KotlinSourceLexer.callStarts
import com.envi.wispr.KotlinSourceLexer.codeMask
import com.envi.wispr.KotlinSourceLexer.view
import java.io.File

/**
 * Drift Guard (#215), not product coverage: the heart's one real ASR-and-local-polish row, and the speaker
 * source that plays the same fixture, ASSERT their stageable fixture instead of assuming it (a bare
 * instrumentation run reports an assumption as a pass), and the row checks the founder's saved term from
 * the repository alone, before built-ins are merged. Read off the source because the row runs on a device,
 * never in the JVM suite.
 *
 * ASSUMPTIONS are read off the RAW text, with no lexer: every assumption word anywhere in the file must be
 * the one plain import line or sit inside the one row allowed to skip ([ASSUMING_ROW], deferred to #225).
 * A comment or a string that merely mentions one fails too; erring that way is the safe side, and it also
 * closes aliases, wrappers and template expressions, which no reading of a single row could.
 *
 * The ASSERTION needs code, so a lexer ([KotlinSourceLexer.classify], shared since #305) sorts every character into CODE, COMMENT or STRING,
 * with `${...}` template bodies read as CODE. A call is a whole CODE identifier followed by `(`, and a
 * predicate counts only when the second argument, comments removed, IS that predicate. Assertion MESSAGES
 * are not checked: a message is a runtime value, so no reading of the source can pin it. The exact
 * `Prerequisite:` prefix the harness classifies is pinned by the harness rows in
 * `scripts/uat/test_wispr_eyes.py`.
 */
class VoicePipelineDeviceShapeTest {

    private val source = File("src/androidTest/java/com/envi/wispr/VoicePipelineDeviceTest.kt").readText()

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

    /** Any assumption word in the RAW [text], comments and strings included. */
    private val assumption = Regex("assume[A-Z]|Assume|Assumption")

    private fun mentionsAssumption(text: String) = assumption.containsMatchIn(text)

    /**
     * Every assumption word in [text] that is neither the exact plain import line nor inside the body of
     * [ASSUMING_ROW]: the ones that could skip some other row.
     */
    private fun strayAssumptions(text: String): List<String> {
        val import = "import org.junit.Assume.assumeTrue"
        val allowed = mutableListOf<IntRange>()
        Regex("(?m)^" + Regex.escape(import) + "$").findAll(text).forEach { allowed += it.range }
        if (codeMask(text).contains(ASSUMING_ROW)) {
            val start = codeMask(text).indexOf(ASSUMING_ROW)
            val open = codeMask(text).indexOf('{', start)
            allowed += open..(open + body(text, ASSUMING_ROW).length + 1)
        }
        return assumption.findAll(text)
            .filter { hit -> allowed.none { hit.range.first in it } }
            .map { text.substring(text.lastIndexOf('\n', it.range.first) + 1, text.indexOf('\n', it.range.first).let { e -> if (e < 0) text.length else e }).trim() }
            .toList()
    }

    private companion object {
        const val ASSUMING_ROW = "fun aDictationWithNoFieldToInsertIntoIsNotReportedToTheUserAsAFailure()"
    }

    // REVERT: put back `assumeTrue(... File(fixturePath).isFile)` in the row.
    @Test
    fun theRealBoundaryRowAssertsItsFixture() {
        val row = body(source, "fun transcribesThenPolishesWithSavedCustomWords()")
        assertFalse("no assumption word in the real-boundary row", mentionsAssumption(row))
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
        assertFalse("no assumption word in the speaker source", mentionsAssumption(prepare))
        assertTrue(
            "ONE assertion asserts the fixture predicate",
            assertsPredicate(prepare, "File(fixturePath).isFile") >= 0,
        )
    }

    // REVERT: add an assumption anywhere outside the no-field row (a wrapper, an alias import, a template).
    @Test
    fun assumptionsLiveOnlyInTheNoFieldRow() {
        assertEquals("assumption words outside the no-field row", emptyList<String>(), strayAssumptions(source))
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
        val d = "$"
        for (call in listOf(
            "val s = \"${d}{assumeTrue (x)}\"",
            "val s = \"a ${d}{f(\"}\")} ${d}{assumeTrue/* c */(x)} b\"",
            "val s = \"\"\"${d}{assumeTrue(x)}\"\"\"",
            "val s = \"${d}{\"${d}{assumeTrue(x)}\"}\"",
        )) {
            assertTrue("a call inside a template is a call: $call", hasCodeCall(call, "assumeTrue"))
        }
        assertFalse("a string after a template is still a string", hasCodeCall("val s = \"${d}{x} assumeTrue(x)\"", "assumeTrue"))
        assertFalse("a raw string ending in extra quotes closes once", hasCodeCall("val s = \"\"\"a\"\"\"\"\n// assumeTrue(x)\n", "assumeTrue"))
        val file = "import org.junit.Assume.assumeTrue\n" +
            "fun aDictationWithNoFieldToInsertIntoIsNotReportedToTheUserAsAFailure() {\n    assumeTrue(x)\n}\n"
        assertEquals("the allowed places are allowed", emptyList<String>(), strayAssumptions(file))
        for (stray in listOf(
            "fun need(x: Boolean) = assumeTrue(x)\n",
            "import org.junit.Assume.assumeTrue as ok\n",
            "import org.junit.Assume\n",
            "val s = \"${d}{assumeTrue(x)}\"\n",
            "// assumeTrue(x)\n",
            "fun r() { org.junit.Assume.assumeFalse(x) }\n",
        )) {
            assertTrue("a stray assumption is found: $stray", strayAssumptions(file + stray).isNotEmpty())
        }
        assertEquals("the views keep every index", real.length, codeMask(real).length)
    }
}
