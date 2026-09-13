package com.envi.wispr.ui

import android.app.PictureInPictureParams
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Bundle
import android.provider.Settings
import android.util.Rational
import androidx.core.view.doOnPreDraw
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.envi.wispr.paste.AccessibilityPermission
import com.envi.wispr.paste.PasteAccessibilityService
import com.envi.wispr.ui.theme.EnviousWisprTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** One shared disclosure route, with optional native PiP guidance after affirmative consent. */
class AccessibilityGuideActivity : ComponentActivity() {
    private var agreed by mutableStateOf(false)
    private var compact by mutableStateOf(false)
    private var launchFailed by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        agreed = savedInstanceState?.getBoolean("agreed") ?: false
        compact = isInPictureInPictureMode
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                PasteAccessibilityService.isBound.collect { bound ->
                    if (agreed && bound && AccessibilityPermission.isGranted(this@AccessibilityGuideActivity)) finish()
                }
            }
        }
        setContent {
            EnviousWisprTheme(dynamicColor = false) {
                if (!agreed) AccessibilityDisclosure(onAgree = ::openSettings, onDecline = ::finish)
                else AccessibilityGuide(compact, launchFailed, ::openSettings, ::finish)
            }
        }
    }

    private fun openSettings() {
        agreed = true
        launchFailed = false
        // Wait for the accepted disclosure to be replaced by guide content before entering PiP.
        window.decorView.doOnPreDraw {
            if (isFinishing || isDestroyed) return@doOnPreDraw
            if (packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)) {
                runCatching { enterPictureInPictureMode(PictureInPictureParams.Builder().setAspectRatio(Rational(230, 215)).build()) }
            }
            runCatching { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                .onFailure { launchFailed = true }
        }
    }

    override fun onResume() {
        super.onResume()
        if (agreed && PasteAccessibilityService.isBound.value && AccessibilityPermission.isGranted(this)) finish()
    }

    override fun onSaveInstanceState(outState: Bundle) { outState.putBoolean("agreed", agreed); super.onSaveInstanceState(outState) }
    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        compact = isInPictureInPictureMode
    }
}

@Composable
private fun AccessibilityDisclosure(onAgree: () -> Unit, onDecline: () -> Unit) {
    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.statusBarsPadding().navigationBarsPadding().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            OnboardingLips(Modifier.size(110.dp).align(Alignment.CenterHorizontally))
            Text("Let EnviousWispr\npaste for you", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text("EnviousWispr uses Android’s Accessibility service to find the text field you’re using. It accesses the field’s text, cursor position and app information to insert your dictation in the right place and check that it appeared.")
            Text("This field information is processed on your phone. Field contents are not sent to Envious Labs.")
            Text("Insertion happens after you start a dictation. EnviousWispr does not operate your phone on its own.")
            Text("You can turn this access off in Android Settings at any time.")
            Button(onClick = onAgree, modifier = Modifier.fillMaxWidth(), contentPadding = PaddingValues(vertical = 16.dp)) { Text("Agree and open Settings") }
            TextButton(onClick = onDecline, modifier = Modifier.align(Alignment.CenterHorizontally)) { Text("Not now") }
        }
    }
}

@Composable
private fun AccessibilityGuide(compact: Boolean, launchFailed: Boolean, openSettings: () -> Unit, close: () -> Unit) {
    val owner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    val reduced = onboardingReducedMotion()
    var step by remember { mutableIntStateOf(0) }
    var paused by remember { mutableStateOf(reduced) }
    val context = androidx.compose.ui.platform.LocalContext.current
    val appLabel = remember { context.applicationInfo.loadLabel(context.packageManager).toString() }
    val samsung = android.os.Build.MANUFACTURER.equals("samsung", ignoreCase = true)
    val frames = if (samsung) listOf(0, 1, 2, 3, 4) else listOf(1, 2, 3, 4)
    val steps = (if (samsung) listOf("Tap Installed apps") else emptyList()) +
        listOf("Choose $appLabel", "Turn on the service", "Review access, then Allow", "Return to EnviousWispr")
    LaunchedEffect(owner, paused) {
        if (!paused) owner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) { delay(3400); step = (step + 1) % steps.size }
        }
    }
    BackHandler(onBack = close)
    Surface(Modifier.fillMaxSize(), color = Color(0xFF251F37), contentColor = Color(0xFFE8E4F1)) {
        Column(Modifier.fillMaxSize().then(if (compact) Modifier else Modifier.statusBarsPadding().navigationBarsPadding()).padding(if (compact) 8.dp else 24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                OnboardingLips(Modifier.size(if (compact) 26.dp else 70.dp))
                Text("Setup guide", fontSize = if (compact) 11.sp else 20.sp, modifier = Modifier.weight(1f))
                Text("${step + 1} / ${steps.size}", fontSize = 11.sp)
            }
            if (compact && reduced) {
                Text(steps.mapIndexed { index, text -> "${index + 1}. $text" }.joinToString("\n"), Modifier.padding(10.dp), fontSize = 12.sp, lineHeight = 19.sp)
            } else Crossfade(targetState = frames[step], animationSpec = tween(if (reduced) 0 else 400), label = "Accessibility instructions") { current ->
                Surface(color = Color(0xFFF3F3F6), contentColor = Color(0xFF17171B), shape = RoundedCornerShape(10.dp), modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(when (current) { 0 -> "Accessibility"; 1 -> if (samsung) "Installed apps" else "Accessibility"; 3 -> "Review Android’s prompt"; else -> appLabel }, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        Text(when (current) { 0 -> "Installed apps    ›"; 1 -> "$appLabel    Off"; 2 -> "Off    ○"; 3 -> "Allow"; else -> "On    ✓" }, Modifier.fillMaxWidth().background(Color(0xFFE7DFFC), RoundedCornerShape(7.dp)).padding(10.dp), fontSize = 11.sp)
                        if (current == 2) Text("Leave the shortcut off", fontSize = 10.sp)
                    }
                }
            }
            Text(steps[step], fontWeight = FontWeight.Bold, fontSize = if (compact) 11.sp else 18.sp)
            if (!compact) {
                Text(if (samsung) "Installed apps → $appLabel → On. Leave the optional Accessibility shortcut off." else "Find $appLabel in Accessibility. Your phone may group it under Downloaded apps or Installed services. Leave the optional shortcut off.", Modifier.padding(vertical = 18.dp), fontSize = 14.sp)
                if (launchFailed) Text("Open your phone’s Settings, then Accessibility.")
                Row { TextButton(onClick = { paused = !paused }) { Text(if (paused) "Play" else "Pause") }; TextButton(onClick = { paused = true; step = (step + 1) % steps.size }) { Text("Next") } }
                Button(onClick = openSettings) { Text("Open Settings again") }
                TextButton(onClick = close) { Text("Return to EnviousWispr") }
            }
        }
    }
}
