package com.envi.wispr.polish

/**
 * The vocabulary of `TranscriptEntity.polishEngine`, and the one place that turns it into a line a
 * person can read on a History card.
 *
 * Two files write that column and both use the constants below: `PolishService`, which names the
 * engine that produced the polished text, and `DictationSessionService`, which writes the draft
 * placeholder before any polish has run and the raw-fallback marker when polish produced nothing.
 * Every other value is an engine's own display name — a provider's, or the local model's — so the
 * `else` branch renders it verbatim. A NEW sentinel added without a branch here therefore reads as
 * "Polished by <sentinel>", which is wrong rather than absent; the constants exist so that grepping
 * one of them finds the producer and this reader together.
 *
 * The VALUES are stored in Room and read back for the life of a row, so a rename here changes what
 * rows written by older builds display. `PolishEngineLabelsTest` pins each one.
 */
object PolishEngineLabels {

    /** No polish has run yet. Written on the draft row the moment recording starts. */
    const val NOT_RECORDED = ""

    /** The microphone heard nothing, so there was no text to polish. */
    const val NO_SPEECH = "No speech"

    /** The user has AI Polish switched off. */
    const val OFF = "Polish off"

    /** The on-phone cleanup rules ran; no model was involved. */
    const val DETERMINISTIC = "Deterministic fallback"

    /** Polish returned nothing usable, so the words as spoken were kept. */
    const val RAW_FALLBACK = "Raw fallback"

    /**
     * @return the polish line for an expanded History card, or an empty string when the row has
     * nothing to say about polish. Since #77 a row that stored a failure opens with the same sentence the
     * completion surface showed, derived through the same `PolishFailure.from`; a row from an older build,
     * or one whose stored reason or context no longer decodes, renders the engine line alone.
     */
    fun historySummary(
        polishEngine: String,
        polishLatencyMs: Long,
        polishReason: String = "",
        polishStatus: Int = 0,
        polishContext: String = "",
    ): String {
        val engineLine = when (polishEngine) {
            NOT_RECORDED -> ""
            NO_SPEECH -> "No speech to polish"
            OFF -> "AI Polish was off"
            RAW_FALLBACK -> "AI Polish returned nothing, so your own words were kept"
            DETERMINISTIC -> withLatency("Cleaned up on this phone", polishLatencyMs)
            else -> withLatency("Polished by $polishEngine", polishLatencyMs)
        }
        val context = PolishContext.decode(polishContext)
        val failure = decodeStoredReason(polishReason)?.let { PolishFailure.from(it, polishStatus, context) } ?: return engineLine
        val failureLine = "${failure.leadIn.text} ${failure.message(context)}"
        return if (engineLine.isEmpty()) failureLine else "$failureLine\n$engineLine"
    }

    /** Tolerant: an empty or unknown stored reason is null, never a throw (schema values outlive code). */
    fun decodeStoredReason(value: String): PolishReason? =
        value.takeIf(String::isNotEmpty)?.let { stored -> PolishReason.entries.firstOrNull { it.name == stored } }

    private fun withLatency(summary: String, latencyMs: Long): String =
        if (latencyMs > 0L) "$summary in $latencyMs ms" else summary
}
