package com.envi.wispr.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.envi.wispr.feedback.FeedbackController
import com.envi.wispr.feedback.FeedbackPhase
import com.envi.wispr.feedback.FeedbackSender
import com.envi.wispr.feedback.FeedbackValidation
import com.envi.wispr.feedback.feedbackGraphemes
import kotlinx.coroutines.delay

/** Native feedback surface; draft/submission lifetime belongs to the feedback domain, not this dialog. */
@Composable
internal fun FeedbackScreen(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val controller = remember(context.applicationContext) { FeedbackController.of(context) }
    val state = controller.state
    var preview by remember { mutableStateOf(false) }
    // Survives activity recreation, but disappears when the user deliberately dismisses this form.
    var savedConsent by rememberSaveable { mutableStateOf<Boolean?>(null) }
    var opening by remember { mutableStateOf(0L) }
    DisposableEffect(controller) {
        val token = controller.open(savedConsent)
        opening = token
        savedConsent = controller.state.includeDiagnostics
        onDispose { controller.close(token) }
    }
    val active = opening != 0L && opening == state.presentation
    LaunchedEffect(state.phase, state.presentation, opening) {
        if (active && state.phase == FeedbackPhase.SAVED) {
            delay(1800)
            onDismiss()
        }
    }
    val issue = FeedbackValidation.issue(state.draft, ::feedbackGraphemes)
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(shape = MaterialTheme.shapes.large, tonalElevation = 3.dp,
            modifier = Modifier.fillMaxWidth(0.94f).imePadding().heightIn(max = 680.dp)) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    FeedbackBugGlyph(Modifier.size(32.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Send feedback", style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
                        Text("Found a bug or have an idea? We read every message.", style = MaterialTheme.typography.bodySmall)
                    }
                    TextButton(onClick = onDismiss) { Text("Close") }
                }
                if (state.phase == FeedbackPhase.SAVED) {
                    Text(if (state.offline) "Saved. We'll send it when you're back online." else "Thanks. Your feedback is on its way.",
                        style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                    Text("If you left your email, we'll reply there.")
                } else {
                    OutlinedTextField(value = state.draft.message,
                        onValueChange = { controller.edit(opening, it, controller.state.draft.email) },
                        enabled = state.loaded && active, minLines = 4, maxLines = 6,
                        label = { Text("Feedback message") }, placeholder = { Text("What happened, or what would you like to see?") },
                        isError = issue == FeedbackValidation.Issue.TOO_LONG,
                        supportingText = { if (issue == FeedbackValidation.Issue.TOO_LONG) Text("Please shorten your message to 4,000 characters.") },
                        modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = state.draft.email,
                        onValueChange = { controller.edit(opening, controller.state.draft.message, it) },
                        enabled = state.loaded && active, singleLine = true,
                        label = { Text("Email (optional, if you'd like a reply)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                        isError = issue == FeedbackValidation.Issue.EMAIL,
                        supportingText = { if (issue == FeedbackValidation.Issue.EMAIL) Text("Please check your email address, or leave it empty.") },
                        modifier = Modifier.fillMaxWidth())
                    Row(Modifier.fillMaxWidth().toggleable(
                        value = state.includeDiagnostics,
                        enabled = active && (state.snapshotLoading || state.diagnostics != null),
                        role = Role.Checkbox,
                        onValueChange = {
                            if (opening == controller.state.presentation) savedConsent = it
                            controller.includeDiagnostics(opening, it)
                        },
                    ).semantics { contentDescription = "Include diagnostics" }) {
                        Checkbox(checked = state.includeDiagnostics, onCheckedChange = null,
                            enabled = active && (state.snapshotLoading || state.diagnostics != null))
                        Column(Modifier.weight(1f).padding(top = 12.dp)) {
                            Text("Include diagnostics", style = MaterialTheme.typography.bodyMedium)
                            Text("Includes recent dictation details. No audio or dictated text.", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    Text("Your message and optional email go to Envious Labs via Sentry. This report can link to earlier usage reports from this install.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (state.includeDiagnostics && state.snapshotLoading) Text("Loading diagnostics...")
                    if (!state.snapshotLoading && state.diagnostics == null) Text("No diagnostics available")
                    if (!state.includeDiagnostics) Text("No diagnostics will be attached", style = MaterialTheme.typography.bodySmall)
                    if (state.includeDiagnostics && state.diagnostics != null) {
                        TextButton(onClick = { preview = !preview }) { Text(if (preview) "Hide diagnostics" else "Preview diagnostics") }
                        if (preview) {
                            Text(FeedbackSender.FILENAME, style = MaterialTheme.typography.labelSmall)
                            SelectionContainer { Text(state.diagnostics!!, style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.fillMaxWidth().heightIn(max = 180.dp).verticalScroll(rememberScrollState())) }
                        }
                    }
                    state.problem?.let {
                        Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        Spacer(Modifier.weight(1f))
                        Button(onClick = { controller.send(opening) }, enabled = active && state.loaded && issue == null &&
                            state.phase != FeedbackPhase.SAVING && !(state.includeDiagnostics && state.snapshotLoading)) {
                            Text(if (state.phase == FeedbackPhase.SAVING) "Saving..." else "Send")
                        }
                    }
                }
            }
        }
    }
}

/** Drawn in the same style as Android's existing header glyphs, using the Mac's ladybug shape. */
@Composable
internal fun FeedbackBugGlyph(modifier: Modifier = Modifier) {
    val color = MaterialTheme.colorScheme.primary
    Canvas(modifier.size(24.dp)) {
        val stroke = 1.8.dp.toPx()
        for (y in listOf(0.38f, 0.55f, 0.72f)) {
            drawLine(color, Offset(size.width * .16f, size.height * y), Offset(size.width * .33f, size.height * y), stroke, StrokeCap.Round)
            drawLine(color, Offset(size.width * .67f, size.height * y), Offset(size.width * .84f, size.height * y), stroke, StrokeCap.Round)
        }
        drawOval(color, Offset(size.width * .28f, size.height * .24f), Size(size.width * .44f, size.height * .59f), style = Stroke(stroke))
        drawArc(color, 180f, 180f, false, Offset(size.width * .36f, size.height * .12f), Size(size.width * .28f, size.height * .24f), style = Stroke(stroke))
        drawLine(color, Offset(size.width * .5f, size.height * .32f), Offset(size.width * .5f, size.height * .80f), stroke)
        for (x in listOf(.39f, .61f)) for (y in listOf(.45f, .65f)) drawCircle(color, stroke * .65f, Offset(size.width * x, size.height * y))
    }
}
