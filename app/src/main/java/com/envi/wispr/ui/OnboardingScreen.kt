package com.envi.wispr.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.envi.wispr.models.ModelHealth
import com.envi.wispr.models.ModelManifest
import com.envi.wispr.models.ModelUiAction
import com.envi.wispr.paste.AutoPasteAvailability
import com.envi.wispr.paste.BubbleLook
import com.envi.wispr.shortcuts.RecordingOverlayState

/** The floating button's own words, shared by the setup screen and the Accessibility disclosure. */
internal const val HOW_THE_LIPS_WORK = "Tap the lips to dictate. Hold them to talk. They appear beside any text box."
internal const val ACCESSIBILITY_CARD_COPY =
    "To find your text box, show the floating lips button beside it, and paste your words after you start a dictation."

@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun OnboardingScreen(
    step: Int,
    readiness: AppReadiness,
    autoPaste: AutoPasteAvailability,
    onStepChange: (Int) -> Unit,
    onDismiss: () -> Unit,
    onRequestMicrophone: () -> Unit,
    onRequestNotifications: () -> Unit,
    onOpenAccessibility: () -> Unit,
    onComplete: () -> Unit,
) {
    val model: OnboardingViewModel = viewModel()
    val downloads by model.downloads.collectAsStateWithLifecycle()
    val stage = onboardingStage(step, readiness, autoPaste)
    val dark = isSystemInDarkTheme()
    val background = if (dark) Color(0xFF251F37) else Color.White
    val surface = if (dark) Color(0xFF201B2C) else Color(0xFFF0ECF8)
    val foreground = if (dark) Color(0xFFE8E4F1) else Color(0xFF160F22)
    val muted = if (dark) Color(0xFFAAA2BF) else Color(0xFF645675)
    val accent = if (dark) Color(0xFFA78BFA) else Color(0xFF7544CE)
    val fill = if (dark) Color(0xFF6B4FD1) else Color(0xFF241432)
    val green = if (dark) Color(0xFF5CC99A) else Color(0xFF087D55)
    BackHandler {
        if (stage == OnboardingStage.WELCOME) onDismiss()
        else onStepChange(OnboardingStage.WELCOME.ordinal)
    }
    // The practice box is admitted to the accessibility service only while the practice stage is on
    // screen, so the floating lips can appear beside it and nowhere else in the app.
    DisposableEffect(stage) {
        if (stage == OnboardingStage.PRACTICE) model.enterPractice()
        onDispose { if (stage == OnboardingStage.PRACTICE) model.leavePractice() }
    }
    val practiceBox = remember { FocusRequester() }
    // Test tags are exported as accessibility view ids so the service can name the practice box.
    Surface(Modifier.fillMaxSize().semantics { testTagsAsResourceId = true }, color = background, contentColor = foreground) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding()
            .padding(horizontal = 24.dp, vertical = 14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            OnboardingLips(Modifier.size(if (stage == OnboardingStage.PRACTICE) 84.dp else 140.dp),
                energetic = stage == OnboardingStage.DOWNLOADS || model.practicePhase == RecordingOverlayState.Phase.RECORDING)
            Column(Modifier.weight(1f).widthIn(max = 480.dp).fillMaxWidth().verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally) {
                when (stage) {
                    OnboardingStage.WELCOME -> {
                        SetupHeading("Speak naturally.\nGet polished text.", "Turn messy speech into clear, ready-to-send writing.", muted)
                        OnboardingWelcomeStory(surface, foreground, muted, accent)
                        if (model.downloadMessage.isNotBlank()) Text(model.downloadMessage, Modifier.padding(top = 12.dp), color = muted, textAlign = TextAlign.Center)
                    }
                    OnboardingStage.DOWNLOADS -> {
                        val index = if (downloads.firstOrNull()?.health == ModelHealth.READY || readiness.speechModelReady) 1 else 0
                        val state = downloads.getOrNull(index)
                        val descriptor = if (index == 0) ModelManifest.parakeet else ModelManifest.s1
                        SetupHeading(if (index == 0) "First, get your\nwords right." else "Less cleanup.\nMore ready to send.",
                            if (index == 0) "Parakeet recognizes your speech in 25 languages, even offline. The foundation for polished text."
                            else "S1-mini turns your spoken thoughts into polished text, with punctuation, paragraphs and less filler.", muted)
                        Surface(color = surface, shape = RoundedCornerShape(16.dp)) {
                            Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                Text(if (index == 0) "Speech recognition" else "AI polish", fontWeight = FontWeight.Bold)
                                Text("${descriptor.displayName} · ${formatModelBytes(descriptor.files.sumOf { it.expectedBytes })}", color = muted, fontSize = 12.sp)
                                if (state != null && state.total > 0 && state.action in listOf(ModelUiAction.PAUSE, ModelUiAction.RESUME)) {
                                    LinearProgressIndicator(progress = { (state.bytes.toFloat() / state.total).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth(), color = accent)
                                    Text("${formatModelBytes(state.bytes)} of ${formatModelBytes(state.total)}", fontSize = 12.sp, color = muted)
                                } else if (state?.action == ModelUiAction.PAUSE) {
                                    LinearProgressIndicator(Modifier.fillMaxWidth(), color = accent)
                                }
                                Text(state?.label ?: "Checking your models…", color = muted, fontSize = 13.sp)
                                state?.reason?.let { Text(it, fontSize = 12.sp, color = muted) }
                            }
                        }
                        Text(if (index == 0) "Both models will be ready before we continue." else "Speech recognition is ready. Finishing AI polish.", Modifier.padding(top = 16.dp), color = muted, fontSize = 12.sp, textAlign = TextAlign.Center)
                        if (model.downloadMessage.isNotEmpty()) Text(model.downloadMessage, Modifier.padding(top = 12.dp), color = muted)
                    }
                    OnboardingStage.PERMISSIONS -> {
                        SetupHeading("How it works", HOW_THE_LIPS_WORK, muted)
                        HowTheLipsWork(surface, muted)
                        Text("Allow EnviousWispr to hear your words and put the polished text where you need it.",
                            Modifier.padding(top = 22.dp, bottom = 14.dp), fontSize = 13.sp, lineHeight = 21.sp, color = muted, textAlign = TextAlign.Center)
                        PermissionRow("Microphone", "To hear your voice for transcription.", SetupPermission.MICROPHONE, readiness.microphoneGranted, surface, muted, accent, green, onRequestMicrophone)
                        PermissionRow("Accessibility", if (autoPaste == AutoPasteAvailability.PERMITTED_NOT_RUNNING) "Access is on. Waiting for the service to connect." else ACCESSIBILITY_CARD_COPY, SetupPermission.ACCESSIBILITY, autoPaste == AutoPasteAvailability.LIVE, surface, muted, accent, green, onOpenAccessibility)
                        PermissionRow("Notifications", "Recording controls in your notification panel.", SetupPermission.NOTIFICATIONS, readiness.notificationsGranted, surface, muted, accent, green, onRequestNotifications)
                        if (!readiness.notificationsGranted) Text("Notifications are optional. You can enable them later.", Modifier.padding(top = 14.dp), color = muted, fontSize = 12.sp, textAlign = TextAlign.Center)
                    }
                    OnboardingStage.PRACTICE -> {
                        val hold = model.lesson == PracticeLesson.HOLD
                        val outcome = model.practiceOutcome
                        val landed = outcome == PracticeOutcome.LANDED || outcome == PracticeOutcome.LANDED_BY_TAP
                        val (title, line) = when (model.practicePhase) {
                            RecordingOverlayState.Phase.STARTING -> "Getting ready to listen…" to (if (hold) "Keep holding." else "One moment.")
                            RecordingOverlayState.Phase.RECORDING ->
                                if (hold) "Keep holding. We’re listening." to "Let go when you’re done."
                                else "Go ahead. We’re listening." to "Speak naturally. Tap the check when you’re done."
                            RecordingOverlayState.Phase.PROCESSING -> "Tidying your words…" to "Your words will appear in the text box."
                            RecordingOverlayState.Phase.IDLE -> when (outcome) {
                                PracticeOutcome.WORKING -> "Tidying your words…" to "Your words will appear in the text box."
                                PracticeOutcome.LANDED -> "Nice, that worked!" to
                                    (if (hold) "You can tap to dictate or hold to talk." else "Your words are in the box. Try holding the lips next.")
                                PracticeOutcome.LANDED_BY_TAP -> "Nice, that worked!" to "That was a tap. Now hold the lips while you talk, and let go when you are done."
                                PracticeOutcome.MISSED_BOX -> "Your words are saved in History." to "They did not reach the box this time. Try again."
                                PracticeOutcome.NO_WORDS -> "No words were detected." to
                                    (if (hold) "Hold the lips and speak, then let go." else "Tap the lips and speak, then tap the check.")
                                PracticeOutcome.NOTHING_ADDED -> "No new text was added." to "Try again when you’re ready."
                                null -> if (hold) "Try holding the lips." to "Now hold the lips and talk; let go when you are done."
                                    else "Try your first dictation." to "Tap the lips and say…"
                            }
                        }
                        SetupHeading(title, line, muted)
                        if (!model.practicing && !landed) {
                            Text(if (hold) "“And, um, let her know I miss her.”" else "“Um, tell Grandma, uh, I’ll call her on Sunday.”",
                                color = muted, fontSize = 12.sp, modifier = Modifier.padding(bottom = 12.dp))
                        }
                        OutlinedTextField(value = model.draft, onValueChange = model::editDraft,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 150.dp).focusRequester(practiceBox).testTag(OnboardingViewModel.PRACTICE_FIELD_ID),
                            placeholder = { Text("Your words will appear here.") }, shape = RoundedCornerShape(15.dp),
                            colors = OutlinedTextFieldDefaults.colors(focusedContainerColor = surface, unfocusedContainerColor = surface))
                        if (landed) Text("In any app, the lips appear when a text box is active, just above the keyboard at the edge you chose.",
                            Modifier.padding(top = 14.dp), color = muted, fontSize = 12.sp, lineHeight = 18.sp, textAlign = TextAlign.Center)
                        // The lips appear beside a FOCUSED box. Ask for focus once the box is on screen.
                        LaunchedEffect(Unit) { practiceBox.requestFocus() }
                    }
                }
            }
            Column(Modifier.widthIn(max = 480.dp).fillMaxWidth().padding(top = 12.dp)) {
                when (stage) {
                    OnboardingStage.WELCOME -> SetupButton("Get Started!", fill, action = model::startSetup)
                    OnboardingStage.DOWNLOADS -> {
                        val active = downloads.getOrNull(if (downloads.firstOrNull()?.health == ModelHealth.READY || readiness.speechModelReady) 1 else 0)
                        val action = active?.action
                        SetupButton(when (action) { ModelUiAction.PAUSE -> "Pause download"; ModelUiAction.RESUME -> "Resume download"; else -> "Retry download" }, fill, active != null) {
                            if (action == ModelUiAction.PAUSE) model.pauseDownloads() else model.resumeDownloads()
                        }
                        TextButton(onClick = { model.resumeDownloads(mobileData = true) }, modifier = Modifier.align(Alignment.CenterHorizontally)) { Text("Use mobile data", color = accent, fontSize = 12.sp) }
                    }
                    OnboardingStage.PERMISSIONS -> {
                        val ready = readiness.microphoneGranted && autoPaste == AutoPasteAvailability.LIVE
                        SetupButton("Try dictation", fill, ready) { onStepChange(OnboardingStage.PRACTICE.ordinal) }
                        if (!ready) Text("Enable Microphone and Accessibility to try dictation.", Modifier.padding(top = 10.dp).align(Alignment.CenterHorizontally), color = muted, fontSize = 12.sp, textAlign = TextAlign.Center)
                        TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.CenterHorizontally)) { Text("Set up later", color = accent, fontSize = 12.sp) }
                    }
                    OnboardingStage.PRACTICE -> {
                        val settled = !model.practicing
                        if (model.practiceComplete && model.lesson == PracticeLesson.TAP && settled) {
                            OutlinedButton(onClick = model::startHoldLesson, modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp), shape = RoundedCornerShape(14.dp), contentPadding = PaddingValues(vertical = 14.dp)) {
                                Text("Try press and hold", color = accent, fontWeight = FontWeight.Bold)
                            }
                        }
                        SetupButton("Finish setup", fill, model.practiceComplete && settled, onComplete)
                        if (!model.practiceComplete) {
                            TextButton(onClick = onComplete, enabled = settled, modifier = Modifier.align(Alignment.CenterHorizontally)) { Text("Skip practice", color = accent, fontSize = 12.sp) }
                        }
                        // The lips sit just above the keyboard at the screen's edge; keep the buttons clear of them.
                        Spacer(Modifier.height(60.dp))
                    }
                }
            }
        }
    }
}

