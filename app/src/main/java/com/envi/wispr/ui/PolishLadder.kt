package com.envi.wispr.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.envi.wispr.models.ModelHealth
import com.envi.wispr.models.ModelManifest
import com.envi.wispr.models.ModelUiAction
import com.envi.wispr.models.ModelUiState
import com.envi.wispr.providers.DiscoveredModel
import com.envi.wispr.providers.ModelAccess
import com.envi.wispr.providers.PolishMode
import com.envi.wispr.providers.Provider
import com.envi.wispr.providers.ProviderPolishClient
import com.envi.wispr.providers.ValidationReason
import com.envi.wispr.providers.capabilities

/**
 * The pure rules behind the AI Polish Ladder (#81): what rung 1 highlights, what a Cloud tap does, which
 * provider the lower rungs show, whether rung 3 is a field or a connected row, what the Check pill reads,
 * which model an accepted key starts with, and what the S1-mini card says. Every input is persisted state
 * or navigation state; nothing here reads a key draft's value. `PolishLadderTest` enumerates each.
 */

/** The providers a fresh setup can pick; self-hosted is excluded by the catalog decision of 2026-09-01. */
internal val CloudProviders: List<Provider> = Provider.entries - Provider.SELF_HOSTED_POLISH

/** The saved model for [provider], or blank when the saved provider is a different one. */
internal fun savedModelFor(provider: Provider, settings: ProviderSettingsUiState): String =
    if (provider == settings.provider) settings.model else ""

enum class RungOne { OFF, THIS_PHONE, CLOUD }

/** What a tap on Cloud does: activate the configured provider, or open the setup rungs without a write. */
enum class CloudTap { ACTIVATE, SETUP }

enum class KeyRung { CONNECTED, FIELD }

data class KeyPill(val label: String, val enabled: Boolean)

object PolishLadder {
    /** Rung 1 follows the persisted mode, except that an open setup shows Cloud while nothing is saved. */
    fun rungOne(mode: PolishMode, cloudSetup: Boolean): RungOne = when (mode) {
        PolishMode.PROVIDER -> RungOne.CLOUD
        PolishMode.OFF -> if (cloudSetup) RungOne.CLOUD else RungOne.OFF
        PolishMode.OFFLINE_S1 -> if (cloudSetup) RungOne.CLOUD else RungOne.THIS_PHONE
    }

    /** Cloud may be activated only with something the session can actually use: a key, or a self-hosted server. */
    fun cloudTap(settings: ProviderSettingsUiState): CloudTap = when {
        !settings.configured -> CloudTap.SETUP
        settings.provider == Provider.SELF_HOSTED_POLISH -> CloudTap.ACTIVATE
        settings.credentialStored -> CloudTap.ACTIVATE
        else -> CloudTap.SETUP
    }

    /** The tile the lower rungs describe: the one tapped, else the saved cloud provider, else none. */
    fun displayedProvider(browsed: Provider?, settings: ProviderSettingsUiState): Provider? =
        browsed ?: settings.provider.takeIf { settings.configured && it in CloudProviders }

    /** Connected only when the DISPLAYED provider is the saved one and its key is in the Keystore. */
    fun keyRung(displayed: Provider, settings: ProviderSettingsUiState, replacing: Boolean): KeyRung = when {
        replacing -> KeyRung.FIELD
        settings.configured && settings.provider == displayed && settings.credentialStored -> KeyRung.CONNECTED
        else -> KeyRung.FIELD
    }

    fun keyPill(draftBlank: Boolean, checking: Boolean, failed: Boolean): KeyPill = when {
        checking -> KeyPill("Checking", enabled = false)
        failed -> KeyPill("Retry", enabled = !draftBlank)
        else -> KeyPill("Check", enabled = !draftBlank)
    }

    /**
     * The model an accepted key starts with, read from the DISCOVERED models only (a pinned saved row or
     * a typed row is synthetic and may name a model this key cannot reach): the first recommended model the
     * probe reached, else the first the probe reached, else the first with no verdict, never one the probe
     * refused; null when there is nothing to start with, so nothing is saved and the typed-id path opens.
     */
    fun defaultModel(models: List<DiscoveredModel>): String? {
        val available = models.filter { it.access == ModelAccess.AVAILABLE }
        return available.firstOrNull { it.recommended }?.id
            ?: available.firstOrNull()?.id
            ?: models.firstOrNull { it.access == ModelAccess.UNVERIFIED }?.id
    }

