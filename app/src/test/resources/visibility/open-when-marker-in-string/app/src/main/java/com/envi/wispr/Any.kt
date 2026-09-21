package com.envi.wispr

internal enum class Color { RED, GREEN, BLUE }

internal fun tag(c: Color): Int = when (c) { // a real comment without the marker
    Color.RED -> 1
    else -> "visibility-open-when: not a comment".length
}
