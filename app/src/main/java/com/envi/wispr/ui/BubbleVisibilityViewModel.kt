package com.envi.wispr.ui

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.envi.wispr.settings.AppPreferences
import com.envi.wispr.settings.BubbleVisibilityStored
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** One app as the Appearance page shows it: the name a person reads, and the package it is stored under. */
internal data class AppChoice(val label: String, val packageName: String)

/**
 * Where the floating button may appear (#523), as the Appearance page renders it. [pickable] is null until the
 * picker asked for the phone's apps, empty when none could be listed.
 */
internal data class BubbleVisibilityUiState(
    val hiddenApps: List<AppChoice> = emptyList(),
    val showOnPasswords: Boolean = false,
    val pickable: List<AppChoice>? = null,
)

/**
 * Owns the Appearance page's "Where it appears" section (#523): reads and writes the hide list and the
 * password switch through [AppPreferences], and lists the apps a launcher shows (the manifest's launcher
 * `<queries>`, so no package-visibility permission). No telemetry: the app list never leaves the phone.
 */
internal class BubbleVisibilityViewModel(
    private val appContext: Context,
    private val appPreferences: AppPreferences,
) : ViewModel() {

    private val pickable = MutableStateFlow<List<AppChoice>?>(null)

    val state: StateFlow<BubbleVisibilityUiState> = combine(
        appPreferences.bubbleVisibilityStored.map { stored -> stored to labelled(stored) },
        pickable,
    ) { (stored, hidden), apps ->
        BubbleVisibilityUiState(hiddenApps = hidden, showOnPasswords = stored.showOnPasswords, pickable = apps)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), BubbleVisibilityUiState())

    fun setShowOnPasswords(show: Boolean) {
        viewModelScope.launch { appPreferences.setBubbleShowOnPasswords(show) }
    }

    fun hideIn(packageName: String) {
        viewModelScope.launch { appPreferences.setBubbleHiddenInApp(packageName, hidden = true) }
    }

    fun showIn(packageName: String) {
        viewModelScope.launch { appPreferences.setBubbleHiddenInApp(packageName, hidden = false) }
    }

    /** Lists the launcher's apps once per page visit, sorted by name, without EnviousWispr itself. */
    fun loadPickable() {
        viewModelScope.launch {
            pickable.value = withContext(Dispatchers.IO) {
                runCatching {
                    val pm = appContext.packageManager
                    val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                    pm.queryIntentActivities(launcher, 0)
                        .map { it.activityInfo.packageName }
                        .filter { it != appContext.packageName }
                        .distinct()
                        .map { AppChoice(labelOf(pm, it), it) }
                        .sortedBy { it.label.lowercase() }
                }.getOrDefault(emptyList())
            }
        }
    }

    private suspend fun labelled(stored: BubbleVisibilityStored): List<AppChoice> = withContext(Dispatchers.IO) {
        val pm = appContext.packageManager
        stored.hiddenApps.map { AppChoice(labelOf(pm, it), it) }.sortedBy { it.label.lowercase() }
    }

    /** An app that is gone or not visible to us keeps its package name, so the user can still remove it. */
    private fun labelOf(pm: PackageManager, packageName: String): String = runCatching {
        pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
    }.getOrDefault(packageName)

    class Factory(private val appContext: Context) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(BubbleVisibilityViewModel::class.java))
            return BubbleVisibilityViewModel(appContext, AppPreferences(appContext)) as T
        }
    }
}
