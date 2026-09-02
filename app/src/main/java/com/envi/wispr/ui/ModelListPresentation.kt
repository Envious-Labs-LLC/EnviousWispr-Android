package com.envi.wispr.ui

import com.envi.wispr.providers.DiscoveredModel
import com.envi.wispr.providers.ModelAccess
import com.envi.wispr.providers.ModelListRules
import com.envi.wispr.providers.Provider
import com.envi.wispr.providers.ProviderPolishClient

/** One row of the setup page's model list (#84). */
data class ModelRow(
    val id: String,
    val displayName: String,
    val note: String?,
    val tag: String?,
    val cost: Int?,
    val speed: Int?,
    val accuracy: Int?,
    val access: ModelAccess,
    /** UNAVAILABLE rows cannot be picked, except the saved model, which stays selectable and says why. */
    val selectable: Boolean,
    /** The saved model, pinned when the live list lacks it or shown with its access when present. */
    val current: Boolean,
    /** A typed id offered when there is no list at all. */
    val typed: Boolean = false,
)

object ModelListPresentation {
    /**
     * The ONE model to badge Recommended, or null when there is nothing worth badging (#99).
     *
     * The founder's complaint, measured on his own phone: 12 of 17 rows carried the badge, because
     * `ModelListRules.isRecommended` matches the token `flash` and Google's whole fast tier is named flash.
     * That is a CLASS test being read as a RANKING, and a badge on 70% of a list is a background colour.
     *
     * The class test is kept — it still groups the cheap fast models above the rest in the sort, which is
     * genuinely useful — and only the BADGE narrows to one row.
     *
     * Three steps, and the second is what makes this answerable for a user who is not us:
     * 1. The provider's preferred id, first one the key actually returned.
     * 2. Otherwise the cheapest of the recommended class, ties to the faster one, unrated ids last. So a
     *    key that returns none of the preferred ids still gets a real recommendation rather than none.
     * 3. Otherwise null. A list with nothing available has nothing to recommend, and saying nothing is
     *    honest where inventing a winner is not.
     *
     * AVAILABLE only, never UNVERIFIED: recommending a model the probe could not reach is worse than
     * recommending nothing. Before this, `gemini-omni-1.1-flash` was badged while unverified.
     */
    fun recommendedPick(provider: Provider, models: List<DiscoveredModel>): String? {
        val available = models.filter { it.access == ModelAccess.AVAILABLE }
        if (available.isEmpty()) return null
        ModelNotes.preferred(provider).firstOrNull { id -> available.any { it.id == id } }?.let { return it }
        return available
            .filter { it.recommended }
            .minWithOrNull(
                compareBy(
                    { ModelNotes.forId(provider, it.id)?.cost ?: Int.MAX_VALUE },
                    { -(ModelNotes.forId(provider, it.id)?.speed ?: 0) },
                    // Accuracy breaks the tie before the id does, so two models at the same price and speed
                    // are separated by which writes better rather than by which sorts earlier. The id is
                    // the last resort and exists only to keep the answer stable, never to decide it.
                    { -(ModelNotes.forId(provider, it.id)?.accuracy ?: 0) },
                    { it.id },
                ),
            )?.id
    }

