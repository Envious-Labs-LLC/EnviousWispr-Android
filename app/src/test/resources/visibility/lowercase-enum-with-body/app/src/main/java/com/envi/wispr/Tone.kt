package com.envi.wispr

internal enum class Tone(val hz: Int) {
    low(120),
    mid(1000),
    `high`(5000);

    fun label(): String = name
}

internal fun bars(t: Tone): Int = when (t) {
    Tone.low -> 1
    Tone.mid -> 2
    else -> 3
}
