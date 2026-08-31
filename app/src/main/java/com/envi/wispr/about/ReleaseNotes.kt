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
    val entries: List<ReleaseNote> = listOf(
        ReleaseNote(
            version = "0.1.0",
            date = "August 2026",
            lines = listOf(
                "Press the side button twice and speak. Your words go straight into whatever you were typing in.",
                "Speech is turned into text on your phone. Your voice never leaves it.",
                "AI Polish tidies your dictation, either on this phone or through a provider key you supply.",
                "Your own words, names and spellings live in Dictionary, and dictation uses them.",
                "Every finished dictation is kept in History on this phone, and you can search it.",
                "When EnviousWispr cannot reach the field you are typing in, it tells you where your words were kept.",
            ),
        ),
    )
}
