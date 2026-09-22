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

    // REVERT: put back `assumeTrue(... File(fixturePath).isFile)` in the row.
    @Test
    fun theRealBoundaryRowAssertsItsFixture() {
        val row = body("fun transcribesThenPolishesWithSavedCustomWords()")
        assertFalse("no assumption in the real-boundary row", row.contains("assumeTrue("))
        assertTrue("the fixture is asserted", Regex("""assertTrue\(\s*"The real-model fixture is missing""").containsMatchIn(row))
        assertTrue(row.contains("File(fixturePath).isFile"))
    }

    // REVERT: remove the saved-user-term assertion, or check the merged vocabulary instead.
    @Test
    fun theSavedNameIsAPrerequisiteReadFromTheUserTermsAlone() {
        val row = body("fun transcribesThenPolishesWithSavedCustomWords()")
        val read = row.indexOf("val userTerms = runBlocking { CustomTermRepository(context).list() }.map(CustomTermRecord::term)")
        val check = row.indexOf("userTerms.any { it.spelling == \"Saurabh\" }")
        val merge = row.indexOf("BuiltinVocabulary.withUserTerms(userTerms)")
        assertTrue("the user terms are read on their own", read >= 0)
        assertTrue("the saved name is checked on the user terms, before the merge", check in (read + 1) until merge)
        assertTrue("with the exact prefix the harness door classifies", row.contains("\"Prerequisite: the saved custom name 'Saurabh'"))
    }

    // REVERT: put back the assumption in `SpeakerAudio.prepare`.
    @Test
    fun theSpeakerSourceAssertsTheSameFixture() {
        val prepare = body("private inner class SpeakerAudio").let { speaker ->
            speaker.substring(speaker.indexOf("override fun prepare()"), speaker.indexOf("override fun deliver()"))
        }
        assertFalse(prepare.contains("assumeTrue("))
        assertTrue(prepare.contains("File(fixturePath).isFile"))
    }
}
