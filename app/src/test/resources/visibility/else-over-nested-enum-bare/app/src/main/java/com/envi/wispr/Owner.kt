package com.envi.wispr

internal class Owner {
    enum class Mode { ON, OFF, AUTO }
    fun f(m: Mode): Int = when (m) {
        ON -> 1
        OFF -> 0
        else -> 2
    }
}
