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

    /** The rescue store's directory for the view model under test (#288). */
    private lateinit var rescueDir: java.io.File

    private fun build(vararg texts: String): HistoryViewModel {
        val dao = DictationSessionRig.FakeTranscriptDao()
        texts.forEachIndexed { index, text -> dao.rows[index + 1L] = row(index + 1L, text) }
        rescueDir = java.nio.file.Files.createTempDirectory("rescued-words").toFile()
        val rescue = com.envi.wispr.ui.RescuedWords(rescueDir, kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO), wallClock = { 0L }, warn = {})
        return HistoryViewModel(TranscriptRepository(dao), rescue, clock = { 0L }).also { viewModel = it }
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

    /**
     * #288: opening History writes rescued words into it, deleting a row deletes its take's rescue, and Delete all
     * deletes every rescue, so deleted words cannot come back. MUTATION m6: Delete all leaves the files.
     */
    @Test fun historyRecoversRescuedWordsAndDeletesThemWithTheRows() = runTest(dispatcher) {
        val take = "0a1b2c3d-4e5f-4a6b-8c7d-9e8f7a6b5c4d"
        val other = "1a1b2c3d-4e5f-4a6b-8c7d-9e8f7a6b5c4d"
        val dao = DictationSessionRig.FakeTranscriptDao()
        rescueDir = java.nio.file.Files.createTempDirectory("rescued-words").toFile()
        java.io.File(rescueDir, "$take.words").writeText("5\nKeep these words.")
        val rescue = com.envi.wispr.ui.RescuedWords(rescueDir, backgroundScope, wallClock = { 0L }, warn = {})
        val history = HistoryViewModel(TranscriptRepository(dao), rescue, clock = { 0L }).also { viewModel = it }
        advanceUntilIdle()
        val recovered = dao.rows.values.single()
        assertEquals("Keep these words.", recovered.finalText)
        assertEquals(take, recovered.takeId)
        assertEquals(emptyList<String>(), rescueDir.list()!!.toList())

        java.io.File(rescueDir, "$take.words").writeText("5\nKeep these words.")
        history.deleteHistory(recovered)
        advanceUntilIdle()
        assertEquals("the row's rescue is deleted with it", emptyList<String>(), rescueDir.list()!!.toList())

        java.io.File(rescueDir, "$other.words").writeText("5\nOther words.")
        history.deleteAllHistory()
        advanceUntilIdle()
        assertEquals(emptyList<String>(), rescueDir.list()!!.toList())
        rescueDir.deleteRecursively()
        Unit
    }

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
