package com.envi.wispr.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.envi.wispr.paste.BrandPalette
import com.envi.wispr.paste.BubbleLook
import com.envi.wispr.audio.SpectrumAnalyzer
import com.envi.wispr.paste.RecordingAccessibilityOverlay
import com.envi.wispr.paste.RecordMarkView
import com.envi.wispr.paste.RecordingLevelMeterView
import kotlin.math.PI
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sin

/** The onboarding colours the demo paints with, as the screen resolved them for the theme. */
internal data class DemoPalette(
    val background: Color,
    val surface: Color,
    val foreground: Color,
    val muted: Color,
    val accent: Color,
    val border: Color,
)

/**
 * The setup demo, drawn live: five scenes ([DemoScene]) that teach that the bubble is how a
 * recording starts, then hand over to the real practice screen. Drawn rather than played from a
 * video so it fits any screen and theme and shows the phone's own apps (founder 2026-09-14).
 *
 * Sizes are the overlay's own (`RecordingAccessibilityOverlay` constants and [BubbleLook]), so the
 * bubble and the pill here are the size the user is about to see for real.
 */
@Composable
internal fun OnboardingDemo(
    icons: List<ImageBitmap>,
    look: BubbleLook,
    palette: DemoPalette,
    onFinished: () -> Unit,
) {
    var elapsed by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) {
        var last = withFrameNanos { it }
        while (elapsed < DemoScript.total) {
            withFrameNanos { now ->
                // Frames stop while the app is in the background; the gap they leave is not demo time. A
                // frame can carry at most a tenth of a second, so Home and back resumes where it left off
                // (Codex review 1, 2026-09-14).
                val step = ((now - last) / 1_000_000_000f).coerceAtMost(MAX_FRAME_SECONDS)
                elapsed = (elapsed + step).coerceAtMost(DemoScript.total)
                last = now
            }
        }
        onFinished()
    }
    val moment = DemoScript.at(elapsed)
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
        DemoCaption(moment, palette)
        Box(Modifier.weight(1f).fillMaxWidth().padding(top = 8.dp)) {
            when (moment.scene) {
                DemoScene.APPS -> AppsScene(moment.t, icons, look, palette)
                DemoScene.BUBBLE -> BubbleScene(moment.t, palette)
                DemoScene.TAP -> GmailScene(moment.t, held = false, look, palette)
                DemoScene.HOLD -> GmailScene(moment.t, held = true, look, palette)
                DemoScene.YOURS -> YoursScene(moment.t, look, palette)
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            DemoProgress(moment.scene, palette)
            TextButton(onClick = onFinished) { Text("Skip to practice", color = palette.accent, fontSize = 12.sp) }
        }
    }
}

@Composable
private fun DemoCaption(moment: DemoMoment, palette: DemoPalette) {
    val (title, sub) = when (moment.scene) {
        DemoScene.BUBBLE -> DemoScript.bubbleCaption(moment.t)
        DemoScene.APPS -> moment.scene.caption to "Messages, notes, search, and more."
        else -> moment.scene.caption to ""
    }
    // Each bubble beat fades in, so the reader notices the words changed.
    val fade = if (moment.scene == DemoScene.BUBBLE) {
        val t = moment.t
        val beatStart = if (t < 2.3f) 0.1f else if (t < 4.6f) 2.3f else 4.6f
        DemoScript.between(t, beatStart, beatStart + 0.4f)
    } else 1f
    val compact = LocalConfiguration.current.screenHeightDp < 560
    Column(Modifier.fillMaxWidth().height(if (compact) 60.dp else 96.dp).alpha(fade), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Text(title, fontSize = if (compact) 17.sp else 22.sp, lineHeight = if (compact) 21.sp else 28.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, color = palette.foreground)
        if (sub.isNotEmpty() && !compact) Text(sub, Modifier.padding(top = 6.dp), fontSize = 13.sp, lineHeight = 18.sp, color = palette.muted, textAlign = TextAlign.Center)
    }
}

