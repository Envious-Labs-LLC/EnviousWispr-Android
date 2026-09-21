package com.envi.wispr

internal data class Box(val side: Int) : Shape

internal fun area(s: Shape): Int = when (s) {
    Shape.Dot -> 0
    is Box -> s.side * s.side
    else -> -1
}