/** A text box with the floating lips beside it, in the default look, so the user recognises them later. */
@Composable
private fun HowTheLipsWork(surface: Color, muted: Color) {
    Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Surface(color = surface, shape = RoundedCornerShape(14.dp), modifier = Modifier.weight(1f).height(48.dp)) {
            Row(Modifier.padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Box(Modifier.width(2.dp).height(18.dp).background(Color(0xFF7C3AED)))
                Text("Write a message…", color = muted, fontSize = 14.sp)
            }
        }
        val look = BubbleLook.DEFAULT
        Box(Modifier.size(48.dp).background(Color(look.surfaceFill), RoundedCornerShape(14.dp)), contentAlignment = Alignment.Center) {
            OnboardingLips(Modifier.size(look.lipsDp.dp))
        }
    }
}

@Composable
private fun SetupHeading(title: String, subtitle: String, muted: Color) {
    Text(title, fontSize = 26.sp, lineHeight = 33.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
    Text(subtitle, Modifier.padding(top = 12.dp, bottom = 24.dp), fontSize = 13.sp, lineHeight = 21.sp, color = muted, textAlign = TextAlign.Center)
}

@Composable
private fun SetupButton(text: String, fill: Color, enabled: Boolean = true, action: () -> Unit) {
    Button(onClick = action, enabled = enabled, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp), contentPadding = PaddingValues(vertical = 16.dp), colors = ButtonDefaults.buttonColors(containerColor = fill, contentColor = Color.White)) { Text(text, fontWeight = FontWeight.Bold) }
}

