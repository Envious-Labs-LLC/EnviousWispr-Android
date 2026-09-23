package com.envi.wispr.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

import java.io.File

/**
 * Drift Guard (#190), counted as such: it pins the SHAPE the split produced, not a user outcome. The shell
 * declares none of the ten reusable settings components or the five pieces of navigation chrome; its root
 * takes one joined state plus one grouped-actions holder (#218: the state is `AppUiState`, one field per
 * owning view model); the activity builds that holder once inside a `remember` keyed on all five view models.
 *
 * Against the pre-split `AppShell.kt` (1,013 lines, commit `01f441e`) every row here was red: fifteen
 * declarations present, a 42-parameter signature, and an activity call with no remembered holder.
 */
class AppShellShapeTest {
    private val shell = File("src/main/java/com/envi/wispr/ui/AppShell.kt").readText()
    private val activity = File("src/main/java/com/envi/wispr/ui/SettingsActivity.kt").readText()

    private val forbidden = setOf(
        "ScreenContainer", "SettingsGroup", "SettingsSliderRow", "SettingsToggleRow", "SettingsActionRow",
        "MicrophoneGlyph", "ReadinessChip", "StatusPill", "StatusDot", "statusDescription",
        "AppScaffold", "SettingsDrawerSheet", "DestinationIcon", "MenuGlyph", "BackGlyph",
    )

    /** Every `fun` the shell declares, the extension on `AutoPasteAvailability` included. */
    private val declarations = Regex("""(?m)^\s*(?:private|internal|public)?\s*fun\s+(?:AutoPasteAvailability\.)?([A-Za-z_][A-Za-z0-9_]*)\s*\(""")
        .findAll(shell).map { it.groupValues[1] }.toSet()

    @Test fun theShellDeclaresNoComponentAndNoChrome() {
        assertEquals("the shell declares a component or chrome piece that has its own file", emptySet<String>(), forbidden.intersect(declarations))
    }

    @Test fun theRootTakesStatePlusGroupedActions() {
        assertTrue(
            "EnviousWisprApp's signature is no longer (state: AppUiState, licenseNotices, actions)",
            Regex(
                """(?ms)^\s*internal\s+fun\s+EnviousWisprApp\s*\(\s*""" +
                    """state:\s*AppUiState,\s*""" +
                    """licenseNotices:\s*String,\s*""" +
                    """actions:\s*AppActions,\s*\)\s*\{""",
            ).containsMatchIn(shell),
        )
    }

    @Test fun theActivityBuildsTheActionsOnce() {
        assertTrue(
            "SettingsActivity no longer builds AppActions inside one remember keyed on all six view models, so a " +
                "recomposition hands the screens a new holder or a recreated view model keeps a stale one",
            Regex(
                """(?s)val\s+actions\s*=\s*remember\s*\(\s*shellViewModel,\s*historyViewModel,\s*dictionaryViewModel,\s*""" +
                    """polishViewModel,\s*readinessViewModel,\s*modelWorkViewModel\s*\)\s*\{\s*AppActions\s*\(""",
            ).containsMatchIn(activity),
        )
    }
}
