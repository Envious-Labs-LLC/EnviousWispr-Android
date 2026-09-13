package com.envi.wispr.ui

import com.envi.wispr.paste.AutoPasteAvailability
import org.junit.Assert.assertEquals
import org.junit.Test

/** Product Outcome: setup cannot skip missing requirements or overwrite earlier practice text. */
class OnboardingPolicyTest {
    private val ready = AppReadiness(microphoneGranted = true, speechModelReady = true, polishModelReady = true)

    @Test fun restoredPracticeReturnsToDownloadsWhenAModelIsMissing() {
        assertEquals(OnboardingStage.DOWNLOADS, onboardingStage(3, ready.copy(polishModelReady = false), AutoPasteAvailability.LIVE))
    }

    @Test fun enabledButDisconnectedAccessibilityDoesNotAllowPractice() {
        assertEquals(OnboardingStage.PERMISSIONS, onboardingStage(3, ready, AutoPasteAvailability.PERMITTED_NOT_RUNNING))
    }

    @Test fun notificationsAreOptionalButMicrophoneIsRequired() {
        assertEquals(OnboardingStage.PRACTICE, onboardingStage(3, ready, AutoPasteAvailability.LIVE))
        assertEquals(OnboardingStage.PERMISSIONS, onboardingStage(3, ready.copy(microphoneGranted = false), AutoPasteAvailability.LIVE))
    }

    @Test fun verifiedDownloadsAdvanceAndWelcomeRemainsAvailable() {
        assertEquals(OnboardingStage.PERMISSIONS, onboardingStage(1, ready, AutoPasteAvailability.LIVE))
        assertEquals(OnboardingStage.WELCOME, onboardingStage(0, ready, AutoPasteAvailability.LIVE))
    }

    @Test fun secondPracticeTakePreservesTheFirstAndAddsSpacing() {
        assertEquals("Call Grandma. Bring milk." to 25, mergePracticeText("Call Grandma.", 13, 13, "Bring milk."))
    }

    @Test fun dictationReplacesOnlyTheSelectedText() {
        assertEquals("Meet on Sunday morning." to 14, mergePracticeText("Meet on Friday morning.", 8, 14, "Sunday"))
    }

    @Test fun emptyPracticeAndReversedSelectionAreHandled() {
        assertEquals("Hello." to 6, mergePracticeText("", 0, 0, "Hello."))
        assertEquals("Hello Sam." to 10, mergePracticeText("Hello Pat.", 10, 6, "Sam."))
    }
    @Test fun anOlderModelCheckCannotRevokeANewerPermissionAnswer() {
        val granted = AppReadiness(microphoneGranted = true, notificationsGranted = true, accessibilityPermitted = true)
        val checkedBeforeGrant = AppReadiness(speechModelReady = true, polishModelReady = true)
        assertEquals(granted.copy(speechModelReady = true, polishModelReady = true), granted.withVerifiedModels(checkedBeforeGrant))
    }

}
