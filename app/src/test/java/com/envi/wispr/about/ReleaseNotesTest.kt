package com.envi.wispr.about

import com.envi.wispr.BuildConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * DRIFT GUARD, not product coverage.
 *
 * The What's New page names the build the user is running and then lists [ReleaseNotes.entries]. Two
 * edits keep that honest and nothing links them: bumping `versionName` in `app/build.gradle.kts`, and
 * adding the note. This is what fails when only one of the two happens.
 *
 * REVERT that turns it red: raise `versionName` without adding an entry here.
 */
class ReleaseNotesTest {

    @Test
    fun theShippedVersionHasANote() {
        val versions = ReleaseNotes.entries.map(ReleaseNote::version)
        assertTrue(
            "versionName is ${BuildConfig.VERSION_NAME} and the What's New page has notes for " +
                "$versions, so the build the user is running has nothing to show.",
            BuildConfig.VERSION_NAME in versions,
        )
    }

    @Test
    fun theNewestNoteIsFirstAndIsTheShippedVersion() {
        assertEquals(
            "The What's New page renders entries in order, so the version the user is running has " +
                "to be the one at the top.",
            BuildConfig.VERSION_NAME,
            ReleaseNotes.entries.first().version,
        )
    }

    @Test
    fun noNoteIsEmptyAndNoLineUsesADash() {
        ReleaseNotes.entries.forEach { note ->
            assertTrue(
                "Version ${note.version} has a heading and no lines under it.",
                note.lines.isNotEmpty(),
            )
            note.lines.forEach { line ->
                // content-brand.md RULE: no-dashes-in-user-facing-text.
                assertEquals(
                    "A release note the user reads contains a dash: \"$line\"",
                    emptyList<String>(),
                    listOf("—", "–").filter(line::contains),
                )
            }
        }
    }
}
