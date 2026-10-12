package com.envi.wispr.ui

import com.envi.wispr.cleanup.EnglishSpelling
import com.envi.wispr.polish.PolishPolicy
import com.envi.wispr.settings.AppPreferencesState
import com.envi.wispr.vocabulary.CustomTerm
import com.envi.wispr.vocabulary.StructuredTermRestorer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** Product Outcome: a live setting/dictionary change must not change an already admitted take. */
class CleaningSnapshotTest {
    @Test fun speechProcessingSurvivesAdmissionAndLaterUiChanges() = runBlocking {
        val selected = com.envi.wispr.processing.ProcessingPreference.DEFAULT.custom(listOf(com.envi.wispr.processing.ProcessingBackend.CPU)).retry()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val preferences = MutableStateFlow(AppPreferencesState(speechProcessing = selected))
        val source = SessionPreferencesSource(preferences, MutableStateFlow(emptyList()), {}, DictationSessionRig.FakeLog())
        try {
            source.start(scope)
            val start = source.awaitAnswers(10_000)
            assertEquals(PreferenceRead.Fresh, start.settings.read)
            val frozen = source.freeze(start, StructuredTermRestorer.compile(emptyList()), PolishPolicy.Off)
            preferences.value = AppPreferencesState()
            assertEquals(selected, start.settings.speechProcessing)
            assertEquals(selected, frozen.speechProcessing)
        } finally { scope.cancel() }
    }
    @Test fun onlyUserSpellingsAreProtectedAndTheChoiceIsFrozenWithTheTake() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val preferences = MutableStateFlow(AppPreferencesState(englishSpelling = EnglishSpelling.BRITISH))
        val words = MutableStateFlow(listOf(CustomTerm("Kennedy Center")))
        val source = SessionPreferencesSource(preferences, words, {}, DictationSessionRig.FakeLog())
        try {
            source.start(scope)
            val start = source.awaitAnswers(10_000)
            assertEquals(PreferenceRead.Fresh, start.settings.read)
            assertEquals(PreferenceRead.Fresh, start.terms.read)
            assertTrue(start.terms.structuredTerms.any { it.spelling == "macOS" })
            val frozen = source.freeze(start, StructuredTermRestorer.compile(start.terms.structuredTerms), PolishPolicy.Off)
            assertEquals(setOf("kennedy center", "kennedy", "center"), frozen.cleanup.spellingProtectedWords)
            assertFalse("built-ins must not override the user's spelling choice", "macos" in frozen.cleanup.spellingProtectedWords)
            preferences.value = AppPreferencesState(englishSpelling = EnglishSpelling.AMERICAN)
            words.value = emptyList()
            assertEquals(EnglishSpelling.BRITISH, frozen.cleanup.englishSpelling)
            assertEquals(setOf("kennedy center", "kennedy", "center"), frozen.cleanup.spellingProtectedWords)
        } finally { scope.cancel() }
    }
}
