package com.envi.wispr.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Product Outcome + Drift Guard suite for Settings Search (`PAR-099`):
 * - Product Outcome: Unicode compatibility/sharp-S/diacritic folding, bounded OSA typo distance,
 *   intent markers, phrase token matching (including partial phrases), 3+ token single-miss allowance,
 *   `"Matches “word”"` synonym, phrase, and hidden-typo hints, plus Developer and onboarding gating.
 * - Drift Guard: every [AppDestination] and [SettingsPage] has an entry in [SettingsSearchCatalog] with
 *   unique IDs and accurate visibility flags.
 */
class SettingsSearchTest {

    private val index = SettingsSearchIndex(SettingsSearchCatalog.entries)

    @Test
    fun normalizeAndWordsFoldCompatibilityFormsSharpSAndDiacritics() {
        assertEquals("strasse", SettingsSearchText.normalize("Straße"))
        assertEquals("strasse", SettingsSearchText.normalize("STRAẞE"))
        assertEquals("naive cafe", SettingsSearchText.normalize("naïve café"))
        assertEquals("theme", SettingsSearchText.normalize("\uFF34\uFF48\uFF45\uFF4D\uFF45"))
        assertEquals(listOf("naive", "cafe"), SettingsSearchText.words("naïve, café!"))
        // Circled compatibility letters fold before splitting into tokens.
        assertEquals(
            listOf("clipboard"),
            SettingsSearchText.words("\u24B8\u24DB\u24D8\u24DF\u24D1\u24DE\u24D0\u24E1\u24D3"),
        )
    }

    @Test
    fun editDistanceCountsAdjacentSwapAsOneAndNeverExceedsCapPlusOne() {
        assertEquals(1, SettingsSearchText.editDistance("transcrption", "transcription", 2))
        assertEquals(1, SettingsSearchText.editDistance("micorphone", "microphone", 1))
        assertEquals(3, SettingsSearchText.editDistance("abcdef", "azced", 2))
        assertEquals(3, SettingsSearchText.editDistance("xxabc", "abcyy", 2))
    }

    @Test
    fun exactTitleRanksTopResultAheadOfSecondaryHits() {
        val results = index.search(query = "Appearance", developerUnlocked = false)
        assertTrue("Expected non-empty results for Appearance", results.isNotEmpty())
        assertEquals("page-appearance", results.first().entry.id)
        assertNull(
            "Visible exact title match should not append redundant hint",
            results.first().matchedHint,
        )
    }

    @Test
    fun darkModeSurfacesThemeWithExactSynonymHint() {
        val results = index.search(query = "dark mode", developerUnlocked = false)
        val theme = results.firstOrNull { it.entry.id == "appearance-theme-mode" }
        assertNotNull("Expected Theme entry to match 'dark mode'", theme)
        assertEquals(SettingsSearchTarget.Page(SettingsPage.Appearance, sectionHint = "Theme"), theme!!.entry.target)
        assertEquals("dark mode", theme.matchedHint)
    }

    @Test
    fun phraseSynonymsContributeCoverageMarkersAndExplainableHints() {
        val offVibration = index.search(query = "turn off vibration", developerUnlocked = false)
        assertTrue(
            "Expected synonym phrase 'turn off vibration' to surface Recording vibration",
            offVibration.any { it.entry.id == "sounds-vibration" },
        )

        val partialOffVibration = index.search(query = "turn off vib", developerUnlocked = false)
        assertTrue(
            "Expected partial synonym phrase 'turn off vib' to surface Recording vibration",
            partialOffVibration.any { it.entry.id == "sounds-vibration" },
        )

        val noPeriod = index.search(query = "no period", developerUnlocked = false)
        assertTrue(
            "Expected synonym phrase 'no period' with 'no' marker to surface Casual mode",
            noPeriod.any { it.entry.id == "transcription-casual-mode" },
        )

        val automatically = index.search(query = "automatically", developerUnlocked = false)
        val autoStop = automatically.firstOrNull { it.entry.id == "transcription-auto-stop" }
        assertNotNull("Expected 'automatically' to match Stop recording on silence", autoStop)
        assertEquals("stop automatically", autoStop!!.matchedHint)
    }

    @Test
    fun intentMarkersAreEnforcedAndThreePlusTokensAllowOneMiss() {
        val polishOff = index.search(query = "turn off polish", developerUnlocked = false)
        assertTrue(
            "Expected polish mode setting to match 'turn off polish'",
            polishOff.any { it.entry.id == "polish-mode" },
        )

        val earbudsMultiWord = index.search(
            query = "how to keep earbuds warm cleanly",
            developerUnlocked = false,
        )
        assertTrue(
            "Expected 3+ content tokens to allow one miss ('cleanly') while hitting Keep earbuds ready",
            earbudsMultiWord.any { it.entry.id == "microphone-keep-earbuds-ready" },
        )

        val impossibleMarker = index.search(query = "earbuds never", developerUnlocked = false)
        assertFalse(
            "An unmet intent marker ('never') must never match entries lacking it",
            impossibleMarker.any { it.entry.id == "microphone-keep-earbuds-ready" },
        )
    }

