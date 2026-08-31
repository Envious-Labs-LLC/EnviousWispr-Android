package com.envi.wispr.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.widget.Toast
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.envi.wispr.history.TranscriptEntity
import com.envi.wispr.insertion.InsertionOutcomeMessages
import com.envi.wispr.polish.PolishEngineLabels
import java.text.DateFormat
import java.util.Date

/**
 * Past dictations, newest first.
 *
 * A card is a preview until it is asked for. Collapsed it carries two lines of the finished text and
 * one quiet line of when and which speech engine, which is what a list being SCANNED needs. The tap
 * adds the full finished text, the words as spoken, the details, and the actions. Founder direction
 * against `docs/mockups/android-v2/01-history-collapsed.png` and `-expanded.png`.
 *
 * Two things this screen deliberately does NOT do, so a reader does not go looking: it does not
 * translate a failed dictation into plainer language (#18), and it does not hide the blank card a
 * cancelled dictation leaves behind (#19). Both are about what is STORED, not about how a stored row
 * is laid out.
 */
@Composable
internal fun HistoryScreen(
    transcripts: List<TranscriptEntity>,
    totalCount: Int,
    search: String,
    error: String?,
    expandedId: Long?,
    onExpandedChange: (Long?) -> Unit,
    onSearchChange: (String) -> Unit,
    onKeep: (TranscriptEntity) -> Unit,
    onDelete: (TranscriptEntity) -> Unit,
    onDeleteAll: () -> Unit,
) {
    var confirmDeleteAll by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf<TranscriptEntity?>(null) }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        item {
            Text(
                "Your past dictations, searchable and ready to reuse.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth().widthIn(max = 900.dp),
            )
        }
        item {
            OutlinedTextField(
                value = search,
                onValueChange = onSearchChange,
                modifier = Modifier.fillMaxWidth().widthIn(max = 900.dp),
                label = { Text("Search history") },
                singleLine = true,
            )
        }
        if (error != null) {
            item {
                Text(
                    error,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.fillMaxWidth().widthIn(max = 900.dp),
                )
            }
        }
        if (transcripts.isEmpty()) {
            item {
                ElevatedCard(Modifier.fillMaxWidth().widthIn(max = 900.dp)) {
                    Column(
                        Modifier.fillMaxWidth().padding(32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            if (search.isBlank()) "No dictations yet" else "No matching dictations",
                            style = MaterialTheme.typography.titleLarge,
                        )
                        Text(
                            "Completed dictations stay on this phone.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        } else {
            item {
                Row(
                    Modifier.fillMaxWidth().widthIn(max = 900.dp),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = { confirmDeleteAll = true }) { Text("Delete all") }
                }
            }
            items(transcripts, key = { it.id }) { transcript ->
                HistoryCard(
                    transcript = transcript,
                    expanded = transcript.id == expandedId,
                    onToggle = {
                        onExpandedChange(if (transcript.id == expandedId) null else transcript.id)
                    },
                    onKeep = { onKeep(transcript) },
                    onDelete = { confirmDelete = transcript },
                )
            }
        }
    }
    confirmDelete?.let { transcript ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("Delete this dictation?") },
            text = { Text("This removes it from local history.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = null
                    // The card being removed is the one that is open, so the screen must not be left
                    // holding an id nothing can match.
                    if (expandedId == transcript.id) onExpandedChange(null)
                    onDelete(transcript)
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("Cancel") } },
        )
    }
    if (confirmDeleteAll) {
        AlertDialog(
            onDismissRequest = { confirmDeleteAll = false },
            title = { Text("Delete all history?") },
            text = { Text("This permanently removes all $totalCount saved dictations from this phone.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDeleteAll = false
                    onExpandedChange(null)
                    onDeleteAll()
                }) { Text("Delete all") }
            },
            dismissButton = { TextButton(onClick = { confirmDeleteAll = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun HistoryCard(
    transcript: TranscriptEntity,
    expanded: Boolean,
    onToggle: () -> Unit,
    onKeep: () -> Unit,
    onDelete: () -> Unit,
) {
    val context = LocalContext.current
    // Empty for every delivery outcome, because where one dictation's words went is a fact about one
    // moment while this row is read for weeks. What survives is a genuine transcript failure. Owner:
    // `InsertionOutcomeMessages.historyStatusLine`. It stays on the COLLAPSED card too: a row the
    // list is being scanned for is exactly where a failure must not hide behind a tap.
    val statusLine = InsertionOutcomeMessages.historyStatusLine(transcript.status)
    val polishLine = PolishEngineLabels.historySummary(transcript.polishEngine, transcript.polishLatencyMs)
    // The founder's spec asks the open card for full Final AND full Original, so the pair is always
    // present when there is an original to show. A row whose `originalText` is blank has none stored
    // at all — a draft, or a dictation that heard nothing — and a heading over an empty paragraph
    // names a contrast that is not on the card, so neither heading appears there.
    val hasOriginal = transcript.originalText.isNotBlank()
    // The one deviation, and it serves the same reading: when polish left the words alone, the
    // original is stated rather than reprinted. Repeating a long paragraph verbatim under a second
    // heading rebuilds the wall of text this card exists to avoid, and the reader's question — what
    // did polish change — is answered faster by the sentence than by comparing two identical blocks.
    val originalIsUnchanged = transcript.originalText == transcript.finalText
    // Medium date, short time: `Aug 30, 2026 10:23 PM`. The default pairing carries seconds, which
    // is noise on a row read months later. Built per card rather than shared, because `DateFormat`
    // instances are not safe to hold across callers.
    val timestamp = remember(transcript.createdAtMs) {
        DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
            .format(Date(transcript.createdAtMs))
    }

    ElevatedCard(Modifier.fillMaxWidth().widthIn(max = 900.dp)) {
        Column(Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(
                        onClickLabel = if (expanded) "Collapse this dictation" else "Show the whole dictation",
                        onClick = onToggle,
                    )
                    .padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    if (statusLine.isNotEmpty()) {
                        Text(
                            statusLine,
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    if (expanded && hasOriginal) Eyebrow("Final")
                    Text(
                        transcript.finalText.ifBlank { "No finalized text yet" },
                        style = MaterialTheme.typography.bodyLarge,
                        maxLines = if (expanded) Int.MAX_VALUE else 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (expanded && hasOriginal) {
                        Eyebrow("Original")
                        Text(
                            if (originalIsUnchanged) "Same as the finished text." else transcript.originalText,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (expanded) Eyebrow("Details")
                    Text(
                        "$timestamp · ${transcript.speechEngine}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (expanded && polishLine.isNotEmpty()) {
                        Text(
                            polishLine,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Chevron(expanded)
            }
            if (expanded) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CardAction("Copy", Modifier.weight(1f)) {
                        val copied = runCatching {
                            context.getSystemService(ClipboardManager::class.java)
                                ?.setPrimaryClip(ClipData.newPlainText("EnviousWispr", transcript.finalText))
                                ?: error("Clipboard unavailable")
                        }.isSuccess
                        Toast.makeText(
                            context,
                            if (copied) "Copied" else "Unable to copy",
                            Toast.LENGTH_SHORT,
                        ).show()
                    }
                    ActionSeparator()
                    CardAction(if (transcript.kept) "Unkeep" else "Keep", Modifier.weight(1f), onKeep)
                    ActionSeparator()
                    CardAction("Delete", Modifier.weight(1f), onDelete)
                }
            }
        }
    }
}

/**
 * The small purple heading that separates one part of an open card from the next.
 *
 * `Details` is always the last of them on an open card. Without it the when-and-how lines sat in the
 * same quiet grey directly under the original text and read as more of that paragraph (founder,
 * looking at the built card: "the date and polished by parts kind of blend in with the original").
 */
@Composable
private fun Eyebrow(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
    )
}

@Composable
private fun CardAction(label: String, modifier: Modifier, onClick: () -> Unit) {
    TextButton(onClick = onClick, modifier = modifier) { Text(label) }
}

@Composable
private fun ActionSeparator() {
    VerticalDivider(
        modifier = Modifier.height(20.dp),
        color = MaterialTheme.colorScheme.outlineVariant,
    )
}

/**
 * The open-and-close arrow, drawn rather than imported, like every other glyph in this shell.
 *
 * It points down when there is more to see and up when there is not, and it turns between the two so
 * that the card's own growth is not the only thing the eye has to follow.
 */
@Composable
private fun Chevron(expanded: Boolean) {
    val rotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        label = "chevron",
    )
    val color = MaterialTheme.colorScheme.onSurfaceVariant
    Canvas(
        modifier = Modifier
            .size(24.dp)
            .rotate(rotation)
            .semantics { contentDescription = if (expanded) "Collapse" else "Expand" },
    ) {
        val strokeWidth = 2.1.dp.toPx()
        drawLine(
            color,
            Offset(size.width * 0.28f, size.height * 0.40f),
            Offset(size.width * 0.50f, size.height * 0.62f),
            strokeWidth,
            StrokeCap.Round,
        )
        drawLine(
            color,
            Offset(size.width * 0.50f, size.height * 0.62f),
            Offset(size.width * 0.72f, size.height * 0.40f),
            strokeWidth,
            StrokeCap.Round,
        )
    }
}
