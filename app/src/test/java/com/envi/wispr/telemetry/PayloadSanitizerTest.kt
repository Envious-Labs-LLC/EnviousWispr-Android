package com.envi.wispr.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Product-outcome test (issue #176): when this fails, a user's words or a key can reach a vendor. One
 * isolated witness per rule, each chosen so no OTHER rule catches it, so removing that rule turns
 * exactly its witness green-to-red. Expected values are literals.
 */
class PayloadSanitizerTest {

    @Test
    fun anUnknownKeyIsDroppedWhateverItsValue() {
        val out = PayloadSanitizer.sanitizeProperties(mapOf("transcript" to "hi", "result" to "completed"))
        assertEquals(mapOf("result" to "completed"), out)
    }

    @Test
    fun aShortTranscriptUnderAKnownTokenKeyIsDroppedBecauseItIsNotAToken() {
        // "hi there" is 8 characters, well under 100: a length rule alone would let it through.
        assertNull(PayloadSanitizer.sanitizeValue("reason", "hi there"))
        assertNull(PayloadSanitizer.sanitizeValue("reason", "Meet me at 6"))
        assertEquals("ASR_FAILED", PayloadSanitizer.sanitizeValue("reason", "ASR_FAILED"))
    }

    @Test
    fun aMultilingualTranscriptIsNeverAToken() {
        assertNull(PayloadSanitizer.sanitizeValue("reason", "नमस्ते"))
        assertNull(PayloadSanitizer.sanitizeValue("reason", "こんにちは"))
        assertNull(PayloadSanitizer.sanitizeValue("target_app", "こんにちは"))
    }

    @Test
    fun boundedKeysAdmitAPackageNameAndADeviceModelButNotProse() {
        assertEquals("com.google.android.apps.messaging", PayloadSanitizer.sanitizeValue("target_app", "com.google.android.apps.messaging"))
        assertEquals("SM-S938B", PayloadSanitizer.sanitizeValue("device_model", "SM-S938B"))
        assertEquals("Galaxy S26 Ultra", PayloadSanitizer.sanitizeValue("device_model", "Galaxy S26 Ultra"))
        assertNull("over 100 characters is dropped, not truncated", PayloadSanitizer.sanitizeValue("device_model", "x".repeat(101)))
    }

    @Test
    fun everyBoundedKeyHasAShapeSoShortProseCannotRideUnderIt() {
        // G3 refutation: a length rule alone admits "Meet me at six" under a package-name key.
        assertNull(PayloadSanitizer.sanitizeValue("target_app", "Meet me at six"))
        assertNull(PayloadSanitizer.sanitizeValue("target_app", "chrome"))
        assertEquals("com.envi.wispr", PayloadSanitizer.sanitizeValue("target_app", "com.envi.wispr"))
        assertNull(PayloadSanitizer.sanitizeValue("take_id", "not a uuid"))
        assertNull(PayloadSanitizer.sanitizeValue("take_id", "0A1B2C3D-4E5F-4A6B-8C7D-9E8F7A6B5C4D"))
        assertEquals("0a1b2c3d-4e5f-4a6b-8c7d-9e8f7a6b5c4d", PayloadSanitizer.sanitizeValue("take_id", "0a1b2c3d-4e5f-4a6b-8c7d-9e8f7a6b5c4d"))
        assertNull("six words is prose, not a model name", PayloadSanitizer.sanitizeValue("device_model", "please call me back at six"))
        assertNull(PayloadSanitizer.sanitizeValue("os_version", "16 and a half"))
        assertEquals("16", PayloadSanitizer.sanitizeValue("os_version", "16"))
    }

    @Test
    fun aRenamedEarbudNameNeverLeavesUnderAnyKey() {
        // The product name in the stored pick used to be `type|name`; no key admits it.
        assertNull(PayloadSanitizer.sanitizeValue("input_device", "7|Saurabh's AirPods Pro"))
        assertEquals("picked", PayloadSanitizer.sanitizeValue("input_device", "picked"))
    }

    @Test
    fun numbersAndBooleansPassUntouched() {
        assertEquals(1234L, PayloadSanitizer.sanitizeValue("asr_ms", 1234L))
        assertEquals(0.42f, PayloadSanitizer.sanitizeValue("peak_amplitude", 0.42f))
        assertEquals(true, PayloadSanitizer.sanitizeValue("recovered", true))
    }

    @Test
    fun keyShapedStringsAreRedactedInFreeText() {
        assertEquals("[REDACTED]", PayloadSanitizer.redactPatterns("token sk-abcdefghijklmnopqrstuvwxyz1234"))
        assertEquals("[REDACTED]", PayloadSanitizer.redactPatterns("phc_abcdefghijklmnopqrstuvwxyz"))
        assertEquals("[REDACTED]", PayloadSanitizer.redactPatterns("AIzaSyAbcdefghijklmnopqrstuvwxyz0123456"))
    }

    @Test
    fun aLongHexRunIsRedactedByThePatternPass() {
        assertEquals("[REDACTED]", PayloadSanitizer.redactPatterns("id 0123456789abcdef0123456789abcdef"))
        assertEquals("id 0123456789abcdef", PayloadSanitizer.redactPatterns("id 0123456789abcdef"))
    }

    @Test
    fun anEmailIsRedactedByThePatternPass() {
        assertEquals("[REDACTED]", PayloadSanitizer.redactPatterns("mail saurabh@example.com now"))
    }

    @Test
    fun androidPrivateAndSharedStoragePathsAreScrubbedInPlace() {
        assertEquals("open [PATH] failed", PayloadSanitizer.redactPatterns("open /data/user/0/com.envi.wispr/files/x.pcm failed"))
        assertEquals("open [PATH] failed", PayloadSanitizer.redactPatterns("open /data/data/com.envi.wispr/cache/y failed"))
        assertEquals("read [PATH]", PayloadSanitizer.redactPatterns("read /storage/emulated/0/Download/notes.txt"))
        assertEquals("read [PATH]", PayloadSanitizer.redactPatterns("read /sdcard/EnviousWispr/debug.log"))
    }

    @Test
    fun aContentUriIsScrubbedInPlace() {
        assertEquals("uri [PATH]", PayloadSanitizer.redactPatterns("uri content://com.android.providers.media.documents/document/image%3A1234"))
    }

    @Test
    fun aUrlWithCredentialsQueryOrFragmentIsRedacted() {
        assertEquals("[REDACTED]", PayloadSanitizer.redactPatterns("https://user:pw@host.example/path"))
        assertEquals("[REDACTED]", PayloadSanitizer.redactPatterns("https://host.example/path?token=abc"))
        assertEquals("[REDACTED]", PayloadSanitizer.redactPatterns("https://host.example/path#secret"))
    }

    @Test
    fun sdkContextKeysOutsideTheAllowlistAreDroppedAndAllowedOnesKept() {
        val out = PayloadSanitizer.sanitizeProperties(
            mapOf(
                "\$user_agent" to "Dalvik/2.1.0 (Linux; U; Android 16; SM-S938B Build/AP4A)",
                "\$network_carrier" to "T-Mobile",
                "\$device_name" to "Saurabh's phone",
                "\$device_model" to "SM-S938B",
                "\$os_version" to "16",
                "\$screen_width" to 1344,
            ),
        )
        assertEquals(mapOf("\$device_model" to "SM-S938B", "\$os_version" to "16"), out)
    }
}
