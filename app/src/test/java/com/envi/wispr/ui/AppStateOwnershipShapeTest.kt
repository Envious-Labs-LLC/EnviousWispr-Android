package com.envi.wispr.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Drift Guard (#218, audit REF-09), counted as such: it pins which view model owns which state, read
 * through `codeOnly`, so a comment or a string naming a store never counts. The shell view model names no
 * feature store; each feature view model names its own and no other's, and wires it to its own state.
 *
 * REVERTS: put `repository.transcripts` back in `EnviousWisprViewModel`; add a `history` field to
 * `EnviousWisprUiState`; replace `HistoryViewModel`'s source with `flowOf(emptyList())` (it compiles, and
 * the wiring row goes red); change the Dictionary transform to `loaded = terms.isNotEmpty()`.
 */
class AppStateOwnershipShapeTest {
    private val root = "src/main/java/com/envi/wispr"
    private val shellFile = File("$root/ui/AppViewModel.kt")
    private val owners = linkedMapOf(
        File("$root/history/ui/HistoryViewModel.kt") to setOf("TranscriptRepository"),
        File("$root/vocabulary/ui/DictionaryViewModel.kt") to setOf("CustomTermRepository"),
        File("$root/providers/ui/PolishSettingsViewModel.kt") to setOf("ProviderConfigurationRepository", "ModelListCache"),
        File("$root/ui/ReadinessViewModel.kt") to setOf("PasteAccessibilityService", "AccessibilityPermission"),
    )
    private val stores = owners.values.flatten().toSet()
    private val code = codeOnly(listOf(shellFile) + owners.keys)

    private fun names(text: String, candidates: Set<String>) =
        candidates.filter { Regex("""\b$it\b""").containsMatchIn(text) }.toSet()

    /** The `val state` initializer up to its `.stateIn(`: the one expression that builds a feature's state. */
    private fun stateExpression(file: File): String {
        val text = code.getValue(file)
        val start = Regex("""\bval\s+state\s*:""").find(text)?.range?.first
        assertTrue("${file.name} declares no `val state`", start != null)
        val end = text.indexOf(".stateIn(", start!!)
        assertTrue("${file.name}'s state is not built with stateIn", end > start)
        return text.substring(start, end)
    }

    @Test fun theShellViewModelNamesNoFeatureStore() {
        assertEquals("EnviousWisprViewModel owns a feature's store again", emptySet<String>(), names(code.getValue(shellFile), stores))
    }

    @Test fun eachFeatureViewModelNamesOnlyItsOwnStore() {
        owners.forEach { (file, own) ->
            assertEquals("${file.name} names another feature's store", own, names(code.getValue(file), stores))
        }
    }

    @Test fun eachFeatureViewModelWiresItsStoreToItsState() {
        val history = owners.keys.first { it.name == "HistoryViewModel.kt" }
        val dictionary = owners.keys.first { it.name == "DictionaryViewModel.kt" }
        val readiness = owners.keys.first { it.name == "ReadinessViewModel.kt" }
        val polish = owners.keys.first { it.name == "PolishSettingsViewModel.kt" }
        assertTrue("History's state is not built from repository.transcripts", stateExpression(history).contains("repository.transcripts"))
        assertTrue("Dictionary's state is not built from customTermRepository.observe()", stateExpression(dictionary).contains("customTermRepository.observe()"))
        val readinessText = code.getValue(readiness)
        assertTrue(
            "readiness no longer joins the permission with the paste service's pushed liveness",
            Regex("""AutoPasteReadiness\.observe\((?s:[^)]*)PasteAccessibilityService\.isBound""").containsMatchIn(readinessText),
        )
        assertTrue("readiness's state does not carry the joined auto-paste answer", Regex("""\bautoPaste\b""").containsMatchIn(stateExpression(readiness)))
        val polishText = code.getValue(polish)
        val refresh = polishText.substring(polishText.indexOf("fun refreshProviderSettings("))
        assertTrue("refreshProviderSettings no longer publishes providerSettings", refresh.substringBefore("\n    }").contains("providerSettings.value = ProviderSettingsUiState("))
        assertTrue("the Polish tab reads something other than the settings the writes publish", Regex("""val\s+settings\s*:[^=]*=\s*providerSettings\.asStateFlow\(\)""").containsMatchIn(polishText))
    }

    /**
     * The half of tap order the JVM row cannot see: the rapid-tap row proves the second tap waits, and this
     * row pins that the lock is taken in the launched coroutine on Main, before any IO. REVERT: launch the
     * write on `Dispatchers.IO` (`viewModelScope.launch(Dispatchers.IO) {`), where the pool picks who locks.
     */
    @Test fun providerWritesTakeTheLockOnMainBeforeAnyIo() {
        val polish = code.getValue(owners.keys.first { it.name == "PolishSettingsViewModel.kt" })
        val start = polish.indexOf("private fun updateProviderSettings(")
        assertTrue("updateProviderSettings moved", start >= 0)
        val write = polish.substring(start)
        assertTrue(
            "a provider write no longer takes the settings lock first thing in a coroutine launched on Main",
            Regex("""viewModelScope\.launch\s*\{\s*providerSettingsMutex\.withLock\s*\{""").containsMatchIn(write),
        )
        assertTrue(
            "the provider write reaches IO before it holds the settings lock",
            write.indexOf("providerSettingsMutex.withLock {") < write.indexOf("withContext(Dispatchers.IO) { operation() }"),
        )
    }

    @Test fun anEmptyDictionaryStillCountsAsLoaded() {
        val dictionary = owners.keys.first { it.name == "DictionaryViewModel.kt" }
        assertTrue(
            "the Dictionary state built from a term emission does not set loaded = true on every emission, " +
                "so an empty word list keeps the app on Preparing",
            Regex("""DictionaryUiState\(\s*loaded\s*=\s*true\s*,""").containsMatchIn(stateExpression(dictionary)),
        )
    }

    @Test fun theShellStateHoldsOnlyLoadedAndPreferences() {
        val text = code.getValue(shellFile)
        val start = text.indexOf("data class EnviousWisprUiState(")
        assertTrue("EnviousWisprUiState moved", start >= 0)
        val params = text.substring(start + "data class EnviousWisprUiState(".length, text.indexOf(") {", start))
        val fields = Regex("""\bval\s+(\w+)\s*:""").findAll(params).map { it.groupValues[1] }.toList()
        assertEquals("EnviousWisprUiState holds a feature's field again", listOf("loaded", "preferences"), fields)
    }
}
