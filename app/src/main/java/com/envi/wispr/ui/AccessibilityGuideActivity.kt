package com.envi.wispr.ui

import android.app.PictureInPictureParams
import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.provider.Settings
import android.util.Rational
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
import com.envi.wispr.debug.DebugLogger
import com.envi.wispr.paste.AccessibilityPermission
import com.envi.wispr.paste.AccessibilityPermissionCheck
import com.envi.wispr.paste.AutoPasteAvailability
import com.envi.wispr.paste.AutoPasteReadiness
import com.envi.wispr.paste.PasteAccessibilityService
import com.envi.wispr.ui.theme.EnviousWisprTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** One shared disclosure route; after consent the guide shows BEFORE Android Settings opens (#476). */
class AccessibilityGuideActivity : ComponentActivity() {
    private var phase by mutableStateOf<GuidePhase>(GuidePhase.Disclosure)
    private var entry by mutableStateOf(AutoPasteAvailability.NOT_PERMITTED)
    /** Presentation only, never stored: true while Android shows the guide in picture-in-picture. */
    private var compact by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        compact = isInPictureInPictureMode
        DebugLogger.log("AccessibilityGuide", "floating=${FloatingGuideEligibility.allows(this)}")
        phase = GuidePhase.decode(savedInstanceState?.getString(KEY_PHASE))?.let(GuidePhase::restored) ?: GuidePhase.Disclosure
        PasteAccessibilityService.loadStopMarker(this)
        entry = availability()
        if (entry == AutoPasteAvailability.LIVE) { complete(); return }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                PasteAccessibilityService.isBound.collect { if (availability() == AutoPasteAvailability.LIVE) complete() }
            }
        }
        setContent {
            EnviousWisprTheme(dynamicColor = false) {
                when (val current = phase) {
                    GuidePhase.Disclosure -> AccessibilityDisclosure(onAgree = { phase = GuidePhase.Ready(GuideHistory.NeverOpened) }, onDecline = ::finish)
                    is GuidePhase.Ready -> AccessibilityGuide(entry, current.history, compact, launchFailed = false, ::openSettings, ::finish)
                    is GuidePhase.LaunchFailed -> AccessibilityGuide(entry, current.history, compact, launchFailed = true, ::openSettings, ::finish)
                    is GuidePhase.Launching -> AccessibilityGuide(entry, current.history, compact, launchFailed = false, {}, ::finish)
                    GuidePhase.AwaitingSettings -> AccessibilityGuide(entry, GuideHistory.Opened, compact, launchFailed = false, ::openSettings, ::finish)
                    GuidePhase.Complete -> Unit
                }
            }
        }
    }

    private fun availability(): AutoPasteAvailability = AutoPasteReadiness.evaluate(
        permission = AccessibilityPermissionCheck.of(AccessibilityPermission.isGranted(this)),
        lifecycle = PasteAccessibilityService.lifecycle.current.value,
    )

    private fun openSettings() {
        val history = when (val current = phase) {
            is GuidePhase.Ready -> current.history
            is GuidePhase.LaunchFailed -> current.history
            GuidePhase.AwaitingSettings -> GuideHistory.Opened
            GuidePhase.Disclosure, is GuidePhase.Launching, GuidePhase.Complete -> return
        }
        phase = GuidePhase.Launching(history)
        // Float the guide over Settings wherever picture-in-picture is available (#477). A refused or failed entry
        // leaves the full-screen guide behind Settings, which already told the user every step before the hand-off.
        if (FloatingGuideEligibility.allows(this)) runCatching {
            enterPictureInPictureMode(
                PictureInPictureParams.Builder().setAspectRatio(Rational(230, 215))
                    .setAutoEnterEnabled(false).setSeamlessResizeEnabled(false).build(),
            )
        }
        phase = runCatching { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            .fold(onSuccess = { GuidePhase.AwaitingSettings }, onFailure = { GuidePhase.LaunchFailed(history) })
    }

    /**
     * Auto-paste is live. Bring the app back in front of Android Settings and close the guide. Android may refuse
     * the start while the guide is hidden behind Settings; the guide told the user how to come back before the
     * hand-off, so a refused start costs nothing.
     */
    private fun complete() {
        if (phase == GuidePhase.Complete) return
        val handedOff = phase != GuidePhase.Disclosure
        phase = GuidePhase.Complete
        if (handedOff) runCatching {
            startActivity(Intent(this, SettingsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        }
        finish()
    }

    override fun onResume() {
        super.onResume()
        if (availability() == AutoPasteAvailability.LIVE) complete() else phase = GuidePhase.returned(phase)
    }

    override fun onSaveInstanceState(outState: Bundle) { outState.putString(KEY_PHASE, GuidePhase.encode(phase)); super.onSaveInstanceState(outState) }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        compact = isInPictureInPictureMode
    }
}

private const val KEY_PHASE = "guide_phase"

/** Shown before the hand-off: Android parks the box over Allow (#477). */
private const val FLOATING_ALLOW_HELP = "Your phone may keep these steps in a small box over Settings. If the box covers Allow, drag it away. If Allow still doesn’t respond, close the box, then tap Allow."

/** The way past Allow sized for the box itself, which Android keeps at about 170 dp on a Galaxy S24 (#477). */
private const val FLOATING_ALLOW_HELP_SHORT = "Drag this box off Allow. Allow not working? Close this box, then tap Allow."

@Composable
private fun AccessibilityDisclosure(onAgree: () -> Unit, onDecline: () -> Unit) {
    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.statusBarsPadding().navigationBarsPadding().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            OnboardingLips(Modifier.size(110.dp).align(Alignment.CenterHorizontally))
            Text("Let the bubble float\nbeside your text box", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text("EnviousWispr finds the text box you’re using, shows the floating bubble beside it, and pastes your words after you start a dictation. It uses Android’s Accessibility service to read the field’s text, cursor position and app information, put your words in the right place, and check that they appeared.")
            Text("Field text and cursor position are processed on your phone. Field contents are not sent to Envious Labs.")
            Text("Usage reports sent to PostHog can include which app you were typing in, whether your text was inserted, and how long insertion took. App and insertion details can also accompany Sentry crash reports. These reports use a random installation ID to help find and fix problems.")
            Text("Insertion happens after you start a dictation. EnviousWispr does not operate your phone on its own.")
            Text("You can turn this access off in Android Settings at any time.")
            Button(onClick = onAgree, modifier = Modifier.fillMaxWidth(), contentPadding = PaddingValues(vertical = 16.dp)) { Text("Agree") }
            TextButton(onClick = onDecline, modifier = Modifier.align(Alignment.CenterHorizontally)) { Text("Not now") }
        }
    }
}

