package com.envi.wispr.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.envi.wispr.settings.AppPreferences
import com.envi.wispr.settings.AppPreferencesState
import com.envi.wispr.ui.theme.EnviousWisprTheme
import com.envi.wispr.ui.theme.brandButtonColors
import com.envi.wispr.vocabulary.CustomTerm
import com.envi.wispr.vocabulary.CustomTermRepository
import com.envi.wispr.vocabulary.DuplicateCustomTermException
import com.envi.wispr.vocabulary.QuickAddWordPolicy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal data class QuickAddUiState(
    val spelling: String = "",
    val existingTerms: List<CustomTerm> = emptyList(),
    val saving: Boolean = false,
    val error: String? = null,
    val savedSpelling: String? = null,
)

internal class QuickAddWordViewModel(
    private val appContext: Context,
    private val repository: CustomTermRepository,
    initialSpelling: String,
    initialError: String?,
) : ViewModel() {
    private val _state = MutableStateFlow(
        QuickAddUiState(
            spelling = initialSpelling,
            error = initialError,
        ),
    )
    val state: StateFlow<QuickAddUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val loaded = withContext(Dispatchers.IO) {
                try {
                    repository.migrateLegacySharedPreferences(appContext)
                    repository.list().map { it.term }
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (_: Throwable) {
                    emptyList()
                }
            }
            _state.update { it.copy(existingTerms = loaded) }
        }
    }

    fun onSpellingChange(value: String) {
        _state.update { it.copy(spelling = value, error = null) }
    }

    fun save() {
        val current = _state.value
        if (current.saving || current.savedSpelling != null) return
        val normalized = when (val result = QuickAddWordPolicy.normalize(current.spelling)) {
            QuickAddWordPolicy.Normalization.Blank -> {
                _state.update { it.copy(error = "Enter a preferred spelling.") }
                return
            }
            QuickAddWordPolicy.Normalization.TooLong -> {
                _state.update {
                    it.copy(error = "Preferred spelling must be ${QuickAddWordPolicy.MAX_SPELLING_LENGTH} characters or fewer.")
                }
                return
            }
            is QuickAddWordPolicy.Normalization.Valid -> result.spelling
        }
        _state.update { it.copy(saving = true, error = null) }
        viewModelScope.launch {
            val outcome = withContext(Dispatchers.IO) {
                runCatching {
                    repository.migrateLegacySharedPreferences(appContext)
                    val latest = repository.list().map { it.term }
                    if (QuickAddWordPolicy.hasExactMatch(normalized, latest)) {
                        throw DuplicateCustomTermException(normalized)
                    }
                    repository.add(CustomTerm(spelling = normalized))
                }
            }
            outcome.onSuccess {
                _state.update { it.copy(saving = false, savedSpelling = normalized) }
            }.onFailure { failure ->
                if (failure is CancellationException) throw failure
                val message = when (failure) {
                    is DuplicateCustomTermException -> "Already saved in Your Words."
                    is IllegalArgumentException,
                    is IllegalStateException,
                    -> failure.message ?: "Unable to save this word."
                    else -> "Unable to save this word."
                }
                _state.update { it.copy(saving = false, error = message) }
            }
        }
    }

    class Factory(
        private val appContext: Context,
        private val initialSpelling: String,
        private val initialError: String?,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(QuickAddWordViewModel::class.java))
            return QuickAddWordViewModel(
                appContext = appContext,
                repository = CustomTermRepository(appContext),
                initialSpelling = initialSpelling,
                initialError = initialError,
            ) as T
        }
    }
}

/**
 * Translucent quick-add sheet launched from Android's text-selection menu (`ACTION_PROCESS_TEXT`, #31).
 * Saves a selected word or phrase into Your Words on this phone without leaving the user's app.
 */
class QuickAddWordActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val incoming = intent
        if (incoming?.action != Intent.ACTION_PROCESS_TEXT || incoming.type != "text/plain") {
            finish()
            return
        }
        val rawSelection = runCatching {
            incoming.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)
        }.getOrNull()
        val initialNorm = QuickAddWordPolicy.normalize(rawSelection)
        val initialCandidate = when (initialNorm) {
            is QuickAddWordPolicy.Normalization.Valid -> initialNorm.spelling
            QuickAddWordPolicy.Normalization.Blank,
            QuickAddWordPolicy.Normalization.TooLong,
            -> ""
        }
        val initialError = if (initialNorm == QuickAddWordPolicy.Normalization.TooLong) {
            "Selected text is longer than ${QuickAddWordPolicy.MAX_SPELLING_LENGTH} characters."
        } else {
            null
        }

        val viewModel: QuickAddWordViewModel by viewModels {
            QuickAddWordViewModel.Factory(applicationContext, initialCandidate, initialError)
        }

        setContent {
            val preferences by produceState<AppPreferencesState?>(initialValue = null, applicationContext) {
                AppPreferences(applicationContext).state.collect { value = it }
            }
            val loadedPrefs = preferences ?: return@setContent
            EnviousWisprTheme(
                dynamicColor = loadedPrefs.dynamicColorEnabled,
                themeMode = loadedPrefs.themeMode,
            ) {
                val uiState by viewModel.state.collectAsState()

                LaunchedEffect(uiState.savedSpelling) {
                    val saved = uiState.savedSpelling ?: return@LaunchedEffect
                    Toast.makeText(
                        applicationContext,
                        "Added \"$saved\" to Your Words",
                        Toast.LENGTH_SHORT,
                    ).show()
                    finish()
                }

                val normalization = remember(uiState.spelling) {
                    QuickAddWordPolicy.normalize(uiState.spelling)
                }
                val exactMatch = remember(uiState.spelling, uiState.existingTerms) {
                    QuickAddWordPolicy.hasExactMatch(uiState.spelling, uiState.existingTerms)
                }
                val relatedMatches = remember(uiState.spelling, uiState.existingTerms) {
                    QuickAddWordPolicy.matchingTerms(uiState.spelling, uiState.existingTerms, limit = 3)
                }
                val displayError = uiState.error ?: if (normalization == QuickAddWordPolicy.Normalization.TooLong) {
                    "Preferred spelling must be ${QuickAddWordPolicy.MAX_SPELLING_LENGTH} characters or fewer."
                } else {
                    null
                }

                AlertDialog(
                    onDismissRequest = { if (!uiState.saving) finish() },
                    title = { Text("Add to Your Words") },
                    text = {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text(
                                "EnviousWispr will prefer this spelling when you dictate.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            OutlinedTextField(
                                value = uiState.spelling,
                                onValueChange = viewModel::onSpellingChange,
                                modifier = Modifier.fillMaxWidth(),
                                label = { Text("Preferred spelling") },
                                singleLine = true,
                                enabled = !uiState.saving,
                                isError = displayError != null,
                            )
                            if (exactMatch) {
                                Text(
                                    "Already saved in Your Words.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            } else if (relatedMatches.isNotEmpty()) {
                                Text(
                                    "Similar saved words: ${relatedMatches.joinToString(", ") { it.spelling }}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            displayError?.let {
                                Text(
                                    it,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { finish() }, enabled = !uiState.saving) {
                            Text("Cancel")
                        }
                    },
                    confirmButton = {
                        Button(
                            colors = brandButtonColors(),
                            enabled = !uiState.saving &&
                                !exactMatch &&
                                normalization is QuickAddWordPolicy.Normalization.Valid,
                            onClick = viewModel::save,
                        ) {
                            Text(if (uiState.saving) "Adding..." else "Add")
                        }
                    },
                )
            }
        }
    }
}
