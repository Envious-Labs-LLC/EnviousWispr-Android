package com.envi.wispr.ui

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.envi.wispr.debug.DeveloperLogs
import com.envi.wispr.debug.DeveloperSwitches
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The hidden Developer page (#378 D1, D5, D6): the Detailed log and Keep recordings switches, Share log and
 * Delete shared log ZIPs. English only (catalog decision 2026-09-25: developer screens are never
 * translated). The founder never needs it: Claude drives the same switches and pulls the same ZIP through
 * the adb door; this page is the backup path.
 *
 * A switch shows On or Off only when `DeveloperSwitches` settled it; while a request runs it says so, and a
 * failure is shown as an error rather than a settled value.
 */
@Composable
internal fun DeveloperPage() {
    val context = LocalContext.current
    val switches = remember { DeveloperSwitches.of(context) }
    val logs = remember { DeveloperLogs.of(context) }
    val state by switches.state.collectAsState()
    val scope = rememberCoroutineScope()
    var message by remember { mutableStateOf<String?>(null) }
    var logBytes by remember { mutableStateOf(0L) }
    LaunchedEffect(state) { logBytes = withContext(Dispatchers.IO) { logs.liveLogBytes() } }

    ScreenContainer(subtitle = SettingsPage.Developer.subtitle) {
        SettingsGroup("Detailed log") {
            SettingsToggleRow(
                title = "Detailed log",
                subtitle = switchSubtitle(
                    state.detailedLog,
                    on = "On. Every step of each dictation, with its words, is written to a file on this phone (last 50 MB).",
                    off = "Off. Turning it off stops new logging; earlier logs stay until later logging overwrites them.",
                ),
                checked = state.detailedLog == DeveloperSwitches.Switch.On,
                enabled = state.detailedLog != DeveloperSwitches.Switch.Pending,
                onCheckedChange = { switches.requestDetailedLog(it) },
            )
            // The recording archive (#373) asks this switch at every take, when the take's audio is cleaned up
            // (`DictationSessionService` passes `keepRecordingsNow()` to `RecordingArchive`), so a row that looks
            // settled is settled (#375, founder 2026-09-30: at launch nothing is recorded, so a release build
            // starts Off). Keeping is best effort, and the On sentence says so.
            SettingsToggleRow(
                title = "Keep recordings",
                subtitle = switchSubtitle(
                    state.keepRecordings,
                    on = "On. The app tries to keep the audio of your last 10 dictations on this phone, in this app's storage, for testing. A dictation made while the app is still starting, or one that fails to save, is skipped. It is never uploaded.",
                    off = "Off. No audio is copied: each recording is queued for deletion once it has been processed. A change applies to every recording not yet processed, including one in progress. Copies already kept stay on the phone.",
                ),
                checked = state.keepRecordings == DeveloperSwitches.Switch.On,
                enabled = state.keepRecordings != DeveloperSwitches.Switch.Pending,
                onCheckedChange = { switches.requestKeepRecordings(it) },
            )
        }
        SettingsGroup("Log files") {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "Log on this phone: ${logBytes / 1024} KB. A shared ZIP lists which parts of the app confirmed their lines; it never claims to be complete.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                FilledTonalButton(onClick = {
                    message = "Preparing the log…"
                    scope.launch {
                        val result = withContext(Dispatchers.IO) { runCatching { logs.shareZip().get() } }
                        result.onSuccess { built ->
                            val uri = FileProvider.getUriForFile(context, "${context.packageName}.logs", built.zip)
                            val send = Intent(Intent.ACTION_SEND).apply {
                                type = "application/zip"
                                putExtra(Intent.EXTRA_STREAM, uri)
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            context.startActivity(Intent.createChooser(send, "Share log").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                            val unconfirmed = built.statuses.filterValues { it != "written 0" }.keys
                            message = "Log ZIP: ${built.zip.length() / 1024} KB." +
                                if (unconfirmed.isEmpty()) "" else " Not fully confirmed: ${unconfirmed.joinToString()}."
                        }.onFailure { message = "The log could not be prepared." }
                    }
                }) { Text("Share log") }
                OutlinedButton(onClick = {
                    scope.launch {
                        val removed = withContext(Dispatchers.IO) { runCatching { logs.deleteSharedZips().get() }.getOrDefault(0) }
                        message = "Deleted $removed shared log ZIP(s)."
                    }
                }) { Text("Delete shared log ZIPs") }
                Text(
                    "Delete shared log ZIPs removes only log exports. Shared recording WAVs remain for their stated 24-hour lifetime. An app still opening one would fail.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                message?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
            }
        }
    }
}

private fun switchSubtitle(switch: DeveloperSwitches.Switch, on: String, off: String): String = when (switch) {
    DeveloperSwitches.Switch.On -> on
    DeveloperSwitches.Switch.Off -> off
    DeveloperSwitches.Switch.Pending -> "Changing…"
    is DeveloperSwitches.Switch.Error -> "Error: ${switch.reason}"
}
