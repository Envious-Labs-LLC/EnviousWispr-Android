package com.envi.wispr.ui

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp

/**
 * A scroll region INSIDE a page whose edge fades where more content continues (founder, 2026-10-01: "it's not
 * obvious things can scroll"). The page's own scroll needs no hint; a row of pills cut off at the card's edge
 * or a list boxed to a fixed height does. Use these in place of `horizontalScroll` and `verticalScroll` for
 * any such region, so the fade and the scroll cannot be applied in the wrong order.
 */
internal fun Modifier.horizontalScrollWithFade(state: ScrollState): Modifier =
    scrollEdgeFade(state, Orientation.Horizontal).horizontalScroll(state)

internal fun Modifier.verticalScrollWithFade(state: ScrollState): Modifier =
    scrollEdgeFade(state, Orientation.Vertical).verticalScroll(state)

/**
 * Each edge fades in proportion to how far content runs past it, up to [length], so the fade grows as the
 * user scrolls away from an end and is gone at that end, with no animation to run. It is drawn OUTSIDE the
 * scroll modifier, over the clipped viewport, and reads the scroll position only while drawing, so scrolling
 * redraws and never recomposes.
 */
private fun Modifier.scrollEdgeFade(state: ScrollState, orientation: Orientation, length: Dp = 24.dp): Modifier =
    // Offscreen, because DstOut must erase this region's own pixels, not the card behind it.
    graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
        .drawWithContent {
            drawContent()
            val fadePx = length.toPx()
            if (fadePx <= 0f) return@drawWithContent
            val before = (state.value / fadePx).coerceIn(0f, 1f)
            val after = ((state.maxValue - state.value) / fadePx).coerceIn(0f, 1f)
            if (orientation == Orientation.Vertical) {
                fadeEdge(before, Offset.Zero, Offset(0f, fadePx), Size(size.width, fadePx), Offset.Zero)
                fadeEdge(after, Offset(0f, size.height), Offset(0f, size.height - fadePx), Size(size.width, fadePx), Offset(0f, size.height - fadePx))
            } else {
                // A horizontal scroll starts at the right in a right-to-left language, so "before" is that edge.
                val rtl = layoutDirection == LayoutDirection.Rtl
                val left = if (rtl) after else before
                val right = if (rtl) before else after
                fadeEdge(left, Offset.Zero, Offset(fadePx, 0f), Size(fadePx, size.height), Offset.Zero)
                fadeEdge(right, Offset(size.width, 0f), Offset(size.width - fadePx, 0f), Size(fadePx, size.height), Offset(size.width - fadePx, 0f))
            }
        }

/** Erases content from [from] (by [strength]) to [to] (not at all) across the rectangle at [topLeft]. */
private fun DrawScope.fadeEdge(strength: Float, from: Offset, to: Offset, rect: Size, topLeft: Offset) {
    if (strength <= 0f) return
    drawRect(
        brush = Brush.linearGradient(listOf(Color.Black.copy(alpha = strength), Color.Transparent), start = from, end = to),
        topLeft = topLeft,
        size = rect,
        blendMode = BlendMode.DstOut,
    )
}
