package com.envi.wispr.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.envi.wispr.R
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

/** Where the tab is standing: which rung one shows, and which tile the lower rungs describe. */
data class SetupNavigation(val cloudSetup: Boolean, val browsedName: String?)

/** What a tap on Cloud does: activate the configured provider, or open the setup rungs without a write. */
enum class CloudTap { ACTIVATE, SETUP }

enum class KeyRung { CONNECTED, FIELD }

/**
 * Where a user goes to make an API key for [Provider], and the domain to show them (#97).
 *
 * The domain is DISPLAYED, not decoration: it is the whole fallback if the browser never opens, because a
 * user who can read `aistudio.google.com` can type it, and one who read "Get your key" cannot.
 *
 * Exhaustive with no `else`, so a new provider must declare a portal or declare it has none. Ported from
 * the macOS links for OpenAI and Gemini; macOS ships no Claude link, and Android offers Claude, so that
 * one is ours.
 *
 * **No claim about price.** macOS says "free API key", which holds for AI Studio and is wrong for the
 * other two, both of which want billing set up before a key does anything.
 */
data class KeyPortal(val domain: String, val url: String)

/**
 * Measured 2026-09-02: `console.anthropic.com/settings/keys` answers 301 to `platform.claude.com`, so the
 * old address ships a redirect rather than a destination. Re-check these by fetching them, never by
 * remembering them; a dead link here is a user who cannot start.
 */
