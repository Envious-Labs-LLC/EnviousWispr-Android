package com.envi.wispr

internal enum class Color { RED, GREEN, BLUE }

internal fun weight(c: Color): Int = when (c) {
    Color.RED -> 1
    Color.GREEN -> 2
    else -> 0
}
