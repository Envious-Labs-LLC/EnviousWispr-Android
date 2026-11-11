package com.envi.wispr.audio

import android.media.AudioAttributes

/**
 * Which phone volume the recording chimes follow (#526). The platform picks the volume stream from the
 * clip's usage, so this choice is the usage. System sounds (`USAGE_ASSISTANCE_SONIFICATION`) is not offered:
 * phones keep it low, Pixel aliases it to the ringer and Samsung mutes it on vibrate, and with 50 ms clips that
 * left the chimes silent for real users.
 */
internal enum class RecordingSoundVolume(val storageKey: String, val title: String, val description: String) {
    MEDIA("media", "Media volume", "Uses the media volume, so chimes still play when your phone is on vibrate."),
    RINGER("ringer", "Ringer and notifications", "Uses the notification volume, so chimes go quiet when your phone is on vibrate or silent.");

    /** Exhaustive over the choices: a new member does not compile until it names its usage. */
    val usage: Int
        get() = when (this) {
            MEDIA -> AudioAttributes.USAGE_MEDIA
            RINGER -> AudioAttributes.USAGE_NOTIFICATION
        }

    companion object {
        val DEFAULT = MEDIA
        fun fromStorage(value: String?): RecordingSoundVolume = entries.firstOrNull { it.storageKey == value } ?: DEFAULT
    }
}
