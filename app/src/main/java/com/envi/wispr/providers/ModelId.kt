package com.envi.wispr.providers

/** Dated aliases for request capabilities and picker decoration. Never changes the transmitted id. */
internal object ModelId {
    /** Existing picker rule: valid ISO or compact snapshots, years 2000 through 2099. */
    fun withoutSnapshot(id: String): String? = snapshotBase(id, SNAPSHOT, 2099)

    /** Mac OpenAI effort rule: only ISO snapshots, with a real date and year at least 2000. */
    fun withoutIsoSnapshot(id: String): String? = snapshotBase(id, ISO_SNAPSHOT, 9999)

    private fun snapshotBase(id: String, pattern: Regex, lastYear: Int): String? {
        val match = pattern.find(id) ?: return null
        val (year, _, month, day) = match.destructured
        val parsed = runCatching { java.time.LocalDate.of(year.toInt(), month.toInt(), day.toInt()) }.getOrNull() ?: return null
        if (parsed.year !in 2000..lastYear) return null
        return id.substring(0, match.range.first)
    }

    // Matching separators exclude mixed date spellings; ASCII digits exclude look-alike numerals.
    private val SNAPSHOT = Regex("-([0-9]{4})(-?)([0-9]{2})\\2([0-9]{2})$")
    private val ISO_SNAPSHOT = Regex("-([0-9]{4})(-)([0-9]{2})\\2([0-9]{2})$")
}
