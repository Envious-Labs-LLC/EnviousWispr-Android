package com.envi.wispr.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.envi.wispr.ui.theme.brandButtonColors
import com.envi.wispr.vocabulary.CustomTerm
import com.envi.wispr.vocabulary.CustomTermAuthoring
import com.envi.wispr.vocabulary.CustomTermRecord
import com.envi.wispr.vocabulary.MatchStrictness
import com.envi.wispr.vocabulary.VocabularyTransfer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Tighter than the default button padding, so Add/Import/Export fit without horizontal scrolling. */
private val pillPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)

/** The magnifying glass on the vocabulary search field. */
@Composable
private fun SearchGlyph() {
    val color = MaterialTheme.colorScheme.onSurfaceVariant
    Canvas(Modifier.size(20.dp)) {
        val stroke = 2.dp.toPx()
        val radius = size.minDimension * 0.30f
        val center = Offset(size.width * 0.42f, size.height * 0.42f)
        drawCircle(color, radius, center, style = Stroke(width = stroke))
        drawLine(
            color,
            Offset(center.x + radius * 0.72f, center.y + radius * 0.72f),
            Offset(size.width * 0.86f, size.height * 0.86f),
            stroke,
            StrokeCap.Round,
        )
    }
}

/** The plus mark on the "Add" button. */
@Composable
private fun PlusGlyph(color: Color = MaterialTheme.colorScheme.primary) {
    Canvas(Modifier.size(18.dp)) {
        val stroke = 2.2.dp.toPx()
        drawLine(
            color,
            Offset(size.width * 0.5f, size.height * 0.18f),
            Offset(size.width * 0.5f, size.height * 0.82f),
            stroke,
            StrokeCap.Round,
        )
        drawLine(
            color,
            Offset(size.width * 0.18f, size.height * 0.5f),
            Offset(size.width * 0.82f, size.height * 0.5f),
            stroke,
            StrokeCap.Round,
        )
    }
}

/** The vertical three-dot "more options" mark on a term row. */
@Composable
private fun KebabGlyph() {
    val color = MaterialTheme.colorScheme.onSurfaceVariant
    Canvas(Modifier.size(20.dp)) {
        val radius = size.minDimension * 0.07f
        listOf(0.22f, 0.5f, 0.78f).forEach { y ->
            drawCircle(color, radius, Offset(size.width * 0.5f, size.height * y))
        }
    }
}

/** An arrow rising out of a tray, for the "Import" button and the "Open a file" picker row. */
@Composable
private fun UploadGlyph() {
    val color = MaterialTheme.colorScheme.primary
    Canvas(Modifier.size(18.dp)) {
        val stroke = 1.8.dp.toPx()
        drawLine(color, Offset(size.width * 0.5f, size.height * 0.78f), Offset(size.width * 0.5f, size.height * 0.22f), stroke, StrokeCap.Round)
        drawLine(color, Offset(size.width * 0.5f, size.height * 0.22f), Offset(size.width * 0.28f, size.height * 0.46f), stroke, StrokeCap.Round)
        drawLine(color, Offset(size.width * 0.5f, size.height * 0.22f), Offset(size.width * 0.72f, size.height * 0.46f), stroke, StrokeCap.Round)
        drawLine(color, Offset(size.width * 0.2f, size.height * 0.92f), Offset(size.width * 0.8f, size.height * 0.92f), stroke, StrokeCap.Round)
    }
}

/** An arrow dropping into a tray, for "Export". */
@Composable
private fun DownloadGlyph() {
    val color = MaterialTheme.colorScheme.primary
    Canvas(Modifier.size(18.dp)) {
        val stroke = 1.8.dp.toPx()
        drawLine(color, Offset(size.width * 0.5f, size.height * 0.18f), Offset(size.width * 0.5f, size.height * 0.72f), stroke, StrokeCap.Round)
        drawLine(color, Offset(size.width * 0.5f, size.height * 0.72f), Offset(size.width * 0.28f, size.height * 0.48f), stroke, StrokeCap.Round)
        drawLine(color, Offset(size.width * 0.5f, size.height * 0.72f), Offset(size.width * 0.72f, size.height * 0.48f), stroke, StrokeCap.Round)
        drawLine(color, Offset(size.width * 0.2f, size.height * 0.92f), Offset(size.width * 0.8f, size.height * 0.92f), stroke, StrokeCap.Round)
    }
}

