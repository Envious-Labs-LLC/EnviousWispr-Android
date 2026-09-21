package com.envi.wispr

internal enum class Color { RED, GREEN, BLUE }

internal fun pick(c: Color, n: Int): String = when (n) {
    1 -> "one"
    in 2..5 -> "few"
    else -> "many"
}

internal fun guarded(c: Color, hot: Boolean): Int = when (c) {
    Color.RED -> 1
    Color.GREEN, Color.BLUE -> if (hot) 2 else 3
}

internal fun mixed(c: Any): Int = when (c) {
    Color.RED -> 1
    is String -> 2
    else -> 3
}