/**
 * One frame of the illustrated path: the screen's title, the row to tap, an optional note under it, and [short], the
 * step as the small floating box lists it.
 */
private data class GuideFrame(val step: String, val screen: String, val row: String, val short: String, val note: String? = null)

private fun guideFrames(samsung: Boolean, appLabel: String, reconnect: Boolean): List<GuideFrame> =
    (if (samsung) listOf(GuideFrame("Tap Installed apps", "Accessibility", "Installed apps    ›", short = "Installed apps")) else emptyList()) + listOf(
        GuideFrame("Choose $appLabel", if (samsung) "Installed apps" else "Accessibility", "$appLabel    ${if (reconnect) "On" else "Off"}", short = appLabel),
        if (reconnect) GuideFrame("Turn it off, then on again", appLabel, "On  →  Off  →  On", short = "Off, then on")
        else GuideFrame("Turn on the service", appLabel, "Off    ○", short = "Turn on"),
        GuideFrame("Leave the shortcut off", appLabel, "$appLabel shortcut    Off  ○", short = "Shortcut: leave off", note = "Leave this one off. Its floating button switches auto-paste off when tapped."),
        GuideFrame("Review access, then Allow", "Review Android’s prompt", "Allow", short = "Allow"),
        GuideFrame("Come back to EnviousWispr", appLabel, "On    ✓", short = "Come back"),
    )

/**
 * The floating box over Settings (#477). Every step stays listed, so the box never claims a step the user has not
 * reached; the looping demo only highlights one row at a time, and paused or reduced motion highlights nothing.
 * Recovery instructions appear first. At 130% text in the measured Galaxy S24-sized box, recovery remained fully
 * visible while the final steps clipped (emulator, 2026-11-02). Other sizes are NOT VERIFIED.
 */
@Composable
private fun FloatingGuideBox(frames: List<GuideFrame>, step: Int, paused: Boolean, launchFailed: Boolean) {
    Surface(Modifier.fillMaxSize(), color = Color(0xFF251F37), contentColor = Color(0xFFE8E4F1)) {
        Column(Modifier.fillMaxSize().padding(8.dp), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(FLOATING_ALLOW_HELP_SHORT, Modifier.padding(bottom = 3.dp), fontSize = 10.sp, lineHeight = 12.sp)
            if (launchFailed) {
                Text("Tap this box and open it full screen to see the guide.", fontSize = 13.sp, fontWeight = FontWeight.Bold, lineHeight = 16.sp)
            } else {
                frames.forEachIndexed { index, frame ->
                    val current = !paused && index == step
                    Text(
                        "${index + 1}. ${frame.short}",
                        Modifier.fillMaxWidth().background(if (current) Color(0xFFE7DFFC) else Color.Transparent, RoundedCornerShape(4.dp)).padding(horizontal = 4.dp, vertical = 1.dp),
                        fontSize = 11.sp, lineHeight = 12.sp,
                        fontWeight = if (current) FontWeight.Bold else FontWeight.Normal,
                        color = if (current) Color(0xFF17171B) else Color.Unspecified,
                    )
                }
            }
        }
    }
}

