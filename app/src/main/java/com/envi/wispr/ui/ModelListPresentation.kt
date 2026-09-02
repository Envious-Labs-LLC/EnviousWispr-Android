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
     * The rows for the page: the live (or cached) models filtered by [query], ordered by [sort], the saved
     * model pinned first when the list lacks it; with NO models at all a non-blank valid [query] becomes
     * one typed row so a power user is never stranded.
     */
    fun present(provider: Provider, models: List<DiscoveredModel>, query: String, sort: ModelSort, savedModel: String): List<ModelRow> {
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
                tag = if (model.recommended) "Recommended" else null,
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
            ModelSort.SUGGESTED -> filtered
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
