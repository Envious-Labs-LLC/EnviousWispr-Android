package com.envi.wispr.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.systemBars
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.SoftwareKeyboardController
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import org.junit.Before
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasText
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.envi.wispr.paste.AutoPasteAvailability
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import java.util.concurrent.atomic.AtomicReference
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Product Outcome: a short setup window must not hide the permission actions or its way out. */
@RunWith(AndroidJUnit4::class)
class OnboardingLayoutDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @Before fun matchProductionWindowInsets() { compose.runOnUiThread { compose.activity.enableEdgeToEdge(); compose.activity.window.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE) } }
    private val modelsReady = AppReadiness(speechModelReady = true, polishModelReady = true)

    @Test fun allThreeGrantActionsAndDismissWorkInAShortViewport() {
        val called = mutableListOf<String>()
        compose.setContent {
            MaterialTheme {
                val bars = WindowInsets.systemBars.asPaddingValues()
                Box(Modifier.fillMaxWidth().requiredHeight(280.dp + bars.calculateTopPadding() + bars.calculateBottomPadding() + 28.dp)) {
                    OnboardingScreen(
                        step = OnboardingStage.PERMISSIONS.ordinal,
                        readiness = modelsReady,
                        autoPaste = AutoPasteAvailability.NOT_PERMITTED,
                        onStepChange = {}, onDismiss = { called += "dismiss" },
                        onRequestMicrophone = { called += "microphone" },
                        onRequestNotifications = { called += "notifications" },
                        onOpenAccessibility = { called += "accessibility" }, onComplete = {},
                    )
                }
            }
        }
        for (title in listOf("Microphone", "Accessibility", "Notifications")) {
            compose.onNodeWithContentDescription("Grant $title").performScrollTo().assertIsDisplayed().performClick()
        }
        compose.onNodeWithText("See how it works").performScrollTo().assertIsDisplayed().assertIsNotEnabled()
        compose.onNodeWithText("Set up later").performScrollTo().assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(listOf("microphone", "accessibility", "notifications", "dismiss"), called) }
    }

    @Test fun permissionActionsRemainReachableAcrossTheHeightBoundaryAndLargerText() {
        val height = mutableStateOf(559)
        val fontScale = mutableStateOf(1f)
        compose.setContent {
            val current = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(current.density, fontScale.value)) {
                MaterialTheme {
                    val bars = WindowInsets.systemBars.asPaddingValues()
                    Box(Modifier.fillMaxWidth().requiredHeight(height.value.dp + bars.calculateTopPadding() + bars.calculateBottomPadding() + 28.dp)) {
                        OnboardingScreen(
                            step = OnboardingStage.PERMISSIONS.ordinal,
                            readiness = modelsReady.copy(microphoneGranted = true, notificationsGranted = true),
                            autoPaste = AutoPasteAvailability.PERMITTED_NOT_RUNNING,
                            onStepChange = {}, onDismiss = {}, onRequestMicrophone = {},
                            onRequestNotifications = {}, onOpenAccessibility = {}, onComplete = {},
                        )
                    }
                }
            }
        }
        for (h in listOf(559, 560, 561, 280)) {
            compose.runOnIdle { height.value = h; fontScale.value = if (h == 280) 1.5f else 1f }
            compose.onNodeWithContentDescription("Grant Accessibility").performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("See how it works").assertIsNotEnabled()
            val footer = compose.onNodeWithText("Set up later")
            if (h < 560) footer.performScrollTo()
            footer.assertIsDisplayed()
        }
    }
    @Test fun welcomeAndDownloadsKeepTheirFootersReachableAndResetStageScrolling() {
        val step = mutableStateOf(OnboardingStage.WELCOME.ordinal)
        compose.setContent {
            MaterialTheme {
                val bars = WindowInsets.systemBars.asPaddingValues()
                Box(Modifier.fillMaxWidth().requiredHeight(280.dp + bars.calculateTopPadding() + bars.calculateBottomPadding() + 28.dp)) {
                    OnboardingScreen(step = step.value, readiness = AppReadiness(), autoPaste = AutoPasteAvailability.NOT_PERMITTED,
                        onStepChange = { step.value = it }, onDismiss = {}, onRequestMicrophone = {},
                        onRequestNotifications = {}, onOpenAccessibility = {}, onComplete = {})
                }
            }
        }
        compose.onNodeWithText("Get Started!").performScrollTo().assertIsDisplayed()
        compose.runOnIdle { step.value = OnboardingStage.DOWNLOADS.ordinal }
        // Download status comes from the real worker. Its action can be Pause, Resume or Retry.
        compose.onNode(hasText("Pause download") or hasText("Resume download") or hasText("Retry download")).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Use mobile data").performScrollTo().assertIsDisplayed()
        // A fresh stage starts at its top rather than keeping the prior stage's bottom offset.
        compose.runOnIdle { step.value = OnboardingStage.WELCOME.ordinal }
        compose.onNodeWithText("Speak naturally.\nGet polished text.").assertIsDisplayed()
    }

    @Test fun practiceDraftSurvivesAvailableHeightChanges() {
        val height = mutableStateOf(700)
        compose.setContent {
            MaterialTheme {
                Box(Modifier.fillMaxWidth().requiredHeight(height.value.dp)) {
                    OnboardingScreen(step = OnboardingStage.PRACTICE.ordinal,
                        readiness = modelsReady.copy(microphoneGranted = true), autoPaste = AutoPasteAvailability.LIVE,
                        onStepChange = {}, onDismiss = {}, onRequestMicrophone = {},
                        onRequestNotifications = {}, onOpenAccessibility = {}, onComplete = {})
                }
            }
        }
        compose.onNodeWithTag(OnboardingViewModel.PRACTICE_FIELD_ID).performScrollTo().performTextReplacement("My draft stays here.")
        compose.runOnIdle { height.value = 550 }
        compose.onNodeWithTag(OnboardingViewModel.PRACTICE_FIELD_ID).performScrollTo().assertTextEquals("My draft stays here.")
        compose.onNodeWithText("Skip practice").performScrollTo().assertIsDisplayed()
        compose.runOnIdle { height.value = 700 }
        compose.onNodeWithTag(OnboardingViewModel.PRACTICE_FIELD_ID).performScrollTo().assertTextEquals("My draft stays here.")
    }

    @Test fun demoKeepsItsSkipActionReachableInAShortViewport() {
        compose.setContent {
            MaterialTheme {
                val bars = WindowInsets.systemBars.asPaddingValues()
                Box(Modifier.fillMaxWidth().requiredHeight(280.dp + bars.calculateTopPadding() + bars.calculateBottomPadding() + 28.dp)) {
                    OnboardingScreen(step = OnboardingStage.DEMO.ordinal,
                        readiness = modelsReady.copy(microphoneGranted = true), autoPaste = AutoPasteAvailability.LIVE,
                        onStepChange = {}, onDismiss = {}, onRequestMicrophone = {},
                        onRequestNotifications = {}, onOpenAccessibility = {}, onComplete = {})
                }
            }
        }
        compose.onNodeWithText("Skip to practice").assertIsDisplayed().assertIsEnabled()
    }

    // Rig: a docked software keyboard. On an AVD use show_ime_with_hard_keyboard=1 and
    // stylus_handwriting_enabled=0; the floating handwriting toolbar has no bottom IME inset.
    @Test fun realKeyboardInsetsSwitchThePageToShortLayoutAndKeepTheDraft() {
        fun imeBottom(): Int = ViewCompat.getRootWindowInsets(compose.activity.window.decorView)
            ?.getInsets(WindowInsetsCompat.Type.ime())?.bottom ?: -1
        val pageWithFooter = hasScrollAction() and hasAnyDescendant(hasText("Skip practice"))
        fun awaitKeyboardAndLayout(open: Boolean) {
            compose.waitUntil(timeoutMillis = 5_000) {
                val keyboardMatches = if (open) imeBottom() > 0 else imeBottom() == 0
                val pages = compose.onAllNodes(pageWithFooter).fetchSemanticsNodes().size
                keyboardMatches && pages == if (open) 1 else 0
            }
        }
        val densityPx = AtomicReference(1f)
        val keyboard = AtomicReference<SoftwareKeyboardController?>()
        var completed = false
        compose.setContent {
            val density = LocalDensity.current
            val controller = LocalSoftwareKeyboardController.current
            SideEffect { densityPx.set(density.density); keyboard.set(controller) }
            MaterialTheme {
                val bars = WindowInsets.systemBars.asPaddingValues()
                Box(Modifier.fillMaxWidth().requiredHeight(700.dp + bars.calculateTopPadding() + bars.calculateBottomPadding() + 28.dp)) {
                    OnboardingScreen(step = OnboardingStage.PRACTICE.ordinal,
                        readiness = modelsReady.copy(microphoneGranted = true), autoPaste = AutoPasteAvailability.LIVE,
                        onStepChange = {}, onDismiss = {}, onRequestMicrophone = {},
                        onRequestNotifications = {}, onOpenAccessibility = {}, onComplete = { completed = true })
                }
            }
        }
        compose.runOnIdle { keyboard.get()!!.hide() }
        awaitKeyboardAndLayout(open = false)
        compose.onNode(pageWithFooter).assertDoesNotExist()
        compose.onNodeWithTag(OnboardingViewModel.PRACTICE_FIELD_ID).performScrollTo().performClick()
        compose.runOnIdle { keyboard.get()!!.show() }
        awaitKeyboardAndLayout(open = true)
        val pageHeight = compose.onNode(pageWithFooter).fetchSemanticsNode().boundsInRoot.height / densityPx.get()
        assertTrue("IME must leave a positive viewport below560dp, observed $pageHeight", pageHeight > 0f && pageHeight < 560f)
        compose.onNodeWithTag(OnboardingViewModel.PRACTICE_FIELD_ID).performScrollTo().performTextReplacement("Keyboard draft stays.")
        compose.runOnIdle { keyboard.get()!!.hide() }
        awaitKeyboardAndLayout(open = false)
        compose.onNodeWithTag(OnboardingViewModel.PRACTICE_FIELD_ID).assertTextEquals("Keyboard draft stays.")
        compose.onNodeWithTag(OnboardingViewModel.PRACTICE_FIELD_ID).performScrollTo().performClick()
        compose.runOnIdle { keyboard.get()!!.show() }
        awaitKeyboardAndLayout(open = true)
        compose.onNodeWithText("Skip practice").performScrollTo().assertIsDisplayed().performClick()
        compose.runOnIdle { assertTrue(completed) }
    }

}
