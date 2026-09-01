package com.envi.wispr.ui

import com.envi.wispr.providers.Provider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Product Outcome: when this fails, the user sees the wrong model, or the wrong order, in the picker. */
class PolishModelCatalogTest {

    @Test fun filtersByNameCaseInsensitively() {
        val results = PolishModelCatalog.filterAndSort(Provider.OPENAI, "MINI", ModelSort.SUGGESTED, savedModel = "")
        assertTrue(results.isNotEmpty())
        assertTrue(results.all { it.name.contains("mini", ignoreCase = true) })
    }

    @Test fun blankQueryReturnsEveryModelForTheProvider() {
        val results = PolishModelCatalog.filterAndSort(Provider.CLAUDE, "", ModelSort.SUGGESTED, savedModel = "")
        assertEquals(PolishModelCatalog.modelsFor(Provider.CLAUDE).size, results.size)
    }

    @Test fun cheapestSortOrdersByLowestCostThenHighestSpeed() {
        val results = PolishModelCatalog.filterAndSort(Provider.OPENAI, "", ModelSort.CHEAPEST, savedModel = "")
        for (i in 0 until results.size - 1) {
            assertTrue(results[i].cost <= results[i + 1].cost)
        }
    }

    @Test fun fastestSortOrdersByHighestSpeedFirst() {
        val results = PolishModelCatalog.filterAndSort(Provider.GEMINI, "", ModelSort.FASTEST, savedModel = "")
        for (i in 0 until results.size - 1) {
            assertTrue(results[i].speed >= results[i + 1].speed)
        }
    }

    @Test fun accurateSortOrdersByHighestAccuracyFirst() {
        val results = PolishModelCatalog.filterAndSort(Provider.OPENAI, "", ModelSort.ACCURATE, savedModel = "")
        for (i in 0 until results.size - 1) {
            assertTrue(results[i].accuracy >= results[i + 1].accuracy)
        }
    }

    @Test fun preservesAModelNameNotInTheCatalogAsTheCurrentSelection() {
        val results = PolishModelCatalog.filterAndSort(
            Provider.OPENAI,
            "",
            ModelSort.SUGGESTED,
            savedModel = "gpt-legacy-custom-deploy",
        )
        assertEquals("gpt-legacy-custom-deploy", results.first().name)
        assertNull(results.first().tag)
    }

    @Test fun theCountIncludesThePreservedLegacyModelRow() {
        // Regression for a real bug caught in code review: the model count shown to the user must
        // count exactly what the list shows, including a preserved row for a legacy saved model,
        // never just the curated catalog entries.
        val withoutLegacy = PolishModelCatalog.filterAndSort(Provider.OPENAI, "", ModelSort.SUGGESTED, savedModel = "")
        val withLegacy = PolishModelCatalog.filterAndSort(
            Provider.OPENAI,
            "",
            ModelSort.SUGGESTED,
            savedModel = "gpt-legacy-custom-deploy",
        )
        assertEquals(withoutLegacy.size + 1, withLegacy.size)
    }

    @Test fun aSavedModelAlreadyInTheCatalogDoesNotDuplicate() {
        val results = PolishModelCatalog.filterAndSort(Provider.OPENAI, "", ModelSort.SUGGESTED, savedModel = "gpt-5.6-terra")
        assertEquals(1, results.count { it.name == "gpt-5.6-terra" })
    }

    @Test fun selfHostedHasNoCatalogEntries() {
        assertTrue(PolishModelCatalog.modelsFor(Provider.SELF_HOSTED_POLISH).isEmpty())
    }

    @Test fun neverOffersOpenAiModelsUnsupportedByTheResponsesApi() {
        // Regression for a real bug caught in code review: ProviderPolishClient sends every OpenAI
        // request to /v1/responses, which does not support o1-mini — picking it would save
        // successfully and then fail on every dictation.
        assertFalse(PolishModelCatalog.modelsFor(Provider.OPENAI).any { it.name == "o1-mini" })
    }

    @Test fun neverOffersOpenAiModelsThatDoNotExistOnTheLiveModelList() {
        // Regression for a real bug caught in code review: gpt-5.5-mini was never a real OpenAI
        // model, so picking it saved successfully and then failed on every dictation. Verified against
        // developers.openai.com/api/docs/models, 2026-09-01: no gpt-5.4 or gpt-5.5 family exists there
        // at all, only gpt-5.6-sol/-terra/-luna.
        val names = PolishModelCatalog.modelsFor(Provider.OPENAI).map { it.name }
        assertFalse(names.any { it == "gpt-5.4-mini" || it == "gpt-5.5" || it == "gpt-5.5-mini" })
    }

    @Test fun neverOffersOpenAiModelsAlreadyShutDownOrScheduledToShutDownSoon() {
        // Regression for a real bug caught in code review: gpt-5-chat-latest was the "Suggested"
        // default here, but OpenAI's own deprecations list (developers.openai.com/api/docs
        // /deprecations, checked 2026-09-01) shows it already shut down on 2026-07-23 — picking the
        // FIRST recommended row would have saved a model that fails every dictation. The other five
        // are scheduled to shut down within a few months of this shipping.
        val names = PolishModelCatalog.modelsFor(Provider.OPENAI).map { it.name }
        assertFalse(
            names.any {
                it in setOf("gpt-5-chat-latest", "gpt-5-mini", "gpt-5-nano", "gpt-4.1-nano", "o4-mini", "o3-mini")
            },
        )
    }
}