/** A clipboard outline, for the "Paste Words" picker row. */
@Composable
private fun ClipboardGlyph() {
    val color = MaterialTheme.colorScheme.primary
    Canvas(Modifier.size(18.dp)) {
        val stroke = 1.7.dp.toPx()
        drawRoundRect(
            color,
            topLeft = Offset(size.width * 0.20f, size.height * 0.16f),
            size = Size(size.width * 0.60f, size.height * 0.76f),
            cornerRadius = CornerRadius(size.width * 0.08f),
            style = Stroke(width = stroke),
        )
        drawRoundRect(
            color,
            topLeft = Offset(size.width * 0.38f, size.height * 0.08f),
            size = Size(size.width * 0.24f, size.height * 0.16f),
            cornerRadius = CornerRadius(size.width * 0.04f),
            style = Stroke(width = stroke),
        )
    }
}

/** A right-pointing chevron, for a tappable picker card. */
@Composable
private fun ChevronGlyph() {
    val color = MaterialTheme.colorScheme.onSurfaceVariant
    Canvas(Modifier.size(16.dp)) {
        val stroke = 1.8.dp.toPx()
        drawLine(color, Offset(size.width * 0.35f, size.height * 0.2f), Offset(size.width * 0.68f, size.height * 0.5f), stroke, StrokeCap.Round)
        drawLine(color, Offset(size.width * 0.68f, size.height * 0.5f), Offset(size.width * 0.35f, size.height * 0.8f), stroke, StrokeCap.Round)
    }
}

/** A rounded-square grid, for the disabled "From another app" row. */
@Composable
private fun AppGlyph(color: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    Canvas(Modifier.size(18.dp)) {
        val stroke = 1.7.dp.toPx()
        val cell = size.width * 0.32f
        val gap = size.width * 0.10f
        val start = (size.width - (cell * 2 + gap)) / 2f
        listOf(0, 1).forEach { row ->
            listOf(0, 1).forEach { col ->
                drawRoundRect(
                    color,
                    topLeft = Offset(start + col * (cell + gap), start + row * (cell + gap)),
                    size = Size(cell, cell),
                    cornerRadius = CornerRadius(cell * 0.22f),
                    style = Stroke(width = stroke),
                )
            }
        }
    }
}

/**
 * One term in the flat, divided vocabulary list: spelling, alias count, and an overflow menu.
 *
 * Tap toggles selection while [selectionMode] is active; a long press, or "Select" in the
 * overflow menu, starts selection from any row, matching the platform's own "select several, act
 * once" pattern rather than an always-on checkbox column the founder's mockup does not show.
 */
