package com.envi.wispr.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Drift Guard (#255), read off the source: model-delivery work is observed and projected only by its owners
 * (`ModelWorkViewModel` for the settings shell, `OnboardingViewModel` for setup), never in a composable, and the
 * owner's two cards reach the screens through `AppUiState`. When this fails, a screen observes WorkManager or
 * reads model storage during composition again, on main.
 */
class ModelWorkOwnershipTest {
    private val sources = File("src/main/java").walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
    private val owners = setOf("ModelWorkViewModel.kt", "OnboardingViewModel.kt")

    private fun filesMatching(pattern: Regex) = sources.filter { pattern.containsMatchIn(it.readText()) }.map { it.name }.toSet()

    @Test fun onlyTheOwnersObserveModelWork() {
        assertTrue("the production tree must be readable", sources.size > 100)
        assertEquals(owners, filesMatching(Regex("""\bgetWorkInfosForUniqueWorkFlow\b""")))
    }

    @Test fun onlyTheOwnersProjectModelWork() {
        // Calls only: the declaration in ModelCards.kt is `fun workUiState(`.
        assertEquals(owners, filesMatching(Regex("""(?<!fun )\bworkUiState\(""")))
    }

    @Test fun theOwnersCardsReachTheScreensThroughAppUiState() {
        val activity = File("src/main/java/com/envi/wispr/ui/SettingsActivity.kt").readText()
        val shell = File("src/main/java/com/envi/wispr/ui/AppShell.kt").readText()
        assertTrue(activity.contains("val models by modelWorkViewModel.models.collectAsStateWithLifecycle()"))
        assertTrue(activity.contains("models = models,"))
        assertTrue(activity.contains("onShowModels = modelWorkViewModel::show,"))
        assertTrue(activity.contains("collectModelRefresh(modelWorkViewModel.finished, ::refreshReadiness)"))
        assertTrue(shell.contains("val polishS1State = state.models.polish"))
        assertTrue(shell.contains("PolishStatusBadge(polishStatusChip(providerSettings, polishS1State))"))
        assertTrue(shell.contains("s1State = polishS1State,"))
        assertTrue(shell.contains("speechModel = state.models.speech,"))
        assertTrue(shell.contains("actions.shell.onShowModels(if (settingsPage == null) destination else null)"))
        assertTrue(shell.contains("onStopOrDispose { actions.shell.onShowModels(null) }"))
    }
}
