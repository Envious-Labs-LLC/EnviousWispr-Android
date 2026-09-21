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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
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
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.envi.wispr.paste.AutoPasteAvailability

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
        Card {
            Column(Modifier.fillMaxWidth(), content = content)
        }
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
        icon?.invoke()
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = null, enabled = enabled)
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
            StatusDot(ready, description)
            Text(label, style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
internal fun StatusPill(label: String, ready: Boolean) {
    Surface(
        shape = RoundedCornerShape(100.dp),
        color = if (ready) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.errorContainer,
    ) {
        Text(
            label,
            modifier = Modifier.padding(horizontal = 11.dp, vertical = 6.dp),
            style = MaterialTheme.typography.labelLarge,
            color = if (ready) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onErrorContainer,
        )
    }
}

@Composable
internal fun StatusDot(ready: Boolean, description: String? = null) {
    // The screen-reader label is the fifth surface that said Ready while auto-paste was dead, and
    // the one that matters most on a feature built out of an accessibility service.
    val label = description ?: if (ready) "Ready" else "Needs attention"
    Box(
        modifier = Modifier
            .size(10.dp)
            .clip(CircleShape)
            .background(if (ready) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
            .semantics { contentDescription = label },
    )
}

internal fun AutoPasteAvailability.statusDescription(): String = when (this) {
    AutoPasteAvailability.LIVE -> "Ready"
    AutoPasteAvailability.PERMITTED_NOT_RUNNING -> "Not connected"
    AutoPasteAvailability.NOT_PERMITTED -> "Needs attention"
}
