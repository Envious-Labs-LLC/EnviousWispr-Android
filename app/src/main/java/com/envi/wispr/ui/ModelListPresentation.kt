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
    /** Release date in epoch millis, for the newest-first order; null when nobody publishes one. */
    val releasedAt: Long?,
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
        // WHAT MAY WEAR THE BADGE: the vendors' own small-and-fast tier, PLUS anything we shortlisted by
        // name. `ModelListRules.isRecommended` carries the tier on every row from the words `mini`, `nano`,
        // `flash` and `haiku` minus the disqualifiers, which is the founder's rule in his words — "what is
        // the cheapest Flash model now" — and the one matcher here whose members a vendor publishes rather
        // than us predicting them.
        //
        // The union is not optional. Those words are the tier NAMES of one generation, and OpenAI's newest
        // cheap-and-fast model is `gpt-5.6-luna`, which contains none of them; on the tier filter alone it
        // could never be badged even though it leads `ModelNotes.preferred(OPENAI)` (review round 6). A
        // model we named by hand is one we already chose to offer, so naming it IS the qualification.
        val preferred = ModelNotes.preferred(provider)
        fun rank(id: String): Int {
            val index = preferred.indexOfFirst { it == id || it == ModelNotes.withoutSnapshot(id) }
            return if (index < 0) preferred.size else index
        }
        val candidates = models.filter {
            it.access == ModelAccess.AVAILABLE && (it.recommended || rank(it.id) < preferred.size)
        }
        if (candidates.isEmpty()) return null
        return candidates.minWithOrNull(
            compareBy(
                // A MODEL WE HAVE A ROW FOR COMES FIRST, and this is a safety rule rather than a taste one
                // (#103 review round 3). The badge auto-saves through `PolishLadder.defaultModel`, so it
                // must not name a model that stops working. The catalogue is where a model was checked
                // against the vendor's own deprecations list, and nothing a provider serves says a word
                // about retirement: OpenAI still LISTS and still ANSWERS `gpt-5-mini` and `gpt-5-nano`,
                // both scheduled to shut down 2026-10-23, so a rule that trusted the live list alone would
                // have picked one seven weeks before it dies. `ModelNotesTest` holds the list of what has
                // been checked out.
                //
                // A key that can reach none of our rows still gets a badge from the rows below, because a
                // recommendation we cannot vouch for beats no recommendation at all on a key we have never
                // seen.
                { ModelNotes.forId(provider, it.id) == null },
                // NEWEST FIRST within that, because that is the recommendation and everything below only
                // breaks ties. Leading with `preferred` instead made a hand-written id beat any model, and
                // leading with COST had the same effect by a second route, because a model we have no row
                // for scores worse than one we do.
                { it.releasedAt == null && ModelNotes.forId(provider, it.id)?.released == null },
                { -(releaseDateOf(provider, it) ?: 0L) },
                // Same day: the founder's own shortlist decides, then the measures, then the id. He picked
                // Gemini 3.8 Flash by hand and that choice is kept, as a tie-break rather than an override.
                { rank(it.id) },
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
    /**
     * The release date to order a row by (#101): the provider's own if it sends one, else the date read
     * off the vendor's changelog into [ModelNotes], else null.
     *
     * The API wins over the table wherever it speaks, because a date we typed cannot be fresher than the
     * one the provider is serving today.
     */
    internal fun releaseDateOf(provider: Provider, model: DiscoveredModel): Long? =
        model.releasedAt ?: ModelNotes.forId(provider, model.id)?.released?.let(::parseDay)

    /** `YYYY-MM-DD` at UTC midnight. Null on anything else rather than a guess at what was meant. */
    private fun parseDay(day: String): Long? =
        runCatching { java.time.LocalDate.parse(day).atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli() }.getOrNull()

    fun present(provider: Provider, models: List<DiscoveredModel>, query: String, savedModel: String): List<ModelRow> {
        val pick = recommendedPick(provider, models)
        val normalized = query.trim().lowercase()
        if (models.isEmpty()) {
            val typed = query.trim()
            val valid = typed.isNotEmpty() && typed.length <= ProviderPolishClient.MAX_MODEL_CHARS && typed.none(Char::isISOControl)
            val rows = mutableListOf<ModelRow>()
            if (savedModel.isNotBlank() && (normalized.isEmpty() || savedModel.lowercase().contains(normalized))) rows += pinned(savedModel)
            if (valid && typed != savedModel) rows += ModelRow(typed, typed, "Use this model id", null, null, null, null, ModelAccess.UNVERIFIED, releasedAt = null, selectable = true, current = false, typed = true)
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
                releasedAt = releaseDateOf(provider, model),
                selectable = model.access != ModelAccess.UNAVAILABLE || model.id == savedModel,
                current = model.id == savedModel,
            )
        }
        // MOBILE SHOWS ONLY WHAT IT CAN OFFER (#104, founder 2026-09-02). Desktop locks an unusable model
        // and leaves it on screen; a phone has no room for a row nobody may tap.
        //
        // UNVERIFIED STAYS, and that is a decision rather than an omission (#104 review round 1, which
        // asked for it to be hidden too). After #104's probe fix UNVERIFIED means "not tested yet", not
        // "broken": the probe stops at MAX_PROBES to bound the network cost, so with his key listing 69
        // OpenAI models the last 29 are never probed however well they work. Hiding them would delete
        // working models from the list permanently, which is a worse failure than showing an untested one,
        // and the count line says how many were checked. The probe budget is spent NEWEST FIRST in
        // `ProviderPolishClient`, so the untested tail is the oldest rows rather than an arbitrary set.
        //
        // The SAVED model is never hidden even when it turns unusable, because a user has to be able to
        // see and change what they are currently running.
        val usable = decorated.filter { it.access != ModelAccess.UNAVAILABLE || it.id == savedModel }
        val filtered = usable.filter {
            normalized.isEmpty() || it.id.lowercase().contains(normalized) || it.displayName.lowercase().contains(normalized)
        }
        // NEWEST FIRST (#101, founder 2026-09-02, replacing four sort chips he found unhelpful).
        //
        // The one badged row still leads the LIVE rows, because a recommendation nobody scrolls to is not
        // one: measured on his phone 2026-09-02, the pick sat fifth and the list opened with no badge in
        // sight (#99). Newest-first is the ORGANISATION; the single highlight sits above it.
        //
        // An UNDATED model sorts after every dated one and keeps discovery's order among its peers. It is
        // not guessed into a position: a model nobody publishes a date for is genuinely unplaceable, and a
        // wrong date reorders the list invisibly. The visible consequence is that a brand-new Gemini model
        // appears at the BOTTOM until its row is added to `ModelNotes`, which is the prompt to add it.
        //
        // One row still comes above the pick, deliberately: the pinned notice added below when the SAVED
        // model is missing from the refreshed catalogue. That row is not a suggestion, it is the news that
        // the model this user is currently polishing with has gone, and they need to read that before
        // being offered a replacement. `theRecommendedRowLeadsTheLiveRowsButNotTheStaleNotice` pins it.
        val sorted = filtered.sortedWith(
            compareBy(
                { if (it.id == pick) 0 else 1 },
                { ModelListRules.accessRank(it.access) },
                { it.releasedAt == null },
                { -(it.releasedAt ?: 0L) },
            ),
        )
        val pinnedRow = if (savedModel.isNotBlank() && models.none { it.id == savedModel } &&
            (normalized.isEmpty() || savedModel.lowercase().contains(normalized))
        ) pinned(savedModel) else null
        // A search that matches nothing offers the typed id, which used to happen only when the whole list
        // was empty. Hiding rows without this makes a hidden model permanently unreachable, and the rows
        // hidden above are the ones a power user is most likely to know by name.
        val typed = query.trim()
        val typedRow = if (sorted.isEmpty() && pinnedRow == null && typed.isNotEmpty() &&
            typed.length <= ProviderPolishClient.MAX_MODEL_CHARS && typed.none(Char::isISOControl) && typed != savedModel
        ) {
            ModelRow(typed, typed, "Use this model id", null, null, null, null, ModelAccess.UNVERIFIED, releasedAt = null, selectable = true, current = false, typed = true)
        } else null
        return listOfNotNull(pinnedRow) + sorted + listOfNotNull(typedRow)
    }

    private fun pinned(savedModel: String) = ModelRow(
        savedModel, savedModel, "Currently selected", null, null, null, null, ModelAccess.UNVERIFIED,
        releasedAt = null, selectable = true, current = true,
    )

    /** The count line over the rows the page can show (the pinned saved row included): "17 models · 15 checked", or the filtered form. */
    fun countLine(rows: List<ModelRow>, shown: Int, query: String): String {
        val real = rows.filter { !it.typed }
        // "checked", not "available": an untested row is not a broken one, and the old wording read as
        // "33 of these do not work" when they had simply never been probed (#104).
        //
        // So the count is of rows that got a VERDICT, either way, rather than of rows that passed. The
        // saved model is shown even when it turns unusable, and it was probed like everything else;
        // counting only the passes reported "4 models · 3 checked" over four checked rows (review round 3).
        val checked = real.count { it.access != ModelAccess.UNVERIFIED }
        val total = real.size
        return if (query.isNotBlank()) "$shown of $total models" else "$total models · $checked checked"
    }

    /** The saved model sorted by the same rule the client uses, so the page and the client agree. */
    fun sorted(models: List<DiscoveredModel>): List<DiscoveredModel> = ModelListRules.sort(models)
}
