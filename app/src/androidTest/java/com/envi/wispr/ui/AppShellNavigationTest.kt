package com.envi.wispr.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Product Outcome. When this fails, opening a settings page and coming back drops the user somewhere
 * other than where they were, or leaves them on a page with no way out.
 *
 * **This is the repo's first test that can press a button** (#48). Everything about `ui/AppShell.kt`
 * was covered either by a person driving the phone, which is the right oracle for how a screen FEELS and
 * the wrong one for a regression that appears months later, or by reading the file as source TEXT in
 * `paste/AutoPasteWiringTest`, which is a drift guard over string literals and cannot press anything.
 *
 * It drives the REAL activity rather than a harness. `AppShell` takes a ui state, a dozen callbacks and
 * queries WorkManager, so standing up a fake would mostly test the fake
 * (`testing-philosophy.md`: a unit test that stubs the service is a Harness Contract test, whatever it
 * is named). `createAndroidComposeRule` launches what the user launches.
 */
@RunWith(AndroidJUnit4::class)
class AppShellNavigationTest {

    @get:Rule val composeRule = createAndroidComposeRule<SettingsActivity>()

    /**
     * Get to the shell, and REFUSE to continue if that did not work.
     *
     * Onboarding replaces the whole shell and returns early, so a device that has not completed it has
     * no navigation to drive. An earlier version skipped that case with `assumeTrue`, and that was a
     * false green of the worst kind: the Android XML writer reports an assumption failure as a PASS with
     * `failures="0"` and `skipped="0"`, so two tests that never executed a single assertion read as two
     * green rows. Two mutation controls, one disabling the back handler and one making the back arrow do
     * nothing, both came back green before this was found.
     *
     * The scenario is stageable, so it is staged rather than skipped: onboarding offers "Set up later".
     * And the precondition is ASSERTED, because a test that configures its own precondition and does not
     * check it landed goes on to exercise the default and pass having tested nothing
     * (`validation-discipline.md` RULE: verify-the-feature-not-the-crash).
     */
    @Before
    fun reachTheShell() {
        composeRule.waitForIdle()
        if (present { composeRule.onNodeWithText("Set up later") }) {
            composeRule.onNodeWithText("Set up later").performClick()
            composeRule.waitForIdle()
        }
        // A page left open by the previous test would make this one start somewhere it did not choose.
        // Observed while controlling the back arrow: with the arrow broken, the test that uses it left
        // the page open and the NEXT test failed on its precondition rather than on its own subject.
        // Order dependence between tests is a defect in the suite even when every test passes.
        if (present { composeRule.onNodeWithContentDescription("Back") }) {
            composeRule.onNodeWithContentDescription("Back").performClick()
            composeRule.waitForIdle()
        }
        composeRule.onNodeWithContentDescription("Open settings menu")
            .assertExists("the shell never appeared, so nothing below tested navigation")
    }

    /** Whether the node the block selects is on screen, without throwing when it is not. */
    private fun present(select: () -> androidx.compose.ui.test.SemanticsNodeInteraction): Boolean =
        runCatching { select().assertExists() }.isSuccess

    @Test
    fun aSettingsPageTakesOverTheBarsAndBackReturnsToTheTabYouLeft() {
        // Leave the landing tab, so "returned to where I was" is a real claim rather than the default.
        composeRule.onNodeWithText("Transcription").performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithContentDescription("Open settings menu").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Storage").performClick()
        composeRule.waitForIdle()

        // On a settings page: a way out, and no tab bar behind it.
        composeRule.onNodeWithContentDescription("Back").assertIsDisplayed()
        composeRule.onNodeWithText("Space used by files in the models folder.").assertIsDisplayed()
        if (present { composeRule.onNodeWithText("AI Polish") }) {
            throw AssertionError("the tab bar must not sit under a settings page: 'AI Polish' is still on screen")
        }

        // The SYSTEM back gesture, not the arrow. Both must work, and this is the one a person testing
        // by hand is least likely to try every time.
        composeRule.runOnUiThread { composeRule.activity.onBackPressedDispatcher.onBackPressed() }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("AI Polish").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Open settings menu").assertIsDisplayed()
        if (present { composeRule.onNodeWithContentDescription("Back") }) {
            throw AssertionError("the way out must be gone once there is nothing to leave: 'Back' is still on screen")
        }
    }

    @Test
    fun theBackArrowAlsoLeavesASettingsPage() {
        composeRule.onNodeWithContentDescription("Open settings menu").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Storage").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription("Back").performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithContentDescription("Open settings menu").assertIsDisplayed()
        composeRule.onNodeWithText("AI Polish").assertIsDisplayed()
    }
}
