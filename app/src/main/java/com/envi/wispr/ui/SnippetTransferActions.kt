package com.envi.wispr.ui

import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.envi.wispr.snippets.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun SnippetTransferActions(current: SnippetLibrary, showError: (String) -> Unit) {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    var importing by rememberSaveable { mutableStateOf(false) }
    var pasted by remember { mutableStateOf("") }
    var format by remember { mutableStateOf(SnippetFormat.AUTO) }
    var review by remember { mutableStateOf<SnippetImport?>(null) }
    var busy by remember { mutableStateOf(false) }
    var exportSnapshot by remember { mutableStateOf<SnippetLibrary?>(null) }
    var notice by remember { mutableStateOf("") }
    fun perform(action: suspend () -> Unit) {
        if (busy) return
        busy = true
        scope.launch {
            try { action() }
            catch (e: CancellationException) { throw e }
            catch (e: IllegalArgumentException) { showError(e.message ?: "Couldn't read these snippets.") }
            catch (_: Exception) { showError("Couldn't read or write the snippet file. Check the file and try again.") }
            finally { busy = false }
        }
    }

    val readFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) perform {
            review = withContext(Dispatchers.IO) {
                val name = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                    if (it.moveToFirst()) it.getString(0) else null
                }.orEmpty()
                val kind = when (name.substringAfterLast('.').lowercase()) {
                    "csv" -> SnippetFormat.CSV
                    "json" -> SnippetFormat.JSON
                    "txt" -> SnippetFormat.LIST
                    else -> SnippetFormat.AUTO
                }
                val content = requireNotNull(context.contentResolver.openInputStream(uri)).use { SnippetTransfer.read(it, kind) }
                SnippetTransfer.preview(content, kind, current)
            }
            importing = false
        }
    }
    val writeFile = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        val snapshot = exportSnapshot ?: current
        exportSnapshot = null
        if (uri != null) perform {
            withContext(Dispatchers.IO) {
                SnippetTransfer.validateLibrary(snapshot)
                requireNotNull(context.contentResolver.openOutputStream(uri, "wt")).use { SnippetSerializer.writeTo(snapshot, it) }
            }
            notice = "Snippets exported."
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(enabled = !busy, onClick = { pasted = ""; format = SnippetFormat.AUTO; importing = true }) { Text("Import") }
        OutlinedButton(enabled = !busy && current.snippets.isNotEmpty(), onClick = {
            perform {
                withContext(Dispatchers.Default) { SnippetTransfer.validateLibrary(current) }
                exportSnapshot = current
                writeFile.launch("EnviousWispr Snippets.json")
            }
        }) { Text("Export") }
    }
    if (notice.isNotEmpty()) Text(notice, style = MaterialTheme.typography.bodySmall)
    if (importing) AlertDialog(onDismissRequest = { if (!busy) importing = false }, title = { Text("Import snippets") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(enabled = !busy, onClick = { readFile.launch(arrayOf("*/*")) }) { Text("Open a file") }
                OutlinedTextField(pasted, { pasted = it }, label = { Text("Paste a list, CSV or exported JSON") }, minLines = 2, maxLines = 5)
                Row {
                    SnippetFormat.entries.filter { it != SnippetFormat.JSON }.forEach { kind ->
                        TextButton(onClick = { format = kind }) {
                            val label = when (kind) {
                                SnippetFormat.AUTO -> "Auto"
                                SnippetFormat.LIST -> "List"
                                SnippetFormat.CSV -> "CSV"
                                SnippetFormat.JSON -> "JSON"
                            }
                            Text(if (format == kind) "✓ $label" else label)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(enabled = !busy && pasted.isNotBlank(), onClick = {
            perform {
                review = withContext(Dispatchers.Default) { SnippetTransfer.preview(pasted, format, current) }
                importing = false
            }
        }) { Text("Review") } },
        dismissButton = { TextButton(enabled = !busy, onClick = { importing = false }) { Text("Cancel") } })
    review?.let { batch ->
        var selected by remember(batch) { mutableStateOf(batch.candidates.map { it.id }.toSet()) }
        AlertDialog(onDismissRequest = { if (!busy) review = null }, title = { Text("Review snippets") },
            text = { LazyColumn(Modifier.heightIn(max = 400.dp)) {
                batch.ambiguousInput?.let { input ->
                    item {
                        val next = if (batch.readAs == SnippetFormat.LIST) SnippetFormat.CSV else SnippetFormat.LIST
                        Text(if (batch.readAs == SnippetFormat.LIST) "Reading as a list." else "Reading as CSV.")
                        TextButton(enabled = !busy, onClick = {
                            perform {
                                review = withContext(Dispatchers.Default) {
                                    SnippetTransfer.preview(input, next, current).copy(ambiguousInput = input)
                                }
                            }
                        }) { Text(if (next == SnippetFormat.CSV) "Read as CSV" else "Read as a list") }
                    }
                }
                if (batch.skippedLines > 0) item { Text("Skipped rows: " + batch.skippedLines) }
                items(batch.candidates, key = { it.id }) { row ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(row.id in selected, { checked -> selected = if (checked) selected + row.id else selected - row.id })
                        Column {
                            Text(row.trigger, style = MaterialTheme.typography.titleSmall)
                            Text(row.expansion, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            if (SnippetFillIns.needsClipboard(row.expansion)) Text("Clipboard fill-in unavailable on Android.")
                        }
                    }
                }
                items(batch.alreadySaved, key = { it.id }) { Text(it.trigger + " · Already included") }
            } },
            confirmButton = { TextButton(enabled = !busy && selected.isNotEmpty(), onClick = {
                perform {
                    context.snippetStore.updateData { batch.commit(it, selected) }
                    notice = "Imported " + selected.size + " snippets."
                    review = null
                }
            }) { Text("Import " + selected.size) } },
            dismissButton = { TextButton(enabled = !busy, onClick = { review = null }) { Text("Close") } })
    }
}
