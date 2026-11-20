package com.envi.wispr.about

/**
 * One shipped version and what changed in it.
 *
 * [version] must match a `versionName` the app has actually shipped, because the What's New page tells
 * the user which build they are running and then lists these entries under it.
 */
internal data class ReleaseNote(
    val version: String,
    val date: String,
    val lines: List<String>,
)

/**
 * The bundled release history, newest first.
 *
 * This is the only source the What's New page reads. `ReleaseNotesTest` fails when `versionName` moves
 * ahead of the newest entry here, so a release cannot ship with the page silent about it.
 */
internal object ReleaseNotes {
    /** Google Play's per-language character ceiling for a release's "What's new" text. */
    const val MAX_PLAY_RELEASE_NOTES_CHARS = 500

    fun playStoreText(note: ReleaseNote = entries.first()): String =
        note.lines.joinToString("\n") { "• $it" }

    val entries: List<ReleaseNote> = listOf(
        ReleaseNote(
            version = "0.2.0",
            date = "November 2026",
            lines = listOf(
                "Import single-word contact names into Dictionary, or add a selected word from Android's text-selection menu when available.",
                "If a dictation did not insert, you can retry from History.",
                "Cloud AI Polish now uses your Dictionary words, and Casual tones note when lowercase or no final period is expected.",
                "Added model, provider and storage checks to Permissions, refreshed Settings cards, and lowered memory use on long takes.",
            ),
        ),
        ReleaseNote(
            version = "0.1.0",
            date = "August to November 2026",
            lines = listOf(
                "Press the side button twice and speak. Your words go straight into whatever you were typing in.",
                "Speech is turned into text on your phone. Your voice never leaves it.",
                "AI Polish tidies your dictation, either on this phone or through a provider key you supply.",
                "Your own words, names and spellings live in Dictionary, and dictation uses them.",
                "Every finished dictation is kept in History on this phone, and you can search it.",
                "When EnviousWispr cannot reach the field you are typing in, it tells you where your words were kept.",
                "Switched on-device speech recognition to Parakeet Ultra and added the EG-1 Micro local AI Polish model alongside S1-mini.",
                "Keep dictating while model updates download over Wi-Fi, and choose when idle models unload from memory.",
                "History can recover text from some interrupted recordings.",
                "Hide the floating bubble in apps you choose, and play start and stop chimes on media or notification volume.",
            ),
        ),
    )
}