    /**
     * The rows for the page: the live (or cached) models filtered by [query], ordered by [sort], the saved
     * model pinned first when the list lacks it; with NO models at all a non-blank valid [query] becomes
     * one typed row so a power user is never stranded.
     */
    fun present(provider: Provider, models: List<DiscoveredModel>, query: String, sort: ModelSort, savedModel: String): List<ModelRow> {
        val pick = recommendedPick(provider, models)
        val normalized = query.trim().lowercase()
        if (models.isEmpty()) {
            val typed = query.trim()
            val valid = typed.isNotEmpty() && typed.length <= ProviderPolishClient.MAX_MODEL_CHARS && typed.none(Char::isISOControl)
            val rows = mutableListOf<ModelRow>()
            if (savedModel.isNotBlank() && (normalized.isEmpty() || savedModel.lowercase().contains(normalized))) rows += pinned(savedModel)
            if (valid && typed != savedModel) rows += ModelRow(typed, typed, "Use this model id", null, null, null, null, ModelAccess.UNVERIFIED, selectable = true, current = false, typed = true)
            return rows
        }
        val decorated = models.map { model ->
            val notes = ModelNotes.forId(provider, model.id)
            ModelRow(
                id = model.id,
                displayName = model.displayName,
                note = when (model.access) {
                    ModelAccess.UNAVAILABLE -> "Not available with this key"
                    ModelAccess.AVAILABLE, ModelAccess.UNVERIFIED -> notes?.note
                },
                tag = if (model.id == pick) "Recommended" else null,
                cost = notes?.cost,
                speed = notes?.speed,
                accuracy = notes?.accuracy,
                access = model.access,
                selectable = model.access != ModelAccess.UNAVAILABLE || model.id == savedModel,
                current = model.id == savedModel,
            )
        }
        val filtered = decorated.filter {
            normalized.isEmpty() || it.id.lowercase().contains(normalized) || it.displayName.lowercase().contains(normalized)
        }
        // Rated sorts: within each access group the rated ids first, ordered by their dots; unrated ids after.
        val sorted = when (sort) {
            // The one badged row leads the LIVE rows, because a recommendation nobody scrolls to is not
            // one: measured on the founder's phone 2026-09-02, the pick sat fifth and the list opened
            // with no badge in sight (#99). The rest keep discovery's order; the other three sorts each
            // mean something specific and are left to say it.
            //
            // One row still comes above it, deliberately: the pinned notice added below when the SAVED
            // model is missing from the refreshed catalogue. That row is not a suggestion, it is the news
            // that the model this user is currently polishing with has gone, and they need to read that
            // before being offered a replacement. `theRecommendedRowLeadsTheLiveRowsButNotTheStaleNotice`
            // pins the order so it stays a decision rather than an accident of list concatenation.
            ModelSort.SUGGESTED -> filtered.sortedBy { if (it.id == pick) 0 else 1 }
            ModelSort.CHEAPEST -> filtered.sortedWith(compareBy({ ModelListRules.accessRank(it.access) }, { it.cost == null }, { it.cost ?: 0 }, { -(it.speed ?: 0) }))
            ModelSort.FASTEST -> filtered.sortedWith(compareBy({ ModelListRules.accessRank(it.access) }, { it.speed == null }, { -(it.speed ?: 0) }, { it.cost ?: 0 }))
            ModelSort.ACCURATE -> filtered.sortedWith(compareBy({ ModelListRules.accessRank(it.access) }, { it.accuracy == null }, { -(it.accuracy ?: 0) }, { it.cost ?: 0 }))
        }
        val pinnedRow = if (savedModel.isNotBlank() && models.none { it.id == savedModel } &&
            (normalized.isEmpty() || savedModel.lowercase().contains(normalized))
        ) pinned(savedModel) else null
        return listOfNotNull(pinnedRow) + sorted
    }

    private fun pinned(savedModel: String) = ModelRow(
        savedModel, savedModel, "Currently selected", null, null, null, null, ModelAccess.UNVERIFIED,
        selectable = true, current = true,
    )

    /** The count line over the rows the page can show (the pinned saved row included): "17 models · 15 available", or the filtered form. */
    fun countLine(rows: List<ModelRow>, shown: Int, query: String): String {
        val real = rows.filter { !it.typed }
        val available = real.count { it.access == ModelAccess.AVAILABLE }
        val total = real.size
        return if (query.isNotBlank()) "$shown of $total models" else "$total models · $available available"
    }

    /** The saved model sorted by the same rule the client uses, so the page and the client agree. */
    fun sorted(models: List<DiscoveredModel>): List<DiscoveredModel> = ModelListRules.sort(models)
}
