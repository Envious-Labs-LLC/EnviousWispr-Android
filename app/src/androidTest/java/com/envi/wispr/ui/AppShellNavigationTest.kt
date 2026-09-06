package com.envi.wispr.ui

import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
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
        if (present(hasText("Set up later"))) {
            composeRule.onNodeWithText("Set up later").performClick()
            composeRule.waitForIdle()
        }
        // A page left open by the previous test would make this one start somewhere it did not choose.
        // Observed while controlling the back arrow: with the arrow broken, the test that uses it left
        // the page open and the NEXT test failed on its precondition rather than on its own subject.
        // Order dependence between tests is a defect in the suite even when every test passes.
        if (present(hasContentDescription("Back"))) {
            composeRule.onNodeWithContentDescription("Back").performClick()
            composeRule.waitForIdle()
        }
        // DO NOT COPY THIS PART. Cleaning up THROUGH a behaviour that is itself under test couples the
        // rows together: breaking the arrow reddens both, not just the row that presses it, so a control
        // says less than it should. It is here because the activity rule does not reset the saved
        // navigation state between rows, and the alternative was leaving the suite order-dependent.
        // Whoever finds a clean reset should take this out.
        composeRule.onNodeWithContentDescription("Open settings menu")
            .assertExists("the shell never appeared, so nothing below tested navigation")
    }

    /**
     * Whether exactly one node matches, WITHOUT swallowing anything else.
     *
     * The first version wrapped `assertExists()` in `runCatching`, which turns every throwable into
     * "absent": an ambiguous match, a synchronisation failure, a broken matcher. In setup that silently
     * skips a required action, and in a negative check it passes for the wrong reason. Counting nodes
     * answers only the question asked, and an ambiguous match is a loud failure rather than a false
     * "no". Absence in the tests themselves is asserted with `assertDoesNotExist`, which is the API for
     * it; this helper exists only for the two SETUP probes, which are genuinely conditional.
     */
    private fun present(matcher: SemanticsMatcher): Boolean {
        val count = composeRule.onAllNodes(matcher).fetchSemanticsNodes().size
        check(count <= 1) { "expected at most one node matching $matcher, found $count" }
        return count == 1
    }

    @Test
    fun aSettingsPageTakesOverTheBarsAndTheSystemBackGestureLeavesIt() {
        // Leave the landing tab, so the app is not sitting on its default while this runs.
        composeRule.onNode(transcriptionTab).performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithContentDescription("Open settings menu").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Storage").performClick()
        composeRule.waitForIdle()

        // On a settings page: a way out, and no tab bar behind it.
        composeRule.onNodeWithContentDescription("Back").assertIsDisplayed()
        composeRule.onNodeWithText("Space used by files in the models folder.").assertIsDisplayed()
        composeRule.onNodeWithText("AI Polish").assertDoesNotExist()

        // The SYSTEM back gesture, not the arrow. Both must work, and this is the one a person testing
        // by hand is least likely to try every time.
        composeRule.runOnUiThread { composeRule.activity.onBackPressedDispatcher.onBackPressed() }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("AI Polish").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Open settings menu").assertIsDisplayed()
        if (present(hasContentDescription("Back"))) {
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

    /**
     * The Transcription tab, used only to move OFF the landing tab so the app is not sitting on its
     * default while a row runs.
     *
     * **"Back returns you to the tab you left" is deliberately NOT asserted, because two attempts to
     * bind it both failed a control (#48).** Review was right that asserting the tab BAR is back proves
     * nothing about WHICH tab, so `assertIsSelected` was added on this matcher. A control that makes
     * `closePages` also reset the destination to History did NOT turn the row red, on a run whose report
     * timestamp advanced, so the assertion is not reading the selection it appears to read. Shipping it
     * would be shipping a comment.
     *
     * The next author needs a matcher that provably reads `NavigationBarItem`'s selected state, and the
     * control above is the one it has to fail.
     */
    private val transcriptionTab = hasText("Transcription") and hasClickAction()
}
