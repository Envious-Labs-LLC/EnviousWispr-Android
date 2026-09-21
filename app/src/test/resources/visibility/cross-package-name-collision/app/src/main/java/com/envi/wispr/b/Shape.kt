package com.envi.wispr.b

internal sealed interface Shape

internal data object Dot : Shape

internal class Blob : Shape

internal fun tag(s: Shape): Int = when (s) {
    Dot -> 0
    is Blob -> 1
    else -> 2
}
