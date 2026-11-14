package com.envi.wispr.ui

import android.content.Context
import android.net.NetworkCapabilities
import androidx.activity.ComponentActivity
import com.envi.wispr.ui.theme.EnviousWisprTheme
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
 * Product Outcome: browsing an uninstalled model leaves the installed model active and makes download reachable.
 */
@RunWith(AndroidJUnit4::class)
class Eg1LocalPolishUiDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val isTab = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab)
    private fun modelTile(label: String) = hasText(label) and isTab

    @Test
    fun browsingEg1MicroKeepsS1ActiveAndOffersDownload() {
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
            EnviousWisprTheme {
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

        val s1Tile = compose.onNode(modelTile("S1-mini")).performScrollTo()
        val eg1Tile = compose.onNode(modelTile("EG-1 Micro")).performScrollTo()
        s1Tile.assertIsSelected()
        eg1Tile.assertIsNotSelected()
        val s1Height = with(compose.density) { s1Tile.fetchSemanticsNode().boundsInRoot.height.toDp() }
        val eg1Height = with(compose.density) { eg1Tile.fetchSemanticsNode().boundsInRoot.height.toDp() }
        assertTrue("Expected S1-mini tile touch target >= 48.dp, got $s1Height", s1Height >= 48.dp)
        assertTrue("Expected EG-1 Micro tile touch target >= 48.dp, got $eg1Height", eg1Height >= 48.dp)

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
        compose.onNodeWithText("Change").assertDoesNotExist()
        compose.onNodeWithText("Turn spoken thoughts into finished text").assertDoesNotExist()

        eg1Tile.performScrollTo().performClick()

        compose.onNode(modelTile("EG-1 Micro")).assertIsSelected()
        compose.onNode(modelTile("S1-mini") and hasText("Active")).assertIsNotSelected()
        compose.onNodeWithText("Casual").assertDoesNotExist()
        compose.onNodeWithText("Turn spoken thoughts into finished text").assertDoesNotExist()
        compose.onNodeWithText("541.9 MB").assertIsDisplayed()
        compose.onNodeWithText("Envious Labs").assertIsDisplayed()
        compose.onNodeWithText("Not downloaded").assertIsDisplayed()

        val workName = ModelDeliveryWorker.downloadWorkName(ModelManifest.eg1Micro)
        val workManager = WorkManager.getInstance(context)
        workManager.cancelUniqueWork(workName).result.get(10, TimeUnit.SECONDS)
        val beforeIds = workManager.getWorkInfosForUniqueWork(workName).get(10, TimeUnit.SECONDS).map { it.id }.toSet()
        try {
            val downloadButton = compose.onNodeWithText("Download").performScrollTo()
            downloadButton.assertIsDisplayed().assertIsEnabled().assertHasClickAction()
            downloadButton.performClick()
            compose.waitUntil("EG-1 Micro Download click did not enqueue a new work item for $workName", 5_000L) {
                workManager.getWorkInfosForUniqueWork(workName).get(10, TimeUnit.SECONDS).any { it.id !in beforeIds }
            }
            val queued = workManager.getWorkInfosForUniqueWork(workName).get(10, TimeUnit.SECONDS)
                .single { it.id !in beforeIds }
            val request = checkNotNull(queued.constraints.requiredNetworkRequest)
            assertTrue("Wi-Fi remains required", request.hasTransport(NetworkCapabilities.TRANSPORT_WIFI))
            assertTrue("Wi-Fi via a VPN is eligible", !request.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN))
        } finally {
            workManager.cancelUniqueWork(workName).result.get(10, TimeUnit.SECONDS)
            ModelDeliveryControlStore(ModelStorage.root(context)).clear(ModelManifest.eg1Micro)
        }
    }
}
