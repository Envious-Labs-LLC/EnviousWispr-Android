package com.envi.wispr.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.envi.wispr.models.ModelHealth
import com.envi.wispr.models.ModelUiState
import com.envi.wispr.providers.PolishMode
import com.envi.wispr.providers.Provider
import com.envi.wispr.providers.capabilities

enum class PolishStatusKind { OFF, LOCAL, OPENAI, GEMINI, CLAUDE, SELF_HOSTED }
enum class PolishStatusDot { GREEN, RED, NEUTRAL }

/** What the app-bar badge on the AI Polish tab shows. Built only from persisted, saved state. */
data class PolishStatusChip(
    val kind: PolishStatusKind,
    val label: String,
    val dot: PolishStatusDot,
)

/**
 * Pure function over persisted state only — never over a screen's own uncommitted local drafts.
 * [settings] and [s1State] are exactly what [PolishScreen] receives from its caller in `AppShell.kt`,
 * so the badge and the screen body can never disagree about what is actually running.
 *
 * The This-phone red/green split classifies every label `modelUiState()` (`ModelDeliveryUi.kt`) can
 * produce: "Ready" is green; "Failed", "Repair needed" and "Update failed" are all genuinely broken
 * states needing user action and are red; everything else in progress (queued, downloading,
 * verifying, paused, cancelled, update available, checking, missing) is neutral. Classifying by the
 * literal label rather than by `ModelUiAction` is deliberate: `RETRY` covers both "Failed" (broken)
 * and "Cancelled" (the user's own choice, not broken), and `UPDATE` covers both "Update failed"
 * (broken) and "Update available" (optional), so the action alone cannot tell them apart. The finer
 * detail beyond red/green/neutral already lives in the "This phone" panel's own status pill directly
 * below the badge; the badge's job is off/running/broken at a glance, not a second copy of that
 * panel's full state machine.
 *
 * The Cloud red/green split checks `credentialStored`, not just `configured` — a saved provider with
 * no actual key is a known-broken state (dictation will fail with a missing-key error), not a working
 * one.
 */
fun polishStatusChip(settings: ProviderSettingsUiState, s1State: ModelUiState): PolishStatusChip = when (settings.mode) {
    PolishMode.OFF -> PolishStatusChip(PolishStatusKind.OFF, "Polish off", PolishStatusDot.NEUTRAL)

    PolishMode.OFFLINE_S1 -> PolishStatusChip(
        kind = PolishStatusKind.LOCAL,
        label = "S1-mini",
        dot = when (s1State.health) {
            ModelHealth.READY -> PolishStatusDot.GREEN
            ModelHealth.BROKEN -> PolishStatusDot.RED
            ModelHealth.NOT_READY, ModelHealth.UNKNOWN -> PolishStatusDot.NEUTRAL
        },
    )

    PolishMode.PROVIDER -> if (!settings.configured) {
        PolishStatusChip(PolishStatusKind.OFF, "Cloud, not set up", PolishStatusDot.NEUTRAL)
    } else when (settings.provider) {
        // `configured` alone does not mean a key exists — `ProviderConfigurationRepository.load()`
        // can return a saved provider/model with no stored key, and removing a key resets
        // `settings.provider` to its default rather than to null (same fact `initialKeyRung` in
        // `PolishScreen.kt` guards against; caught for the badge specifically while enumerating that
        // class in code review, 2026-09-01). Self-hosted has no key concept, so it is exempt.
        Provider.OPENAI, Provider.GEMINI, Provider.CLAUDE -> if (!settings.credentialStored) {
            PolishStatusChip(kindFor(settings.provider), settings.model, PolishStatusDot.RED)
        } else {
            PolishStatusChip(kindFor(settings.provider), settings.model, PolishStatusDot.GREEN)
        }
        Provider.SELF_HOSTED_POLISH -> PolishStatusChip(
            PolishStatusKind.SELF_HOSTED,
            Provider.SELF_HOSTED_POLISH.capabilities().displayName,
            PolishStatusDot.GREEN,
        )
    }
}

private fun kindFor(provider: Provider): PolishStatusKind = when (provider) {
    Provider.OPENAI -> PolishStatusKind.OPENAI
    Provider.GEMINI -> PolishStatusKind.GEMINI
    Provider.CLAUDE -> PolishStatusKind.CLAUDE
    Provider.SELF_HOSTED_POLISH -> PolishStatusKind.SELF_HOSTED
}

// The macOS reference uses stToggleOn (#5CC99A) for this exact "working" signal; Android's theme has
// no equivalent semantic token yet, so it is named here rather than guessed as a MaterialTheme color.
private val WorkingGreen = Color(0xFF5CC99A)

/**
 * One or two letters standing in for a brand mark. No icon library is a dependency of this app and
 * porting exact provider logos is out of scope for this change; a monogram is the cheapest honest
 * stand-in for "an icon for what's running" until a real glyph set exists.
 */
private fun PolishStatusKind.monogram(): String = when (this) {
    PolishStatusKind.OFF -> "–"
    PolishStatusKind.LOCAL -> "1P"
    PolishStatusKind.OPENAI -> "AI"
    PolishStatusKind.GEMINI -> "G"
    PolishStatusKind.CLAUDE -> "C"
    PolishStatusKind.SELF_HOSTED -> "SH"
}

@Composable
internal fun PolishStatusBadge(chip: PolishStatusChip) {
    val dotColor = when (chip.dot) {
        PolishStatusDot.GREEN -> WorkingGreen
        PolishStatusDot.RED -> MaterialTheme.colorScheme.error
        PolishStatusDot.NEUTRAL -> MaterialTheme.colorScheme.outline
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(50))
            .padding(horizontal = 8.dp, vertical = 6.dp),
    ) {
        Box(
            modifier = Modifier
                .size(18.dp)
                .background(MaterialTheme.colorScheme.secondaryContainer, RoundedCornerShape(6.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                chip.kind.monogram(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
            )
        }
        Text(
            chip.label,
            // A long custom model name, or a larger system font size, must never let this label push
            // the mic button or the screen title out of the top bar (caught in code review,
            // 2026-09-01) — `maxLines`/`overflow` alone only trim the text once it already has an
            // unbounded width to grow into.
            modifier = Modifier.widthIn(max = 120.dp),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Box(
            modifier = Modifier
                .size(8.dp)
                .background(dotColor, CircleShape),
        )
    }
}
