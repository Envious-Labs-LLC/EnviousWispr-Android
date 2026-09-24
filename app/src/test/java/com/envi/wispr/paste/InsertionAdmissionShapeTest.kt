package com.envi.wispr.paste

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Drift Guard (#362): accepting an insertion returns before any editor work. The session's publication waits for
 * the answer through `MainThreadHandoff`'s untimed wait; an attempt made inline would hold it through an
 * accessibility action's binder call into another process. Source-level because the runner needs a live
 * `AccessibilityService`; the emulator take is the outcome. MUTATION m1: the first attempt made inline again.
 */
class InsertionAdmissionShapeTest {
    private val runner = File("src/main/java/com/envi/wispr/paste/AccessibilityInsertionRunner.kt").readText()

    private fun body(signature: String): String {
        val start = runner.indexOf(signature)
        assertTrue("$signature must exist", start >= 0)
        val open = runner.indexOf('{', runner.indexOf(')', start))
        var depth = 0
        for (i in open until runner.length) {
            when (runner[i]) {
                '{' -> depth++
                '}' -> if (--depth == 0) return runner.substring(open + 1, i)
            }
        }
        error("unbalanced $signature")
    }

    @Test fun admissionSchedulesTheFirstAttemptAndMakesNoneInline() {
        val request = body("fun requestInsertion(")
        assertEquals("no attempt inside admission", 0, Regex("""\btryPendingInsertion\(""").findAll(request).count())
        assertTrue("the first attempt is the retry loop's first turn", request.contains("scheduleRetry(delayMs = 0L)"))
        assertTrue("scheduled only after the pending insertion is in place", request.indexOf("pendingInsertion = pending") < request.indexOf("scheduleRetry(delayMs = 0L)"))
        assertTrue("and the retry runs the attempt", runner.contains("private val retryRunnable = Runnable {\n        retryScheduled = false\n        tryPendingInsertion()"))
    }
}
