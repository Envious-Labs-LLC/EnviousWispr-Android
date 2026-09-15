package com.envi.wispr.paste

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Product Outcome: the floating lips and the paste never reach a field of EnviousWispr's own, except
 * the onboarding practice box while it is on screen, so a new user's first dictation runs the real path.
 */
class OwnFieldAdmissionTest {
    private val self = "com.envi.wispr"

    @After fun withdraw() = OwnFieldAdmission.withdraw("envious_practice_field")

    @Test fun anotherAppsFieldIsAlwaysATarget() {
        assertTrue(OwnFieldAdmission.accepts(self, "com.google.android.gm", null))
        assertTrue(OwnFieldAdmission.searches(self, "com.google.android.gm"))
        assertFalse(OwnFieldAdmission.accepts(self, "", "anything"))
        assertFalse(OwnFieldAdmission.searches(self, null))
    }

    @Test fun ourOwnFieldsAreNeverTargetsUntilOneIsAdmittedById() {
        assertFalse(OwnFieldAdmission.searches(self, self))
        assertFalse(OwnFieldAdmission.accepts(self, self, "envious_practice_field"))
        OwnFieldAdmission.admit("envious_practice_field")
        assertTrue(OwnFieldAdmission.searches(self, self))
        assertTrue(OwnFieldAdmission.accepts(self, self, "envious_practice_field"))
        // Every other field of ours stays out, id or no id.
        assertFalse(OwnFieldAdmission.accepts(self, self, "dictionary_word"))
        assertFalse(OwnFieldAdmission.accepts(self, self, null))
    }

    @Test fun withdrawingAnotherIdLeavesTheAdmissionAlone() {
        OwnFieldAdmission.admit("envious_practice_field")
        OwnFieldAdmission.withdraw("something_else")
        assertEquals("envious_practice_field", OwnFieldAdmission.admitted)
        OwnFieldAdmission.withdraw("envious_practice_field")
        assertEquals(null, OwnFieldAdmission.admitted)
        assertFalse(OwnFieldAdmission.searches(self, self))
    }

    @Test fun theServiceAsksTheAdmissionEverywhereItJudgesAPackage() {
        // DRIFT GUARD: a bare own-package comparison on a node or event would reopen one route around
        // the admission (Codex mechanism note: "scope the same field predicate through the whole path").
        val service = File("src/main/java/com/envi/wispr/paste/PasteAccessibilityService.kt").readText()
        val bareComparisons = service.lines().filter { Regex("""(==|!=) packageName\b""").containsMatchIn(it) }
        // The one allowed line: insertion retries on an event from the pinned target's own package.
        assertEquals(bareComparisons.toString(), 1, bareComparisons.size)
        assertTrue(bareComparisons.single().contains("pinnedTarget?.packageName == packageName"))
        listOf("rememberEditableTarget", "findFocusedEditableTarget(root", "isSafeFocusedEditor").forEach { site ->
            val body = service.substringAfter("private fun $site").substringBefore("\n    private fun ")
            assertTrue(site, body.contains("OwnFieldAdmission."))
        }
        val screen = File("src/main/java/com/envi/wispr/ui/OnboardingScreen.kt").readText()
        assertTrue(screen.contains("testTagsAsResourceId = true"))
        assertTrue(screen.contains("testTag(OnboardingViewModel.PRACTICE_FIELD_ID)"))
    }
}
