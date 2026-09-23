package com.envi.wispr.history.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.envi.wispr.history.TranscriptEntity
import com.envi.wispr.history.TranscriptRepository
import com.envi.wispr.telemetry.Telemetry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** The History tab's state (#218). */
internal data class HistoryUiState(
    /** False only in the initial value; every value built from a real emission sets it, an empty store included. */
    val loaded: Boolean = false,
    val transcripts: List<TranscriptEntity> = emptyList(),
    val totalCount: Int = 0,
    val search: String = "",
    val error: String? = null,
)

/** Owns the History tab (#218): the transcripts, the search, the error, and the startup recovery. */
internal class HistoryViewModel(
    private val repository: TranscriptRepository,
    private val clock: () -> Long = System::currentTimeMillis,
) : ViewModel() {
    private val historySearch = MutableStateFlow("")
    private val historyError = MutableStateFlow<String?>(null)

    val state: StateFlow<HistoryUiState> = combine(
        repository.transcripts,
        historySearch,
        historyError,
    ) { transcripts, search, error ->
        HistoryUiState(
            loaded = true,
            transcripts = transcripts.filter {
                search.isBlank() || it.finalText.contains(search, ignoreCase = true) ||
                    it.originalText.contains(search, ignoreCase = true)
            },
            totalCount = transcripts.size,
            search = search,
            error = error,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = HistoryUiState(),
    )

    init {
        viewModelScope.launch {
            runCatching {
                Telemetry.insertionsRecovered(repository.recoverStaleOpenRows(clock()).readyRowIds)
                // Rows an older build saved for a dictation with no words in them. Swept here rather
                // than left for the user to delete, because they are the reason History could not be
                // scanned. Nothing writes them any more, so on a phone that has run this once it
                // deletes nothing.
                repository.pruneWordlessRows()
            }
                .onFailure { error -> historyError.value = error.message ?: error::class.simpleName }
        }
    }

    fun updateHistorySearch(value: String) { historySearch.value = value }

    fun setHistoryKept(transcript: TranscriptEntity) {
        updateHistory { repository.setKept(transcript.id, !transcript.kept) }
    }

    fun deleteHistory(transcript: TranscriptEntity) {
        updateHistory { repository.delete(transcript) }
    }

    fun deleteAllHistory() {
        updateHistory { repository.deleteAll() }
    }

    private fun updateHistory(operation: suspend () -> Unit) {
        historyError.value = null
        viewModelScope.launch {
            runCatching { operation() }.onFailure { error ->
                if (error is CancellationException) throw error
                historyError.value = error.message ?: error::class.simpleName
            }
        }
    }

    class Factory(
        private val repository: TranscriptRepository,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(HistoryViewModel::class.java))
            return HistoryViewModel(repository) as T
        }
    }
}
