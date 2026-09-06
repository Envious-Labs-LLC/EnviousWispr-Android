package com.envi.wispr.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Product Outcome. When this fails a user following the setup instructions is told to press a control
 * that is not called that anywhere on their phone.
 *
 * Samsung calls it the side button. The app called it the right button in four places and the side
 * button everywhere else, including in its own release notes, so the two names disagreed with each
 * other as well as with the phone (issue #25).
 */
class TriggerNameTest {

    private val userFacing = listOf(
        "ui/AppShell.kt",
        "ui/SettingsPages.kt",
        "about/ReleaseNotes.kt",
    ).associateWith { File("src/main/java/com/envi/wispr/$it").readText() }

    @Test
    fun nothingAUserReadsCallsItTheRightButton() {
        userFacing.forEach { (name, source) ->
            assertFalse(
                "$name still calls the trigger the right button, which is not what the phone calls it",
                Regex("[Rr]ight.?[Bb]utton").containsMatchIn(source),
            )
        }
    }

    @Test
    fun theSetupInstructionNamesTheControlSamsungNames() {
        val shell = userFacing.getValue("ui/AppShell.kt")
        assertTrue(
            "the insertion setup step must name the side button",
            shell.contains("Double-press the side button"),
        )
        assertTrue(
            "and both auto-insert lines must agree with it",
            shell.contains("\"Side-button auto-insert ready\"") &&
                shell.contains("\"Enable side-button auto-insert\""),
        )
        assertTrue(
            "as must the permissions page",
            userFacing.getValue("ui/SettingsPages.kt").contains("\"Ready for side-button dictation\""),
        )
    }
}