@Composable
private fun DictionaryTermRow(
    record: CustomTermRecord,
    selectionMode: Boolean,
    selected: Boolean,
    onToggleSelected: () -> Unit,
    onLongPress: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    val term = record.term
    // Outside selection mode a tap does nothing, so it must not claim clickable semantics: a
    // no-op click still draws a ripple and makes TalkBack announce "double tap to activate" for
    // an activation that has no result. Long-press alone, via pointerInput, carries neither.
    //
    // In selection mode the toggle semantics live on the ROW via `toggleable`, the same pattern
    // `SettingsToggleRow` uses for its Switch: the Checkbox passes `onCheckedChange = null` so it
    // does not also register as its own focusable node, which would otherwise give TalkBack two
    // adjacent stops — the row and the checkbox — for one action.
    val rowGestures = if (selectionMode) {
        Modifier.toggleable(value = selected, role = Role.Checkbox, onValueChange = { onToggleSelected() })
    } else {
        Modifier.pointerInput(record.id) {
            detectTapGestures(onLongPress = { onLongPress() })
        }
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(rowGestures)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (selectionMode) {
            Checkbox(checked = selected, onCheckedChange = null)
        }
        Column(Modifier.weight(1f)) {
            Text(term.spelling, style = MaterialTheme.typography.titleMedium)
            if (term.aliases.isNotEmpty()) {
                Text(
                    if (term.aliases.size == 1) "1 alias" else "${term.aliases.size} aliases",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
        if (!selectionMode) {
            Box {
                IconButton(
                    onClick = { menuExpanded = true },
                    modifier = Modifier.semantics { contentDescription = "More options for ${term.spelling}" },
                ) { KebabGlyph() }
                DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                    DropdownMenuItem(text = { Text("Edit") }, onClick = { menuExpanded = false; onEdit() })
                    DropdownMenuItem(text = { Text("Select") }, onClick = { menuExpanded = false; onLongPress() })
                    DropdownMenuItem(text = { Text("Delete") }, onClick = { menuExpanded = false; onDelete() })
                }
            }
        }
    }
}

@Composable
internal fun DictionaryScreen(
    terms: List<CustomTermRecord>,
    allTerms: List<CustomTermRecord>,
    search: String,
    message: String,
    error: String?,
    onSearchChange: (String) -> Unit,
    onAdd: (CustomTerm) -> Unit,
    onEdit: (CustomTermRecord, CustomTerm) -> Unit,
    onDelete: (CustomTermRecord) -> Unit,
    onBulkDelete: (Set<Long>) -> Unit,
    onImport: (String) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val importFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val imported = runCatching {
                withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.use { stream ->
                        val bytes = stream.readNBytes(2_000_001)
                        require(bytes.size <= 2_000_000) { "That vocabulary file is too large." }
                        bytes.toString(Charsets.UTF_8)
                    } ?: error("That vocabulary file could not be opened.")
                }
            }
            imported.onSuccess(onImport).onFailure { failure ->
                Toast.makeText(context, failure.message ?: "Unable to read vocabulary", Toast.LENGTH_LONG).show()
            }
        }
    }
    var selectedIds by remember { mutableStateOf(emptySet<Long>()) }
    var showNewEditor by remember { mutableStateOf(false) }
    var editTarget by remember { mutableStateOf<CustomTermRecord?>(null) }
    var deleteTarget by remember { mutableStateOf<CustomTermRecord?>(null) }
    var confirmBulkDelete by remember { mutableStateOf(false) }
    var showImport by remember { mutableStateOf(false) }
    var showImportPicker by remember { mutableStateOf(false) }
    val selectionMode = selectedIds.isNotEmpty()
    ScreenContainer {
        if (selectionMode) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    "${selectedIds.size} selected",
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { selectedIds = emptySet() }) { Text("Cancel") }
                OutlinedButton(onClick = { confirmBulkDelete = true }) { Text("Delete") }
            }
        } else {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(onClick = { showNewEditor = true }, contentPadding = pillPadding) {
                    PlusGlyph()
                    Spacer(Modifier.width(4.dp))
                    Text("Add", style = MaterialTheme.typography.labelMedium)
                }
                OutlinedButton(onClick = { showImportPicker = true }, contentPadding = pillPadding) {
                    UploadGlyph()
                    Spacer(Modifier.width(4.dp))
                    Text("Import", style = MaterialTheme.typography.labelMedium)
                }
                OutlinedButton(
                    contentPadding = pillPadding,
                    onClick = {
                        val exported = VocabularyTransfer.export(allTerms.map(CustomTermRecord::term))
                        val copied = runCatching {
                            context.getSystemService(ClipboardManager::class.java)
                                ?.setPrimaryClip(ClipData.newPlainText("EnviousWispr vocabulary", exported))
                                ?: error("Clipboard unavailable")
                        }.isSuccess
                        // Vocabulary JSON is now on the clipboard, so any standing claim that a
                        // dictation is waiting there to be pasted is false.
                        if (copied) {
                        }
                        Toast.makeText(
                            context,
                            if (copied) "Vocabulary copied" else "Unable to export vocabulary",
                            Toast.LENGTH_SHORT,
                        ).show()
                    },
                ) {
                    DownloadGlyph()
                    Spacer(Modifier.width(4.dp))
                    Text("Export", style = MaterialTheme.typography.labelMedium)
                }
            }
        }
        OutlinedTextField(
            value = search,
            onValueChange = onSearchChange,
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("Search your words") },
            leadingIcon = { SearchGlyph() },
            singleLine = true,
            shape = MaterialTheme.shapes.medium,
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.35f),
                unfocusedContainerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.25f),
                focusedBorderColor = MaterialTheme.colorScheme.primary,
                unfocusedBorderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.35f),
            ),
        )
        if (message.isNotBlank()) {
            Text(message, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        }
        error?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
        }
        if (terms.isEmpty()) {
            ElevatedCard {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(28.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        if (search.isBlank()) "No custom terms yet" else "No matching terms",
                        style = MaterialTheme.typography.titleLarge,
                    )
                    Text(
                        "Add a preferred spelling and optional aliases.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        } else {
            Card {
                Column {
                    terms.forEachIndexed { index, record ->
                        DictionaryTermRow(
                            record = record,
                            selectionMode = selectionMode,
                            selected = record.id in selectedIds,
                            onToggleSelected = {
                                selectedIds = if (record.id in selectedIds) selectedIds - record.id
                                else selectedIds + record.id
                            },
                            onLongPress = { selectedIds = selectedIds + record.id },
                            onEdit = { editTarget = record },
                            onDelete = { deleteTarget = record },
                        )
                        if (index < terms.lastIndex) HorizontalDivider()
                    }
                }
            }
        }
    }

    if (showNewEditor || editTarget != null) {
        CustomTermEditorDialog(
            record = editTarget,
            onDismiss = {
                showNewEditor = false
                editTarget = null
            },
            onSave = { term ->
                editTarget?.let { onEdit(it, term) } ?: onAdd(term)
                showNewEditor = false
                editTarget = null
            },
        )
    }
    deleteTarget?.let { record ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("Delete ${record.term.spelling}?") },
            text = { Text("This removes the term and its aliases from this phone.") },
            confirmButton = {
                TextButton(onClick = {
                    selectedIds -= record.id
                    onDelete(record)
                    deleteTarget = null
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("Cancel") } },
        )
    }
    if (confirmBulkDelete) {
        AlertDialog(
            onDismissRequest = { confirmBulkDelete = false },
            title = { Text("Delete ${selectedIds.size} custom terms?") },
            text = { Text("This permanently removes the selected terms from this phone.") },
            confirmButton = {
                TextButton(onClick = {
                    onBulkDelete(selectedIds)
                    selectedIds = emptySet()
                    confirmBulkDelete = false
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirmBulkDelete = false }) { Text("Cancel") } },
        )
    }
    if (showImport) {
        VocabularyImportDialog(
            onDismiss = { showImport = false },
            onImport = {
                onImport(it)
                showImport = false
            },
        )
    }
    if (showImportPicker) {
        ImportPickerDialog(
            onDismiss = { showImportPicker = false },
            onPasteWords = { showImportPicker = false; showImport = true },
            onOpenFile = {
                showImportPicker = false
                importFile.launch(arrayOf("application/json", "text/plain"))
            },
        )
    }
}

