package com.envi.wispr.paste

import com.envi.wispr.settings.AppPreferencesState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * DRIFT GUARD for #523's wiring, not product coverage. Each row pins one decision of the reviewed plan so a
 * later edit cannot quietly route around it: one place tells the overlay a field is active; the insertion
 * runner asks the shared password rule; the hide list stays out of the telemetry-fed settings state.
 *
 * REVERTS (performed before commit): make the runner's `isSensitive` return `false` and
 * [theInsertionRunnerAsksTheSharedPasswordRule] turns red; call `overlay.fieldActivated(key)` directly in
 * `revalidateBubbleField` and [onlyActivateFieldTellsTheOverlayAFieldIsActive] turns red.
 */
class BubbleVisibilityWiringTest {

    private val host = PasteSources.bubble
    private val runner = PasteSources.runner
    private val rule = File("src/main/java/com/envi/wispr/paste/PasswordFieldRule.kt").readText()
    private val overlay = File("src/main/java/com/envi/wispr/paste/RecordingAccessibilityOverlay.kt").readText()
    private val settingsViewModel = File("src/main/java/com/envi/wispr/ui/BubbleVisibilityViewModel.kt").readText()

    private fun count(text: String, needle: String) = text.windowed(needle.length).count { it == needle }

    @Test
    fun onlyActivateFieldTellsTheOverlayAFieldIsActive() {
        assertEquals("one overlay.fieldActivated in the paste service", 1, count(PasteSources.all, "overlay.fieldActivated("))
        val activate = PasteSources.slice(host, "private fun activateField(", "private fun keyguardLocked(")
        assertTrue(activate.contains("overlay.fieldActivated(key)"))
        // Both former call sites: the remembered-field event and the revalidation.
        assertEquals("two callers of activateField", 2, count(host, "activateField(overlay, "))
        assertTrue(host.contains("tracker.rememberedFieldKey()?.let { activateField(overlay, it) }"))
        assertTrue(host.contains("if (key != null) activateField(overlay, key) else overlay.fieldLost()"))
    }

    @Test
    fun everyHiddenDecisionSuppressesAndNoBubbleIsDrawnBeforeTheListIsKnown() {
        val activate = PasteSources.slice(host, "private fun activateField(", "private fun keyguardLocked(")
        assertTrue(activate.contains("val stored = visibilityStored ?: return overlay.fieldSuppressed(key)"))
        assertTrue(activate.contains("BubbleVisibility.Decision.HiddenPassword -> overlay.fieldSuppressed(key)"))
        assertFalse("the decision `when` stays exhaustive", activate.contains("else ->"))
    }

    @Test
    fun aChangedListReachesTheFocusedFieldAtOnce() {
        val collect = PasteSources.slice(host, "bubbleVisibilityStored.collect", "lookScope.launch")
        assertTrue(collect.contains("visibilityStored = stored"))
        assertTrue(collect.contains("refresh()"))
    }

    @Test
    fun lockAndUnlockReCheckTheFocusedField() {
        // Measured 2026-11-08 on a Galaxy S24 Ultra (Test Lab): unlock above a shown-over-lock app sends no
        // window event, so without these broadcasts the lips stayed hidden until a re-tap.
        listOf("Intent.ACTION_SCREEN_ON", "Intent.ACTION_SCREEN_OFF", "Intent.ACTION_USER_PRESENT").forEach {
            assertTrue("$it is not watched", host.contains("addAction($it)"))
        }
        // The retrying discovery, not a single refresh: the unlock broadcast can beat the window's focus back.
        val receiver = PasteSources.slice(host, "private val lockReceiver", "\n    }\n")
        assertTrue(receiver, receiver.contains("discoverFieldWithRetry()"))
        assertFalse(receiver.contains("refresh()"))
        assertTrue("registered with the main handler", host.contains("}, null, mainHandler, Context.RECEIVER_NOT_EXPORTED)"))
        assertTrue("unregistered at close", PasteSources.slice(host, "fun close()", "\n    }").contains("unregisterReceiver(lockReceiver)"))
    }

    @Test
    fun aKeyguardReadThatFailsCountsAsUnlocked() {
        val read = PasteSources.slice(host, "private fun keyguardLocked()", "\n    }")
        assertTrue(read.contains("isKeyguardLocked ?: false"))
        assertTrue(host.substringAfter("private fun keyguardLocked()").contains(".getOrDefault(false)"))
    }

    @Test
    fun suppressionEndsOnlyAnIdleDragNeverARunningTake() {
        val suppressed = PasteSources.slice(overlay, "fun fieldSuppressed(key: Any)", "fun fieldLost()")
        assertTrue(suppressed.contains("val endIdleDrag = !snapshot.visible && dragBox != null"))
        // The cancel lives INSIDE the guard; moved outside it, it would end a hold-to-talk (Codex code round 1).
        assertTrue(
            suppressed,
            Regex("""if\s*\(endIdleDrag\)\s*\{\s*cancelGesture\(\)\s*removeHideTarget\(\)\s*}""").containsMatchIn(suppressed),
        )
    }

    @Test
    fun aSuppressedDifferentFieldStillEndsADropToHide() {
        val suppressed = PasteSources.slice(overlay, "fun fieldSuppressed(key: Any)", "fun fieldLost()")
        assertTrue(suppressed.contains("if (hiddenForKey != null && hiddenForKey != key) hiddenForKey = null"))
        val activate = PasteSources.slice(host, "private fun activateField(", "private fun keyguardLocked(")
        assertEquals("both suppress calls pass the field", 2, count(activate, "overlay.fieldSuppressed(key)"))
        assertFalse(activate.contains("fieldSuppressed()"))
    }

    @Test
    fun theInsertionRunnerAsksTheSharedPasswordRule() {
        assertTrue(runner.contains("private fun isSensitive(node: AccessibilityNodeInfo): Boolean = PasswordFieldRule.isPasswordField(node)"))
        assertTrue(runner.contains("sensitive = isSensitive(node)"))
        assertFalse("no second copy of the password predicate", runner.contains("TYPE_TEXT_VARIATION_PASSWORD"))
        // isPassword short-circuits before the input type is read, as the runner always did.
        assertTrue(rule.contains("node.isPassword || isPasswordInputType(node.inputType)"))
    }

    @Test
    fun theHideListNeverJoinsTheTelemetryFedSettingsState() {
        val fields = AppPreferencesState::class.java.declaredFields.map { it.name.lowercase() }
        assertFalse(fields.toString(), fields.any { "hidden" in it || "showonpassword" in it || "visibility" in it })
        assertFalse(settingsViewModel.contains("Telemetry"))
        assertFalse(settingsViewModel.contains("changeSetting"))
    }
}
