package com.envi.wispr.paste

/**
 * The last [capacity] microphone levels, oldest first. Pure, so the scrolling is testable without a
 * canvas.
 *
 * A bar on the rail is a MOMENT, not a position on a shape. The first rail drew every bar from the
 * same instant's level under a fixed per-bar weight, so all of them could only rise and fall together:
 * a level meter in a waveform's clothes. The founder saw it on the phone (2026-09-12): "they expand
 * and shrink" rather than move. Real audio hands the recorder one number per tick, so the only honest
 * record is to keep the numbers. Ported from the macOS `RainbowLevelMeter`.
 */
internal class LevelHistory(private val capacity: Int) {

    private val samples = ArrayDeque<Float>(capacity.coerceAtLeast(0))

    val size: Int get() = samples.size

    /** Add one sample, dropping the oldest once full. Clamped on the way IN so a bad reading cannot sit in the record. */
    fun push(level: Float) {
        if (capacity <= 0) return
        val safe = if (level.isFinite()) level.coerceIn(0f, 1f) else 0f
        samples.addLast(safe)
        while (samples.size > capacity) samples.removeFirst()
    }

    fun clear() = samples.clear()

    /**
     * The level for each of [count] bars, left to right, RIGHT-ALIGNED so the newest sample always sits
     * at the same edge. Without the alignment the whole shape slides sideways for the first second of
     * every take. Missing history reads as silence.
     */
    fun bars(count: Int): FloatArray {
        val out = FloatArray(count.coerceAtLeast(0))
        val pad = (count - samples.size).coerceAtLeast(0)
        val offset = (samples.size - count).coerceAtLeast(0)
        for (i in out.indices) {
            val index = i - pad + offset
            out[i] = if (index >= 0 && index < samples.size) samples.elementAt(index) else 0f
        }
        return out
    }
}