@Composable
private fun CustomTermEditorDialog(
    record: CustomTermRecord?,
    onDismiss: () -> Unit,
    onSave: (CustomTerm) -> Unit,
) {
    val existing = record?.term
    var spelling by remember(record?.id) { mutableStateOf(existing?.spelling.orEmpty()) }
    var aliases by remember(record?.id) { mutableStateOf(existing?.aliases.orEmpty()) }
    var newAlias by remember(record?.id) { mutableStateOf("") }
    var matchStrictness by remember(record?.id) {
        mutableStateOf(MatchStrictness.from(existing?.minSimilarityOverride))
    }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (record == null) "Add custom term" else "Edit custom term") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(0.72f)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedTextField(
                    value = spelling,
                    onValueChange = { spelling = it; error = null },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Preferred spelling") },
                    singleLine = true,
                )
                Text("What EnviousWispr might hear", style = MaterialTheme.typography.titleSmall)
                Text(
                    "Add each misspelling or alternate phrase that should become the preferred spelling.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                aliases.forEachIndexed { index, alias ->
                    ElevatedCard {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(alias, modifier = Modifier.weight(1f))
                            TextButton(onClick = { aliases = aliases.toMutableList().also { it.removeAt(index) } }) {
                                Text("Remove")
                            }
                        }
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedTextField(
                        value = newAlias,
                        onValueChange = { newAlias = it },
                        modifier = Modifier.weight(1f),
                        label = { Text("Misspelling or alias") },
                        singleLine = true,
                    )
                    OutlinedButton(
                        onClick = {
                            val updated = CustomTermAuthoring.includePendingAlias(aliases, newAlias)
                            if (updated !== aliases) {
                                aliases = updated
                                newAlias = ""
                            }
                        },
                        enabled = newAlias.isNotBlank(),
                    ) { Text("Add") }
                }
                Text("Match strictness", style = MaterialTheme.typography.titleSmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MatchStrictness.entries.forEach { strictness ->
                        FilterChip(
                            selected = matchStrictness == strictness,
                            onClick = { matchStrictness = strictness },
                            label = { Text(strictness.name.lowercase().replaceFirstChar(Char::uppercase)) },
                        )
                    }
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        confirmButton = {
            Button(colors = brandButtonColors(), onClick = {
                val normalizedSpelling = spelling.trim()
                val savedAliases = CustomTermAuthoring.includePendingAlias(aliases, newAlias)
                when {
                    normalizedSpelling.isEmpty() -> error = "Enter a preferred spelling."
                    normalizedSpelling.length > 200 -> error = "Preferred spelling must be 200 characters or fewer."
                    else -> onSave(
                        CustomTerm(
                            spelling = normalizedSpelling,
                            aliases = savedAliases,
                            category = existing?.category,
                            priority = existing?.priority ?: 0,
                            forceReplace = existing?.forceReplace ?: false,
                            caseSensitive = existing?.caseSensitive ?: false,
                            minSimilarityOverride = matchStrictness.thresholdOverride,
                            usageCount = existing?.usageCount ?: 0,
                            imported = existing?.imported ?: false,
                        ),
                    )
                }
            }) { Text("Save") }
        },
    )
}

