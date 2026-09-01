package com.envi.wispr.polish

import com.envi.wispr.cleanup.CleanupOptions
import com.envi.wispr.cleanup.PolishPipeline
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Product Outcome: when this fails, the words a user gets depend on which side of the binder
 * failed. The retired regex polisher capitalised sentences and appended a period, so "hello world"
 * came back as "Hello world." from the session owner and "hello world" from the engine.
 */
class DeterministicFallbackTest {

    @Test fun sessionOwnerFallbackAndEngineOffPathReturnTheSameLiteral() {
        val options = CleanupOptions()
        assertEquals("hello world", PolishFallback.deterministic("hello world", options))
        assertEquals("hello world", PolishPipeline.run("hello world", options).text)
    }

    /**
     * Drift Guard, not product coverage: the two rows above prove the shared helper's text, and this
     * one proves the session owner still CALLS it. Restoring the regex polisher only inside
     * `DictationSessionService.deterministicFallback` would leave the rows above green.
     */
    @Test fun sessionOwnerUsesTheSharedDeterministicFallback() {
        val source = java.io.File("src/main/java/com/envi/wispr/ui/DictationSessionService.kt").readText()
        assertTrue(source.contains("PolishFallback.deterministic(prepared, takePreferences.cleanup)"))
        assertFalse(source.contains("RegexPolisher"))
    }

    @Test fun fallbackStillAppliesTheTakeCleanupOptions() {
        assertEquals("hello world", PolishFallback.deterministic("um hello world", CleanupOptions(removeFillers = true)))
        assertEquals("um hello world", PolishFallback.deterministic("um hello world", CleanupOptions(removeFillers = false)))
    }
}