@Composable
private fun DemoProgress(scene: DemoScene, palette: DemoPalette) {
    Row(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
        for (s in DemoScene.entries) {
            Box(Modifier.width(if (s == scene) 32.dp else 15.dp).height(3.dp).background(if (s == scene) palette.accent else palette.border, RoundedCornerShape(3.dp)))
        }
    }
}

// ---- the bubble and the pill, at the overlay's sizes ----

private val BUBBLE = RecordingAccessibilityOverlay.BUBBLE_DP.dp
private val BUBBLE_GROUND = (RecordingAccessibilityOverlay.BUBBLE_DP - 2 * RecordingAccessibilityOverlay.BUBBLE_INSET_DP).dp
private val BUBBLE_RADIUS = RecordingAccessibilityOverlay.BUBBLE_RADIUS_DP.dp
private val EDGE_MARGIN = RecordingAccessibilityOverlay.MARGIN_DP.dp
private val PILL_HEIGHT = RecordingAccessibilityOverlay.PILL_HEIGHT_DP.dp
private val FULL_PILL = RecordingAccessibilityOverlay.FULL_PILL_DP.dp
private val COMPACT_PILL = RecordingAccessibilityOverlay.COMPACT_PILL_DP.dp
private val KEYBOARD = 236.dp
private val BUBBLE_ABOVE_KEYBOARD = 18.dp
/** The Gmail card's rows above the body: bar, To, Subject. */
private val GMAIL_HEADER = 34.dp + 37.dp + 37.dp
/** The shortest Gmail scene that still fits the header, a line of body, the real bubble and a keyboard. */
private val GMAIL_MIN_HEIGHT = 300.dp
private const val MAX_FRAME_SECONDS = 0.1f

/** How tall the drawn keyboard is in a scene [height] tall: 236 dp with room, at most 40% of a short one. */
private fun keyboardHeight(height: Dp): Dp = minOf(KEYBOARD, height * 0.4f)

/** The number row goes first when the keyboard is short (Gboard's own default keeps it hidden). */
private fun keyboardHasNumberRow(keyboard: Dp): Boolean = keyboard >= 200.dp

private fun argb(value: Int): Color = Color(value.toLong() and 0xFFFFFFFFL)

/** The idle bubble: a 56 dp target with the look's 48 dp ground and lips, exactly as the overlay draws it. */
@Composable
private fun DemoBubble(look: BubbleLook, modifier: Modifier = Modifier, scale: Float = 1f) {
    Box(modifier.size(BUBBLE).graphicsLayer { scaleX = scale; scaleY = scale }, contentAlignment = Alignment.Center) {
        val ground = argb(look.surfaceFill)
        Box(
            Modifier.size(BUBBLE_GROUND)
                .then(if (look.surfaceElevationDp > 0) Modifier.shadow(look.surfaceElevationDp.dp, RoundedCornerShape(BUBBLE_RADIUS), spotColor = argb(BrandPalette.PILL_BACKGROUND)) else Modifier)
                .background(ground, RoundedCornerShape(BUBBLE_RADIUS)),
            contentAlignment = Alignment.Center,
        ) {
            OnboardingLips(Modifier.size(look.lipsDp.dp))
        }
    }
}

/**
 * The recorder pill: clock, rail, cancel, accept for a tap; the rail alone, shorter, for a hold. Same
 * height either way, as in the overlay. [t] drives the rail and the clock; [seconds] is the clock.
 */
