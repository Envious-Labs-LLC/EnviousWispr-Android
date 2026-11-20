package com.envi.wispr.about

import com.envi.wispr.BuildConfig
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

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
    fun versionsAreUniqueAndDated() {
        val versions = ReleaseNotes.entries.map(ReleaseNote::version)
        assertEquals("ReleaseNotes entries must use distinct version names.", versions.distinct(), versions)
        ReleaseNotes.entries.forEach { note ->
            assertTrue("Version name must not be blank.", note.version.isNotBlank())
            assertTrue("Version ${note.version} must include a release date.", note.date.isNotBlank())
        }
    }

    @Test
    fun noNoteIsEmptyAndNoLineUsesADash() {
        ReleaseNotes.entries.forEach { note ->
            assertTrue(
                "Version ${note.version} has a heading and no lines under it.",
                note.lines.isNotEmpty(),
            )
            note.lines.forEach { line ->
                assertTrue("Version ${note.version} has a blank release note line.", line.isNotBlank())
                // content-brand.md RULE: no-dashes-in-user-facing-text.
                assertEquals(
                    "A release note the user reads contains a dash: \"$line\"",
                    emptyList<String>(),
                    listOf("—", "–").filter(line::contains),
                )
            }
        }
    }

    @Test
    fun theShippedVersionFitsGooglePlayWhatsNewLimit() {
        val text = ReleaseNotes.playStoreText()
        assertTrue(
            "Google Play rejects en-US What's New text over ${ReleaseNotes.MAX_PLAY_RELEASE_NOTES_CHARS} " +
                "characters; version ${BuildConfig.VERSION_NAME} is ${text.length} characters.",
            text.isNotEmpty() && text.length <= ReleaseNotes.MAX_PLAY_RELEASE_NOTES_CHARS,
        )
        val outFile = File("build/release-notes/release-notes.json")
        outFile.parentFile?.mkdirs()
        val payload = JSONObject()
            .put("version_name", BuildConfig.VERSION_NAME)
            .put("release_notes", text)
            .toString(2) + "\n"
        outFile.writeText(payload, Charsets.UTF_8)
        val parsed = JSONObject(outFile.readText(Charsets.UTF_8))
        assertEquals(BuildConfig.VERSION_NAME, parsed.getString("version_name"))
        assertEquals(text, parsed.getString("release_notes"))
    }
}
