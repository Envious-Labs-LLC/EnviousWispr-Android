package com.envi.wispr.paste

import com.envi.wispr.ui.codeOnly
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Drift Guard (#217): the paste service is the Android shell and its published binding; the work is its
 * three collaborators'. [EditorTargetTracker] owns which editor a take aims at, [AccessibilityInsertionRunner]
 * owns one insertion from request to outcome, [AccessibilityBubbleHost] owns the floating bubble, and each
 * has one idempotent `close` the service calls from `onDestroy` in a fixed order. Read as CODE through
 * [codeOnly], so a comment or a string can neither satisfy nor break a row.
 *
 * When it fails, the user sees nothing at once; an edit is putting a domain back into the service, giving a
 * collaborator the service's own settings or binding, or dropping or reordering a delegation.
 * REVERT: move `clearTarget` back into the service; add a second `close` to a collaborator; a
 * `Toast.makeText` in the bubble host; `serviceInfo =` in the runner; drop `tracker.close()` from
 * `onDestroy` or swap `runner.close()` and `historyScope.cancel()`.
 */
class PasteServiceShapeTest {
    private val dir = "src/main/java/com/envi/wispr/paste"
    private val code = codeOnly(
        listOf("PasteAccessibilityService.kt", "EditorTargetTracker.kt", "AccessibilityInsertionRunner.kt", "AccessibilityBubbleHost.kt", "InsertionOutcomeRecorder.kt")
            .map { File("$dir/$it") },
    ).mapKeys { it.key.name }
    private val service = code.getValue("PasteAccessibilityService.kt")
    private val tracker = code.getValue("EditorTargetTracker.kt")
    private val runner = code.getValue("AccessibilityInsertionRunner.kt")
    private val bubble = code.getValue("AccessibilityBubbleHost.kt")
    /** The insertion's ending since #359: the History outcome and the terminal event. */
    private val recorder = code.getValue("InsertionOutcomeRecorder.kt")
    private val all = listOf(service, tracker, runner, bubble, recorder)

    private fun count(text: String, pattern: String) = Regex(pattern).findAll(text).count()
    private fun total(pattern: String) = all.sumOf { count(it, pattern) }

    /** A function's code from [head] to its closing brace at member indentation; fails when either is missing. */
    private fun body(text: String, head: String): String {
        val from = text.indexOf(head)
        check(from >= 0) { "missing: $head" }
        val to = text.indexOf("\n    }\n", from)
        check(to > from) { "no end for: $head" }
        return text.substring(from, to)
    }

    /** The same, for a member of the service's companion (one level deeper). */
    private fun companionBody(head: String): String {
        val from = service.indexOf(head)
        check(from >= 0) { "missing: $head" }
        val to = service.indexOf("\n        }\n", from)
        check(to > from) { "no end for: $head" }
        return service.substring(from, to)
    }

    private fun assertInOrder(text: String, vararg steps: String) {
        var at = -1
        for (step in steps) {
            val next = text.indexOf(step, at + 1)
            assertTrue("'$step' must follow the step before it in:\n$text", next > at)
            at = next
        }
    }

    @Test
    fun theServiceHoldsNoneOfTheMovedDomains() {
        listOf(
            """\bpendingInsertion\b""", """\bpinnedTarget\b""", """\blastTarget\b""", """\brecordingOverlay\b""",
            """\bsetPrimaryClip\(""", """\bfinalizeInsertionOutcome\(""", """\bToast\.makeText\(""",
            """\bregisterAudioDeviceCallback\(""", """\bfindFocus\(""", """\bclearTarget\(""",
        ).forEach { assertEquals("the service's code holds no $it", 0, count(service, it)) }
    }

    @Test
    fun eachCollaboratorHasOneCloseAndEachMechanicOneHome() {
        mapOf("tracker" to tracker, "runner" to runner, "bubble" to bubble).forEach { (name, text) ->
            assertEquals("$name declares exactly one close", 1, count(text, """\bfun close\(\)"""))
        }
        assertEquals("the service itself declares no close", 0, count(service, """\bfun close\(\)"""))
        // The runner owns the announcement and every clipboard write; the recorder owns the outcome write (#359).
        assertEquals("one toast", 1, total("""\bToast\.makeText\("""))
        assertEquals("the toast is the runner's", 1, count(runner, """\bToast\.makeText\("""))
        assertEquals("one outcome enqueue, the recorder's", 1, count(recorder, """\bhistoryWrites\("""))
        assertEquals("no other file enqueues an outcome", 1, total("""\bhistoryWrites\("""))
        assertTrue("the runner writes the clipboard", count(runner, """\bsetPrimaryClip\(""") > 0)
        assertEquals("only the runner writes the clipboard", count(runner, """\bsetPrimaryClip\("""), total("""\bsetPrimaryClip\("""))
        // The tracker owns every search for a focused editor and every pin assignment.
        assertTrue(count(tracker, """\bfindFocus\(""") > 0)
        assertEquals("only the tracker searches for focus", count(tracker, """\bfindFocus\("""), total("""\bfindFocus\("""))
        assertEquals("only the tracker assigns the pin", total("""\bpinnedTarget = """), count(tracker, """\bpinnedTarget = """))
        assertEquals("one pin creation", 1, count(tracker, """\bpinnedTarget\s*=\s*TargetToken\("""))
        // The result haptic is the success cue only: its declaration and one call, inside the Verified branch.
        assertEquals("the result haptic: its declaration and one call", 2, total("""\bperformResultHaptic\("""))
        val verified = runner.substring(runner.indexOf("is InsertionAttempt.Tick.Verified ->"), runner.indexOf("InsertionAttempt.Tick.Sensitive ->"))
        assertTrue("the one call is the Verified branch's", verified.contains("performResultHaptic(success = true)"))
        // The bubble host owns the overlay and the audio-device watch.
        assertEquals("one overlay, built by the bubble host", 1, count(bubble, """\bRecordingAccessibilityOverlay\("""))
        assertEquals("no other file builds one", 1, total("""\bRecordingAccessibilityOverlay\("""))
        assertEquals("one audio-device registration, the bubble host's", 1, count(bubble, """\bregisterAudioDeviceCallback\("""))
        assertEquals("no other file registers one", 1, total("""\bregisterAudioDeviceCallback\("""))
    }

    @Test
    fun noCollaboratorTouchesTheServicesSettingsOrBinding() {
        mapOf("tracker" to tracker, "runner" to runner, "bubble" to bubble).forEach { (name, text) ->
            assertEquals("$name writes no serviceInfo", 0, count(text, """\bserviceInfo\b"""))
            assertEquals("$name publishes no binding", 0, count(text, """\bpublishBinding\b"""))
        }
    }

    @Test
    fun theServiceDelegatesInOrder() {
        assertInOrder(
            body(service, "override fun onAccessibilityEvent("),
            "tracker.rememberEditableTarget(event)", "bubble.updateBubbleFromEvent(event, remembered)", "runner.onAccessibilityEvent(",
        )
        assertTrue(companionBody("internal fun pasteWhenTargetReturns(").contains("service.runner.requestInsertion("))
        assertTrue(companionBody("internal fun pinTargetForDictation()").contains("service.tracker.pinTarget(insertionPending = service.runner.isPending)"))
        assertTrue(companionBody("fun releasePinnedTarget()").contains("if (!service.runner.isPending) service.tracker.clearPinnedTarget()"))
        assertTrue(companionBody("fun pinnedFieldId()").contains("service.tracker.pinnedViewId"))
        assertTrue(companionBody("fun refreshBubble()").contains("service.mainHandler.post { service.bubble.refresh() }"))
        // windowTreeXml stays the service's own read of `windows` (#217 grounded round 2).
        assertTrue(companionBody("fun windowTreeXml()").contains("WindowTreeXml.render(WindowTreeXml.fromWindows(service.windows))"))
        assertInOrder(
            body(service, "override fun onServiceConnected()"),
            "publishBinding(this)", "configureEventMode(includeContentChanges = false)", "bubble.attach()",
            "bubble.discoverOnConnect()", "reportPreviousStop()", "bubble.restorePosition()",
        )
        assertInOrder(
            body(service, "override fun onInterrupt()"),
            "runner.abandon(", "tracker.clearPinnedTarget()", "bubble.cancelDiscoveryRetries()",
        )
        assertInOrder(
            body(service, "override fun onUnbind("),
            "if (instance === this) publishBinding(null)", "markStopWasClean()", "bubble.cancelDiscoveryRetries()",
        )
        assertInOrder(
            body(service, "override fun onDestroy()"),
            "if (instance === this) publishBinding(null)", "bubble.close()", "runner.close()", "historyScope.cancel()",
            "tracker.close()", "markStopWasClean()", "super.onDestroy()",
        )
    }
}
