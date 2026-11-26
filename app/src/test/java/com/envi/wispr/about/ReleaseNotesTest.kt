package com.envi.wispr.about

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import com.envi.wispr.BuildConfig
import com.envi.wispr.settings.AppPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
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
    @get:Rule
    val tempFolder = TemporaryFolder()

    private suspend fun <T> withClosedStore(file: File, block: suspend (AppPreferences) -> T): T {
        val job = Job()
        val scope = CoroutineScope(job + Dispatchers.IO)
        return try {
            val store = PreferenceDataStoreFactory.create(scope = scope) { file }
            block(AppPreferences(store))
        } finally {
            job.cancelAndJoin()
        }
    }

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

    @Test
    fun unreadBadgePolicyDistinguishesFreshInstallsFromUpgrades() {
        assertEquals(BuildConfig.VERSION_NAME, ReleaseNotes.currentContentVersion)
        // Fresh install before onboarding finishes is never badged.
        assertFalse(ReleaseNotes.hasUnread(lastSeenVersion = null, onboardingComplete = false, onboardingDismissed = false, currentVersion = "0.2.0"))
        // Upgraded build from before tracking existed (already completed or dismissed onboarding) is badged.
        assertTrue(ReleaseNotes.hasUnread(lastSeenVersion = null, onboardingComplete = true, onboardingDismissed = false, currentVersion = "0.2.0"))
        assertTrue(ReleaseNotes.hasUnread(lastSeenVersion = null, onboardingComplete = false, onboardingDismissed = true, currentVersion = "0.2.0"))
        // Older tracked version is badged; current version is cleared.
        assertTrue(ReleaseNotes.hasUnread(lastSeenVersion = "0.1.0", onboardingComplete = true, onboardingDismissed = false, currentVersion = "0.2.0"))
        assertFalse(ReleaseNotes.hasUnread(lastSeenVersion = "0.2.0", onboardingComplete = true, onboardingDismissed = false, currentVersion = "0.2.0"))
    }

    @Test
    fun freshOnboardingSeedsWhatsNewSeenWhileUpgradeKeepsBadgeUntilViewed() = runTest {
        val completeFile = tempFolder.newFile("fresh_complete.preferences_pb")
        withClosedStore(completeFile) { prefs ->
            assertFalse(prefs.state.first().hasUnreadWhatsNew)
            prefs.completeOnboarding()
        }
        val completed = withClosedStore(completeFile) { it.state.first() }
        assertEquals(ReleaseNotes.currentContentVersion, completed.lastSeenWhatsNewVersion)
        assertFalse(completed.hasUnreadWhatsNew)

        val dismissFile = tempFolder.newFile("fresh_dismiss.preferences_pb")
        withClosedStore(dismissFile) { prefs ->
            prefs.dismissOnboarding()
        }
        val dismissed = withClosedStore(dismissFile) { it.state.first() }
        assertEquals(ReleaseNotes.currentContentVersion, dismissed.lastSeenWhatsNewVersion)
        assertFalse(dismissed.hasUnreadWhatsNew)

        val upgradeFile = tempFolder.newFile("upgrade_unread.preferences_pb")
        withClosedStore(upgradeFile) { prefs ->
            prefs.markWhatsNewSeen("0.1.0")
            prefs.completeOnboarding()
        }
        val beforeOpening = withClosedStore(upgradeFile) { it.state.first() }
        assertEquals("0.1.0", beforeOpening.lastSeenWhatsNewVersion)
        assertTrue(beforeOpening.hasUnreadWhatsNew)

        withClosedStore(upgradeFile) { prefs -> prefs.markWhatsNewSeen() }
        val afterOpening = withClosedStore(upgradeFile) { it.state.first() }
        assertEquals(ReleaseNotes.currentContentVersion, afterOpening.lastSeenWhatsNewVersion)
        assertFalse(afterOpening.hasUnreadWhatsNew)

        // Pre-badge dismissed user who upgrades, resumes setup, and completes or re-dismisses keeps unread badge.
        val resumedFile = tempFolder.newFile("pre_badge_dismissed_resumed.preferences_pb")
        run {
            val job = Job()
            val scope = CoroutineScope(job + Dispatchers.IO)
            try {
                val rawStore = PreferenceDataStoreFactory.create(scope = scope) { resumedFile }
                rawStore.edit { it[booleanPreferencesKey("onboarding_dismissed")] = true }
                val prefs = AppPreferences(rawStore)
                assertTrue(prefs.state.first().hasUnreadWhatsNew)
                prefs.resumeOnboarding()
                prefs.dismissOnboarding()
                assertTrue(prefs.state.first().hasUnreadWhatsNew)
                prefs.resumeOnboarding()
                prefs.completeOnboarding()
                assertTrue(prefs.state.first().hasUnreadWhatsNew)
                prefs.markWhatsNewSeen()
            } finally {
                job.cancelAndJoin()
            }
        }
        assertFalse(withClosedStore(resumedFile) { it.state.first().hasUnreadWhatsNew })
    }
}
