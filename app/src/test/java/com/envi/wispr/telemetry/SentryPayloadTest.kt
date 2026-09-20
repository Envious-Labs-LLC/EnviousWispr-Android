package com.envi.wispr.telemetry

import io.sentry.Breadcrumb
import io.sentry.SentryEvent
import io.sentry.protocol.Message
import io.sentry.protocol.SentryException
import io.sentry.protocol.SentryStackFrame
import io.sentry.protocol.SentryStackTrace
import io.sentry.protocol.SentryThread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Product-outcome test (issue #176): the final Sentry event, as `beforeSend` rewrites it, carries no
 * exception message, no local variable, no source line and no unscrubbed path, on exception frames and
 * on thread frames alike (code review round 1, F1). The SDK types are plain Java, so this runs on the JVM.
 */
class SentryPayloadTest {

    private fun frame(absPath: String?, filename: String?, pkg: String?): SentryStackFrame = SentryStackFrame().apply {
        this.absPath = absPath
        this.filename = filename
        this.`package` = pkg
        contextLine = "val words = \"meet me at six\""
        preContext = listOf("// before")
        postContext = listOf("// after")
        vars = mapOf("transcript" to "meet me at six")
        function = "publishResult"
        module = "com.envi.wispr.ui.DictationSessionService"
        lineno = 1200
    }

    @Test
    fun anExceptionKeepsItsTypeAndFrameLocationsAndLosesEverythingThatCanCarryAValue() {
        val event = SentryEvent(RuntimeException("meet me at six"))
        val exception = SentryException().apply {
            type = "RuntimeException"
            value = "meet me at six"
            stacktrace = SentryStackTrace(listOf(frame("/data/user/0/com.envi.wispr/files/x.kt", "DictationSessionService.kt", null)))
        }
        event.exceptions = listOf(exception)
        event.threads = listOf(SentryThread().apply { stacktrace = SentryStackTrace(listOf(frame(null, "libgeniex.so", "/data/app/~~abc==/com.envi.wispr-xyz==/lib/arm64/libgeniex.so"))) })

        val out = SentryBootstrap.sanitize(event)

        val ex = out.exceptions!!.single()
        assertEquals("RuntimeException", ex.type)
        assertNull("the message is always dropped", ex.value)
        val exFrame = ex.stacktrace!!.frames!!.single()
        assertEquals("[PATH]", exFrame.absPath)
        assertEquals("DictationSessionService.kt", exFrame.filename)
        assertEquals("publishResult", exFrame.function)
        assertEquals(1200, exFrame.lineno)
        assertNull(exFrame.contextLine)
        assertNull(exFrame.preContext)
        assertNull(exFrame.postContext)
        assertNull(exFrame.vars)
        val threadFrame = out.threads!!.single().stacktrace!!.frames!!.single()
        assertEquals("a library path outside the scrubbed roots passes as a location", "/data/app/~~abc==/com.envi.wispr-xyz==/lib/arm64/libgeniex.so", threadFrame.`package`)
        assertNull(threadFrame.vars)
        assertNull(threadFrame.contextLine)
    }

    @Test
    fun aMessageAndABreadcrumbAreScrubbedNotRemoved() {
        val event = SentryEvent().apply { message = Message().apply { formatted = "saved /sdcard/EnviousWispr/debug.log for saurabh@example.com" } }
        assertEquals("[REDACTED]", SentryBootstrap.sanitize(event).message!!.formatted)
        val crumb = Breadcrumb("open /data/data/com.envi.wispr/cache/y failed").apply { setData("path", "/sdcard/z") }
        val out = SentryBootstrap.sanitize(crumb)
        assertEquals("open [PATH] failed", out.message)
        assertEquals("[PATH]", out.data["path"])
    }
}