    /**
     * A key that listed nothing usable waits for a typed model id before anything is stored: true when
     * the listing for this draft's Check arrived and [defaultModel] found nothing in it.
     */
    fun needsTypedModel(discovery: ProviderDiscoveryUiState, displayed: Provider, checkSequence: Int?, draftBlank: Boolean): Boolean =
        checkSequence != null &&
            !draftBlank &&
            discovery.provider == displayed &&
            discovery.phase == ProviderDiscoveryUiState.Phase.LISTED &&
            discovery.sequence == checkSequence &&
            defaultModel(discovery.models) == null

    /** The repository's own model rule, applied before a typed id is offered for saving. */
    fun typedModelValid(id: String): Boolean {
        val trimmed = id.trim()
        return trimmed.isNotEmpty() && trimmed.length <= ProviderPolishClient.MAX_MODEL_CHARS && trimmed.none(Char::isISOControl)
    }

    /**
     * Whether the accepted Check for the displayed provider should save now: the listing is the one the
     * current draft's Check produced, the draft is still in the field, this sequence has not saved yet,
     * and no other write is pending (the tab allows one write at a time and a Check can finish while a
     * mode, model or remove write is in flight).
     */
    fun saveAtAccept(
        discovery: ProviderDiscoveryUiState,
        displayed: Provider,
        checkSequence: Int?,
        draftBlank: Boolean,
        savedForSequence: Int?,
        writePending: Boolean,
    ): Boolean = !writePending &&
        checkSequence != null &&
        discovery.provider == displayed &&
        discovery.phase == ProviderDiscoveryUiState.Phase.LISTED &&
        discovery.sequence == checkSequence &&
        !draftBlank &&
        savedForSequence != checkSequence &&
        defaultModel(discovery.models) != null

    /**
     * The sentence under the S1-mini title, or null when a ready model has nothing left to say: the facts
     * row already carries who made it, how big it is and that it runs offline, so a sentence repeating
     * "on this phone" and "nothing is sent anywhere" is the same claim three times. Exhaustive over
     * health, so a new state must still decide what it says.
     */
    fun s1Line(state: ModelUiState): String? = when (state.health) {
        ModelHealth.READY -> null
        ModelHealth.BROKEN -> "S1-mini is not working right now. Your words come back with basic cleanup only."
        ModelHealth.NOT_READY -> when (state.action) {
            ModelUiAction.DOWNLOAD, ModelUiAction.RETRY -> "Download S1-mini to polish on this phone."
            ModelUiAction.PAUSE, ModelUiAction.RESUME, ModelUiAction.REPAIR, ModelUiAction.REMOVE,
            ModelUiAction.UPDATE, ModelUiAction.CANCEL, ModelUiAction.NONE -> "Getting S1-mini ready."
        }
        ModelHealth.UNKNOWN -> "Getting S1-mini ready."
    }

    /**
     * The facts row under the S1-mini title: who published the model, how much of the phone it occupies,
     * and that it needs no network. The size is summed from the manifest rather than written here, so it
     * cannot drift from the file the delivery worker actually fetches.
     */
    fun s1Facts(): List<String> = listOf(
        ModelManifest.s1.creator,
        formatModelBytes(ModelManifest.s1.files.sumOf { it.expectedBytes }),
        "Offline",
    )

    /**
     * S1-mini's row on the SAME hand-written 1-3 scale as every cloud model, so the two rungs are read
     * against each other rather than each in its own units. Its sibling is `ModelNotes.CatalogModel`,
     * whose own KDoc calls the whole set DECORATION: no dot anywhere in this app is a benchmark result,
     * and a reader who believes this one is calibrated would believe the same of the thirty beside it.
     *
     * Anchored where the cloud rows are anchored, and no further:
     * - **Cost 1** on the same pricing axis `ModelNotes` uses. The model is free, which is below the
     *   cheapest metered model on that list, so it belongs in the lowest bucket the scale has.
     * - **Speed 3** is the one value here with a measurement behind it: 0.65 s on a short take, 2.5 to
     *   3.5 s on a long one, and no network round trip
     *   (`.claude/knowledge/polish-engines.md` FACT: residency-measured-2026-09-01).
     * - **Accuracy 2 is an editorial judgement**, exactly as every cloud accuracy dot is. S1-mini is
     *   trained for this one job, so it clears the bucket the oldest general chat models sit in, and it
     *   is too small to reach the top. Replace it if a head-to-head is ever run, and say here what ran.
     *
     * `PolishLadderTest` asserts the RANGE across this row and every `ModelNotes` row, because a value
     * outside 1-3 renders a meter that is all-empty or never-full. It does not freeze the values: a
     * frozen constant asserts only that nobody edited it (`../rules/testing-philosophy.md`
     * RULE: every-test-declares-which-of-four-things-it-protects).
     */
    internal val S1_SCORES: ModelScores = ModelScores(cost = 1, speed = 3, accuracy = 2)
}