@Composable
private fun DemoPill(look: BubbleLook, held: Boolean, seconds: Int, t: Float, modifier: Modifier = Modifier) {
    val ground = argb(look.surfaceFill)
    val shape = RoundedCornerShape(PILL_HEIGHT / 2)
    Row(
        modifier.width(if (held) COMPACT_PILL else FULL_PILL).height(PILL_HEIGHT)
            .then(if (look.surfaceElevationDp > 0) Modifier.shadow(look.surfaceElevationDp.dp, shape, spotColor = argb(BrandPalette.PILL_BACKGROUND)) else Modifier)
            .background(ground, shape)
            .padding(horizontal = if (held) 16.dp else 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (!held) {
            Text(
                "0:%02d".format(seconds), Modifier.width(48.dp), color = argb(BrandPalette.TEXT), fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center,
            )
            Spacer(Modifier.width(10.dp))
        }
        DemoRail(t, Modifier.weight(1f).height(if (held) 28.dp else 22.dp), bars = if (held) RecordingLevelMeterView.BAR_COUNT else RecordingAccessibilityOverlay.FULL_PILL_BARS)
        Spacer(Modifier.width(10.dp))
        if (held) {
            // The recording mark under the thumb, as the real hold pill draws it.
            DemoRecordMark()
        } else {
            DemoControl(argb(look.cancelFill), cross = true)
            Spacer(Modifier.width(8.dp))
            DemoControl(argb(look.acceptFill), cross = false)
        }
    }
}

/** The hold pill's recording mark: a ring with a dot, in the thumb's slot, as `RecordMarkView` draws it. */
@Composable
private fun DemoRecordMark() {
    Canvas(Modifier.size(RecordingAccessibilityOverlay.RECORD_MARK_DP.dp, 40.dp)) {
        val ink = argb(BrandPalette.TEXT)
        val c = Offset(size.width / 2, size.height / 2)
        drawCircle(ink, RecordMarkView.RING_RADIUS_DP.dp.toPx(), c, style = Stroke(RecordMarkView.RING_STROKE_DP.dp.toPx()))
        drawCircle(ink, RecordMarkView.DOT_RADIUS_DP.dp.toPx(), c)
    }
}

/** The X and the tick: 40 dp, the look's see-through ground, a solid glyph. */
@Composable
private fun DemoControl(fill: Color, cross: Boolean) {
    Canvas(Modifier.size(40.dp).background(fill, RoundedCornerShape(RecordingAccessibilityOverlay.CONTROL_RADIUS_DP.dp))) {
        val stroke = Stroke(2.2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
        val ink = argb(BrandPalette.TEXT)
        val c = size.width / 2
        val r = 6.5.dp.toPx()
        if (cross) {
            drawLine(ink, Offset(c - r, c - r), Offset(c + r, c + r), stroke.width, StrokeCap.Round)
            drawLine(ink, Offset(c + r, c - r), Offset(c - r, c + r), stroke.width, StrokeCap.Round)
        } else {
            val path = Path().apply { moveTo(c - r, c); lineTo(c - r * .25f, c + r * .7f); lineTo(c + r, c - r * .6f) }
            drawPath(path, ink, style = stroke)
        }
    }
}

/**
 * A drawn voice for the demo rail: the loudness of a spoken phrase over time, 0..1, with syllables about
 * six a second and a breath between phrases. Deterministic in [t], so the demo reads the same every time.
 */
private fun demoVoice(t: Float): Float {
    val phrase = t % 2.6f
    if (phrase > 1.9f) return 0f
    val syllable = 0.55f + 0.45f * sin(phrase * 2f * PI.toFloat() * 5.5f)
    val envelope = (phrase * 4f).coerceAtMost(1f) * ((1.9f - phrase) * 3f).coerceAtMost(1f)
    return (syllable * envelope).coerceIn(0f, 1f)
}

/**
 * The live voice rail as the recorder draws it: every bar a pitch band of the sound right now, the lowest
 * band in the middle and the highest at the edges, rainbow when there is sound. The demo's voice is
 * drawn, so its bands are shaped from one loudness: strong in the middle, thinning to the edges, with a
 * flick at the ends on the loud syllables the way an "s" would.
 */
@Composable
private fun DemoRail(t: Float, modifier: Modifier, bars: Int) {
    Canvas(modifier) {
        val gap = 0.55f
        val barWidth = size.width / (bars + (bars - 1) * gap)
        val voice = demoVoice(t)
        for (i in 0 until bars) {
            val band = RecordingLevelMeterView.barBand(i, bars).toFloat() / (SpectrumAnalyzer.BAND_COUNT - 1)
            val body = voice * (1f - 0.75f * band)
            val flick = if (voice > 0.8f) (band - 0.6f).coerceAtLeast(0f) * 1.5f * voice else 0f
            val level = (body + flick).coerceIn(0f, 1f)
            val height = size.height * (RecordingLevelMeterView.fill(level))
            val color = if (level > RecordingLevelMeterView.RESTING_EPSILON) argb(BrandPalette.RAINBOW[i % BrandPalette.RAINBOW.size]) else argb(BrandPalette.METER_RESTING)
            drawRoundRect(color, Offset(i * barWidth * (1 + gap), (size.height - height) / 2), Size(barWidth, height), CornerRadius(barWidth / 2))
        }
    }
}

// ---- the hand ----

/** Where the fingertip sits inside the hand's canvas, so a target point can be placed under it. */
private val HAND_SIZE = Size(80f, 96f)
private val FINGERTIP = Offset(20f, 6f)

@Composable
private fun DemoHand(modifier: Modifier, pressed: Boolean) {
    Canvas(modifier.size(HAND_SIZE.width.dp, HAND_SIZE.height.dp)) {
        val d = size.width / HAND_SIZE.width
        val skin = Brush.horizontalGradient(listOf(Color(0xFFE8AF88), Color(0xFFF8CFAA), Color(0xFFDD9F78)))
        rotate(-15f, pivot = Offset(FINGERTIP.x * d, FINGERTIP.y * d)) {
            scale(if (pressed) .94f else 1f, pivot = Offset(FINGERTIP.x * d, FINGERTIP.y * d)) {
                // Cuff, palm, thumb, finger: back to front.
                drawRoundRect(Color(0xFF8B5CF6), Offset(18 * d, 78 * d), Size(50 * d, 22 * d), CornerRadius(6 * d))
                drawRoundRect(skin, Offset(12 * d, 34 * d), Size(46 * d, 46 * d), CornerRadius(16 * d))
                drawRoundRect(skin, Offset(48 * d, 40 * d), Size(18 * d, 30 * d), CornerRadius(9 * d))
                drawRoundRect(skin, Offset(12 * d, 0f), Size(16 * d, 50 * d), CornerRadius(8 * d))
            }
        }
    }
}

// ---- scene 1: the wall of the phone's apps ----

@Composable
private fun AppsScene(t: Float, icons: List<ImageBitmap>, look: BubbleLook, palette: DemoPalette) {
    BoxWithConstraints(Modifier.fillMaxSize().clip(RoundedCornerShape(8.dp))) {
        val tile = 56.dp
        val gap = 10.dp
        val pitch = tile + gap
        val columns = (maxWidth / pitch).toInt() + 2
        val rows = (maxHeight / pitch).toInt() + 3
        val bg = palette.background
        Canvas(Modifier.fillMaxSize()) {
            if (icons.isEmpty()) return@Canvas
            val tilePx = tile.toPx()
            val pitchPx = pitch.toPx()
            val scroll = t * 14.dp.toPx()
            for (row in 0 until rows) for (col in 0 until columns) {
                val index = row * columns + col
                val icon = icons[index % icons.size]
                val x = col * pitchPx - pitchPx / 2
                val y = row * pitchPx - scroll - pitchPx / 2
                drawImage(icon, dstOffset = IntOffset(x.roundToInt(), y.roundToInt()), dstSize = IntSize(tilePx.roundToInt(), tilePx.roundToInt()))
            }
            // Fade the wall out at the top and bottom edges, as the mock did.
            drawRect(Brush.verticalGradient(0f to bg, .12f to bg.copy(alpha = 0f), .87f to bg.copy(alpha = 0f), 1f to bg))
        }
        // "99+ apps. One button."
        Box(Modifier.padding(start = 12.dp, top = 12.dp).background(palette.surface.copy(alpha = .92f), RoundedCornerShape(100.dp)).border(1.dp, palette.border, RoundedCornerShape(100.dp)).padding(horizontal = 14.dp, vertical = 7.dp)) {
            Text("99+ apps. One button.", color = palette.accent, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        }
        // The text box slides in from the left, then the bubble pops in at its right end.
        val slide = DemoScript.between(t, .85f, 1.5f)
        val pop = DemoScript.between(t, 1.55f, 1.95f)
        Box(
            Modifier.align(Alignment.Center).fillMaxWidth(.94f).height(94.dp)
                .graphicsLayer { translationX = (1 - slide) * -1.2f * size.width; alpha = slide }
                .shadow(12.dp, RoundedCornerShape(18.dp)).background(palette.surface, RoundedCornerShape(18.dp)).border(1.dp, palette.border, RoundedCornerShape(18.dp)),
        ) {
            Column(Modifier.padding(start = 18.dp, top = 16.dp)) {
                Text("ANY TEXT BOX", color = palette.muted, fontSize = 10.sp, letterSpacing = 1.sp)
                Text("Start here", Modifier.padding(top = 6.dp), color = palette.foreground, fontSize = 17.sp)
            }
            DemoBubble(look, Modifier.align(Alignment.CenterEnd).padding(end = 10.dp).alpha(pop), scale = .65f + .35f * pop)
        }
    }
}

// ---- scene 2: the bubble alone ----

@Composable
private fun BubbleScene(t: Float, palette: DemoPalette) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val go = DemoScript.between(t, 2.6f, 3.2f)
        val back = DemoScript.between(t, 3.7f, 4.3f)
        val side = go - back // 0 at the right edge, 1 at the left
        val beat1 = DemoScript.between(t, .1f, .5f) * (1 - DemoScript.between(t, 2f, 2.3f))
        val beat2 = DemoScript.between(t, 2.3f, 2.7f) * (1 - DemoScript.between(t, 4.3f, 4.6f))
        val beat3 = DemoScript.between(t, 4.6f, 5f)
        var pulse = 0f
        for (at in floatArrayOf(.6f, 1.3f)) { val v = (t - at) / .6f; if (v in 0f..1f) pulse = sin(v * PI).toFloat() }
        val look = BubbleLook.entries[listOf(2, 1, 0)[DemoScript.bubbleLookIndex(t)]] // Smoke, Clear, Bare
        val travel = maxWidth - EDGE_MARGIN * 2 - BUBBLE
        val centerY = maxHeight * .42f
        // The lit edge names the side the bubble is on.
        Box(Modifier.align(Alignment.CenterStart).offset(y = centerY - maxHeight / 2).size(3.dp, 96.dp).alpha(beat2 * side).background(palette.accent, RoundedCornerShape(2.dp)))
        Box(Modifier.align(Alignment.CenterEnd).offset(y = centerY - maxHeight / 2).size(3.dp, 96.dp).alpha(beat2 * (1 - side)).background(palette.accent, RoundedCornerShape(2.dp)))
        Box(Modifier.offset(x = maxWidth - EDGE_MARGIN - BUBBLE - travel * side, y = centerY - BUBBLE / 2)) {
            // The highlight ring of the first beat.
            Box(Modifier.size(BUBBLE + 36.dp).offset(-18.dp, -18.dp).alpha(beat1 * .9f).graphicsLayer { scaleX = 1 + .08f * pulse; scaleY = 1 + .08f * pulse }.border(3.dp, palette.accent, CircleShape))
            DemoBubble(look, scale = 1 + .06f * pulse)
        }
        Row(Modifier.align(Alignment.TopCenter).offset(y = centerY + BUBBLE / 2 + 28.dp).alpha(beat3), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for ((i, name) in listOf("Smoke", "Clear", "Bare").withIndex()) {
                val on = i == DemoScript.bubbleLookIndex(t)
                Text(
                    name, Modifier.border(1.dp, if (on) palette.accent else palette.border, RoundedCornerShape(100.dp))
                        .background(if (on) palette.surface else Color.Transparent, RoundedCornerShape(100.dp)).padding(horizontal = 14.dp, vertical = 6.dp),
                    color = if (on) palette.foreground else palette.muted, fontSize = 13.sp,
                )
            }
        }
    }
}

// ---- scenes 3 and 4: a Gmail draft ----

/** One frame of the Gmail scene: the pill's slide (0 closed, 1 open), the hand, and the words. */
private class GmailFrame(
    val open: Float,
    val seconds: Int,
    val handDx: Float,
    val handDy: Float,
    val handAlpha: Float,
    val pressed: Boolean,
    val contactAt: Float,
    val landed: Boolean,
)

private fun tapFrame(t: Float): GmailFrame {
    val b = DemoScript::between
    val open = b(t, .52f, .78f) * (1 - b(t, 2.62f, 2.88f))
    val approach = b(t, .12f, .48f); val toCheck = b(t, 2.05f, 2.5f); val exit = b(t, 2.76f, 3.08f)
    val retreat = b(t, .65f, .92f) * (1 - b(t, 2.05f, 2.5f))
    return GmailFrame(
        open = open, seconds = if (open > 0f) floor(t - .52f).toInt().coerceAtLeast(0) else 0,
        handDx = 114 - 114 * approach - 4 * toCheck + 100 * exit + 38 * retreat, handDy = 90 - 90 * approach + 140 * exit + 105 * retreat,
        handAlpha = b(t, .1f, .3f) * (1 - exit), pressed = (t >= .5f && t < .62f) || (t >= 2.58f && t < 2.7f),
        contactAt = if (t < 2f) .52f else 2.62f, landed = t >= DemoScript.TAP_LANDS,
    )
}

private fun holdFrame(t: Float): GmailFrame {
    val b = DemoScript::between
    val open = b(t, .82f, 1.06f) * (1 - b(t, 2.55f, 2.82f))
    val approach = b(t, .08f, .4f); val lift = b(t, 2.55f, 2.92f)
    return GmailFrame(
        open = open, seconds = 0,
        handDx = 114 - 114 * approach + 70 * lift, handDy = 90 - 90 * approach + 135 * lift,
        handAlpha = b(t, .06f, .25f) * (1 - lift), pressed = t >= .42f && t < 2.55f,
        contactAt = .42f, landed = t >= DemoScript.HOLD_LANDS,
    )
}

@Composable
private fun GmailScene(t: Float, held: Boolean, look: BubbleLook, palette: DemoPalette, modifier: Modifier = Modifier, frozen: Boolean = false) {
    // A window shorter than the scene's minimum (landscape, a split screen) shows the whole scene
    // scaled down rather than a scene with its bubble clipped away (Codex review 1, 2026-09-14).
    BoxWithConstraints(modifier.fillMaxSize()) {
        val fit = (maxHeight / GMAIL_MIN_HEIGHT).coerceAtMost(1f)
        val width = maxWidth
        if (fit < 1f) {
            // The card is laid out at its full minimum height, outside the short parent's limits
            // (`requiredSize` inside an unbounded wrapper), and only then drawn scaled to fit (Codex
            // review 2, 2026-09-14: a plain `size` was clamped to the parent and shrunk twice).
            Box(Modifier.wrapContentSize(Alignment.TopStart, unbounded = true)) {
                Box(Modifier.requiredSize(width / fit, GMAIL_MIN_HEIGHT).graphicsLayer { scaleX = fit; scaleY = fit; transformOrigin = TransformOrigin(0f, 0f) }) {
                    GmailCard(t, held, look, palette, frozen)
                }
            }
        } else GmailCard(t, held, look, palette, frozen)
    }
}

@Composable
private fun GmailCard(t: Float, held: Boolean, look: BubbleLook, palette: DemoPalette, frozen: Boolean) {
    val frame = if (frozen) GmailFrame(0f, 0, 0f, 0f, 0f, false, 0f, true) else if (held) holdFrame(t) else tapFrame(t)
    BoxWithConstraints(Modifier.fillMaxSize().shadow(16.dp, RoundedCornerShape(22.dp)).background(GMAIL_GROUND, RoundedCornerShape(22.dp)).border(1.dp, Color(0xFF2C2C2C), RoundedCornerShape(22.dp)).clip(RoundedCornerShape(22.dp))) {
        val w = maxWidth
        val h = maxHeight
        val keyboard = keyboardHeight(h)
        Column(Modifier.fillMaxSize()) {
            GmailBar()
            GmailRow { Text("To", color = GMAIL_INK, fontSize = 13.sp); Spacer(Modifier.width(14.dp)); Text("Priya Shah", Modifier.background(Color(0xFF2E3238), RoundedCornerShape(100.dp)).padding(horizontal = 9.dp, vertical = 3.dp), color = GMAIL_INK, fontSize = 12.sp); Spacer(Modifier.weight(1f)); Text("⌄", color = GMAIL_INK, fontSize = 15.sp) }
            GmailRow { Text(if (held) "Re: Our call this week" else "Our call this week", color = GMAIL_INK, fontSize = 13.sp) }
            Column(Modifier.fillMaxWidth().weight(1f).clip(RoundedCornerShape(0.dp))) {
                Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0xFF2E2E2E)))
                Box(Modifier.fillMaxWidth().padding(start = 13.dp, top = 11.dp, end = 13.dp)) {
                Row {
                    if (frame.landed) Text(if (held) DemoScript.HOLD_EMAIL else DemoScript.TAP_EMAIL, color = GMAIL_INK, fontSize = 13.sp, lineHeight = 19.sp)
                    // The cursor blinks at the left of the empty body; the landed email replaces it.
                    if (!frame.landed) Box(Modifier.padding(top = 2.dp).size(1.5.dp, 15.dp).alpha(if (floor(t * 3).toInt() % 2 == 0) 1f else 0f).background(GMAIL_INK))
                }
                }
            }
            GmailKeyboard(keyboard)
        }
        // The bubble: 12 dp from the edge, just above the keyboard, as the overlay places it.
        val bubbleLeft = w - EDGE_MARGIN - BUBBLE
        val bubbleTop = h - keyboard - BUBBLE_ABOVE_KEYBOARD - BUBBLE
        val center = Offset((bubbleLeft + BUBBLE / 2).value, (bubbleTop + BUBBLE / 2).value)
        DemoBubble(look, Modifier.offset(bubbleLeft, bubbleTop).alpha(1 - frame.open))
        if (frame.open > 0f) {
            val pillWidth = if (held) COMPACT_PILL else FULL_PILL
            DemoPill(
                look, held, frame.seconds, t,
                Modifier.offset(x = w - EDGE_MARGIN - pillWidth + 240.dp * (1 - frame.open), y = bubbleTop - (PILL_HEIGHT - BUBBLE) / 2).alpha(frame.open),
            )
        }
        // The contact ring at the tap, then the hand.
        val ring = (t - frame.contactAt) / .35f
        if (ring in 0f..1f) {
            Box(Modifier.offset((center.x - 17).dp, (center.y - 17).dp).size(34.dp).alpha((1 - ring) * .8f).graphicsLayer { scaleX = .55f + ring * .8f; scaleY = .55f + ring * .8f }.border(2.dp, palette.accent, CircleShape))
        }
        if (frame.handAlpha > 0f) {
            DemoHand(Modifier.offset((center.x + frame.handDx - FINGERTIP.x).dp, (center.y + frame.handDy - FINGERTIP.y).dp).alpha(frame.handAlpha), frame.pressed)
        }
    }
}

