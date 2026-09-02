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
 * the list does not open on the newest model, or a power user with no list cannot type the id they know.
 */
class ModelListPresentationTest {
    private val models = listOf(
        DiscoveredModel("gpt-5.6-terra", "Gpt 5.6 Terra", ModelAccess.AVAILABLE, false),
        DiscoveredModel("gpt-4.1-mini", "Gpt 4.1 Mini", ModelAccess.AVAILABLE, true),
        DiscoveredModel("gpt-9-new", "Gpt 9 New", ModelAccess.AVAILABLE, false),
        DiscoveredModel("gpt-5.6-sol", "Gpt 5.6 Sol", ModelAccess.UNAVAILABLE, false),
    )

    @Test fun filtersByIdOrDisplayNameCaseInsensitively() {
        val rows = ModelListPresentation.present(Provider.OPENAI, models, "MINI", "")
        assertEquals(listOf("gpt-4.1-mini"), rows.map { it.id })
        assertEquals(listOf("gpt-9-new"), ModelListPresentation.present(Provider.OPENAI, models, "9 new", "").map { it.id })
    }

    @Test fun decoratesKnownIdsAndLeavesUnknownOnesPlain() {
        val rows = ModelListPresentation.present(Provider.OPENAI, models, "", "")
        val terra = rows.first { it.id == "gpt-5.6-terra" }
        assertEquals("Reasoning, best value for most dictation", terra.note)
        assertEquals(2, terra.cost)
        val unknown = rows.first { it.id == "gpt-9-new" }
        assertEquals(null, unknown.note)
        assertEquals(null, unknown.cost)
        assertEquals("Recommended", rows.first { it.id == "gpt-4.1-mini" }.tag)
    }

    /**
     * Product Outcome. Renamed from lockedRowsAreNotSelectableUnlessTheyAreTheSavedModel, whose first half
     * asserted that an unusable row is SHOWN and untappable. On mobile it is now hidden outright (#104),
     * so that half describes a behaviour that no longer exists; what survives, and matters more, is that
     * the model you are CURRENTLY RUNNING is never hidden from you even when it turns unusable.
     */
    @Test fun anUnusableRowIsHiddenUnlessItIsTheModelYouAreRunning() {
        // Not the saved model: gone from the list entirely, rather than shown greyed out as on desktop.
        val plain = ModelListPresentation.present(Provider.OPENAI, models, "", "")
        assertTrue(plain.none { it.id == "gpt-5.6-sol" })

        // The saved model is never hidden, however unusable it has become, and stays selectable so the
        // user can see what they are running and change it. The note still says why it is a problem.
        val saved = ModelListPresentation.present(Provider.OPENAI, models, "", "gpt-5.6-sol").first { it.id == "gpt-5.6-sol" }
        assertTrue(saved.selectable)
        assertTrue(saved.current)
        assertEquals("Not available with this key", saved.note)
    }

    @Test fun aSavedModelMissingFromTheListIsPinnedFirstAndNotDuplicated() {
        val rows = ModelListPresentation.present(Provider.OPENAI, models, "", "gpt-old")
        assertEquals("gpt-old", rows.first().id)
        assertTrue(rows.first().current)
        assertEquals("Currently selected", rows.first().note)
        assertEquals(1, rows.count { it.id == "gpt-old" })
        val present = ModelListPresentation.present(Provider.OPENAI, models, "", "gpt-5.6-terra")
        assertEquals(1, present.count { it.id == "gpt-5.6-terra" })
    }

    @Test fun withNoListATypedValidIdBecomesARow() {
        val rows = ModelListPresentation.present(Provider.OPENAI, emptyList(), " gpt-typed ", "")
        assertEquals(listOf("gpt-typed"), rows.map { it.id })
        assertTrue(rows.single().typed)
        assertTrue(rows.single().selectable)
        assertEquals(emptyList<ModelRow>(), ModelListPresentation.present(Provider.OPENAI, emptyList(), "badid", ""))
        assertEquals(emptyList<ModelRow>(), ModelListPresentation.present(Provider.OPENAI, emptyList(), "", ""))
        val withSaved = ModelListPresentation.present(Provider.OPENAI, emptyList(), "", "gpt-old")
        assertEquals(listOf("gpt-old"), withSaved.map { it.id })
    }

