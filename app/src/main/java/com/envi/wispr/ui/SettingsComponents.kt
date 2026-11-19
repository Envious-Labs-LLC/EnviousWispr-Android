package com.envi.wispr.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.envi.wispr.paste.AutoPasteAvailability
import com.envi.wispr.ui.theme.brandCardBorder
import com.envi.wispr.ui.theme.brandReadyColor
import com.envi.wispr.ui.theme.brandReadyTextColor
import com.envi.wispr.ui.theme.brandSwitchColors

/**
 * The body of every screen and page.
 *
 * The title is not repeated here: the top app bar already carries it, and printing it twice was the
 * first thing to look wrong when the bar arrived. [subtitle] is the one line of orientation macOS puts
 * under each page's title; null skips that line and the space it would take, for a screen dense enough
 * not to need one.
 */
@Composable
internal fun ScreenContainer(
    subtitle: String? = null,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (subtitle != null) {
            item {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .fillMaxWidth()
                        .widthIn(max = 900.dp),
                )
            }
        }
        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 900.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                content = content,
            )
        }
    }
}

@Composable
internal fun SettingsGroup(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
        Card(
            shape = MaterialTheme.shapes.medium,
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
            border = brandCardBorder(),
        ) {
            Column(Modifier.fillMaxWidth(), content = content)
        }
    }
}

/** Stepped rounded-square icon tile used on model cards and featured setting rows (#52). */
@Composable
internal fun BrandIconTile(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = modifier
            .size(44.dp)
            .clip(MaterialTheme.shapes.small)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh),
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}

/**
 * A row carrying one slider, with its current value read out beside the title.
 *
 * The first slider in this app, so it borrows everything it can from [SettingsToggleRow]: the same 18 dp
 * padding, the same title and subtitle typography, the same muted subtitle colour. The control sits BELOW
 * the text rather than beside it, because eleven positions need the row's full width to be usable with a
 * thumb.
 *
 * [valueLabel] is deliberately approximate wording rather than a number alone. The stored value is the
 * policy the detector is given, not a stopwatch promise: the state machine spends a block noticing the
 * silence before it starts counting, so the real wait is a little longer than the number shown.
 */
@Composable
internal fun SettingsSliderRow(
    title: String,
    subtitle: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int,
    valueLabel: String,
    enabled: Boolean = true,
    onValueChange: (Float) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(
                valueLabel,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Text(
            subtitle,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange,
            steps = steps,
            enabled = enabled,
            modifier = Modifier
                .fillMaxWidth()
                .semantics { contentDescription = "$title, $valueLabel" },
        )
    }
}

/**
 * A row carrying one switch.
 *
 * [enabled] exists for a setting the phone itself cannot honour. A switch that stores a value nothing
 * reads is worse than an absent one, because it looks like it worked. The row keeps its title and says
 * why in its subtitle instead.
 */
@Composable
internal fun SettingsToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    enabled: Boolean = true,
    icon: (@Composable () -> Unit)? = null,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(
                value = checked,
                enabled = enabled,
                role = Role.Switch,
                onValueChange = onCheckedChange,
            )
            .padding(18.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        icon?.let { BrandIconTile { it() } }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(
            checked = checked,
            onCheckedChange = null,
            enabled = enabled,
            colors = brandSwitchColors(),
        )
    }
}

/**
 * A row that leads somewhere.
 *
 * [enabled] exists because a permission row stops leading anywhere once the permission is granted:
 * re-launching a granted request returns instantly and draws nothing, so the row would promise a
 * result with a chevron and then deliver none. A disabled row keeps its status dot and its sentence,
 * loses the chevron, and does not accept a tap.
 */