fun keyPortal(provider: Provider): KeyPortal? = when (provider) {
    Provider.OPENAI -> KeyPortal("platform.openai.com", "https://platform.openai.com/api-keys")
    Provider.GEMINI -> KeyPortal("aistudio.google.com", "https://aistudio.google.com/apikey")
    Provider.CLAUDE -> KeyPortal("platform.claude.com", "https://platform.claude.com/settings/keys")
    // A user's own server has no portal to send them to, and it never reaches this rung anyway.
    Provider.SELF_HOSTED_POLISH -> null
}

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

    /**
     * The tile to keep showing when a remove starts (#94).
     *
     * **Removing a key is a step BACK inside cloud setup, never a departure from it.**
     * `ProviderConfigurationRepository.clearSelection` resets the mode to `OFFLINE_S1` along with the key,
     * and that write is right: cloud polish cannot run without a key, so leaving the mode at `PROVIDER`
     * would make every later dictation attempt a provider that cannot answer. What was wrong is that the
     * SCREEN followed the write out of the rung the user was standing in (founder report 2026-09-02).
     *
     * Two things have to be pinned for the tab to hold its place, and this is the second: `displayedProvider`
     * falls back to the saved provider only while `configured` is true, so after the clear a user who
     * reached the connected row without ever tapping a tile has nothing displayed and rung 3 vanishes.
     *
     * **It answers about the TILE, never about what was removed**, and those come apart at the self-hosted
     * card: that card is drawn from `settings` alone, so it is still on screen after the user taps a cloud
     * tile, and Remove on it can be pressed with Gemini open below. Keeping Gemini is the right answer
     * there for the same reason the whole fix exists, that the tab must not lose the user's place; the
     * self-hosted card simply disappears from above a key field they were already filling in.
     *
     * Null only when there is no cloud tile to keep, which is a stale or absent browse rather than a
     * self-hosted removal. The caller assigns that null rather than skipping it, so a browse naming a
     * provider no longer in [CloudProviders] is cleared instead of left behind.
     */
    fun browsedAfterRemove(displayed: Provider?): Provider? = displayed?.takeIf { it in CloudProviders }

    /**
     * The WHOLE navigation a starting remove must produce, so the tab holds no part of the decision.
     *
     * `cloudSetup` lives here rather than as a `true` written at the call site because that flag IS the
     * bug: `rungOne` sends `OFFLINE_S1` to `THIS_PHONE` without it, and a test that passes the flag in by
     * hand asserts the fix it is supposed to be checking. Owning both values means a test can state what
     * a remove produces rather than what it hopes the caller remembered to set.
     */
    fun navigationAfterRemove(displayed: Provider?): SetupNavigation =
        SetupNavigation(cloudSetup = true, browsedName = browsedAfterRemove(displayed)?.name)

    /**
     * Connected whenever the DISPLAYED provider has a key in the Keystore, whatever is selected (#103).
     *
     * It used to also require the displayed tile to BE the saved provider, which made one boolean about one
     * provider answer for all four tiles. Removing a key clears the selection, so every tile then read
     * FIELD at once and the two keys still in the Keystore became invisible, unusable and impossible to
     * delete (founder report 2026-09-02, three keys stored and none shown). Switching providers also cost
     * a re-typed key that was already on the phone.
     *
     * There is no in-place replace (founder 2026-09-02): the row offers Remove alone, and adding a
     * different key is remove-then-enter. So this depends on stored state only, with no UI mode able to
     * force the field open over a live key.
     */
    fun keyRung(displayed: Provider, settings: ProviderSettingsUiState): KeyRung =
        if (displayed in settings.storedProviders) KeyRung.CONNECTED else KeyRung.FIELD

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
    fun defaultModel(provider: Provider, models: List<DiscoveredModel>): String? {
        val available = models.filter { it.access == ModelAccess.AVAILABLE }
        // The model a key STARTS on is the one the list badges Recommended (#99). Before this the two
        // disagreed: the badge was a class, so the start was merely the first of twelve equally-badged
        // rows, and a user could be started on a model while a different row wore the badge.
        return ModelListPresentation.recommendedPick(provider, models)
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
            defaultModel(displayed, discovery.models) == null

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
        defaultModel(displayed, discovery.models) != null

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
     * **There is no cost meter, and its absence is the point** (founder, 2026-09-02). The cloud rows need
     * one because their prices differ from each other; S1-mini is free and cannot become anything else,
     * so a meter here would spend a line encoding a constant. A meter earns its place by VARYING, and
     * this one cannot.
     *
     * Of the two that remain, only one has a measurement behind it:
     * - **Speed 3** is measured: 0.65 s on a short take, 2.5 to 3.5 s on a long one, and no network round
     *   trip (`.claude/knowledge/polish-engines.md` FACT: residency-measured-2026-09-01).
     * - **Accuracy 2 is an editorial judgement**, exactly as every cloud accuracy dot is. S1-mini is
     *   trained for this one job, so it clears the bucket the oldest general chat models sit in, and it
     *   is too small to reach the top. Replace it if a head-to-head is ever run, and say here what ran.
     *
     * `PolishLadderTest` asserts the RANGE across this row and every `ModelNotes` row, because a value
     * outside 1-3 renders a meter that is all-empty or never-full. It does not freeze the values: a
     * frozen constant asserts only that nobody edited it (`../rules/testing-philosophy.md`
     * RULE: every-test-declares-which-of-four-things-it-protects).
     */
    internal val S1_SCORES: ModelScores = ModelScores(speed = 3, accuracy = 2)
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

/**
 * The provider's own brand mark, tinted to [tint] and drawn at [size].
 *
 * The marks are ported from the shipping macOS app rather than redrawn (issue #92); the path data and the
 * trademark posture live in the drawables themselves, starting with `ic_provider_openai.xml`.
 *
 * **No content description, deliberately.** The only caller draws the provider's NAME directly under this
 * mark, so a description here makes a screen reader say the provider twice. macOS marks the same tile
 * `.accessibilityHidden(true)` for the same reason. A future caller that shows a mark with no visible name
 * must pass its own label rather than relying on this.
 *
 * Exhaustive over [Provider] with no `else`, so a new provider is a compile error here rather than a tile
 * that silently falls back to a letter.
 */
@Composable
internal fun ProviderTile(provider: Provider, tint: Color, size: androidx.compose.ui.unit.Dp) {
    val mark: Int? = when (provider) {
        Provider.OPENAI -> R.drawable.ic_provider_openai
        Provider.GEMINI -> R.drawable.ic_provider_gemini
        Provider.CLAUDE -> R.drawable.ic_provider_claude
        // Not reachable from rung 2 today: `CloudProviders` removes it from the row that is the only
        // caller. It still needs to draw, because the type permits it and a self-hosted endpoint has no
        // vendor whose mark we could use.
        Provider.SELF_HOSTED_POLISH -> null
    }
    if (mark != null) {
        Icon(
            painter = painterResource(mark),
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(size),
        )
    } else {
        Box(
            modifier = Modifier
                .size(size)
                .background(tint.copy(alpha = 0.15f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(provider.capabilities().displayName.take(1), style = MaterialTheme.typography.titleMedium, color = tint)
        }
    }
}

/**
 * What a meter says out loud. ONE owner for both renderers below, because the two draw the same value in
 * different directions and a description written twice is a description that drifts.
 */
internal fun scoreDescription(label: String, value: Int): String = "$label, $value of 3"

/** One dot, filled when the meter has reached [level]. Shared so the two directions cannot diverge. */
@Composable
private fun ScoreDot(value: Int, level: Int) {
    Box(
        modifier = Modifier
            .size(6.dp)
            .background(
                color = if (value >= level) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                shape = CircleShape,
            ),
    )
}

/**
 * Three dots stacked, filled from the bottom up to [value]. This is the DENSE form, for the cloud model
 * list, where thirty rows sit under one C/S/A header and a spelled-out label per row would not fit.
 *
 * [label] is required rather than optional because the dots are undecorated boxes: without a description
 * TalkBack reaches this meter and announces nothing at all, and the letter in the header is a separate
 * node that says "C" without a value. Making the label a parameter means a new meter cannot be added
 * silently mute, which is what the cloud rows were before this was moved down here.
 */
@Composable
internal fun ScoreDots(label: String, value: Int) {
    Column(
        modifier = Modifier.semantics(mergeDescendants = true) { contentDescription = scoreDescription(label, value) },
        verticalArrangement = Arrangement.spacedBy(3.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        for (level in 3 downTo 1) ScoreDot(value, level)
    }
}

/**
 * The same meter written along a line, `Speed: * * o`. This is the SPARSE form, for a card showing two of
 * them, where the label can be a word instead of a letter and the whole meter costs one line of height
 * rather than two (founder, 2026-09-02).
 */
@Composable
internal fun ScoreBar(label: String, value: Int) {
    Row(
        modifier = Modifier.semantics(mergeDescendants = true) { contentDescription = scoreDescription(label, value) },
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "$label:",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            // Cleared because the Row above already announces "Speed, 3 of 3"; merged with the word still
            // in the tree, TalkBack says the label twice. The stacked renderer carried this guard on its
            // own label and it was dropped when that composable was replaced, which is how a guard gets
            // lost: not by being argued away, but by living inside something deleted for another reason.
            modifier = Modifier.clearAndSetSemantics {},
        )
        for (level in 1..3) ScoreDot(value, level)
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