    @Test
    fun typoToleranceMatchesTitleAndSurfacesHiddenKeywordHint() {
        val swappedMic = index.search(query = "micorphone", developerUnlocked = false)
        assertTrue(
            "Expected 1-edit transposition 'micorphone' to match Microphone entries",
            swappedMic.any {
                it.entry.target is SettingsSearchTarget.Page &&
                    (it.entry.target as SettingsSearchTarget.Page).page == SettingsPage.Microphone
            },
        )

        val swappedAirpods = index.search(query = "airpdos", developerUnlocked = false)
        val earbuds = swappedAirpods.firstOrNull { it.entry.id == "microphone-keep-earbuds-ready" }
        assertNotNull("Expected typo 'airpdos' on hidden keyword 'airpods' to match Keep earbuds ready", earbuds)
        assertEquals("airpods", earbuds!!.matchedHint)

        val shortQuery = index.search(query = "thme", developerUnlocked = false)
        assertTrue("Short (<5 char) misspelled words must not trigger fuzzy noise", shortQuery.isEmpty())
    }

    @Test
    fun developerAndOnboardingEntriesFollowUnlockAndSetupState() {
        val lockedResults = index.search(query = "detailed log", developerUnlocked = false)
        assertFalse(
            "Locked developer settings must never leak when developerUnlocked is false",
            lockedResults.any { it.entry.developerOnly },
        )

        val unlockedResults = index.search(query = "detailed log", developerUnlocked = true)
        assertTrue(
            "Unlocked developer settings must surface 'Detailed log'",
            unlockedResults.any { it.entry.id == "developer-detailed-log" },
        )

        val preOnboardingIndex = SettingsSearchCatalog.index(onboardingComplete = false)
        assertTrue(
            "Continue guided setup must surface while onboarding is incomplete",
            preOnboardingIndex.search("guided setup", developerUnlocked = false).any { it.entry.id == "permissions-guided-setup" },
        )

        val postOnboardingIndex = SettingsSearchCatalog.index(onboardingComplete = true)
        assertFalse(
            "Continue guided setup must not surface once onboarding is complete",
            postOnboardingIndex.search("guided setup", developerUnlocked = false).any { it.entry.id == "permissions-guided-setup" },
        )
    }

    @Test
    fun catalogCoversEveryAppDestinationEverySettingsPageAndScreenActions() {
        val entries = SettingsSearchCatalog.entries
        assertEquals(
            "Catalog entry IDs must be unique",
            entries.size,
            entries.map { it.id }.toSet().size,
        )

        val coveredTabs = entries
            .mapNotNull { (it.target as? SettingsSearchTarget.Tab)?.destination }
            .toSet()
        assertEquals(AppDestination.entries.toSet(), coveredTabs)

        val coveredPages = entries
            .mapNotNull { (it.target as? SettingsSearchTarget.Page)?.page }
            .toSet()
        assertEquals(SettingsPage.entries.toSet(), coveredPages)

        assertEquals(
            SettingsSearchTarget.Page(SettingsPage.Permissions, "Microphone"),
            index.search("microphone permission", developerUnlocked = false).firstOrNull()?.entry?.target,
        )
        assertEquals(
            SettingsSearchTarget.Tab(AppDestination.History, "Delete all"),
            index.search("delete all history", developerUnlocked = false).firstOrNull()?.entry?.target,
        )
        assertEquals(
            SettingsSearchTarget.Page(SettingsPage.Privacy, "Read the privacy policy"),
            index.search("read privacy policy", developerUnlocked = false).firstOrNull()?.entry?.target,
        )
        assertFalse(
            "Cloud setup search must not promise unsupported Ollama setup",
            index.search("ollama", developerUnlocked = false).any { it.entry.id == "polish-cloud-providers" },
        )

        entries.forEach { entry ->
            val targetPage = (entry.target as? SettingsSearchTarget.Page)?.page
            val expectedDeveloperOnly = targetPage == SettingsPage.Developer
            assertEquals(
                "Entry ${entry.id} developerOnly flag disagrees with target $targetPage",
                expectedDeveloperOnly,
                entry.developerOnly,
            )
            assertFalse("Entry ${entry.id} title must not use em/en dashes", entry.title.contains('\u2014') || entry.title.contains('\u2013'))
            assertFalse("Entry ${entry.id} description must not use em/en dashes", entry.description.contains('\u2014') || entry.description.contains('\u2013'))
        }
    }
}