@Composable
private fun PermissionRow(title: String, description: String, kind: SetupPermission, granted: Boolean, surface: Color, muted: Color, accent: Color, green: Color, action: () -> Unit) {
    Surface(color = surface, shape = RoundedCornerShape(15.dp), modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
        Row(Modifier.padding(13.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OnboardingPermissionIcon(kind, if (granted) green else accent)
            Column(Modifier.weight(1f)) { Text(title, fontWeight = FontWeight.Bold, fontSize = 14.sp); Text(description, color = muted, fontSize = 11.sp, lineHeight = 16.sp) }
            if (granted) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                Box(Modifier.size(17.dp).background(green, androidx.compose.foundation.shape.CircleShape), contentAlignment = Alignment.Center) { androidx.compose.foundation.Canvas(Modifier.size(12.dp)) {
                    val path = androidx.compose.ui.graphics.Path().apply { moveTo(size.width * .12f, size.height * .52f); lineTo(size.width * .4f, size.height * .8f); lineTo(size.width * .9f, size.height * .2f) }
                    drawPath(path, surface, style = androidx.compose.ui.graphics.drawscope.Stroke(size.width * .13f, cap = androidx.compose.ui.graphics.StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round))
                } }
                Text("Granted", color = green, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
            else FilledTonalButton(onClick = action, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp), shape = RoundedCornerShape(10.dp)) { Text("Grant", fontSize = 12.sp) }
        }
    }
}