private val GMAIL_GROUND = Color(0xFF161616)
private val GMAIL_INK = Color(0xFFE8EAED)
private val GMAIL_HINT = Color(0xFF8F949A)
private val KEY_GROUND = Color(0xFF22262B)
private val KEY = Color(0xFF3A3F46)
private val KEY_FN = Color(0xFF2C3038)

@Composable
private fun GmailBar() {
    Row(Modifier.fillMaxWidth().height(34.dp).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("←", color = GMAIL_INK, fontSize = 17.sp)
        Spacer(Modifier.weight(1f))
        for (glyph in listOf("✎", "⊘", "➤", "⋮")) Text(glyph, Modifier.padding(start = 18.dp), color = GMAIL_INK, fontSize = 15.sp)
    }
}

@Composable
private fun GmailRow(content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0xFF2E2E2E)))
        Row(Modifier.fillMaxWidth().height(36.dp).padding(horizontal = 13.dp), verticalAlignment = Alignment.CenterVertically) { content() }
    }
}

/** A Samsung-style keyboard, drawn: toolbar, number row, three letter rows, the bottom row. */
@Composable
private fun GmailKeyboard(height: Dp) {
    val numbers = keyboardHasNumberRow(height)
    // Toolbar plus four or five rows, each the same share of what is left after the paddings and gaps.
    val rows = if (numbers) 5 else 4
    val row = (height - 8.dp - 4.dp * rows - 30.dp) / rows
    Column(Modifier.fillMaxWidth().height(height).background(KEY_GROUND).padding(horizontal = 4.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth().height(30.dp), horizontalArrangement = Arrangement.SpaceAround, verticalAlignment = Alignment.CenterVertically) {
            for (glyph in listOf("▦", "GIF", "✦", "▤", "⚙")) Text(glyph, color = GMAIL_INK, fontSize = if (glyph == "GIF") 9.sp else 13.sp, fontWeight = FontWeight.Bold)
            Box(Modifier.size(26.dp).background(KEY, CircleShape), contentAlignment = Alignment.Center) { Text("🎙", fontSize = 12.sp) }
        }
        if (numbers) KeyRow("1234567890".map { it.toString() }, row)
        KeyRow("QWERTYUIOP".map { it.toString() }, row)
        KeyRow("ASDFGHJKL".map { it.toString() }, row, inset = 16.dp)
        KeyRow(listOf("⇧") + "ZXCVBNM".map { it.toString() } + "⌫", row, wide = setOf("⇧", "⌫"))
        KeyRow(listOf("?123", ",", "☺", " ", ".", "↵"), row, wide = setOf("?123", "↵"), fn = setOf("?123", ",", "☺", ".", "↵"), space = " ")
    }
}

