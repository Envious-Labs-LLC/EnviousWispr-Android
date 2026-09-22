package com.envi.wispr.debug

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Product Outcome (#194, `kotlin-patterns.md` RULE: no-content-in-diagnostics): what the logger writes for
 * a failure carries the failure's CLASS and one code location, never its message and never the message of
 * anything in its cause chain. A logcat line is readable by anyone with the phone on a cable, so a
 * transcript or a key inside an exception message is content leaving the app.
 *
 * Every expected value here is a LITERAL; nothing passes through [DebugLogger.render] on the way to the
 * assertion. The frame rows SET the stack-trace array explicitly so the branch each proves is the one it
 * names, not the one the JVM happened to produce.
 */
class DebugLoggerRenderTest {

    /** REVERT: render `throwable.toString()`, or hand the throwable to a three-argument `Log.e`. */
    @Test
    fun aThrowablesMessageNeverReachesTheLine() {
        val failure = RuntimeException(
            "TRANSCRIPT_MARKER https://provider.example/v1?k=SECRET_MARKER",
            IllegalStateException("MARKER2"),
        )
        val line = DebugLogger.render("Polish failed", failure)
        assertTrue(line, line.startsWith("Polish failed (RuntimeException at DebugLoggerRenderTest."))
        assertTrue(line, line.endsWith(") caused by IllegalStateException"))
        assertFalse(line, line.contains("TRANSCRIPT_MARKER"))
        assertFalse(line, line.contains("SECRET_MARKER"))
        assertFalse(line, line.contains("MARKER2"))
        assertFalse(line, line.contains("provider.example"))
    }

    /** REVERT: append a suffix when there is no throwable. */
    @Test
    fun noThrowableRendersTheMessageAlone() {
        assertEquals("x", DebugLogger.render("x", null))
    }

    /** REVERT: take the top frame unconditionally. */
    @Test
    fun prefersTheFirstAppFrameAndFallsBackToTheTop() {
        val lib = StackTraceElement("lib.Frame", "a", "Frame.kt", 1)
        val ours = StackTraceElement("com.envi.wispr.x.Y", "b", "Y.kt", 2)

        val throughOurCode = RuntimeException("m").apply { stackTrace = arrayOf(lib, ours) }
        assertEquals("x (RuntimeException at Y.b:2)", DebugLogger.render("x", throughOurCode))

        val libraryOnly = RuntimeException("m").apply { stackTrace = arrayOf(lib) }
        assertEquals("x (RuntimeException at Frame.a:1)", DebugLogger.render("x", libraryOnly))
    }

    /** REVERT: index the first frame without the empty guard (the render then throws). */
    @Test
    fun emptyStackRendersNoFrame() {
        val bare = RuntimeException("m").apply { stackTrace = emptyArray() }
        assertEquals("x (RuntimeException at no frame)", DebugLogger.render("x", bare))
    }

    /**
     * REVERT: drop the identity set (the two-node loop then never ends; the timeout is the tell) or the
     * cap (six names appear).
     */
    @Test(timeout = 5_000)
    fun causeWalkStopsAtARepeatOrFourClasses() {
        val a = RuntimeException("a").apply { stackTrace = emptyArray() }
        val b = IllegalStateException("b", a)
        a.initCause(b)
        assertEquals("x (RuntimeException at no frame) caused by IllegalStateException, …", DebugLogger.render("x", a))

        var deep: Throwable = IllegalArgumentException("6")
        for (i in 5 downTo 1) deep = IllegalArgumentException("$i", deep)
        val top = RuntimeException("0", deep).apply { stackTrace = emptyArray() }
        assertEquals(
            "x (RuntimeException at no frame) caused by IllegalArgumentException, IllegalArgumentException, " +
                "IllegalArgumentException, IllegalArgumentException, …",
            DebugLogger.render("x", top),
        )
        assertEquals(4, DebugLogger.MAX_CAUSE_CLASSES)
    }
}