@Composable
internal fun SettingsActionRow(
    title: String,
    subtitle: String,
    ready: Boolean?,
    statusDescription: String? = null,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    TextButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(18.dp),
        colors = ButtonDefaults.textButtonColors(
            contentColor = MaterialTheme.colorScheme.onSurface,
            disabledContentColor = MaterialTheme.colorScheme.onSurface,
        ),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (ready != null) StatusDot(ready, statusDescription)
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.Start) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (enabled) {
                Text("›", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
internal fun MicrophoneGlyph(modifier: Modifier, color: Color = Color.Unspecified) {
    val actualColor = if (color == Color.Unspecified) MaterialTheme.colorScheme.onPrimary else color
    Canvas(modifier = modifier.semantics { contentDescription = "Microphone" }) {
        val strokeWidth = 3.dp.toPx()
        drawRoundRect(
            color = actualColor,
            topLeft = Offset(size.width * 0.34f, size.height * 0.08f),
            size = androidx.compose.ui.geometry.Size(size.width * 0.32f, size.height * 0.52f),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.width * 0.16f),
            style = Stroke(strokeWidth, cap = StrokeCap.Round),
        )
        val arcPath = Path().apply {
            moveTo(size.width * 0.20f, size.height * 0.48f)
            cubicTo(
                size.width * 0.20f,
                size.height * 0.78f,
                size.width * 0.80f,
                size.height * 0.78f,
                size.width * 0.80f,
                size.height * 0.48f,
            )
        }
        drawPath(arcPath, actualColor, style = Stroke(strokeWidth, cap = StrokeCap.Round))
        drawLine(actualColor, Offset(size.width * 0.50f, size.height * 0.75f), Offset(size.width * 0.50f, size.height * 0.91f), strokeWidth, StrokeCap.Round)
        drawLine(actualColor, Offset(size.width * 0.36f, size.height * 0.91f), Offset(size.width * 0.64f, size.height * 0.91f), strokeWidth, StrokeCap.Round)
    }
}

@Composable
internal fun ReadinessChip(label: String, ready: Boolean, description: String? = null) {
    Surface(
        shape = RoundedCornerShape(100.dp),
        color = if (ready) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.errorContainer,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StatusDot(
                ready = ready,
                description = description,
                readyColor = MaterialTheme.colorScheme.onPrimaryContainer,
                notReadyColor = MaterialTheme.colorScheme.onErrorContainer,
            )
            Text(label, style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
internal fun StatusPill(label: String, ready: Boolean) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StatusDot(ready = ready, decorative = true)
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            color = if (ready) brandReadyTextColor() else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
internal fun StatusDot(
    ready: Boolean,
    description: String? = null,
    readyColor: Color? = null,
    notReadyColor: Color? = null,
    decorative: Boolean = false,
) {
    // The screen-reader label is the fifth surface that said Ready while auto-paste was dead, and
    // the one that matters most on a feature built out of an accessibility service. Inline status rows
    // with adjacent text pass decorative=true so TalkBack announces the status once.
    val label = description ?: if (ready) "Ready" else "Needs attention"
    val color = if (ready) (readyColor ?: brandReadyColor()) else (notReadyColor ?: MaterialTheme.colorScheme.error)
    Box(
        modifier = Modifier
            .size(10.dp)
            .clip(CircleShape)
            .background(color)
            .let { mod -> if (decorative) mod else mod.semantics { contentDescription = label } },
    )
}

/** Soundwave bars glyph for speech engine cards. */
@Composable
internal fun WaveformGlyph(
    modifier: Modifier = Modifier.size(22.dp),
    color: Color = MaterialTheme.colorScheme.primary,
) {
    Canvas(modifier) {
        val stroke = 2.4.dp.toPx()
        val bars = listOf(0.2f to 0.42f, 0.4f to 0.78f, 0.6f to 0.56f, 0.8f to 0.70f)
        bars.forEach { (xFraction, heightFraction) ->
            val topY = (1f - heightFraction) * 0.5f * size.height
            val bottomY = size.height - topY
            drawLine(color, Offset(size.width * xFraction, topY), Offset(size.width * xFraction, bottomY), stroke, StrokeCap.Round)
        }
    }
}

/** Funnel glyph for filler-word filtering. */
@Composable
internal fun FilterGlyph(
    modifier: Modifier = Modifier.size(22.dp),
    color: Color = MaterialTheme.colorScheme.primary,
) {
    Canvas(modifier) {
        val stroke = 2.dp.toPx()
        val path = Path().apply {
            moveTo(size.width * 0.14f, size.height * 0.22f)
            lineTo(size.width * 0.86f, size.height * 0.22f)
            lineTo(size.width * 0.58f, size.height * 0.54f)
            lineTo(size.width * 0.58f, size.height * 0.82f)
            lineTo(size.width * 0.42f, size.height * 0.88f)
            lineTo(size.width * 0.42f, size.height * 0.54f)
            close()
        }
        drawPath(path, color, style = Stroke(stroke, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

/** Smiley glyph for spoken emoji formatting. */
@Composable
internal fun EmojiGlyph(
    modifier: Modifier = Modifier.size(22.dp),
    color: Color = MaterialTheme.colorScheme.primary,
) {
    Canvas(modifier) {
        val stroke = 2.dp.toPx()
        drawCircle(color, radius = size.minDimension * 0.38f, style = Stroke(stroke))
        drawCircle(color, radius = stroke * 0.55f, center = Offset(size.width * 0.38f, size.height * 0.42f))
        drawCircle(color, radius = stroke * 0.55f, center = Offset(size.width * 0.62f, size.height * 0.42f))
        val smile = Path().apply {
            moveTo(size.width * 0.34f, size.height * 0.58f)
            cubicTo(size.width * 0.42f, size.height * 0.72f, size.width * 0.58f, size.height * 0.72f, size.width * 0.66f, size.height * 0.58f)
        }
        drawPath(smile, color, style = Stroke(stroke, cap = StrokeCap.Round))
    }
}

/** Comma/punctuation glyph for spoken punctuation formatting. */
@Composable
internal fun PunctuationGlyph(
    modifier: Modifier = Modifier.size(22.dp),
    color: Color = MaterialTheme.colorScheme.primary,
) {
    Canvas(modifier) {
        val stroke = 2.2.dp.toPx()
        drawCircle(color, radius = size.minDimension * 0.16f, center = Offset(size.width * 0.36f, size.height * 0.38f))
        val tail = Path().apply {
            moveTo(size.width * 0.48f, size.height * 0.42f)
            cubicTo(size.width * 0.50f, size.height * 0.64f, size.width * 0.38f, size.height * 0.78f, size.width * 0.28f, size.height * 0.82f)
        }
        drawPath(tail, color, style = Stroke(stroke, cap = StrokeCap.Round))
        drawCircle(color, radius = size.minDimension * 0.10f, center = Offset(size.width * 0.70f, size.height * 0.68f))
    }
}

/** Small leading glyph rendered inside fact chips (#52). */
@Composable
internal fun FactChipGlyph(fact: String, modifier: Modifier = Modifier.size(14.dp)) {
    val color = MaterialTheme.colorScheme.primary
    val lower = fact.lowercase()
    Canvas(modifier) {
        val stroke = 1.6.dp.toPx()
        when {
            "offline" in lower -> {
                val arc1 = Path().apply {
                    moveTo(size.width * 0.14f, size.height * 0.42f)
                    cubicTo(size.width * 0.34f, size.height * 0.22f, size.width * 0.66f, size.height * 0.22f, size.width * 0.86f, size.height * 0.42f)
                }
                val arc2 = Path().apply {
                    moveTo(size.width * 0.30f, size.height * 0.60f)
                    cubicTo(size.width * 0.42f, size.height * 0.50f, size.width * 0.58f, size.height * 0.50f, size.width * 0.70f, size.height * 0.60f)
                }
                drawPath(arc1, color, style = Stroke(stroke, cap = StrokeCap.Round))
                drawPath(arc2, color, style = Stroke(stroke, cap = StrokeCap.Round))
                drawCircle(color, radius = stroke * 0.6f, center = Offset(size.width * 0.50f, size.height * 0.78f))
                drawLine(color, Offset(size.width * 0.16f, size.height * 0.84f), Offset(size.width * 0.84f, size.height * 0.16f), stroke, StrokeCap.Round)
            }
            "stays on this phone" in lower || "private" in lower || "on-device" in lower -> {
                val shield = Path().apply {
                    moveTo(size.width * 0.50f, size.height * 0.12f)
                    lineTo(size.width * 0.82f, size.height * 0.26f)
                    lineTo(size.width * 0.80f, size.height * 0.56f)
                    cubicTo(size.width * 0.76f, size.height * 0.76f, size.width * 0.58f, size.height * 0.86f, size.width * 0.50f, size.height * 0.90f)
                    cubicTo(size.width * 0.42f, size.height * 0.86f, size.width * 0.24f, size.height * 0.76f, size.width * 0.20f, size.height * 0.56f)
                    lineTo(size.width * 0.18f, size.height * 0.26f)
                    close()
                }
                drawPath(shield, color, style = Stroke(stroke, join = StrokeJoin.Round))
            }
            "local" in lower || "audio" in lower -> {
                drawRoundRect(
                    color,
                    topLeft = Offset(size.width * 0.34f, size.height * 0.14f),
                    size = androidx.compose.ui.geometry.Size(size.width * 0.32f, size.height * 0.48f),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.width * 0.16f),
                    style = Stroke(stroke, cap = StrokeCap.Round),
                )
                drawLine(color, Offset(size.width * 0.50f, size.height * 0.72f), Offset(size.width * 0.50f, size.height * 0.88f), stroke, StrokeCap.Round)
            }
            else -> {
                drawCircle(color, radius = size.minDimension * 0.36f, style = Stroke(stroke))
                drawLine(color, Offset(size.width * 0.16f, size.height * 0.50f), Offset(size.width * 0.84f, size.height * 0.50f), stroke * 0.85f)
                drawLine(color, Offset(size.width * 0.50f, size.height * 0.16f), Offset(size.width * 0.50f, size.height * 0.84f), stroke * 0.85f)
            }
        }
    }
}

internal fun AutoPasteAvailability.statusDescription(): String = when (this) {
    AutoPasteAvailability.LIVE -> "Ready"
    AutoPasteAvailability.PERMITTED_NOT_RUNNING -> "Not connected"
    AutoPasteAvailability.NOT_PERMITTED,
    AutoPasteAvailability.SWITCHED_OFF_UNEXPECTEDLY,
    -> "Needs attention"
}

/** The auto-paste row's sentence on the Permissions page and in setup when Android switched it off (#131). */
internal const val AUTO_PASTE_SWITCHED_OFF_ROW = "Switched off. Turn it back on in Accessibility settings."
