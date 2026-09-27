package com.envi.wispr.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import com.envi.wispr.privacy.PrivacyDisclosures

/**
 * The privacy boundary in the app's own words (issue #176): what stays, what leaves for telemetry, and
 * the policy. The sentences are `PrivacyDisclosures`'s, the enforcer file, so this page cannot drift
 * from what the sanitizer and the identity file actually guarantee. [showDetailedLog] is
 * [detailedLogSentenceShown]'s answer (#378).
 */
/** #378 D5: the detailed-log sentence shows while the switch is on OR while a log file or shared export remains. */
internal fun detailedLogSentenceShown(detailedLogOn: Boolean, retainedFilesExist: Boolean): Boolean =
    detailedLogOn || retainedFilesExist

@Composable
internal fun PrivacyPage(showDetailedLog: Boolean) {
    val uriHandler = LocalUriHandler.current
    ScreenContainer(subtitle = SettingsPage.Privacy.subtitle) {
        if (showDetailedLog) {
            Card {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Detailed log", style = MaterialTheme.typography.titleMedium)
                    Text(
                        PrivacyDisclosures.DETAILED_LOG,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        Card {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Your voice stays with you", style = MaterialTheme.typography.titleMedium)
                Text(
                    PrivacyDisclosures.ON_DEVICE_SUMMARY,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Card {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Usage and crash reports", style = MaterialTheme.typography.titleMedium)
                Text(
                    PrivacyDisclosures.TELEMETRY_SUMMARY,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    PrivacyDisclosures.TELEMETRY_VENDORS,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        FilledTonalButton(onClick = { uriHandler.openUri(PrivacyDisclosures.POLICY_URL) }) {
            Text("Read the privacy policy")
        }
    }
}
