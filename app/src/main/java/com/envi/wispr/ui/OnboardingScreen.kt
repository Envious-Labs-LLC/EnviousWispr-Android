package com.envi.wispr.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
        if (model.practicing) model.cancelPractice()
        else if (stage == OnboardingStage.WELCOME) onDismiss()
        else onStepChange(OnboardingStage.WELCOME.ordinal)
    }
    LaunchedEffect(stage) {
        if (stage != OnboardingStage.PRACTICE && model.practicing) model.cancelPractice()
    }
    Surface(Modifier.fillMaxSize(), color = background, contentColor = foreground) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding()
            .padding(horizontal = 24.dp, vertical = 14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            OnboardingLips(Modifier.size(if (stage == OnboardingStage.PRACTICE) 104.dp else 140.dp),
                energetic = stage == OnboardingStage.DOWNLOADS || model.practicePhase == PracticePhase.RECORDING)
            Column(Modifier.weight(1f).widthIn(max = 480.dp).fillMaxWidth().verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally) {
                when (stage) {
                    OnboardingStage.WELCOME -> {
                        SetupHeading("Speak naturally.\nGet polished text.", "Turn messy speech into clear, ready-to-send writing.", muted)
                        OnboardingWelcomeStory(surface, foreground, muted, accent)
                        if (model.downloadMessage.isNotBlank()) Text(model.downloadMessage, Modifier.padding(top = 12.dp), color = muted, textAlign = TextAlign.Center)
                    }
                    OnboardingStage.DOWNLOADS -> {
                        val index = if (readiness.speechModelReady) 1 else 0
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
                                Text(if (state?.label == "Queued") "Waiting for network or available storage." else state?.label ?: "Checking your models…", color = muted, fontSize = 13.sp)
                                state?.reason?.let { Text(it, fontSize = 12.sp, color = muted) }
                            }
                        }
                        Text(if (index == 0) "Both models will be ready before we continue." else "Speech recognition is ready. Finishing AI polish.", Modifier.padding(top = 16.dp), color = muted, fontSize = 12.sp, textAlign = TextAlign.Center)
                        if (model.downloadMessage.isNotEmpty()) Text(model.downloadMessage, Modifier.padding(top = 12.dp), color = muted)
                    }
                    OnboardingStage.PERMISSIONS -> {
                        SetupHeading("Your models are ready.\nLet’s try them.", "Allow EnviousWispr to hear your words and put the polished text where you need it.", muted)
                        PermissionRow("Microphone", "To hear your voice for transcription.", SetupPermission.MICROPHONE, readiness.microphoneGranted, surface, muted, accent, green, onRequestMicrophone)
                        PermissionRow("Accessibility", if (autoPaste == AutoPasteAvailability.PERMITTED_NOT_RUNNING) "Access is on. Waiting for the service to connect." else "To find your text field and paste your words.", SetupPermission.ACCESSIBILITY, autoPaste == AutoPasteAvailability.LIVE, surface, muted, accent, green, onOpenAccessibility)
                        PermissionRow("Notifications", "Recording controls in your notification panel.", SetupPermission.NOTIFICATIONS, readiness.notificationsGranted, surface, muted, accent, green, onRequestNotifications)
                        if (!readiness.notificationsGranted) Text("Notifications are optional. You can enable them later.", Modifier.padding(top = 14.dp), color = muted, fontSize = 12.sp, textAlign = TextAlign.Center)
                    }
                    OnboardingStage.PRACTICE -> {
                        val title = when (model.practicePhase) {
                            PracticePhase.STARTING -> "Getting ready to listen…"
                            PracticePhase.RECORDING -> "Go ahead.\nWe’re listening."
                            PracticePhase.PROCESSING -> "Turning your words\ninto polished text."
                            PracticePhase.IDLE -> if (model.practiceComplete) "Your words.\nReady to send." else "Try your first dictation."
                        }
                        SetupHeading(title, if (model.practiceComplete && !model.practicing) "That’s the difference. Less editing after you speak." else "Speak naturally. Tap Stop when you’re done.", muted)
                        if (!model.practicing && !model.practiceComplete) Text("Try: “Um, tell Grandma, uh, I’ll call her on Sunday.”", color = muted, fontSize = 12.sp, modifier = Modifier.padding(bottom = 12.dp))
                        OutlinedTextField(value = model.draft, onValueChange = model::editDraft,
                            readOnly = model.practicing, modifier = Modifier.fillMaxWidth().heightIn(min = 170.dp),
                            placeholder = { Text("Your words will appear here.") }, shape = RoundedCornerShape(15.dp),
                            colors = OutlinedTextFieldDefaults.colors(focusedContainerColor = surface, unfocusedContainerColor = surface))
                        if (model.practiceMessage.isNotBlank()) Text(model.practiceMessage, Modifier.padding(top = 12.dp), color = muted, fontSize = 13.sp)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                            when (model.practicePhase) {
                                PracticePhase.IDLE -> TextButton(onClick = model::startPractice) { Text(if (model.practiceComplete) "Try again" else "Start dictation", color = accent) }
                                PracticePhase.STARTING, PracticePhase.PROCESSING -> TextButton(onClick = model::cancelPractice) { Text("Cancel", color = accent) }
                                PracticePhase.RECORDING -> {
                                    TextButton(onClick = model::cancelPractice) { Text("Cancel", color = accent) }
                                    TextButton(onClick = model::stopPractice) { Text("Stop", color = accent) }
                                }
                            }
                        }
                    }
                }
            }
            Column(Modifier.widthIn(max = 480.dp).fillMaxWidth().padding(top = 12.dp)) {
                when (stage) {
                    OnboardingStage.WELCOME -> SetupButton("Get Started!", fill, action = model::startSetup)
                    OnboardingStage.DOWNLOADS -> {
                        val active = downloads.getOrNull(if (readiness.speechModelReady) 1 else 0)
                        val action = active?.action
                        SetupButton(when (action) { ModelUiAction.PAUSE -> "Pause download"; ModelUiAction.RESUME -> "Resume download"; else -> "Retry download" }, fill, active != null) {
                            if (action == ModelUiAction.PAUSE) model.pauseDownloads() else model.resumeDownloads()
                        }
                        TextButton(onClick = { model.resumeDownloads(mobileData = true) }, modifier = Modifier.align(Alignment.CenterHorizontally)) { Text("Use mobile data", color = accent, fontSize = 12.sp) }
                    }
                    OnboardingStage.PERMISSIONS -> {
                        SetupButton("Try dictation", fill, readiness.microphoneGranted && autoPaste == AutoPasteAvailability.LIVE) { onStepChange(OnboardingStage.PRACTICE.ordinal) }
                        TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.CenterHorizontally)) { Text("Set up later", color = accent, fontSize = 12.sp) }
                    }
                    OnboardingStage.PRACTICE -> {
                        SetupButton("Finish setup", fill, model.practiceComplete && !model.practicing, onComplete)
                        TextButton(onClick = onComplete, enabled = !model.practicing, modifier = Modifier.align(Alignment.CenterHorizontally)) { Text("Skip practice", color = accent, fontSize = 12.sp) }
                    }
                }
            }
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
                Box(Modifier.size(17.dp).background(green, androidx.compose.foundation.shape.CircleShape), contentAlignment = Alignment.Center) { Text("✓", color = surface, fontSize = 12.sp, fontWeight = FontWeight.Bold) }
                Text("Granted", color = green, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
            else FilledTonalButton(onClick = action, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp), shape = RoundedCornerShape(10.dp)) { Text("Grant", fontSize = 12.sp) }
        }
    }
}
