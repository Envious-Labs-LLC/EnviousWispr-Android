package com.envi.wispr.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.envi.wispr.snippets.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
internal fun SnippetsPage() {
    val store = LocalContext.current.applicationContext.snippetStore
    val scope = rememberCoroutineScope()
    var library by remember { mutableStateOf<SnippetLibrary?>(null) }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    var refresh by remember { mutableIntStateOf(0) }
    LaunchedEffect(store, refresh) {
        try {
            store.data.collect { library = it }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            error = "Your snippets couldn't be opened."
        }
    }
    var query by rememberSaveable { mutableStateOf("") }
    var editId by rememberSaveable { mutableStateOf<String?>(null) }
    var keywordOpen by rememberSaveable { mutableStateOf(false) }
    val current = library
    fun write(action: (SnippetLibrary) -> SnippetLibrary, complete: () -> Unit) {
        scope.launch {
            try {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { store.updateData(action) }
                complete()
            } catch (e: CancellationException) {
                throw e
            } catch (e: IllegalArgumentException) {
                error = e.message
            } catch (_: Exception) {
                error = "Couldn't save your changes. Try again."
            }
        }
    }

    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Text("Say your command, then a trigger, to insert its saved text.", style = MaterialTheme.typography.bodyMedium)
        }
        if (current != null) {
            item {
                OutlinedButton(onClick = { keywordOpen = true }) { Text("Command word: " + current.keyword) }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = query, onValueChange = { query = it }, label = { Text("Search snippets") },
                        singleLine = true, modifier = Modifier.weight(1f))
                    Button(onClick = { editId = "" }) { Text("Add") }
                }
            }
            item { SnippetTransferActions(current, showError = { error = it }) }
            val search = query.trim()
            val visible = current.snippets.filter {
                it.trigger.contains(search, ignoreCase = true) || it.expansion.contains(search, ignoreCase = true)
            }
            if (visible.isEmpty()) item { Text(if (search.isEmpty()) "No snippets yet." else "No matching snippets.") }
            items(visible, key = { it.id }) { row ->
                OutlinedCard(modifier = Modifier.fillMaxWidth().clickable { editId = row.id }) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                            Text(row.trigger, style = MaterialTheme.typography.titleMedium)
                            if (row.isUneditedExample()) Text("Example", style = MaterialTheme.typography.labelSmall)
                        }
                        Text(row.expansion, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
    if (keywordOpen && current != null) {
        var word by rememberSaveable { mutableStateOf(current.keyword) }
        AlertDialog(onDismissRequest = { keywordOpen = false }, title = { Text("Command word") },
            text = { OutlinedTextField(word, { word = it }, label = { Text("One spoken word") }, singleLine = true) },
            confirmButton = { TextButton(onClick = { write({ it.withKeyword(word) }) { keywordOpen = false } }) { Text("Save") } },
            dismissButton = { TextButton(onClick = { keywordOpen = false }) { Text("Cancel") } })
    }
    val selected = editId
    if (selected != null && current != null) key(selected) {
        val initial = remember { current.snippets.find { it.id == selected } }
        var trigger by rememberSaveable { mutableStateOf(initial?.trigger.orEmpty()) }
        var expansion by rememberSaveable { mutableStateOf(initial?.expansion.orEmpty()) }
        var deleting by rememberSaveable { mutableStateOf(false) }
        if (deleting && initial != null) {
            AlertDialog(onDismissRequest = { deleting = false }, title = { Text("Delete snippet?") },
                text = { Text(initial.trigger) },
                dismissButton = { TextButton(onClick = { deleting = false }) { Text("Cancel") } },
                confirmButton = { TextButton(onClick = {
                    write({ latest -> latest.copy(snippets = latest.snippets.filterNot { it == initial }) }) { editId = null }
                }) { Text("Delete") } })
        } else {
            AlertDialog(onDismissRequest = { editId = null }, title = { Text(if (initial == null) "Add snippet" else "Edit snippet") },
                text = {
                    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(trigger, { trigger = it }, singleLine = true, label = { Text("Spoken trigger") })
                        OutlinedTextField(expansion, { expansion = it }, label = { Text("Text to insert") }, minLines = 3, maxLines = 7)
                        Row {
                            TextButton(onClick = { expansion += "{{date}}" }) { Text("Today's date") }
                            TextButton(onClick = { expansion += "{{time}}" }) { Text("Time now") }
                        }
                        if (SnippetFillIns.needsClipboard(expansion)) {
                            Text("This snippet needs the previous clipboard. Android can't read it while you're dictating in another app.")
                        }
                        if (initial != null) TextButton(onClick = { deleting = true }) { Text("Delete snippet") }
                    }
                },
                dismissButton = { TextButton(onClick = { editId = null }) { Text("Cancel") } },
                confirmButton = { TextButton(enabled = trigger.isNotBlank() && expansion.isNotBlank(), onClick = {
                    val next = initial?.copy(trigger = trigger.trim(), expansion = expansion) ?: newSnippet(trigger, expansion)
                    write({ it.save(initial, next) }) { editId = null }
                }) { Text("Save") } })
        }
    }
    error?.let { message ->
        AlertDialog(onDismissRequest = { error = null }, text = { Text(message) }, confirmButton = {
            TextButton(onClick = { error = null; refresh++ }) { Text("OK") }
        })
    }
}