@Composable
private fun AccessibilityGuide(entry: AutoPasteAvailability, history: GuideHistory, compact: Boolean, launchFailed: Boolean, openSettings: () -> Unit, close: () -> Unit) {
    val owner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    val reduced = onboardingReducedMotion()
    var step by remember { mutableIntStateOf(0) }
    var paused by remember { mutableStateOf(reduced) }
    val context = androidx.compose.ui.platform.LocalContext.current
    val appLabel = remember { context.applicationInfo.loadLabel(context.packageManager).toString() }
    val samsung = android.os.Build.MANUFACTURER.equals("samsung", ignoreCase = true)
    val floating = remember { FloatingGuideEligibility.allows(context) }
    val reconnect = when (entry) {
        AutoPasteAvailability.PERMITTED_NOT_RUNNING -> true
        AutoPasteAvailability.NOT_PERMITTED, AutoPasteAvailability.SWITCHED_OFF_UNEXPECTEDLY, AutoPasteAvailability.LIVE -> false
    }
    val heading = when (entry) {
        AutoPasteAvailability.SWITCHED_OFF_UNEXPECTEDLY -> "Turn $appLabel back on"
        AutoPasteAvailability.PERMITTED_NOT_RUNNING -> "Reconnect $appLabel"
        AutoPasteAvailability.NOT_PERMITTED, AutoPasteAvailability.LIVE -> "Turn on auto-paste"
    }
    val frames = guideFrames(samsung, appLabel, reconnect)
    LaunchedEffect(owner, paused) {
        if (!paused) owner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) { delay(3400); step = (step + 1) % frames.size }
        }
    }
    BackHandler(onBack = close)
    if (compact) {
        // The floating copy over Settings (#477). PiP content takes no input: the user drags the box, or taps it for
        // Android's own expand and close controls. Android parks it over Allow, so every frame says how to get past it.
        FloatingGuideBox(frames, step, paused, launchFailed)
        return
    }
    Surface(Modifier.fillMaxSize(), color = Color(0xFF251F37), contentColor = Color(0xFFE8E4F1)) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().verticalScroll(rememberScrollState()).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                OnboardingLips(Modifier.size(70.dp))
                Text(heading, fontSize = 20.sp, modifier = Modifier.weight(1f))
                Text("${step + 1} / ${frames.size}", fontSize = 11.sp)
            }
            Crossfade(targetState = frames[step], animationSpec = tween(if (reduced) 0 else 400), label = "Accessibility instructions") { frame ->
                Surface(color = Color(0xFFF3F3F6), contentColor = Color(0xFF17171B), shape = RoundedCornerShape(10.dp), modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(frame.screen, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        Text(frame.row, Modifier.fillMaxWidth().background(Color(0xFFE7DFFC), RoundedCornerShape(7.dp)).padding(10.dp), fontSize = 11.sp)
                        frame.note?.let { Text(it, fontSize = 11.sp) }
                    }
                }
            }
            Text(frames[step].step, fontWeight = FontWeight.Bold, fontSize = 18.sp)
            Text(if (samsung) "Installed apps → $appLabel → On. Leave the $appLabel shortcut off." else "Find $appLabel in Accessibility. Your phone may group it under Downloaded apps or Installed services. Leave the $appLabel shortcut off.", Modifier.padding(top = 18.dp), fontSize = 14.sp)
            if (floating) Text(FLOATING_ALLOW_HELP, Modifier.padding(top = 12.dp), fontSize = 14.sp)
            Text("After you allow access, if $appLabel does not reopen, open it from Recents or its app icon.", Modifier.padding(vertical = 12.dp), fontSize = 14.sp)
            if (launchFailed) Text("Open your phone’s Settings, then Accessibility.")
            Row { TextButton(onClick = { paused = !paused }) { Text(if (paused) "Play" else "Pause") }; TextButton(onClick = { paused = true; step = (step + 1) % frames.size }) { Text("Next") } }
            Button(onClick = openSettings, modifier = Modifier.fillMaxWidth(), contentPadding = PaddingValues(vertical = 16.dp)) {
                Text(when (history) { GuideHistory.NeverOpened -> "Open Settings"; GuideHistory.Opened -> "Open Settings again" })
            }
            TextButton(onClick = close) { Text("Not now") }
        }
    }
}
