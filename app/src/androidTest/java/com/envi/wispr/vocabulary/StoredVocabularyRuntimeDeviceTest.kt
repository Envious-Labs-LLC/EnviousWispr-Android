package com.envi.wispr.vocabulary

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/** Content-redacted acceptance check for the privately migrated phone library. */
class StoredVocabularyRuntimeDeviceTest {
    @Test
    fun everyStoredAliasRestoresToItsPreferredSpelling() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val terms = CustomTermRepository(context).list().map(CustomTermRecord::term)

        assertEquals(EXPECTED_USER_TERMS, terms.size)
        assertEquals(EXPECTED_ALIASES, terms.sumOf { it.aliases.size })
        val effectiveTerms = BuiltinVocabulary.withUserTerms(terms)
        assertEquals(EXPECTED_EFFECTIVE_TERMS, effectiveTerms.size)

        val matcher = StructuredTermRestorer.compile(effectiveTerms)
        terms.forEachIndexed { termIndex, term ->
            term.aliases.forEachIndexed { aliasIndex, alias ->
                assertEquals(
                    "Runtime alias mismatch at term $termIndex alias $aliasIndex",
                    term.spelling,
                    matcher.restore(alias),
                )
            }
        }
    }

    private companion object {
        const val EXPECTED_USER_TERMS = 36
        const val EXPECTED_ALIASES = 109
        const val EXPECTED_EFFECTIVE_TERMS = 43
    }
}
