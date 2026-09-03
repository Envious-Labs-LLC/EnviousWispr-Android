package com.envi.wispr.vad

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Shape guards on the detector service.
 *
 * Source-level because the subject is an Android `Service` reached across a process boundary, and because
 * what matters is the ORDER of two things rather than any value either produces.
 */
class SilenceVadServiceShapeTest {

    private val source = File("src/main/java/com/envi/wispr/vad/SilenceVadService.kt").readText()

    private fun bodyOf(signature: String): String {
        val start = source.indexOf(signature)
        assertTrue("$signature must exist", start >= 0)
        val end = source.indexOf("\n        override fun ", start + 1)
        return source.substring(start, if (end > start) end else source.length)
    }

    @Test
    fun everyCallTakesTheLockBeforeItArmsItsDeadline() {
        // A call that armed a deadline and THEN waited for the lock can kill this process while a newer
        // take owns it. Taking the lock first means only the call actually doing work is on a clock.
        listOf(
            "override fun start(",
            "override fun processBlock(",
            "override fun finish(",
        ).forEach { signature ->
            val body = bodyOf(signature)
            val lock = body.indexOf("synchronized(lock)")
            val deadline = body.indexOf("guarded(")
            assertTrue("$signature must take the lock", lock >= 0)
            assertTrue("$signature must arm a deadline", deadline >= 0)
            assertTrue("$signature must take the lock BEFORE arming the deadline", lock < deadline)
        }
    }

    @Test
    fun aTokenIsCheckedBeforeAnyDeadlineIsArmed() {
        // Otherwise a stale caller is on a clock purely for having been descheduled.
        listOf("override fun processBlock(", "override fun finish(").forEach { signature ->
            val body = bodyOf(signature)
            assertTrue(
                "$signature must check the token before arming",
                body.indexOf("captureToken != activeToken") < body.indexOf("guarded("),
            )
        }
        val start = bodyOf("override fun start(")
        assertTrue(
            "start must order tokens before arming",
            start.indexOf("tokenOrder.accept(captureToken)") < start.indexOf("guarded("),
        )
    }

    @Test
    fun aCallThatLostItsDeadlineNeverReturns() {
        // Returning would release the lock and let a newer take begin work inside a process that is
        // already scheduled to end.
        assertTrue(source.contains("private fun terminateDetectorProcess(): Nothing"))
        assertTrue(source.contains("if (!active.compareAndSet(true, false)) terminateDetectorProcess()"))
        val terminate = source.substringAfter("private fun terminateDetectorProcess(): Nothing")
        assertTrue("it kills its own process", terminate.contains("Process.killProcess(Process.myPid())"))
        assertTrue("and does not come back while it waits to die", terminate.contains("LockSupport.park()"))
    }

    @Test
    fun theDeadlineMatchesTheRingItIsSizedAgainst() {
        assertTrue(source.contains("const val CALL_DEADLINE_MS = 2_000L"))
        assertTrue("a block is exactly one block", source.contains("const val PCM_BYTES_PER_BLOCK = 8_192"))
    }
}
