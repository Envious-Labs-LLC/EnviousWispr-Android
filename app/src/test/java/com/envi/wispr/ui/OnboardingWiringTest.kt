package com.envi.wispr.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** DRIFT GUARD for three founder findings on build 121 (2026-09-14), so a later edit cannot quietly undo them. */
class OnboardingWiringTest {
    private val screen = File("src/main/java/com/envi/wispr/ui/OnboardingScreen.kt").readText()
    private val warmUp = File("src/main/java/com/envi/wispr/ui/EngineWarmUp.kt").readText()

    @Test
    fun backNeverSkipsSetupAndNeverDismissesIt() {
        val back = screen.substringAfter("BackHandler(").substringBefore("\n    }")
        assertFalse("Back must not dismiss setup", back.contains("onDismiss"))
        assertTrue(back.contains("enabled = stage != OnboardingStage.WELCOME"))
        assertTrue(back.contains("OnboardingStage.PERMISSIONS.ordinal"))
        // "Set up later" remains the one dismissal.
        assertTrue(screen.contains("TextButton(onClick = onDismiss"))
    }

    @Test
    fun theEnginesWarmWhileThePermissionsAndPracticeScreensShow() {
        assertTrue(screen.contains("stage == OnboardingStage.PERMISSIONS || stage == OnboardingStage.PRACTICE"))
        assertTrue(screen.contains("if (warming) model.warmEngines()"))
        assertTrue(screen.contains("onDispose { if (warming) model.coolEngines() }"))
        // Both engines, by binding, in their own processes; the polish load follows the user's policy.
        assertTrue(warmUp.contains("Intent(context, AsrService::class.java)"))
        assertTrue(warmUp.contains("Intent(context, PolishService::class.java)"))
        assertTrue(warmUp.contains("warmUpWithPolicy(policy)"))
    }

    @Test
    fun thePermissionsScreenCarriesNoFakeTextBox() {
        assertFalse(screen.contains("Write a message"))
        assertFalse(screen.contains("HowTheLipsWork"))
    }
}