/**
 * The tab's wait on the one write it started (#81, the setup page's policy from #67 generalised): WAITING
 * until the write it named has completed, then DONE or FAILED. It never infers process death from the
 * numbers; the tab clears a restored target under its loading gate instead.
 */
object PolishWritePolicy {
    enum class Outcome { WAITING, DONE, FAILED }

    fun outcome(target: Int?, completed: Int, error: String?): Outcome = when {
        target == null -> Outcome.WAITING
        completed < target -> Outcome.WAITING
        error == null -> Outcome.DONE
        else -> Outcome.FAILED
    }
}

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

/** The host of a saved endpoint, for the self-hosted card; the whole string when it has no scheme. */
internal fun hostOf(endpoint: String): String =
    runCatching { java.net.URI(endpoint).host }.getOrNull()?.takeIf(String::isNotBlank) ?: endpoint

/** The provider's initial in a rounded tile, the same measurements as the Dictionary's picker cards. */
@Composable
internal fun ProviderTile(provider: Provider, size: androidx.compose.ui.unit.Dp = 44.dp) {
    Box(
        modifier = Modifier
            .size(size)
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(provider.capabilities().displayName.take(1), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
    }
}

/**
 * Three dots, filled up to [value] on a 1-3 scale, read against the C/S/A legend.
 *
 * [label] is required rather than optional because the dots are undecorated boxes: without a description
 * TalkBack reaches this meter and announces nothing at all, and the letter beside it is a separate node
 * that says "C" without a value. Making the label a parameter means a new meter cannot be added silently
 * mute, which is what the cloud rows were before this was moved down here.
 */
@Composable
internal fun ScoreDots(label: String, value: Int) {
    Column(
        modifier = Modifier.semantics(mergeDescendants = true) { contentDescription = "$label, $value of 3" },
        verticalArrangement = Arrangement.spacedBy(3.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        for (level in 3 downTo 1) {
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .background(
                        color = if (value >= level) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                        shape = CircleShape,
                    ),
            )
        }
    }
}

internal fun ValidationReason.userMessage(): String = when (this) {
    ValidationReason.API_KEY_REQUIRED -> "Enter an API key for this provider."
    ValidationReason.API_KEY_MUST_NOT_CONTAIN_CONTROL_CHARACTERS -> "The API key contains invalid characters."
    ValidationReason.ENDPOINT_REQUIRED -> "Enter the self-hosted server URL."
    ValidationReason.ENDPOINT_MUST_BE_HTTPS -> "Use HTTPS for this server URL."
    ValidationReason.ENDPOINT_MUST_BE_LOOPBACK_OR_HTTPS -> "Use an HTTPS URL, or loopback for local development."
    ValidationReason.ENDPOINT_MUST_HAVE_HOST -> "The server URL needs a valid host."
    ValidationReason.ENDPOINT_MUST_NOT_INCLUDE_CREDENTIALS -> "Remove credentials from the server URL."
    ValidationReason.ENDPOINT_MUST_NOT_INCLUDE_FRAGMENT -> "Remove the # fragment from the server URL."
    ValidationReason.ENDPOINT_MUST_NOT_CONTAIN_WHITESPACE -> "Remove spaces from the server URL."
}

/** "2 minutes ago" for the cache age line; coarse on purpose. */
internal fun relativeAge(fetchedAt: Long, now: Long = System.currentTimeMillis()): String {
    val minutes = ((now - fetchedAt) / 60_000L).coerceAtLeast(0)
    return when {
        minutes < 1 -> "just now"
        minutes < 60 -> "$minutes min ago"
        minutes < 60 * 24 -> "${minutes / 60} h ago"
        else -> "${minutes / (60 * 24)} d ago"
    }
}
