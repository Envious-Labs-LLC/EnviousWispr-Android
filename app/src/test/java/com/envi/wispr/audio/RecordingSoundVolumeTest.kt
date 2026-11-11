package com.envi.wispr.audio

import android.media.AudioAttributes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/** Product Outcome (#526): chimes play on a volume people keep audible, never the System sounds volume. */
class RecordingSoundVolumeTest {
    @Test fun eachChoicePlaysOnItsOwnPhoneVolume() {
        assertEquals(AudioAttributes.USAGE_MEDIA, RecordingSoundVolume.MEDIA.usage)
        assertEquals(AudioAttributes.USAGE_NOTIFICATION, RecordingSoundVolume.RINGER.usage)
        RecordingSoundVolume.entries.forEach { assertNotEquals(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION, it.usage) }
    }

    @Test fun storedNamesAreStable() {
        assertEquals(listOf("media", "ringer"), RecordingSoundVolume.entries.map { it.storageKey })
    }
}
