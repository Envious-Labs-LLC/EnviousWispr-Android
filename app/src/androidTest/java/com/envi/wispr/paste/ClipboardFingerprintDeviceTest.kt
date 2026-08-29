package com.envi.wispr.paste

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.os.PersistableBundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.envi.wispr.ui.SettingsActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ClipboardFingerprintDeviceTest {
    @Test
    fun distinguishesPlainAndRichClipsWithTheSameVisibleText() {
        val plain = ClipData.newPlainText("label", "same")
        val rich = ClipData.newHtmlText("label", "same", "<b>same</b>")

        assertNotEquals(ClipboardFingerprint.from(plain), ClipboardFingerprint.from(rich))
    }

    @Test
    fun includesEveryItemUriAndIntentWithoutCoercingThemToText() {
        val clip = ClipData.newPlainText("label", "first")
        clip.addItem(ClipData.Item(Uri.parse("content://example/second")))
        clip.addItem(ClipData.Item(Intent("example.action.TEST").setData(Uri.parse("app://third"))))

        val fingerprint = ClipboardFingerprint.from(clip)!!

        assertEquals(3, fingerprint.items.size)
        assertEquals("content://example/second", fingerprint.items[1].uri)
        assertEquals("app://third", Intent.parseUri(fingerprint.items[2].intentUri, 0).dataString)
        assertNull(ClipboardFingerprint.from(null))
    }

    @Test
    fun identicalLookingClipWithADifferentOwnerTokenIsNotOurs() {
        fun ownedClip(token: String) = enviousWisprTextClip("same", token)
        val ours = ownedClip("take-one")
        val newer = ownedClip("take-two")

        assertEquals(true, ours.isOwnedBy("take-one", ClipboardFingerprint.from(ours)))
        assertEquals(false, newer.isOwnedBy("take-one", ClipboardFingerprint.from(ours)))
    }

    @Test
    fun sameOwnerTokenWithNewDescriptionMetadataIsNotOurs() {
        val original = enviousWisprTextClip("same", "take-one")
        val newer = enviousWisprTextClip("same", "take-one").apply {
            description.extras = PersistableBundle().apply {
                putBoolean("clipboard_manager_metadata", true)
            }
        }

        assertEquals(false, newer.isOwnedBy("take-one", ClipboardFingerprint.from(original)))
    }

    @Test
    fun extrasArraysUseCollisionSafeFraming() {
        val first = enviousWisprTextClip("same", "take-one").apply {
            description.extras = PersistableBundle().apply {
                putStringArray("items", arrayOf("a|java.lang.String:b"))
            }
        }
        val second = enviousWisprTextClip("same", "take-one").apply {
            description.extras = PersistableBundle().apply {
                putStringArray("items", arrayOf("a", "b"))
            }
        }

        assertNotEquals(
            ClipboardFingerprint.from(first),
            ClipboardFingerprint.from(second),
        )
    }

    @Test
    fun ownerTokenAndFingerprintSurviveTheAndroidClipboardRoundTrip() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val activity = instrumentation.startActivitySync(
            Intent(context, SettingsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        val clipboard = context.getSystemService(ClipboardManager::class.java)
        val previous = clipboard.primaryClip

        try {
            val clip = enviousWisprTextClip("round trip", "round-trip-token")
            val expected = ClipboardFingerprint.from(clip)

            clipboard.setPrimaryClip(clip)
            instrumentation.waitForIdleSync()

            val actual = clipboard.primaryClip
            assertEquals(expected, ClipboardFingerprint.from(actual))
            assertTrue(actual.isOwnedBy("round-trip-token", expected))
        } finally {
            if (previous == null) {
                clipboard.clearPrimaryClip()
            } else {
                clipboard.setPrimaryClip(previous)
            }
            instrumentation.runOnMainSync { activity.finish() }
        }
    }
}
