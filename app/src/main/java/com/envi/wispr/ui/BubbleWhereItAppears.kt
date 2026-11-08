package com.envi.wispr.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * The Appearance page's "Where it appears" section (#523): the password-box switch, and the apps the floating
 * button is kept out of, each with its own way back on this same page
 * (`code-design-rules.md` RULE: an-affordance-that-sets-a-state-must-clear-it-from-the-same-surface).
 */
@Composable
internal fun BubbleWhereItAppears(
    state: BubbleVisibilityUiState,
    onShowOnPasswordsChanged: (Boolean) -> Unit,
    onHideIn: (String) -> Unit,
    onShowIn: (String) -> Unit,
    onLoadPickable: () -> Unit,
) {
    var picking by rememberSaveable { mutableStateOf(false) }
    // Keyed on the saved flag, so a picker restored after process death lists the apps again.
    LaunchedEffect(picking) {
        if (picking) onLoadPickable()
    }

    SettingsGroup("Where it appears") {
        SettingsToggleRow(
            title = "Show on password boxes",
            subtitle = "Off keeps the floating button away from passwords and PINs.",
            checked = state.showOnPasswords,
            onCheckedChange = onShowOnPasswordsChanged,
        )
        state.hiddenApps.forEach { app ->
            HiddenAppRow(app = app, onShowAgain = { onShowIn(app.packageName) })
        }
        SettingsActionRow(
            title = "Hide in an app",
            subtitle = if (state.hiddenApps.isEmpty()) {
                "The floating button shows in every app. Pick one you never dictate in."
            } else {
                "Pick another app to keep the floating button out of."
            },
            ready = null,
            onClick = { picking = true },
        )
    }
    Text(
        "The floating button never shows on the lock screen. Hiding it only hides the button; " +
            "the side button and the Quick Settings tile still start a dictation.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    if (picking) {
        AppPicker(
            apps = state.pickable?.filter { candidate -> state.hiddenApps.none { it.packageName == candidate.packageName } },
            onPick = { packageName ->
                onHideIn(packageName)
                picking = false
            },
            onDismiss = { picking = false },
        )
    }
}

@Composable
private fun HiddenAppRow(app: AppChoice, onShowAgain: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 18.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(app.label, style = MaterialTheme.typography.titleMedium)
            Text(
                "Floating button hidden here",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        TextButton(onClick = onShowAgain) { Text("Show again") }
    }
}

/** [apps] null while the phone's apps are being listed; empty when none could be listed or all are hidden. */
@Composable
private fun AppPicker(apps: List<AppChoice>?, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Hide the floating button in") },
        text = {
            when {
                apps == null -> Text("Listing your apps")
                apps.isEmpty() -> Text("No apps to pick from.")
                else -> LazyColumn(Modifier.heightIn(max = 420.dp)) {
                    items(apps, key = { it.packageName }) { app ->
                        TextButton(onClick = { onPick(app.packageName) }, modifier = Modifier.fillMaxWidth()) {
                            Text(app.label, modifier = Modifier.fillMaxWidth())
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
