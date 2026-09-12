package com.envi.wispr.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp

internal enum class SetupPermission { MICROPHONE, ACCESSIBILITY, NOTIFICATIONS }

@Composable
internal fun OnboardingPermissionIcon(kind: SetupPermission, color: Color) {
    Box(Modifier.size(38.dp).background(color.copy(alpha = .12f), RoundedCornerShape(10.dp)).padding(8.dp)) {
        if (kind == SetupPermission.MICROPHONE) MicrophoneGlyph(Modifier.size(22.dp), color)
        else Canvas(Modifier.size(22.dp)) {
            val u = size.width / 24
            val stroke = Stroke(1.8f * u, cap = StrokeCap.Round)
            when (kind) {
                SetupPermission.MICROPHONE -> Unit
                SetupPermission.ACCESSIBILITY -> {
                    drawCircle(color, 2 * u, Offset(12 * u, 4 * u), style = stroke)
                    val path = Path().apply {
                        moveTo(3 * u, 8 * u); lineTo(12 * u, 10 * u); lineTo(21 * u, 8 * u)
                        moveTo(12 * u, 10 * u); lineTo(12 * u, 16 * u)
                        moveTo(7 * u, 22 * u); lineTo(12 * u, 16 * u); lineTo(17 * u, 22 * u)
                    }
                    drawPath(path, color, style = stroke)
                }
                SetupPermission.NOTIFICATIONS -> {
                    val path = Path().apply {
                        moveTo(4 * u, 17 * u); lineTo(20 * u, 17 * u)
                        cubicTo(17 * u, 14 * u, 18 * u, 12 * u, 18 * u, 9 * u)
                        cubicTo(18 * u, 1 * u, 6 * u, 1 * u, 6 * u, 9 * u)
                        cubicTo(6 * u, 12 * u, 7 * u, 14 * u, 4 * u, 17 * u)
                    }
                    drawPath(path, color, style = stroke)
                    drawLine(color, Offset(10 * u, 21 * u), Offset(14 * u, 21 * u), 1.8f * u, StrokeCap.Round)
                }
            }
        }
    }
}
