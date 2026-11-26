package com.envi.wispr.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Product Outcome Compose UI tests for Settings Search (`PAR-099`):
 * verifies drawer search trigger -> synonym matching + `"Matches “…”"` hint -> emitted target,
 * suggestion chip + empty-state recovery, Developer gating, and narrow 360dp top-bar actions.
 * Production end-to-end navigation + section cue delivery is verified on `SettingsActivity` in
 * `AppShellNavigationTest.drawerSearchJumpsToAppearanceAndShowsSectionCue`.
 */
@RunWith(AndroidJUnit4::class)
class SettingsSearchDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun drawerSearchEmitsAppearanceTarget() {
        var landedPage by mutableStateOf<SettingsPage?>(null)
        var pickedTarget: SettingsSearchTarget? = null
        compose.setContent {
            MaterialTheme {
                val drawerState = rememberDrawerState(DrawerValue.Closed)
                val scope = rememberCoroutineScope()
                val index = remember { SettingsSearchIndex(SettingsSearchCatalog.entries) }
                var searchOpen by remember { mutableStateOf(false) }
                var query by remember { mutableStateOf("") }

                ModalNavigationDrawer(
                    drawerState = drawerState,
                    drawerContent = {
                        SettingsDrawerSheet(
                            current = landedPage,
                            developerUnlocked = false,
                            onVersionTap = {},
                            onPick = { page ->
                                landedPage = page
                                scope.launch { drawerState.close() }
                            },
                            onOpenSearch = {
                                searchOpen = true
                                scope.launch { drawerState.close() }
                            },
                        )
                    },
                ) {
                    AppScaffold(
                        destination = AppDestination.History,
                        page = landedPage?.let { PageChrome(it.title) },
                        snackbarHostState = SnackbarHostState(),
                        onOpenDrawer = { scope.launch { drawerState.open() } },
                        onBack = { landedPage = null },
                        onSelectDestination = {},
                        onStartDictation = {},
                        onFeedback = {},
                        onOpenSearch = { searchOpen = true },
                    ) { modifier ->
                        Box(modifier) {
                            Text(landedPage?.title ?: "History body")
                        }
                    }
                }

                if (searchOpen) {
                    SettingsSearchDialog(
                        index = index,
                        query = query,
                        developerUnlocked = false,
                        onQueryChange = { query = it },
                        onSelect = { result ->
                            pickedTarget = result.entry.target
                            searchOpen = false
                            query = ""
                            if (result.entry.target is SettingsSearchTarget.Page) {
                                landedPage = (result.entry.target as SettingsSearchTarget.Page).page
                            }
                        },
                        onDismiss = { searchOpen = false; query = "" },
                    )
                }
            }
        }

        compose.onNodeWithContentDescription("Open settings menu").performClick()
        compose.onNodeWithText("Search settings").assertIsDisplayed().performClick()
        compose.onNodeWithText("Open the tab or settings page for your result.").assertIsDisplayed()

        compose.onNode(hasSetTextAction() and hasText("Search settings")).performTextReplacement("dark mode")
        compose.onNodeWithText("Matches \u201Cdark mode\u201D").assertIsDisplayed()
        compose.onNodeWithText("Theme").assertIsDisplayed().performClick()

        compose.waitForIdle()
        assertEquals(SettingsSearchTarget.Page(SettingsPage.Appearance, sectionHint = "Theme"), pickedTarget)
        assertEquals(SettingsPage.Appearance, landedPage)
        compose.onNodeWithContentDescription("Back").assertIsDisplayed()
        val searchButtons = compose.onAllNodesWithContentDescription("Search settings")
        val count = searchButtons.fetchSemanticsNodes().size
        assertEquals(1, (0 until count).count { searchButtons[it].isDisplayed() })
    }

    @Test
    fun suggestionChipAndEmptyStateRecoverCleanly() {
        var pickedTarget: SettingsSearchTarget? = null
        var dismissed = false
        compose.setContent {
            MaterialTheme {
                val index = remember { SettingsSearchIndex(SettingsSearchCatalog.entries) }
                var query by remember { mutableStateOf("") }
                SettingsSearchDialog(
                    index = index,
                    query = query,
                    developerUnlocked = false,
                    onQueryChange = { query = it },
                    onSelect = { pickedTarget = it.entry.target },
                    onDismiss = { dismissed = true },
                )
            }
        }

        compose.onNodeWithText("Auto-paste").performClick()
        compose.onNodeWithText("Auto-paste access (Accessibility)").assertIsDisplayed()

        compose.onNode(hasSetTextAction() and hasText("Search settings")).performTextReplacement("zzzznonexistent")
        compose.onNodeWithText("No settings match \u201Czzzznonexistent\u201D").assertIsDisplayed()
        compose.onNodeWithText("Clear search").performClick()
        compose.onNodeWithText("Open the tab or settings page for your result.").assertIsDisplayed()

        compose.onNodeWithText("Auto-paste").performClick()
        compose.onNodeWithText("Auto-paste access (Accessibility)").performClick()
        assertEquals(
            SettingsSearchTarget.Page(SettingsPage.Permissions, sectionHint = "Auto-paste access"),
            pickedTarget,
        )
        assertFalse(dismissed)
    }

    @Test
    fun developerResultsHideWhenLockedAndAppearWhenUnlocked() {
        val unlocked = mutableStateOf(false)
        compose.setContent {
            MaterialTheme {
                val index = remember { SettingsSearchIndex(SettingsSearchCatalog.entries) }
                var query by mutableStateOf("detailed log")
                SettingsSearchDialog(
                    index = index,
                    query = query,
                    developerUnlocked = unlocked.value,
                    onQueryChange = { query = it },
                    onSelect = {},
                    onDismiss = {},
                )
            }
        }

        compose.onNodeWithText("No settings match \u201Cdetailed log\u201D").assertIsDisplayed()
        compose.runOnUiThread { unlocked.value = true }
        compose.waitForIdle()
        compose.onNodeWithText("Detailed log").assertIsDisplayed()
    }

    @Test
    fun narrow360dpTopBarShowsNavigationActions() {
        val currentPage = mutableStateOf<PageChrome?>(null)
        compose.setContent {
            MaterialTheme {
                Box(Modifier.width(360.dp)) {
                    AppScaffold(
                        destination = AppDestination.Polish,
                        page = currentPage.value,
                        snackbarHostState = SnackbarHostState(),
                        onOpenDrawer = {},
                        onBack = {},
                        onSelectDestination = {},
                        onStartDictation = {},
                        onFeedback = {},
                        topBarBadge = {
                            PolishStatusBadge(PolishStatusChip(PolishStatusKind.OPENAI, "OpenAI", PolishStatusDot.GREEN))
                        },
                        onOpenSearch = {},
                    ) { modifier ->
                        Box(modifier) { Text("Body") }
                    }
                }
            }
        }

        compose.onNode(
            hasText("AI Polish") and !SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab),
        ).assertIsDisplayed()
        compose.onNodeWithContentDescription("Start dictation").assertIsDisplayed()

        compose.runOnUiThread { currentPage.value = PageChrome(SettingsPage.Licenses.title) }
        compose.waitForIdle()
        compose.onNodeWithText(SettingsPage.Licenses.title).assertIsDisplayed()
        compose.onNodeWithContentDescription("Search settings").assertIsDisplayed()
        compose.onNodeWithContentDescription("Send feedback").assertIsDisplayed()
    }
}
