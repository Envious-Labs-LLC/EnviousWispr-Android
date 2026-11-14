package com.envi.wispr.ui

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.WorkManager
import com.envi.wispr.models.ModelDeliveryControlStore
import com.envi.wispr.models.ModelDeliveryWorker
import com.envi.wispr.models.ModelHealth
import com.envi.wispr.models.ModelManifest
import com.envi.wispr.models.ModelStorage
import com.envi.wispr.models.ModelUiAction
import com.envi.wispr.models.ModelUiState
import com.envi.wispr.polish.LocalPolishModel
import com.envi.wispr.providers.PolishMode
import com.envi.wispr.providers.ui.ProviderDiscoveryUiState
import com.envi.wispr.providers.ui.ProviderSettingsUiState
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Product Outcome: switching local polish models updates radio semantics, hides S1-only writing style
 * controls behind explicit notice and CPU processing cards, and keeps EG-1 Micro actions reachable.
 */
@RunWith(AndroidJUnit4::class)
class Eg1LocalPolishUiDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val isRadio = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton)
    private fun modelRadio(label: String) = hasText(label) and isRadio

    @Test
    fun selectingEg1MicroUpdatesRadiosHidesS1ControlsShowsNoticeAndEnqueuesDownload() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val s1State = ModelUiState("Ready", ModelHealth.READY, action = ModelUiAction.REMOVE)
        val eg1State = ModelUiState("Missing", ModelHealth.NOT_READY, action = ModelUiAction.DOWNLOAD)
        var nextSequence = 0
        val settingsState = mutableStateOf(
            ProviderSettingsUiState(
                loading = false,
                mode = PolishMode.OFFLINE_S1,
                localPolishModel = LocalPolishModel.S1_MINI,
            ),
        )

        compose.setContent {
            MaterialTheme {
                PolishScreen(
                    settings = settingsState.value,
                    s1State = s1State,
                    eg1State = eg1State,
                    discovery = ProviderDiscoveryUiState(),
                    onSetMode = { mode ->
                        nextSequence += 1
                        settingsState.value = settingsState.value.copy(mode = mode, writeSequence = nextSequence)
                        nextSequence
                    },
                    onSetLocalModel = { picked ->
                        nextSequence += 1
                        settingsState.value = settingsState.value.copy(localPolishModel = picked, writeSequence = nextSequence)
                        nextSequence
                    },
                    onSetS1Control = { control ->
                        nextSequence += 1
                        settingsState.value = settingsState.value.copy(s1Control = control, writeSequence = nextSequence)
                        nextSequence
                    },
                    onSave = { _, _, _, _ -> 0 },
                    onClearProvider = { 0 },
                    onCheckKey = { _, _ -> 0 },
                    onKeyDraftChanged = {},
                    onLoadCachedModels = {},
                    onRefreshReadiness = {},
                )
            }
        }

        val s1Radio = compose.onNode(modelRadio("S1-mini")).performScrollTo()
        val eg1Radio = compose.onNode(modelRadio("EG-1 Micro")).performScrollTo()
        s1Radio.assertIsSelected()
        eg1Radio.assertIsNotSelected()
        val s1Height = with(compose.density) { s1Radio.fetchSemanticsNode().boundsInRoot.height.toDp() }
        val eg1Height = with(compose.density) { eg1Radio.fetchSemanticsNode().boundsInRoot.height.toDp() }
        assertTrue("Expected S1-mini radio touch target >= 48.dp, got $s1Height", s1Height >= 48.dp)
        assertTrue("Expected EG-1 Micro radio touch target >= 48.dp, got $eg1Height", eg1Height >= 48.dp)

        compose.onNodeWithText("484.2 MB").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Casual").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Semi-casual").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Semi-formal").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Formal").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Prose").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Lists").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("General").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Email").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Automatic").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Change").performScrollTo().assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithText(
            "EG-1 Micro uses built-in formatting and language adaptation automatically. Switch back to S1-mini to customize casual/formal style.",
        ).assertDoesNotExist()
        compose.onNodeWithText("EG-1 Micro runs on your phone's processor (CPU).").assertDoesNotExist()

        eg1Radio.performScrollTo().performClick()
        compose.waitForIdle()

        compose.onNode(modelRadio("EG-1 Micro")).assertIsSelected()
        compose.onNode(modelRadio("S1-mini")).assertIsNotSelected()

        compose.onNodeWithText("Casual").assertDoesNotExist()
        compose.onNodeWithText("Semi-casual").assertDoesNotExist()
        compose.onNodeWithText("Semi-formal").assertDoesNotExist()
        compose.onNodeWithText("Formal").assertDoesNotExist()
        compose.onNodeWithText("Prose").assertDoesNotExist()
        compose.onNodeWithText("Lists").assertDoesNotExist()
        compose.onNodeWithText("General").assertDoesNotExist()
        compose.onNodeWithText("Email").assertDoesNotExist()
        compose.onNodeWithText("Change").assertDoesNotExist()

        compose.onNodeWithText("541.9 MB").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("17 languages").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Processor (CPU)").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("WRITING STYLE").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(
            "EG-1 Micro uses built-in formatting and language adaptation automatically. Switch back to S1-mini to customize casual/formal style.",
        ).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("EG-1 Micro runs on your phone's processor (CPU).").performScrollTo().assertIsDisplayed()

        val workName = ModelDeliveryWorker.downloadWorkName(ModelManifest.eg1Micro)
        val workManager = WorkManager.getInstance(context)
        workManager.cancelUniqueWork(workName).result.get(10, TimeUnit.SECONDS)
        val beforeIds = workManager.getWorkInfosForUniqueWork(workName).get(10, TimeUnit.SECONDS).map { it.id }.toSet()
        try {
            val downloadButton = compose.onNodeWithText("Download").performScrollTo()
            downloadButton.assertIsDisplayed().assertIsEnabled().assertHasClickAction()
            downloadButton.performClick()
            compose.waitUntil("Eg1Card Download click did not enqueue a new work item for $workName", 5_000L) {
                workManager.getWorkInfosForUniqueWork(workName).get(10, TimeUnit.SECONDS).any { it.id !in beforeIds }
            }
        } finally {
            workManager.cancelUniqueWork(workName).result.get(10, TimeUnit.SECONDS)
            ModelDeliveryControlStore(ModelStorage.root(context)).clear(ModelManifest.eg1Micro)
        }
    }
}