@Composable
private fun KeyRow(keys: List<String>, height: Dp, inset: Dp = 0.dp, wide: Set<String> = emptySet(), fn: Set<String> = emptySet(), space: String? = null) {
    Row(Modifier.fillMaxWidth().height(height).padding(horizontal = inset), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        for (key in keys) {
            val weight = when (key) { space -> 5f; in wide -> 1.5f; else -> 1f }
            Box(Modifier.weight(weight).fillMaxHeight().background(if (key in fn) KEY_FN else KEY, RoundedCornerShape(5.dp)), contentAlignment = Alignment.Center) {
                Text(key, color = GMAIL_INK, fontSize = if (key in fn) 11.sp else 14.sp)
            }
        }
    }
}

// ---- scene 5: your turn ----

@Composable
private fun YoursScene(t: Float, look: BubbleLook, palette: DemoPalette) {
    val shrink = DemoScript.between(t, .3f, 1.2f)
    val gone = DemoScript.between(t, .7f, 1.2f)
    val come = DemoScript.between(t, 1.1f, 1.5f)
    var pulse = 0f
    for (at in floatArrayOf(1.7f, 2.4f)) { val v = (t - at) / .6f; if (v in 0f..1f) pulse = sin(v * PI).toFloat() }
    Box(Modifier.fillMaxSize()) {
        if (gone < 1f) GmailScene(t, held = true, look, palette, Modifier.graphicsLayer { scaleX = 1 - .9f * shrink; scaleY = 1 - .9f * shrink; alpha = 1 - gone }, frozen = true)
        Box(Modifier.align(Alignment.Center).alpha(come)) {
            DemoBubble(look, scale = 1 + .1f * pulse)
        }
    }
}
