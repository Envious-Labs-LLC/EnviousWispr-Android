package com.envi.wispr.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.envi.wispr.models.ModelManifest
import com.envi.wispr.models.ModelStorage
import com.envi.wispr.paste.AccessibilityPermission
import com.envi.wispr.paste.AutoPasteAvailability
import com.envi.wispr.paste.AutoPasteReadiness
import com.envi.wispr.paste.PasteAccessibilityService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

internal data class AppReadiness(
    val microphoneGranted: Boolean = false,
    val notificationsGranted: Boolean = false,
    val accessibilityPermitted: Boolean = false,
    val speechModelReady: Boolean = false,
    val polishModelReady: Boolean = false,
) {
    val requiredModelsReady: Boolean
        get() = speechModelReady && polishModelReady

    val coreReady: Boolean
        get() = microphoneGranted && requiredModelsReady
}

/** The platform facts every screen reads (#218): permissions, models, and the live auto-paste answer. */
internal data class ReadinessUiState(
    /** False only in the initial value; every value built from a real emission sets it (the root gate). */
    val loaded: Boolean = false,
    val readiness: AppReadiness = AppReadiness(),
    val autoPaste: AutoPasteAvailability = AutoPasteReadiness.initial,
)

/**
 * Owns what the app knows about the phone (#218): the permission and model snapshot the activity pushes,
 * joined with the paste service's pushed liveness. It lives in `ui/` because it reads the platform and two
 * packages, not one feature.
 */
internal class ReadinessViewModel(
    private val appContext: Context,
) : ViewModel() {
    private val readiness = MutableStateFlow(AppReadiness())

    // Derived, never stored. AppReadiness is a snapshot written wholesale from the Settings screen,
    // so a liveness field inside it would go stale between pushes; combining here gives the answer
    // exactly one producer and no second home (`architecture-rules.md` RULE: own-state-locally).
    // Assigned directly, with no operator after it: AutoPasteReadiness.observe owns the join, and
    // projecting its answer back down here would put the permission fact in charge again.
    private val autoPaste = AutoPasteReadiness.observe(
        permittedInSettings = readiness.map { it.accessibilityPermitted },
        serviceBound = PasteAccessibilityService.isBound,
    )

    val state: StateFlow<ReadinessUiState> = combine(readiness, autoPaste) { currentReadiness, autoPasteStatus ->
        ReadinessUiState(loaded = true, readiness = currentReadiness, autoPaste = autoPasteStatus)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = ReadinessUiState(),
    )

    fun updateVerifiedModels(snapshot: AppReadiness) {
        readiness.value = readiness.value.withVerifiedModels(snapshot)
    }

    fun refreshPermissions() {
        readiness.value = readiness.value.copy(
            microphoneGranted = ContextCompat.checkSelfPermission(appContext, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED,
            notificationsGranted = ContextCompat.checkSelfPermission(appContext, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED,
            accessibilityPermitted = AccessibilityPermission.isGranted(appContext),
        )
    }

    class Factory(
        private val appContext: Context,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(ReadinessViewModel::class.java))
            return ReadinessViewModel(appContext) as T
        }
    }
}

/**
 * Reads the facts Android will only answer when asked. Auto-paste LIVENESS is not one of them: the
 * setting string still names a service that has crashed, and so do `AccessibilityManager.isEnabled`
 * and `getEnabledAccessibilityServiceList`, which project the same setting. Liveness is pushed by
 * `PasteAccessibilityService.isBound`, and the two are combined in [ReadinessViewModel].
 */
internal fun readAppReadiness(context: Context): AppReadiness {
    val accessibilityPermitted = AccessibilityPermission.isGranted(context)

    val speechModelReady = ModelStorage.isReady(context, ModelManifest.parakeet)

    return AppReadiness(
        microphoneGranted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO,
        ) == PackageManager.PERMISSION_GRANTED,
        notificationsGranted =
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED,
        accessibilityPermitted = accessibilityPermitted,
        speechModelReady = speechModelReady,
        polishModelReady = ModelStorage.isReady(context, ModelManifest.s1),
    )
}
