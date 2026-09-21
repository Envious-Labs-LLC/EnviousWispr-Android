package com.envi.wispr

internal sealed interface Shape {
    data object Dot : Shape
    data class Box(val side: Int) : Shape
}

internal fun area(s: Shape): Int = when (s) {
    Shape.Dot -> 0
    is Shape.Box -> s.side * s.side
    else -> -1
}