@Composable
private fun ImportPickerDialog(
    onDismiss: () -> Unit,
    onPasteWords: () -> Unit,
    onOpenFile: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Import") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                ImportPickerCard(
                    glyph = { ClipboardGlyph() },
                    title = "Paste Words",
                    description = "Paste an EnviousWispr export or one word per line.",
                    onClick = onPasteWords,
                )
                ImportPickerCard(
                    glyph = { UploadGlyph() },
                    title = "Open a file",
                    description = "Choose a .txt list or an EnviousWispr .json export.",
                    onClick = onOpenFile,
                )
                ImportPickerCard(
                    glyph = { AppGlyph() },
                    title = "From another app",
                    description = "Bring words in from another dictation app.",
                    badge = "Coming soon",
                    onClick = null,
                )
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun ImportPickerCard(
    glyph: @Composable () -> Unit,
    title: String,
    description: String,
    onClick: (() -> Unit)?,
    badge: String? = null,
) {
    val cardModifier = Modifier
        .fillMaxWidth()
        .let { base ->
            if (onClick != null) {
                base.clickable(onClick = onClick)
            } else {
                base.alpha(0.55f).semantics(mergeDescendants = true) {
                    contentDescription = if (badge != null) "$title, $badge" else title
                    disabled()
                }
            }
        }
    Card(
        modifier = cardModifier,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.25f),
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center,
            ) { glyph() }
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (badge != null) {
                    Surface(
                        modifier = Modifier.padding(top = 4.dp),
                        shape = MaterialTheme.shapes.small,
                        color = MaterialTheme.colorScheme.surfaceVariant,
                    ) {
                        Text(
                            badge,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            if (onClick != null) {
                ChevronGlyph()
            }
        }
    }
}

@Composable
private fun VocabularyImportDialog(onDismiss: () -> Unit, onImport: (String) -> Unit) {
    var input by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Import vocabulary") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "Paste an EnviousWispr vocabulary export or one spelling per line.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(240.dp),
                    label = { Text("Vocabulary data") },
                )
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        confirmButton = {
            Button(
                onClick = { if (input.isNotBlank()) onImport(input) },
                enabled = input.isNotBlank(),
                colors = brandButtonColors(),
            ) {
                Text("Import")
            }
        },
    )
}
