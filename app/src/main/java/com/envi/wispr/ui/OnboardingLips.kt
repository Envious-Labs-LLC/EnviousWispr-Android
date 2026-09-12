package com.envi.wispr.ui

import android.provider.Settings
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.sin

@Composable
internal fun onboardingReducedMotion(): Boolean {
    val context = LocalContext.current
    return remember { Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f }
}

/** The approved brand geometry, animated only while this screen is visible. */
@Composable
internal fun OnboardingLips(modifier: Modifier = Modifier, energetic: Boolean = false) {
    val reduced = onboardingReducedMotion()
    val phase = remember { Animatable(0f) }
    val bounce = remember { Animatable(1f) }
    val owner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    LaunchedEffect(owner, reduced, energetic) {
        if (!reduced) owner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            phase.snapTo(0f)
            phase.animateTo(6.2832f, infiniteRepeatable(tween(if (energetic) 1800 else 3600, easing = LinearEasing), RepeatMode.Restart))
        }
    }
    val upperY = floatArrayOf(84.2f,65.75f,43.36f,63.04f,81.43f,63.04f,43.36f,65.75f,84.2f)
    val upperH = floatArrayOf(20f,32f,48f,36f,24f,36f,48f,32f,20f)
    val lowerY = floatArrayOf(125.8f,119.35f,112.96f,120.64f,127.03f,120.64f,112.96f,119.35f,125.8f)
    val lowerH = floatArrayOf(20f,36f,48f,60f,68f,60f,48f,36f,20f)
    val colors = listOf(0xFFFF2A40,0xFFFF8C00,0xFFFFD700,0xFFADFF2F,0xFF00FA9A,0xFF00FFFF,0xFF1E90FF,0xFF4169E1,0xFF8A2BE2).map { Color(it) }
    val lowerColors = listOf(7,6,5,4,3,2,1,0,8)
    Canvas(modifier.semantics { contentDescription = "EnviousWispr lips" }.clickable {
        if (!reduced) scope.launch { bounce.animateTo(1.15f, tween(140)); bounce.animateTo(1f, tween(240)) }
    }) {
        val unit = minOf(size.width, size.height) / 256f
        for (row in 0..1) for (i in 0..8) {
            val y = if (row == 0) upperY[i] else lowerY[i]
            val height = if (row == 0) upperH[i] else lowerH[i]
            val scale = if (reduced) 1f else (if (energetic) .35f + .8f * abs(sin(phase.value + i * .6f + row)) else 1f - .13f * abs(sin(phase.value + i * .2f))) * bounce.value
            val h = height * scale
            val top = y + 13f + if (row == 0) height - h else 0f
            drawRoundRect(colors[if (row == 0) i else lowerColors[i]], Offset((24 + i * 24) * unit, top * unit), Size(14 * unit, h * unit), CornerRadius(5 * unit))
        }
    }
}
