package com.envi.wispr.ui

import com.envi.wispr.models.ModelHealth
import com.envi.wispr.models.ModelUiAction
import com.envi.wispr.models.ModelUiState
import com.envi.wispr.providers.PolishMode
import com.envi.wispr.providers.Provider
import com.envi.wispr.providers.capabilities

/**
 * What the two engine cards on the AI Polish tab say and allow (#67), computed from persisted facts only,
 * so the tab holds no draft of active state. Pure: `PolishCardStateTest` enumerates every input class.
 */

/** How a card's status line is coloured; the colour never carries readiness alone (the text does). */
enum class CardTone { NEUTRAL, GOOD, PROBLEM }

enum class PhoneCardAction { MANAGE_MODEL, DOWNLOAD_MODEL }

data class PhoneCardState(
    val selected: Boolean,
    /** The radio may be chosen only when the model is ready, or when the card is already the active one. */
    val selectable: Boolean,
    val status: String,
    val tone: CardTone,
    val action: PhoneCardAction,
)

enum class ProviderCardAction { CHOOSE_PROVIDER, EDIT_PROVIDER, REMOVE_SELF_HOSTED }

data class ProviderCardState(
    val selected: Boolean,
    val selectable: Boolean,
    val title: String,
    val status: String,
    val tone: CardTone,
    val privacyLine: String,
    val action: ProviderCardAction,
    /** Whether the secondary "Switch provider" affordance shows (a configured key provider only). */
    val canSwitchProvider: Boolean,
    /** Tapping the card body when it cannot be selected opens the picker (unconfigured) or does nothing. */
    val tapOpensPicker: Boolean,
)

fun phoneCard(s1State: ModelUiState, settings: ProviderSettingsUiState): PhoneCardState {
    val selected = settings.mode == PolishMode.OFFLINE_S1
    val ready = s1State.health == ModelHealth.READY
    val status = when (s1State.health) {
        ModelHealth.READY -> "S1-mini · Ready"
        ModelHealth.NOT_READY -> if (s1State.action == ModelUiAction.DOWNLOAD) "S1-mini · Model needed" else "S1-mini · ${s1State.label}"
        ModelHealth.BROKEN -> "S1-mini · ${s1State.label}"
        ModelHealth.UNKNOWN -> "S1-mini"
    }
    val tone = when (s1State.health) {
        ModelHealth.READY -> CardTone.GOOD
        ModelHealth.BROKEN -> CardTone.PROBLEM
        ModelHealth.NOT_READY, ModelHealth.UNKNOWN -> CardTone.NEUTRAL
    }
    val action = when (s1State.action) {
        ModelUiAction.DOWNLOAD, ModelUiAction.RETRY -> PhoneCardAction.DOWNLOAD_MODEL
        ModelUiAction.PAUSE, ModelUiAction.RESUME, ModelUiAction.REPAIR, ModelUiAction.REMOVE,
        ModelUiAction.UPDATE, ModelUiAction.CANCEL, ModelUiAction.NONE -> PhoneCardAction.MANAGE_MODEL
    }
    return PhoneCardState(selected = selected, selectable = ready || selected, status = status, tone = tone, action = action)
}

fun providerCard(settings: ProviderSettingsUiState): ProviderCardState {
    val selected = settings.mode == PolishMode.PROVIDER
    if (!settings.configured) {
        return ProviderCardState(
            selected = selected,
            selectable = false,
            title = "Your provider",
            status = "OpenAI, Gemini, or Claude",
            tone = CardTone.NEUTRAL,
            privacyLine = "Uses your key",
            action = ProviderCardAction.CHOOSE_PROVIDER,
            canSwitchProvider = false,
            tapOpensPicker = true,
        )
    }
    return when (settings.provider) {
        Provider.SELF_HOSTED_POLISH -> ProviderCardState(
            selected = selected,
            selectable = true,
            title = "Self-hosted",
            status = "${hostOf(settings.endpoint)} · Configured",
            tone = CardTone.GOOD,
            privacyLine = "Text is sent to your server",
            action = ProviderCardAction.REMOVE_SELF_HOSTED,
            canSwitchProvider = true,
            tapOpensPicker = false,
        )
        Provider.OPENAI, Provider.GEMINI, Provider.CLAUDE -> {
            val keyPresent = settings.credentialStored
            ProviderCardState(
                selected = selected,
                selectable = keyPresent || selected,
                title = settings.provider.capabilities().displayName,
                status = if (keyPresent) "${settings.model} · Configured" else "${settings.model} · Key missing",
                tone = if (keyPresent) CardTone.GOOD else CardTone.PROBLEM,
                privacyLine = "Text is sent using your key",
                action = ProviderCardAction.EDIT_PROVIDER,
                canSwitchProvider = true,
                tapOpensPicker = false,
            )
        }
    }
}

/** The host of a saved endpoint, for the self-hosted card; the whole string when it has no scheme. */
internal fun hostOf(endpoint: String): String =
    runCatching { java.net.URI(endpoint).host }.getOrNull()?.takeIf(String::isNotBlank) ?: endpoint

/**
 * The tab's snackbar shows a message once per completed write (#67). [lastShown] is remembered above the
 * animated screen body; [current] is the view model's `writeSequence`. After process recreation the
 * remembered value can exceed the fresh view model's count, which is the reset case.
 */
object PolishSnackbarPolicy {
    /** @return the sequence to remember after this evaluation, and whether to show. */
    fun decide(lastShown: Int, current: Int, message: String): Decision = when {
        current < lastShown -> Decision(remember = current, show = false)
        current > lastShown && message.isNotBlank() -> Decision(remember = current, show = true)
        current > lastShown -> Decision(remember = current, show = false)
        else -> Decision(remember = lastShown, show = false)
    }

    data class Decision(val remember: Int, val show: Boolean)
}

/**
 * The setup page's wait on its own write (#67): it stays until the write it started has completed, then
 * pops only when that write succeeded; a failure keeps the page and its drafts.
 */
object ProviderSetupSavePolicy {
    enum class Outcome { WAITING, DONE, FAILED }

    fun outcome(target: Int?, completed: Int, error: String?, origin: ProviderWriteOrigin): Outcome = when {
        target == null -> Outcome.WAITING
        completed < target -> Outcome.WAITING
        origin != ProviderWriteOrigin.SETUP_PAGE -> Outcome.WAITING
        error == null -> Outcome.DONE
        else -> Outcome.FAILED
    }
}
