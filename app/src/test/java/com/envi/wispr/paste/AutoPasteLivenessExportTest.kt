package com.envi.wispr.paste

import java.lang.reflect.Field
import java.lang.reflect.Method
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Proves at RUNTIME that the liveness the UI collects is the liveness the service lifecycle writes.
 *
 * The sibling checks in [AutoPasteWiringTest] read source text, and text can be rearranged: an
 * exported `isBound` backed by its own `MutableStateFlow(true)` leaves every one of them green
 * while the Settings row reports Ready with no service bound, which is issue #16 exactly. Reading
 * the objects instead of the source is not a stricter version of that check, it is a different
 * kind of check, and it is the one that cannot be worded around.
 *
 * Reflection is deliberate. `boundState` and `publishBinding` are private because nothing in
 * production may reach them, and widening them so a test could would damage the property being
 * protected (`validation-discipline.md` RULE: do-not-loosen-production-to-suit-a-test).
 */
class AutoPasteLivenessExportTest {

    private fun boundStateField(): Field =
        sequenceOf(PasteAccessibilityService::class.java, PasteAccessibilityService.Companion::class.java)
            .mapNotNull { runCatching { it.getDeclaredField("boundState") }.getOrNull() }
            .firstOrNull()
            ?.apply { isAccessible = true }
            ?: error("PasteAccessibilityService no longer has a boundState field to publish liveness into")

    private fun publishBindingMethod(): Method =
        sequenceOf(PasteAccessibilityService.Companion::class.java, PasteAccessibilityService::class.java)
            .flatMap { it.declaredMethods.asSequence() }
            .firstOrNull { it.name.contains("publishBinding") && it.parameterCount == 1 }
            ?.apply { isAccessible = true }
            ?: error("PasteAccessibilityService no longer has a publishBinding to write liveness with")

    @Suppress("UNCHECKED_CAST")
    private fun boundState(): MutableStateFlow<Boolean> =
        boundStateField().get(null) as? MutableStateFlow<Boolean>
            ?: error("boundState is not a MutableStateFlow, so nothing can push liveness into it")

    @Test
    fun theFlowTheUiCollectsIsTheStateTheServiceWrites() {
        val backing = boundState()
        val original = backing.value
        try {
            backing.value = true
            assertTrue(
                "PasteAccessibilityService.isBound does not follow the state publishBinding writes, " +
                    "so every readiness surface can report Ready while no service is bound. " +
                    "That is issue #16.",
                PasteAccessibilityService.isBound.value,
            )
            backing.value = false
            assertEquals(
                "PasteAccessibilityService.isBound stayed true after liveness was retracted, so a " +
                    "dead service still reports Ready",
                false,
                PasteAccessibilityService.isBound.value,
            )
        } finally {
            backing.value = original
        }
    }

    @Test
    fun retractingTheBindingRetractsBothFactsTogether() {
        val backing = boundState()
        val original = backing.value
        try {
            backing.value = true
            publishBindingMethod().invoke(PasteAccessibilityService.Companion, null)
            assertEquals(
                "publishBinding(null) did not clear the flow the UI collects, so the insertion path " +
                    "and the UI can disagree about the same service",
                false,
                PasteAccessibilityService.isBound.value,
            )
            val instance = sequenceOf(
                PasteAccessibilityService::class.java,
                PasteAccessibilityService.Companion::class.java,
            )
                .mapNotNull { runCatching { it.getDeclaredField("instance") }.getOrNull() }
                .first()
                .apply { isAccessible = true }
                .get(null)
            assertNull(
                "publishBinding(null) did not clear the instance the insertion path reads",
                instance,
            )
        } finally {
            backing.value = original
        }
    }
}