    @Test fun countLineNamesTotalsAndAvailabilityAndCountsThePinnedSavedRow() {
        val rows = ModelListPresentation.present(Provider.OPENAI, models, "", "")
        // Three rows, not four: the unusable one is hidden now. And "checked", not "available", because an
        // untested row is not a broken one (#104).
        assertEquals("3 models · 3 checked", ModelListPresentation.countLine(rows, 3, ""))
        assertEquals("1 of 3 models", ModelListPresentation.countLine(rows, 1, "mini"))
        val withPinned = ModelListPresentation.present(Provider.OPENAI, models, "", "gpt-old")
        assertEquals(4, withPinned.size)
        assertEquals("4 models · 3 checked", ModelListPresentation.countLine(withPinned, 4, ""))
        val typed = ModelListPresentation.present(Provider.OPENAI, emptyList(), "gpt-typed", "")
        assertEquals("0 models · 0 checked", ModelListPresentation.countLine(typed, 0, ""))
    }

    /**
     * Product Outcome. Replaces the two rated-sort tests deleted with the sort chips they covered (#101).
     * They protected orderings that no longer exist; what a user can still be denied is a list that does
     * not open on the newest model, which is what this asserts instead.
     */
    @Test fun modelsAreOrderedNewestFirstWithUndatedOnesLast() {
        val day = 24 * 60 * 60 * 1000L
        val dated = listOf(
            DiscoveredModel("gpt-old", "Old", ModelAccess.AVAILABLE, false, releasedAt = 100 * day),
            DiscoveredModel("gpt-newest", "Newest", ModelAccess.AVAILABLE, false, releasedAt = 900 * day),
            DiscoveredModel("gpt-undated", "Undated", ModelAccess.AVAILABLE, false, releasedAt = null),
            DiscoveredModel("gpt-middle", "Middle", ModelAccess.AVAILABLE, false, releasedAt = 500 * day),
        )
        val ids = ModelListPresentation.present(Provider.OPENAI, dated, "", "").map { it.id }
        assertEquals(listOf("gpt-newest", "gpt-middle", "gpt-old", "gpt-undated"), ids)

        // The input order must not decide the output order, or the sort is not doing the work.
        assertEquals(ids, ModelListPresentation.present(Provider.OPENAI, dated.reversed(), "", "").map { it.id })

        // A model the key cannot reach is not shown at all, however new it is (#104). It used to sort last;
        // now being newest cannot drag an unusable row onto the screen.
        val lockedButNew = dated + DiscoveredModel("gpt-locked", "Locked", ModelAccess.UNAVAILABLE, false, releasedAt = 9_999 * day)
        assertEquals(ids, ModelListPresentation.present(Provider.OPENAI, lockedButNew, "", "").map { it.id })
    }

    /**
     * Product Outcome. Gemini publishes NO release date, measured 2026-09-02, so its order comes entirely
     * from the dates researched into ModelNotes. When this fails his list is in an arbitrary order.
     */
    @Test fun geminiIsOrderedFromTheDatesWeResearchedBecauseItsApiSendsNone() {
        val fromGemini = listOf("gemini-2.5-flash", "gemini-3.8-flash", "gemini-3.5-flash", "gemini-3.6-flash")
            .map { DiscoveredModel(it, it, ModelAccess.AVAILABLE, false, releasedAt = null) }
        val ids = ModelListPresentation.present(Provider.GEMINI, fromGemini, "", "").map { it.id }
        assertEquals(listOf("gemini-3.8-flash", "gemini-3.6-flash", "gemini-3.5-flash", "gemini-2.5-flash"), ids)

        // Every dated row really does carry a date, so an undated one is a gap in the table rather than
        // the sort quietly falling back to input order.
        fromGemini.forEach { assertTrue(it.id, ModelListPresentation.releaseDateOf(Provider.GEMINI, it) != null) }
    }

    /**
     * Product Outcome. Hiding rows makes them unreachable unless the search offers the typed id, and the
     * rows hidden are exactly the ones a power user is most likely to know by name (#104).
     */
    @Test fun aSearchThatMatchesNothingStillLetsYouTypeAModelId() {
        val typed = ModelListPresentation.present(Provider.OPENAI, models, "gpt-5.6-sol", "")
        assertEquals(listOf("gpt-5.6-sol"), typed.map { it.id })
        assertTrue(typed.single().typed)
        assertTrue(typed.single().selectable)

        // A search that DOES match offers the matches, not a typed row on top of them.
        val matched = ModelListPresentation.present(Provider.OPENAI, models, "mini", "")
        assertTrue(matched.none { it.typed })

        // Rubbish is not offered as a model id.
        assertTrue(ModelListPresentation.present(Provider.OPENAI, models, "  ", "").none { it.typed })
    }

    /** The provider's own date beats the table, because a date we typed cannot be fresher than theirs. */
    @Test fun anApiDateWinsOverTheOneWeWroteDown() {
        val fromApi = 9_999L * 24 * 60 * 60 * 1000
        val model = DiscoveredModel("gemini-2.5-flash", "x", ModelAccess.AVAILABLE, false, releasedAt = fromApi)
        assertEquals(fromApi, ModelListPresentation.releaseDateOf(Provider.GEMINI, model))
    }
}
