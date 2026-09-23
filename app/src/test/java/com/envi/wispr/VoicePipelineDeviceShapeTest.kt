package com.envi.wispr

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
 */
class VoicePipelineDeviceShapeTest {

    private val source = File("src/androidTest/java/com/envi/wispr/VoicePipelineDeviceTest.kt").readText()

    /** The text between the `{` that follows [signature] and its matching `}`, skipping string literals. */
    private fun body(signature: String): String {
        val start = source.indexOf(signature)
        assertTrue("$signature must exist", start >= 0)
        val open = source.indexOf('{', start)
        var depth = 0
        var i = open
        var inString = false
        while (i < source.length) {
            val c = source[i]
            if (inString) {
                if (c == '\\') i++ else if (c == '"') inString = false
            } else when (c) {
                '"' -> inString = true
                '{' -> depth++
                '}' -> if (--depth == 0) return source.substring(open + 1, i)
            }
            i++
        }
        error("unbalanced $signature")
    }

    /**
     * Every `assertTrue(` call's argument text in [text], balanced, skipping string literals and comments, so a
     * row can require ONE assertion to carry both its message and its predicate.
     */
    private fun assertCalls(text: String): List<Pair<Int, String>> {
        val calls = mutableListOf<Pair<Int, String>>()
        var from = text.indexOf("assertTrue(")
        while (from >= 0) {
            val open = from + "assertTrue".length
            var depth = 0
            var i = open
            var inString = false
            while (i < text.length) {
                val c = text[i]
                if (inString) {
                    if (c == '\\') i++ else if (c == '"') inString = false
                } else if (c == '/' && i + 1 < text.length && text[i + 1] == '/') {
                    i = text.indexOf('\n', i).let { if (it < 0) text.length else it }
                    continue
                } else when (c) {
                    '"' -> inString = true
                    '(' -> depth++
                    ')' -> if (--depth == 0) { calls += from to text.substring(open + 1, i); break }
                }
                i++
            }
            from = text.indexOf("assertTrue(", from + 1)
        }
        return calls
    }

    private fun oneAssertionCarries(text: String, message: String, predicate: String): Int =
        assertCalls(text).firstOrNull { (_, args) -> args.contains(message) && args.contains(predicate) }?.first ?: -1

    // REVERT: put back `assumeTrue(... File(fixturePath).isFile)` in the row.
    @Test
    fun theRealBoundaryRowAssertsItsFixture() {
        val row = body("fun transcribesThenPolishesWithSavedCustomWords()")
        assertFalse("no assumption in the real-boundary row", row.contains("assumeTrue("))
        assertTrue(
            "ONE assertion carries the fixture message and the fixture predicate",
            oneAssertionCarries(row, "The real-model fixture is missing", "File(fixturePath).isFile") >= 0,
        )
    }

    // REVERT: remove the saved-user-term assertion, or check the merged vocabulary instead.
    @Test
    fun theSavedNameIsAPrerequisiteReadFromTheUserTermsAlone() {
        val row = body("fun transcribesThenPolishesWithSavedCustomWords()")
        val read = row.indexOf("val userTerms = runBlocking { CustomTermRepository(context).list() }.map(CustomTermRecord::term)")
        val check = oneAssertionCarries(row, "\"Prerequisite: the saved custom name 'Saurabh'", "userTerms.any { it.spelling == \"Saurabh\" }")
        val merge = row.indexOf("BuiltinVocabulary.withUserTerms(userTerms)")
        assertTrue("the user terms are read on their own", read >= 0)
        assertTrue("ONE assertion carries the exact prefix and the user-term predicate, after the read and before the merge", check in (read + 1) until merge)
    }

    // REVERT: put back the assumption in `SpeakerAudio.prepare`.
    @Test
    fun theSpeakerSourceAssertsTheSameFixture() {
        val prepare = body("private inner class SpeakerAudio").let { speaker ->
            speaker.substring(speaker.indexOf("override fun prepare()"), speaker.indexOf("override fun deliver()"))
        }
        assertFalse(prepare.contains("assumeTrue("))
        assertTrue(
            "ONE assertion carries the fixture message and predicate",
            oneAssertionCarries(prepare, "The fixture is missing", "File(fixturePath).isFile") >= 0,
        )
    }
}
