package com.envi.wispr.llama

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Harness Contract (#532): verifies the S1 JNI bridge reads Java UTF-16 as standard UTF-8 (not
 * modified UTF-8) and decodes standard UTF-8 back losslessly, including surrogate pairs and U+FFFD
 * substitution on unpaired surrogates and malformed UTF-8 byte sequences.
 */
@RunWith(AndroidJUnit4::class)
class S1NativeDeviceTest {
    @Test
    fun textCrossesTheBridgeAsStandardUtf8() {
        assertEquals("f09f9880", S1Native.testUtf8Hex("\uD83D\uDE00"))
        assertEquals("e4b8ad", S1Native.testUtf8Hex("中"))
        assertEquals("f0a0ae9f", S1Native.testUtf8Hex("\uD842\uDF9F"))
        assertEquals("", S1Native.testUtf8Hex(""))

        for (text in listOf("", "plain", "Grüße, Frau Krüger", "中文 名字", "😀 ok 👍🏽", "\uD842\uDF9F 吉")) {
            assertEquals(text, S1Native.testTextRoundTrip(text))
        }

        assertEquals("unpaired high surrogate becomes U+FFFD", "a\uFFFDb", S1Native.testTextRoundTrip("a\uD83Db"))
        assertEquals("trailing high surrogate becomes U+FFFD", "a\uFFFD", S1Native.testTextRoundTrip("a\uD83D"))
        assertEquals("unpaired low surrogate becomes U+FFFD", "\uFFFDz", S1Native.testTextRoundTrip("\uDC00z"))
    }

    @Test
    fun malformedUtf8DecodesToReplacementCharacter() {
        val cases = listOf(
            "a\uFFFD" to byteArrayOf('a'.code.toByte(), 0x80.toByte()),
            "\uFFFD\uFFFD\uFFFD" to byteArrayOf(0xF0.toByte(), 0x9F.toByte(), 0x98.toByte()),
            "\uFFFD\uFFFD" to byteArrayOf(0xC0.toByte(), 0xAF.toByte()),
            "\uFFFD\uFFFD\uFFFD" to byteArrayOf(0xED.toByte(), 0xA0.toByte(), 0x80.toByte()),
            "\uFFFD\uFFFD\uFFFD\uFFFD" to byteArrayOf(0xF4.toByte(), 0x90.toByte(), 0x80.toByte(), 0x80.toByte()),
        )
        for ((expected, raw) in cases) {
            assertEquals(expected, S1Native.testInvalidUtf8ToJava(raw))
        }
    }
}
