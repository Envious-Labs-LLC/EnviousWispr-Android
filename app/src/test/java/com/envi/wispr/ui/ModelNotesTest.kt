package com.envi.wispr.ui

import com.envi.wispr.providers.Provider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Product Outcome. The catalogue is the ONLY place a model is vetted before the app can recommend it, and
 * `ModelListPresentation.recommendedPick` prefers a catalogued model precisely because nothing a provider
 * serves says a word about retirement: OpenAI still lists and still answers a model months after
 * announcing its shutdown. `PolishLadder.defaultModel` then SAVES that recommendation, so a retired row
 * here is a user whose every dictation stops being polished.
 *
 * This restores a guard the repo already had and lost. `PolishModelCatalogTest`
 * `neverOffersOpenAiModelsAlreadyShutDownOrScheduledToShutDownSoon` was written on 2026-09-01
 * (`docs/feature-requests/issue-62-2026-09-01-ai-polish-ladder.md`) and went with `PolishModelCatalog`
 * when live discovery replaced it, leaving nothing to stop a retired id being added back.
 */
class ModelNotesTest {
    /**
     * Checked against OpenAI's own deprecations list (developers.openai.com/api/docs/deprecations) on
     * 2026-09-01. `gpt-5-chat-latest` shut down 2026-07-23; the rest are scheduled for 2026-10-23 or
     * 2026-12-11. **Re-read that page before adding any OpenAI row**, because a model can be listed,
     * answer a probe, and still be weeks from death.
     */
    private val retiredOpenAi = listOf(
        "gpt-5-chat-latest", "gpt-5-mini", "gpt-5-nano", "gpt-4.1-nano", "o4-mini", "o3-mini",
    )

    @Test fun theCatalogueNeverCarriesAModelThatIsShuttingDown() {
        val named = ModelNotes.all.map { it.name }
        retiredOpenAi.forEach { id ->
            assertTrue("$id is retired and must not be catalogued", id !in named)
            // And not reachable through the snapshot normaliser either, which is a second door into the
            // same rows: `gpt-5-mini-2025-08-07` must not inherit a `gpt-5-mini` row.
            assertTrue("$id is retired and must not be a shortlist entry", Provider.entries.none { id in ModelNotes.preferred(it) })
        }
    }

    /** Every shortlist entry names a row that exists, or the shortlist is pointing at nothing. */
    @Test fun everyShortlistEntryNamesACatalogueRow() {
        Provider.entries.forEach { provider ->
            ModelNotes.preferred(provider).forEach { id ->
                assertTrue("$provider shortlists $id, which has no row", ModelNotes.forId(provider, id) != null)
            }
        }
    }

    /** One row per id, or `forId` answers with whichever happened to be written first. */
    @Test fun theCatalogueNeverCarriesTheSameModelTwice() {
        val names = ModelNotes.all.map { it.name }
        assertEquals("duplicate catalogue rows", names.distinct(), names)
    }
}
