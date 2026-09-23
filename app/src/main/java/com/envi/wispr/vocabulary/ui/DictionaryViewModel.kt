package com.envi.wispr.vocabulary.ui

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.envi.wispr.vocabulary.CustomTerm
import com.envi.wispr.vocabulary.CustomTermRecord
import com.envi.wispr.vocabulary.CustomTermRepository
import com.envi.wispr.vocabulary.VocabularyTransfer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** The Dictionary tab's state (#218). */
internal data class DictionaryUiState(
    /** False only in the initial value; every value built from a real emission sets it, an empty list included. */
    val loaded: Boolean = false,
    val allTerms: List<CustomTermRecord> = emptyList(),
    val terms: List<CustomTermRecord> = emptyList(),
    val totalCount: Int = 0,
    val search: String = "",
    val message: String = "",
    val error: String? = null,
)

/** Owns the Dictionary tab (#218): the terms, the search, the six term actions, and the legacy migration. */
internal class DictionaryViewModel(
    private val customTermRepository: CustomTermRepository,
    private val appContext: Context,
) : ViewModel() {
    private val customTermSearch = MutableStateFlow("")
    private val customTermMessage = MutableStateFlow("")
    private val customTermError = MutableStateFlow<String?>(null)

    val state: StateFlow<DictionaryUiState> = combine(
        customTermRepository.observe(),
        customTermSearch,
        customTermMessage,
        customTermError,
    ) { terms, termSearch, termMessage, termError ->
        DictionaryUiState(
            loaded = true,
            allTerms = terms,
            terms = terms.filter { record ->
                termSearch.isBlank() ||
                    record.term.spelling.contains(termSearch, ignoreCase = true) ||
                    record.term.aliases.any { it.contains(termSearch, ignoreCase = true) } ||
                    record.term.category?.contains(termSearch, ignoreCase = true) == true
            },
            totalCount = terms.size,
            search = termSearch,
            message = termMessage,
            error = termError,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = DictionaryUiState(),
    )

    init {
        viewModelScope.launch {
            runCatching { customTermRepository.migrateLegacySharedPreferences(appContext) }
                .onSuccess { imported ->
                    if (imported > 0) customTermMessage.value = "Imported $imported existing custom terms"
                }
                .onFailure { failure ->
                    customTermError.value = failure.message ?: "Could not migrate existing custom terms"
                }
        }
    }

    fun updateCustomTermSearch(value: String) {
        customTermSearch.value = value
    }

    fun addCustomTerm(term: CustomTerm) {
        updateCustomTerms {
            customTermRepository.add(term)
            "Added ${term.spelling.trim()}"
        }
    }

    fun editCustomTerm(record: CustomTermRecord, term: CustomTerm) {
        updateCustomTerms {
            checkNotNull(customTermRepository.edit(record.id, term)) { "Custom term no longer exists" }
            "Updated ${term.spelling.trim()}"
        }
    }

    fun deleteCustomTerm(record: CustomTermRecord) {
        updateCustomTerms {
            check(customTermRepository.delete(record.id)) { "Custom term no longer exists" }
            "Deleted ${record.term.spelling}"
        }
    }

    fun bulkDeleteCustomTerms(ids: Set<Long>) {
        updateCustomTerms {
            val deleted = customTermRepository.bulkDelete(ids)
            "Deleted $deleted custom ${if (deleted == 1) "term" else "terms"}"
        }
    }

    fun importCustomTerms(input: String) {
        updateCustomTerms {
            val existing = customTermRepository.list().map(CustomTermRecord::term)
            val preview = VocabularyTransfer.preview(input, existing)
            val result = customTermRepository.applyImport(preview)
            buildString {
                append("Imported ${result.added}")
                if (result.updated > 0) append("; updated ${result.updated}")
                if (result.skipped > 0) append("; ${result.skipped} duplicates skipped")
                if (result.rejected > 0) append("; ${result.rejected} invalid skipped")
            }
        }
    }

    private fun updateCustomTerms(operation: suspend () -> String) {
        customTermMessage.value = ""
        customTermError.value = null
        viewModelScope.launch {
            runCatching { operation() }.fold(
                onSuccess = { customTermMessage.value = it },
                onFailure = { failure ->
                    if (failure is CancellationException) throw failure
                    customTermError.value = failure.message ?: "Could not update custom terms"
                },
            )
        }
    }

    class Factory(
        private val customTermRepository: CustomTermRepository,
        private val appContext: Context,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(DictionaryViewModel::class.java))
            return DictionaryViewModel(customTermRepository, appContext) as T
        }
    }
}
