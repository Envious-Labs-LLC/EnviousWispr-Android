package com.envi.wispr.polish

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Modifier

/**
 * PRODUCT OUTCOME for [PolishEngineLabels.historySummary], and a DRIFT GUARD for the constants.
 *
 * When the summary fails, the expanded History card tells the user the wrong thing about how their
 * dictation was polished. When a constant's VALUE changes, the rows already stored carrying that
 * value stop matching, so they silently take the `else` branch: change [PolishEngineLabels.OFF] and
 * every existing `Polish off` row reads "Polished by Polish off".
 *
 * REVERT that turns the drift half red: change any constant's VALUE while leaving its name alone.
 */
class PolishEngineLabelsTest {

    @Test
    fun theStoredValuesAreTheOnesAlreadyInTheDatabase() {
        assertEquals("", PolishEngineLabels.NOT_RECORDED)
        assertEquals("No speech", PolishEngineLabels.NO_SPEECH)
        assertEquals("Polish off", PolishEngineLabels.OFF)
        assertEquals("Deterministic fallback", PolishEngineLabels.DETERMINISTIC)
        assertEquals("Raw fallback", PolishEngineLabels.RAW_FALLBACK)
    }

    @Test
    fun aRowWithNoPolishRecordedSaysNothing() {
        assertEquals("", PolishEngineLabels.historySummary(PolishEngineLabels.NOT_RECORDED, 0L))
    }

    /**
     * Read off the OBJECT rather than a list written here, so a sentinel added to
     * [PolishEngineLabels] without a branch in `historySummary` is caught by this test on the day it
     * is added. A hand-kept list can only contain the members someone remembered.
     */
    @Test
    fun everySentinelReadsAsPlainEnglishRatherThanItsStoredValue() {
        val sentinels = PolishEngineLabels::class.java.declaredFields
            .filter { Modifier.isStatic(it.modifiers) && it.type == String::class.java }
            .onEach { it.isAccessible = true }
            .map { it.name to (it.get(null) as String) }
        assertTrue(
            "No String constants were found on PolishEngineLabels, so this test asserts nothing.",
            sentinels.size >= 5,
        )
        sentinels.forEach { (name, sentinel) ->
            val summary = PolishEngineLabels.historySummary(sentinel, 120L)
            assertTrue(
                "$name fell through to the engine-name branch, so the card reads '$summary'.",
                !summary.startsWith("Polished by"),
            )
            if (sentinel.isNotEmpty()) {
                assertTrue("$name produced an empty line.", summary.isNotEmpty())
            }
        }
    }

    @Test
    fun anEngineNameIsNamedAndTimed() {
        assertEquals(
            "Polished by Claude Sonnet in 812 ms",
            PolishEngineLabels.historySummary("Claude Sonnet", 812L),
        )
    }

    @Test
    fun anUntimedRunOmitsTheLatencyRatherThanClaimingZero() {
        assertEquals("Polished by Claude Sonnet", PolishEngineLabels.historySummary("Claude Sonnet", 0L))
        assertEquals("Cleaned up on this phone", PolishEngineLabels.historySummary(PolishEngineLabels.DETERMINISTIC, 0L))
    }

    @Test
    fun theOnPhoneCleanupIsNotDescribedAsAnEngine() {
        assertEquals(
            "Cleaned up on this phone in 4 ms",
            PolishEngineLabels.historySummary(PolishEngineLabels.DETERMINISTIC, 4L),
        )
    }

    @Test
    fun aSwitchedOffPolishSaysSoRatherThanNamingAnEngine() {
        assertEquals("AI Polish was off", PolishEngineLabels.historySummary(PolishEngineLabels.OFF, 0L))
    }

    @Test
    fun aPolishThatReturnedNothingSaysTheUsersOwnWordsWereKept() {
        assertEquals(
            "AI Polish returned nothing, so your own words were kept",
            PolishEngineLabels.historySummary(PolishEngineLabels.RAW_FALLBACK, 40L),
        )
    }

    // #77: a row that stored a failure opens with the sentence the completion surface showed.
    @Test
    fun aStoredFailureOpensWithItsSentenceOverTheEngineLine() {
        val line = PolishEngineLabels.historySummary(PolishEngineLabels.DETERMINISTIC, 12L, "HTTP_ERROR", 401, "cloud:GEMINI")
        assertEquals(
            "AI polish failed: Gemini rejected your API key. Check or replace it in Settings.\nCleaned up on this phone in 12 ms",
            line,
        )
    }

    @Test
    fun aRowFromAnOlderBuildRendersExactlyAsBefore() {
        assertEquals(
            PolishEngineLabels.historySummary(PolishEngineLabels.DETERMINISTIC, 12L),
            PolishEngineLabels.historySummary(PolishEngineLabels.DETERMINISTIC, 12L, "", 0, ""),
        )
        assertEquals("Cleaned up on this phone in 12 ms", PolishEngineLabels.historySummary(PolishEngineLabels.DETERMINISTIC, 12L, "", 0, ""))
    }

    @Test
    fun anUnknownStoredReasonOrContextSaysNothingAboutAFailure() {
        assertEquals("Cleaned up on this phone", PolishEngineLabels.historySummary(PolishEngineLabels.DETERMINISTIC, 0L, "RETIRED_REASON", 401, "cloud:GEMINI"))
        assertEquals("Cleaned up on this phone", PolishEngineLabels.historySummary(PolishEngineLabels.DETERMINISTIC, 0L, "HTTP_ERROR", 401, "legacy-token"))
        assertEquals("Cleaned up on this phone", PolishEngineLabels.historySummary(PolishEngineLabels.DETERMINISTIC, 0L, "HTTP_ERROR", 401, "off"))
    }

    @Test
    fun aHealthyPolishStoresItsReasonAndStillReadsAsPolishedBy() {
        assertEquals("Polished by Gemini in 700 ms", PolishEngineLabels.historySummary("Gemini", 700L, "POLISHED", 200, "cloud:GEMINI"))
    }
}
