package com.envi.wispr.audio

/**
 * The name of one capture file (#212, #221). One file per capture, so a late answer for an ended take can
 * only ever delete its own recording. A production take is named by the owner's take id; a legacy start
 * (no id, or an id of the wrong shape) is named by the capture's own token, so two legacy captures never
 * share a file either.
 */
internal object CaptureFiles {
    /** The single shared name every take used before #212; swept so an upgrade leaves none. */
    const val PRE_212_NAME = "recording.pcm"
    private val TAKE_ID = Regex("[A-Za-z0-9-]{1,64}")
    private val PRODUCTION = Regex("recording-take-[A-Za-z0-9-]{1,64}\\.pcm")

    fun nameFor(takeId: String, fallbackToken: Long): String =
        if (isProductionTake(takeId)) "recording-take-$takeId.pcm" else "recording-legacy-$fallbackToken.pcm"

    fun isProductionTake(takeId: String): Boolean = TAKE_ID.matches(takeId)

    /**
     * What a PRODUCTION take's start removes: the pre-#212 name and every other production take's file.
     * Never a legacy capture (a separately installed client may still hold its path across an unbind) and
     * never another cache file.
     */
    fun isSweptAtTakeStart(name: String): Boolean = name == PRE_212_NAME || PRODUCTION.matches(name)
}
