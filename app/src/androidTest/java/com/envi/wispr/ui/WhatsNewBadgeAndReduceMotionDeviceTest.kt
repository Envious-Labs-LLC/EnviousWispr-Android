package com.envi.wispr.ui

import androidx.activity.ComponentActivity
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.envi.wispr.paste.BubbleLook
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WhatsNewBadgeAndReduceMotionDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun unreadWhatsNewBadgeShowsInTopBarAndDrawerThenClearsWhenOpened() {
        val unread = mutableStateOf(true)
        val openPage = mutableStateOf<SettingsPage?>(null)
        var markedSeen = 0
        compose.setContent {
            MaterialTheme {
                val drawerState = rememberDrawerState(DrawerValue.Closed)
                val scope = rememberCoroutineScope()
                ModalNavigationDrawer(
                    drawerState = drawerState,
                    drawerContent = {
                        SettingsDrawerSheet(
                            current = openPage.value,
                            developerUnlocked = false,
                            hasUnreadWhatsNew = unread.value,
                            onVersionTap = {},
                            onPick = { page ->
                                openPage.value = page
                                scope.launch { drawerState.close() }
                            },
                        )
                    },
                ) {
                    AppScaffold(
                        destination = AppDestination.History,
                        page = openPage.value?.let { PageChrome(it.title) },
                        snackbarHostState = SnackbarHostState(),
                        onOpenDrawer = { scope.launch { drawerState.open() } },
                        onBack = { openPage.value = null },
                        onSelectDestination = {},
                        onStartDictation = {},
                        onFeedback = {},
                        hasUnreadWhatsNew = unread.value,
                    ) { _ ->
                        if (openPage.value == SettingsPage.WhatsNew) {
                            WhatsNewPage(onSeen = {
                                markedSeen++
                                unread.value = false
                            })
                        } else {
                            Text("History stub")
                        }
                    }
                }
            }
        }

        val hasUnreadState = SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "New release notes")
        val noUnreadState = SemanticsMatcher.keyNotDefined(SemanticsProperties.StateDescription)

        compose.onNodeWithContentDescription("Open settings menu").assert(hasUnreadState).performClick()
        compose.onNodeWithText("What's New").assert(hasUnreadState).performClick()
        compose.onNodeWithText("Check for updates on Google Play").assertIsDisplayed()
        compose.waitForIdle()
        assertEquals(1, markedSeen)
        assertEquals(false, unread.value)

        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithContentDescription("Open settings menu").assert(noUnreadState)
    }

    @Test fun onboardingDemoStepsThroughSettledScenesUnderReduceMotion() {
        compose.setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalReducedMotionOverride provides true) {
                    val palette = DemoPalette(
                        background = MaterialTheme.colorScheme.background,
                        surface = MaterialTheme.colorScheme.surface,
                        foreground = MaterialTheme.colorScheme.onBackground,
                        muted = MaterialTheme.colorScheme.onSurfaceVariant,
                        accent = MaterialTheme.colorScheme.primary,
                        border = MaterialTheme.colorScheme.outlineVariant,
                    )
                    OnboardingDemo(
                        icons = emptyList(),
                        look = BubbleLook.DEFAULT,
                        palette = palette,
                        onFinished = {},
                    )
                }
            }
        }

        compose.onNodeWithText("Works in compatible apps with a text box.").assertIsDisplayed()
        compose.onNodeWithText("Next").performClick()
        compose.onNodeWithText("It appears whenever a text box is active.").assertIsDisplayed()
        compose.onNodeWithText("Next").performClick()
        compose.onNodeWithText("Drag it to the side you like.").assertIsDisplayed()
        compose.onNodeWithText("Next").performClick()
        compose.onNodeWithText("It comes in three looks.").assertIsDisplayed()
        compose.onNodeWithText("Next").performClick()
        compose.onNodeWithText(DemoScript.TAP_EMAIL).assertIsDisplayed()
        compose.onNodeWithText("Back").performClick()
        compose.onNodeWithText("It comes in three looks.").assertIsDisplayed()
    }

    @Test fun manualPauseSurvivesReduceMotionToggleInWelcomeStory() {
        val override = mutableStateOf(false)
        compose.setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalReducedMotionOverride provides override.value) {
                    OnboardingWelcomeStory(
                        surface = MaterialTheme.colorScheme.surface,
                        foreground = MaterialTheme.colorScheme.onBackground,
                        muted = MaterialTheme.colorScheme.onSurfaceVariant,
                        accent = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }

        compose.onNodeWithText("Pause").assertIsDisplayed().performClick()
        compose.onNodeWithText("Play").assertIsDisplayed()
        override.value = true
        compose.waitForIdle()
        compose.onNodeWithText("Play").assertIsDisplayed()
        override.value = false
        compose.waitForIdle()
        compose.onNodeWithText("Play").assertIsDisplayed()
    }

    @Test fun compactViewportKeepsBubbleInstructionsVisibleUnderReduceMotion() {
        compose.setContent {
            MaterialTheme {
                val baseConfig = androidx.compose.ui.platform.LocalConfiguration.current
                val compactConfig = android.content.res.Configuration(baseConfig).apply { screenHeightDp = 420 }
                CompositionLocalProvider(
                    androidx.compose.ui.platform.LocalConfiguration provides compactConfig,
                    LocalReducedMotionOverride provides true,
                ) {
                    val palette = DemoPalette(
                        background = MaterialTheme.colorScheme.background,
                        surface = MaterialTheme.colorScheme.surface,
                        foreground = MaterialTheme.colorScheme.onBackground,
                        muted = MaterialTheme.colorScheme.onSurfaceVariant,
                        accent = MaterialTheme.colorScheme.primary,
                        border = MaterialTheme.colorScheme.outlineVariant,
                    )
                    OnboardingDemo(
                        icons = emptyList(),
                        look = BubbleLook.DEFAULT,
                        palette = palette,
                        onFinished = {},
                    )
                }
            }
        }

        compose.onNodeWithText("Messages, notes, search, and more.").assertIsDisplayed()
        compose.onNodeWithText("Next").performClick()
        compose.onNodeWithText("It appears whenever a text box is active.").assertIsDisplayed()
        compose.onNodeWithText("Next").performClick()
        compose.onNodeWithText("Drag it to the side you like.").assertIsDisplayed()
    }
}
