package com.envi.wispr.ui

import com.envi.wispr.audio.InputDevicePick
import com.envi.wispr.history.TranscriptEntity
import com.envi.wispr.paste.BubbleLook
import com.envi.wispr.polish.S1ControlSettings
import com.envi.wispr.providers.PolishMode
import com.envi.wispr.providers.Provider
import com.envi.wispr.providers.SelfHostedProtocol
import com.envi.wispr.vocabulary.CustomTerm
import com.envi.wispr.vocabulary.CustomTermRecord

/**
 * Everything the shell can ask the activity and the view model to do (#190): thirty-nine callbacks in ten
 * groups, each named after the CAPABILITY that owns the action, so a screen that shares a capability reads
 * the same group. The activity builds one instance inside `remember(viewModel)`, so the object and every
 * lambda in it keep their identity across recompositions; the lambda types are exactly what the screens
 * took before the groups existed.
 *
 * Plain classes, not data classes: identity is what `remember` gives, and value equality over lambdas buys
 * nothing.
 */
internal class AppActions(
    val shell: ShellActions,
    val permissions: PermissionActions,
    val onboarding: OnboardingActions,
    val history: HistoryActions,
    val dictionary: DictionaryActions,
    val transcription: TranscriptionActions,
    val polish: PolishActions,
    val microphone: MicrophoneActions,
    val clipboard: ClipboardActions,
    val appearance: AppearanceActions,
)

/** The two actions the shell itself fires: the top-bar microphone and the readiness refresh. */
internal class ShellActions(
    val onStartDictation: () -> Unit,
    val onRefreshReadiness: () -> Unit,
)

/** Launching a system permission or settings screen; read by onboarding, the Microphone page and the Permissions page. */
internal class PermissionActions(
    val onRequestMicrophone: () -> Unit,
    val onRequestNotifications: () -> Unit,
    val onOpenAccessibility: () -> Unit,
)

/** Onboarding progress; read by onboarding and by the Permissions page's continue-setup control. */
internal class OnboardingActions(
    val onStep: (Int) -> Unit,
    val onDismiss: () -> Unit,
    val onResume: () -> Unit,
    val onComplete: () -> Unit,
)

internal class HistoryActions(
    val onSearchChange: (String) -> Unit,
    val onKeep: (TranscriptEntity) -> Unit,
    val onDelete: (TranscriptEntity) -> Unit,
    val onDeleteAll: () -> Unit,
)

internal class DictionaryActions(
    val onSearchChange: (String) -> Unit,
    val onAdd: (CustomTerm) -> Unit,
    val onEdit: (CustomTermRecord, CustomTerm) -> Unit,
    val onDelete: (CustomTermRecord) -> Unit,
    val onBulkDelete: (Set<Long>) -> Unit,
    val onImport: (String) -> Unit,
)

internal class TranscriptionActions(
    val onFillerRemovalChanged: (Boolean) -> Unit,
    val onEmojiFormatterChanged: (Boolean) -> Unit,
    val onSpokenPunctuationChanged: (Boolean) -> Unit,
    val onAutoStopOnSilenceChanged: (Boolean) -> Unit,
    val onSilencePauseSecondsChanged: (Float) -> Unit,
)

internal class PolishActions(
    val onSetMode: (PolishMode) -> Int,
    val onSetS1Control: (S1ControlSettings) -> Int,
    val onSaveProviderSettings: (Provider, String, String?, String?, SelfHostedProtocol, Int?) -> Int,
    val onClearProvider: (Provider) -> Int,
    val onCheckKey: (Provider, String?) -> Int,
    val onKeyDraftChanged: (Provider) -> Unit,
    val onLoadCachedModels: (Provider) -> Unit,
)

internal class MicrophoneActions(
    val onInputDevicePickChanged: (InputDevicePick) -> Unit,
    val onShowBluetoothTipsChanged: (Boolean) -> Unit,
    val onKeepEarbudsReadyChanged: (Boolean) -> Unit,
)

internal class ClipboardActions(
    val onAutoCopyChanged: (Boolean) -> Unit,
    val onRestoreClipboardChanged: (Boolean) -> Unit,
    val onSmartInsertionChanged: (Boolean) -> Unit,
)

internal class AppearanceActions(
    val onDynamicColorChanged: (Boolean) -> Unit,
    val onBubbleLookChanged: (BubbleLook) -> Unit,
)
