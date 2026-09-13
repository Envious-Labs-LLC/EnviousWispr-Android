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
        "ui/OnboardingScreen.kt",
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
    fun onboardingUsesItsOwnRecorderWhileSamsungSettingsKeepTheCorrectName() {
        val onboarding = userFacing.getValue("ui/OnboardingScreen.kt")
        assertTrue("Practice must provide its own recording action", onboarding.contains("Start dictation"))
        assertFalse("New users must not be required to own a Samsung phone", onboarding.contains("Double-press the side button"))
        assertTrue(
            "The existing Samsung shortcut status still uses the phone's control name",
            userFacing.getValue("ui/SettingsPages.kt").contains("\"Ready for side-button dictation\""),
        )
    }
}
