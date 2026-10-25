package com.envi.wispr.ui

import com.envi.wispr.models.ModelManifest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Product outcome (#108): when this fails, the update prompt promises dictation keeps working when no verified model
 * would carry it, names the wrong model, hides the size the user pays for in mobile data, or carries a dash.
 */
class ModelUpdatePromptCopyTest {
    /** REVERT: always append the "keeps working" sentence. */
    @Test fun theKeepsWorkingPromiseIsMadeOnlyWhenThePreviousModelVerifies() {
        val speech = ModelUpdatePromptCopy.of(ModelManifest.parakeet, keepsWorking = true)
        assertEquals("New speech model available", speech.title)
        assertTrue(speech.body.contains("Dictation keeps working with your current model until it finishes."))
        assertFalse(ModelUpdatePromptCopy.of(ModelManifest.parakeet, keepsWorking = false).body.contains("keeps working"))
        val polish = ModelUpdatePromptCopy.of(ModelManifest.s1, keepsWorking = true)
        assertEquals("New polish model available", polish.title)
        assertTrue(polish.body.contains("Polish keeps working"))
    }

    @Test fun theBodyNamesTheSizeAndBothChoicesWithoutDashes() {
        for (model in ModelManifest.all) {
            val content = ModelUpdatePromptCopy.of(model, keepsWorking = true)
            assertTrue(content.body.contains(formatModelBytes(model.files.sumOf { it.expectedBytes })))
            assertTrue(content.body.contains("mobile data") && content.body.contains("wait for Wi-Fi"))
            assertFalse((content.title + content.body).contains('—') || (content.title + content.body).contains('–'))
        }
    }
}
