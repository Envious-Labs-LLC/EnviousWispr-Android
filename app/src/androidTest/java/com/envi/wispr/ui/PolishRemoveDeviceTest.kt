package com.envi.wispr.ui

import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import android.os.Build
import com.envi.wispr.providers.PolishMode
import com.envi.wispr.providers.Provider
import com.envi.wispr.providers.ProviderConfigurationRepository
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

/** A fresh process re-verifies the downloaded models before setup offers "Set up later" (as in AppShellNavigationTest). */
private const val SHELL_BOUND_MS = 120_000L

/**
 * Product Outcome (#95, the screen half of #94/#96). When this fails, removing a provider on the AI Polish tab throws
 * the user out of cloud setup onto "This phone", or drops the cloud tile they were filling in.
 *
 * The #96 fix lives in `PolishScreen`'s `start`, which applies `PolishLadder.navigationAfterRemove` when a remove
 * starts; `PolishLadderTest` covers the rule as a pure function and cannot see whether the screen applies it. This
 * drives the real screen. A self-hosted provider is staged because it needs no key and no live check, so the remove
 * path is reachable with no network: after Remove, the repository resets the mode to on-device (a cloud mode with
 * nothing to call fails at dictation time), and only the screen's pinned navigation keeps it on Cloud.
 *
 * Staged BEFORE the activity starts (the polish settings load once, at view model creation). It runs on the emulator
 * only (it may dismiss setup), refuses a device that already has a provider or a stored key, and puts the polish mode
 * back as it found it.
 * REVERT: delete the `if (kind == WriteKind.REMOVE)` block in `PolishScreen`'s `start`; the Cloud read goes red.
 */
@RunWith(AndroidJUnit4::class)
class PolishRemoveDeviceTest {
    private val repository by lazy { ProviderConfigurationRepository(InstrumentationRegistry.getInstrumentation().targetContext) }

    private val staging = object : ExternalResource() {
        private var originalMode: PolishMode? = null

        override fun before() {
            // The row may press "Set up later", which is saved, so it runs only where nothing real is kept.
            check(Build.HARDWARE == "ranchu" || Build.HARDWARE == "goldfish") { "run this row on the emulator only; it changes setup and polish settings" }
            check(repository.load() == null && repository.storedProviders().isEmpty()) {
                "this device already has AI Polish provider settings; this row would clear them"
            }
            originalMode = repository.loadMode()
            repository.saveProvider(Provider.SELF_HOSTED_POLISH, model = "stage-model", endpoint = "https://polish.example.test/v1")
            check(repository.load()?.provider == Provider.SELF_HOSTED_POLISH) { "the self-hosted provider was not staged" }
        }

        override fun after() {
            // Put the polish choice back as found; a failed restore is reported, never swallowed.
            repository.clearSelection()
            originalMode?.let(repository::setMode)
        }
    }

    private val composeRule = createAndroidComposeRule<SettingsActivity>()

    @get:Rule val rules: RuleChain = RuleChain.outerRule(staging).around(composeRule)

    @Test
    fun removingAProviderKeepsTheUserInCloudSetupOnTheTileTheyChose() {
        reachTheShell()
        composeRule.onNode(tab("AI Polish")).performClick()
        composeRule.waitForIdle()

        // Precondition: the staged provider is live, so the tab opens on Cloud with its card.
        composeRule.onNode(rung("Cloud")).assertIsSelected()
        composeRule.onNodeWithText("Self-hosted · polish.example.test").assertIsDisplayed()

        // Start filling in another provider, then remove the self-hosted one from above that field.
        composeRule.onNode(hasContentDescription("Gemini") and isRadio).performScrollTo().performClick()
        composeRule.waitForIdle()
        composeRule.onNode(hasContentDescription("Gemini") and isRadio).assertIsSelected()
        composeRule.onNodeWithText("Remove").performScrollTo().performClick()
        composeRule.waitUntil("the self-hosted card to leave after Remove", 10_000L) {
            composeRule.onAllNodes(hasText("Self-hosted · polish.example.test")).fetchSemanticsNodes().isEmpty()
        }

        // The write really happened: nothing is selected any more, and the mode left the cloud.
        check(repository.load() == null) { "Remove did not clear the self-hosted selection" }
        composeRule.onNode(rung("Cloud")).assertIsSelected()
        composeRule.onNode(rung("This phone")).assertIsNotSelected()
        composeRule.onNode(hasContentDescription("Gemini") and isRadio).assertIsSelected()
    }

    private fun reachTheShell() {
        composeRule.waitForIdle()
        val onward = hasText("Set up later") or hasContentDescription("Open settings menu")
        composeRule.waitUntil("setup's permissions step or the shell (are both models downloaded on this device?)", SHELL_BOUND_MS) {
            composeRule.onAllNodes(onward).fetchSemanticsNodes().isNotEmpty()
        }
        if (composeRule.onAllNodes(hasText("Set up later")).fetchSemanticsNodes().isNotEmpty()) {
            composeRule.onNodeWithText("Set up later").performClick()
            composeRule.waitForIdle()
        }
        composeRule.onNode(hasContentDescription("Open settings menu")).assertExists("the shell never appeared")
    }

    private val isRadio = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton)

    /** A rung-one choice by its label: the selectable radio row, not the label text alone. */
    private fun rung(label: String) = hasText(label) and isRadio

    private fun tab(label: String) = hasText(label) and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab)
}
