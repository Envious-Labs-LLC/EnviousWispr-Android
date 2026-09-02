package com.envi.wispr.ui

import com.envi.wispr.providers.DiscoveredModel
import com.envi.wispr.providers.ModelAccess
import com.envi.wispr.providers.Provider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Product Outcome: when this fails the saved model vanishes from the picker, a locked row can be picked,
 * an unrated live model sorts above a rated one on a rated sort, or a power user with no list cannot
 * type the id they know.
 */
class ModelListPresentationTest {
    private val models = listOf(
        DiscoveredModel("gpt-5.6-terra", "Gpt 5.6 Terra", ModelAccess.AVAILABLE, false),
        DiscoveredModel("gpt-4.1-mini", "Gpt 4.1 Mini", ModelAccess.AVAILABLE, true),
        DiscoveredModel("gpt-9-new", "Gpt 9 New", ModelAccess.AVAILABLE, false),
        DiscoveredModel("gpt-5.6-sol", "Gpt 5.6 Sol", ModelAccess.UNAVAILABLE, false),
    )

    @Test fun filtersByIdOrDisplayNameCaseInsensitively() {
        val rows = ModelListPresentation.present(Provider.OPENAI, models, "MINI", ModelSort.SUGGESTED, "")
        assertEquals(listOf("gpt-4.1-mini"), rows.map { it.id })
        assertEquals(listOf("gpt-9-new"), ModelListPresentation.present(Provider.OPENAI, models, "9 new", ModelSort.SUGGESTED, "").map { it.id })
    }

    @Test fun decoratesKnownIdsAndLeavesUnknownOnesPlain() {
        val rows = ModelListPresentation.present(Provider.OPENAI, models, "", ModelSort.SUGGESTED, "")
        val terra = rows.first { it.id == "gpt-5.6-terra" }
        assertEquals("Reasoning, best value for most dictation", terra.note)
        assertEquals(2, terra.cost)
        val unknown = rows.first { it.id == "gpt-9-new" }
        assertEquals(null, unknown.note)
        assertEquals(null, unknown.cost)
        assertEquals("Recommended", rows.first { it.id == "gpt-4.1-mini" }.tag)
    }

    @Test fun ratedSortsPutUnratedIdsLastWithinTheirAccessGroup() {
        val cheapest = ModelListPresentation.present(Provider.OPENAI, models, "", ModelSort.CHEAPEST, "").map { it.id }
        assertEquals(listOf("gpt-4.1-mini", "gpt-5.6-terra", "gpt-9-new", "gpt-5.6-sol"), cheapest)
        val accurate = ModelListPresentation.present(Provider.OPENAI, models, "", ModelSort.ACCURATE, "").map { it.id }
        assertEquals(listOf("gpt-5.6-terra", "gpt-4.1-mini", "gpt-9-new", "gpt-5.6-sol"), accurate)
    }

    @Test fun lockedRowsAreNotSelectableUnlessTheyAreTheSavedModel() {
        val plain = ModelListPresentation.present(Provider.OPENAI, models, "", ModelSort.SUGGESTED, "")
        val sol = plain.first { it.id == "gpt-5.6-sol" }
        assertFalse(sol.selectable)
        assertEquals("Not available with this key", sol.note)
        val saved = ModelListPresentation.present(Provider.OPENAI, models, "", ModelSort.SUGGESTED, "gpt-5.6-sol").first { it.id == "gpt-5.6-sol" }
        assertTrue(saved.selectable)
        assertTrue(saved.current)
        assertEquals("Not available with this key", saved.note)
    }

    @Test fun aSavedModelMissingFromTheListIsPinnedFirstAndNotDuplicated() {
        val rows = ModelListPresentation.present(Provider.OPENAI, models, "", ModelSort.SUGGESTED, "gpt-old")
        assertEquals("gpt-old", rows.first().id)
        assertTrue(rows.first().current)
        assertEquals("Currently selected", rows.first().note)
        assertEquals(1, rows.count { it.id == "gpt-old" })
        val present = ModelListPresentation.present(Provider.OPENAI, models, "", ModelSort.SUGGESTED, "gpt-5.6-terra")
        assertEquals(1, present.count { it.id == "gpt-5.6-terra" })
    }

    @Test fun withNoListATypedValidIdBecomesARow() {
        val rows = ModelListPresentation.present(Provider.OPENAI, emptyList(), " gpt-typed ", ModelSort.SUGGESTED, "")
        assertEquals(listOf("gpt-typed"), rows.map { it.id })
        assertTrue(rows.single().typed)
        assertTrue(rows.single().selectable)
        assertEquals(emptyList<ModelRow>(), ModelListPresentation.present(Provider.OPENAI, emptyList(), "badid", ModelSort.SUGGESTED, ""))
        assertEquals(emptyList<ModelRow>(), ModelListPresentation.present(Provider.OPENAI, emptyList(), "", ModelSort.SUGGESTED, ""))
        val withSaved = ModelListPresentation.present(Provider.OPENAI, emptyList(), "", ModelSort.SUGGESTED, "gpt-old")
        assertEquals(listOf("gpt-old"), withSaved.map { it.id })
    }

    @Test fun countLineNamesTotalsAndAvailabilityAndCountsThePinnedSavedRow() {
        val rows = ModelListPresentation.present(Provider.OPENAI, models, "", ModelSort.SUGGESTED, "")
        assertEquals("4 models · 3 available", ModelListPresentation.countLine(rows, 4, ""))
        assertEquals("1 of 4 models", ModelListPresentation.countLine(rows, 1, "mini"))
        val withPinned = ModelListPresentation.present(Provider.OPENAI, models, "", ModelSort.SUGGESTED, "gpt-old")
        assertEquals(5, withPinned.size)
        assertEquals("5 models · 3 available", ModelListPresentation.countLine(withPinned, 5, ""))
        val typed = ModelListPresentation.present(Provider.OPENAI, emptyList(), "gpt-typed", ModelSort.SUGGESTED, "")
        assertEquals("0 models · 0 available", ModelListPresentation.countLine(typed, 0, ""))
    }

    @Test fun fastestSortUsesHighestSpeedThenLowestCostAndKeepsUnratedLast() {
        val fastest = ModelListPresentation.present(Provider.OPENAI, models, "", ModelSort.FASTEST, "").map { it.id }
        // gpt-4.1-mini speed 3; gpt-5.6-terra speed 2; gpt-9-new unrated; gpt-5.6-sol locked last.
        assertEquals(listOf("gpt-4.1-mini", "gpt-5.6-terra", "gpt-9-new", "gpt-5.6-sol"), fastest)
        val reordered = ModelListPresentation.present(Provider.OPENAI, models.reversed(), "", ModelSort.FASTEST, "").map { it.id }
        assertEquals(fastest, reordered)
    }
}
