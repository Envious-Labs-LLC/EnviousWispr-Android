package com.envi.wispr

internal class Holder {
    val s = listOf(1).let { "${it.map { "${it}" }}" }
}
    class Leak
