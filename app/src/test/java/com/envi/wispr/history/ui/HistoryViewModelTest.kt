package com.envi.wispr.history.ui

import androidx.lifecycle.viewModelScope
import com.envi.wispr.history.TranscriptEntity
import com.envi.wispr.history.TranscriptRepository
import com.envi.wispr.ui.DictationSessionRig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Product Outcome (#218): the History tab gets its own view model and still opens the moment its store
 * answers, even when the store is empty, and still filters by the search. The store is the session rig's
 * in-memory History behind the real repository; it is seeded BEFORE the repository is built, because the
 * fake's `observeAll()` captures the rows once.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HistoryViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private var viewModel: HistoryViewModel? = null

    @Before fun setUp() = Dispatchers.setMain(dispatcher)

    @After fun tearDown() {
        viewModel?.viewModelScope?.cancel()
        Dispatchers.resetMain()
    }

    private fun build(vararg texts: String): HistoryViewModel {
        val dao = DictationSessionRig.FakeTranscriptDao()
        texts.forEachIndexed { index, text -> dao.rows[index + 1L] = row(index + 1L, text) }
        return HistoryViewModel(TranscriptRepository(dao), clock = { 0L }).also { viewModel = it }
    }

    private fun row(id: Long, text: String) = TranscriptEntity(
        id = id,
        originalText = text,
        finalText = text,
        createdAtMs = id,
        durationMs = 1_000L,
        speechEngine = "test",
        polishEngine = "none",
        polishLatencyMs = 0L,
        insertionResult = "pasted",
    )

    @Test fun theFirstValueIsNotLoaded() {
        assertFalse(build().state.value.loaded)
    }

    @Test fun anEmptyButRealStoreEndsTheWait() = runTest(dispatcher) {
        val history = build()
        backgroundScope.launch { history.state.collect {} }
        advanceUntilIdle()
        val state = history.state.value
        assertTrue("an empty History never reported that it had loaded", state.loaded)
        assertEquals(emptyList<TranscriptEntity>(), state.transcripts)
        assertEquals(0, state.totalCount)
    }

    @Test fun aPopulatedStoreShowsItsRowsFilteredByTheSearch() = runTest(dispatcher) {
        val history = build("The quarterly report is ready.", "Lunch at noon.")
        backgroundScope.launch { history.state.collect {} }
        advanceUntilIdle()
        assertEquals(2, history.state.value.transcripts.size)
        history.updateHistorySearch("quarterly")
        advanceUntilIdle()
        val state = history.state.value
        assertTrue(state.loaded)
        assertEquals(listOf("The quarterly report is ready."), state.transcripts.map { it.finalText })
        assertEquals("the total counts every row, not only the matches", 2, state.totalCount)
        assertEquals("quarterly", state.search)
    }
}
