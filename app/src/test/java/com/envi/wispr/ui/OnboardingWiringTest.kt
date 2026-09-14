package com.envi.wispr.ui

import org.junit.Assert.assertEquals
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
        // One screen back: practice to the demo, the demo to the permissions.
        assertTrue(back.contains("OnboardingStage.PRACTICE -> OnboardingStage.DEMO.ordinal"))
        assertTrue(back.contains("OnboardingStage.DEMO -> OnboardingStage.PERMISSIONS.ordinal"))
        // "Set up later" remains the one dismissal.
        assertTrue(screen.contains("TextButton(onClick = onDismiss"))
    }

    @Test
    fun theEnginesWarmWhileThePermissionsAndPracticeScreensShow() {
        assertTrue(screen.contains("stage == OnboardingStage.PERMISSIONS || stage == OnboardingStage.DEMO || stage == OnboardingStage.PRACTICE"))
        // Started and stopped with the app, not only with the screen: Home or the lock releases both.
        assertTrue(screen.contains("LifecycleStartEffect(warming)"))
        assertTrue(screen.contains("if (warming) model.warmEngines()"))
        assertTrue(screen.contains("onStopOrDispose { if (warming) model.coolEngines() }"))
        // Both engines, by binding, in their own processes; the polish load follows the user's policy.
        assertTrue(warmUp.contains("Intent(context, AsrService::class.java)"))
        assertTrue(warmUp.contains("Intent(context, PolishService::class.java)"))
        assertTrue(warmUp.contains("warmUpWithPolicy(policy)"))
    }

    @Test
    fun theDemoPlaysBetweenThePermissionsAndPractice() {
        // "Try dictation" opens the demo; the demo's end and its Skip both open practice.
        assertTrue(screen.contains("SetupButton(\"Try dictation\", fill, ready) { onStepChange(OnboardingStage.DEMO.ordinal) }"))
        assertTrue(screen.contains("onStepChange(OnboardingStage.PRACTICE.ordinal)"))
        // Setup calls the button one thing, the founder's word (2026-09-14).
        val copy = Regex("\"[^\"]*\"").findAll(screen).map { it.value }.joinToString("\n")
        assertFalse("setup copy must say bubble, not lips: $copy", copy.contains("lips"))
    }

    @Test
    fun theStoredStepCanReachTheLastStage() {
        val preferences = File("src/main/java/com/envi/wispr/settings/AppPreferences.kt").readText()
        assertTrue(preferences.contains("step.coerceIn(0, OnboardingStage.entries.lastIndex)"))
        assertEquals(OnboardingStage.PRACTICE, OnboardingStage.entries.last())
    }

    @Test
    fun thePermissionsScreenCarriesNoFakeTextBox() {
        assertFalse(screen.contains("Write a message"))
        assertFalse(screen.contains("HowTheLipsWork"))
    }
}
