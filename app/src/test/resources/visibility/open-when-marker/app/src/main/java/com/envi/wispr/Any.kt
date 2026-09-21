package com.envi.wispr

internal enum class Color { RED, GREEN, BLUE }

internal fun tag(x: Any): Int = when (x) { // visibility-open-when: x is Any, RED is an equality check
    Color.RED -> 1
    else -> 0
}
