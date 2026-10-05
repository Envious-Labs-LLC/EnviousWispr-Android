package com.envi.wispr.audio

import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.security.MessageDigest

/** Drift Guard: Android ships the exact twelve macOS pairs, rather than similar replacement sounds. */
class RecordingSoundAssetsTest {
    @Test fun everyNamedPairHasItsOriginalMacStartAndStopBytes() {
        val manifest = JSONArray(javaClass.getResource("/recording-sounds/mac-assets.json")!!.readText())
        assertEquals(24, manifest.length())
        assertEquals(12, RecordingSoundPairing.entries.size)
        for (index in 0 until manifest.length()) {
            val item = manifest.getJSONObject(index)
            val name = item.getString("name")
            val key = name.substringBeforeLast('_')
            val pair = RecordingSoundPairing.entries.single { it.storageKey == key }
            val moment = name.substringAfterLast('_').substringBefore('.')
            val snake = key.replace(Regex("(?<!^)(?=[A-Z])"), "_").lowercase()
            val file = File("src/main/res/raw/chime_${snake}_$moment.wav")
            val hash = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
            assertEquals("${pair.title} $moment must be the Mac asset", item.getString("sha256"), hash)
            assertEquals(32, pair.waveform.size)
            assertTrue(pair.waveform.all { it in 0f..1f })
        }
    }
}
