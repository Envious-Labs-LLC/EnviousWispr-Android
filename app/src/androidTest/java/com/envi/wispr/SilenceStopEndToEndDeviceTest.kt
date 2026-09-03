package com.envi.wispr

import android.content.Intent
import androidx.test.platform.app.InstrumentationRegistry
import com.envi.wispr.ui.DictationSessionService
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * The whole thing, end to end, driven the way a user drives it.
 *
 * A dictation is started with the same command the side button sends, real speech plays through the
 * speaker, the silence ends the take, and the words are asserted **in the editor's own field** rather
 * than on the clipboard. The clipboard is the fallback and it is populated whether or not a single
 * character reached anything the user was typing into.
 *
 * The editor here is the test fixture rather than a third-party app, which is the one thing this cannot
 * reach while the phone is locked. What it does reach is every stage this change touches.
 */
class SilenceStopEndToEndDeviceTest {

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun aTakeThatEndedOnSilenceLandsItsWordsInTheEditor() {
        // Deliberately NOT gated on PasteAccessibilityService.isBound: that static lives in the app's
        // own process and reads as its default from here, so gating on it skipped this test while the
        // system reported the service bound. A value read in one process is a documented trap in this
        // project. The assertion below is the real check, and a failure to insert is a failure, not a
        // skip.
        // The fixture lives in the TEST package, not the app, so it must be named rather than resolved
        // from the app context.
        context.startActivity(
            Intent()
                .setClassName("com.envi.wispr.test", "com.envi.wispr.PasteTargetActivity")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        )
        Thread.sleep(2_000)
        assumeTrue("the target field must exist", PasteTargetActivity.field != null)
        assertTrue("it must start empty", PasteTargetActivity.currentText().isBlank())

        // The same command the Samsung side button sends.
        DictationSessionService.sendCommand(context, DictationSessionService.ACTION_TOGGLE)
        Thread.sleep(2_500)

        context.startActivity(
            Intent()
                .setClassName("com.envi.wispr.test", "com.envi.wispr.SpeakerPlaybackActivity")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )

        var landed = ""
        for (i in 0 until 900) {
            Thread.sleep(100)
            val text = PasteTargetActivity.currentText()
            if (text.isNotBlank()) { landed = text; break }
        }

        assertTrue(
            "a take that ended on silence must put its words in the editor, the field was empty after 90s",
            landed.isNotBlank(),
        )
        android.util.Log.i("SilenceUat", "The editor received: '$landed'")
    }
}
