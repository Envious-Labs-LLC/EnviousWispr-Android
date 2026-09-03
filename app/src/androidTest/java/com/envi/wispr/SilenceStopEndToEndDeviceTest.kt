package com.envi.wispr

import android.content.Intent
import androidx.test.platform.app.InstrumentationRegistry
import com.envi.wispr.ui.DictationSessionService
import org.junit.Test

/**
 * Drives one whole dictation the way the side button does, and stops there.
 *
 * **This test asserts nothing, on purpose.** Its oracle is a file the editor writes, and that file lives
 * in the test package's private storage while this code runs in the APP's process, so it cannot be read
 * from here. The caller reads it with `run-as com.envi.wispr.test cat files/paste-target-received.txt`.
 *
 * Writing the assertion here anyway would be worse than leaving it out: it would read a static that is
 * always null from this process and pass, which is exactly how an earlier version of this test skipped
 * itself in seven milliseconds and looked green.
 *
 * It exists because the shell cannot start `DictationSessionService`, which is not exported, while
 * instrumentation runs inside the app and can.
 */
class SilenceStopEndToEndDeviceTest {

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun startInTestPackage(activity: String) {
        context.startActivity(
            Intent()
                .setClassName("com.envi.wispr.test", "com.envi.wispr.$activity")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    @Test
    fun driveOneSilenceStoppedDictationIntoTheEditor() {
        startInTestPackage("PasteTargetActivity")
        Thread.sleep(2_500)

        // The same command the Samsung side button sends.
        DictationSessionService.sendCommand(context, DictationSessionService.ACTION_TOGGLE)
        Thread.sleep(2_500)

        startInTestPackage("SpeakerPlaybackActivity")

        // Speech, then the silence that ends the take, then transcription, polish and insertion.
        Thread.sleep(45_000)
    }
}
