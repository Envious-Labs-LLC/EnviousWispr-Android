package com.envi.wispr.asr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Drift Guard (#212) on how `AsrService` is wired to its [RecognizerOwner]: teardown only closes the
 * owner, a closed owner answers `MODEL_NOT_LOADED`, and the file checks keep their precedence. Source-level
 * because the service is an Android `Service` this module cannot construct in a JVM test.
 */
class AsrServiceShapeTest {

    private val service = File("src/main/java/com/envi/wispr/asr/AsrService.kt").readText()

    /** The text between [signature]'s opening brace and its matching close. */
    private fun body(signature: String): String {
        val start = service.indexOf(signature)
        assertTrue("$signature must exist", start >= 0)
        val open = service.indexOf('{', start)
        var depth = 0
        for (i in open until service.length) {
            when (service[i]) {
                '{' -> depth++
                '}' -> if (--depth == 0) return service.substring(open + 1, i)
            }
        }
        error("unbalanced $signature")
    }

    // REVERT R7 (add transcriptionExecutor.shutdownNow() to onDestroy) turns this red.
    @Test
    fun onDestroyOnlyClosesTheOwner() {
        val statements = body("override fun onDestroy()").lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("//") }
        assertEquals(
            listOf(
                "owner.close()",
                "watchdogScheduler.shutdown()",
                "super.onDestroy()",
                "DebugLogger.log(TAG, \"AsrService destroyed; recognizer release queued\")",
            ),
            statements,
        )
    }

    // REVERT R10 (one refusal reports AsrFailureReason.UNKNOWN) turns this red.
    @Test
    fun closedRequestsAreWiredToModelNotLoaded() {
        val calls = Regex("""owner\.use\(refused = \{ ([^}]*) \}\)""").findAll(service).map { it.groupValues[1] }.toList()
        assertEquals("the file path and the legacy byte path", 2, calls.size)
        calls.forEach { refusal ->
            assertEquals("failure.report(AsrFailureReason.MODEL_NOT_LOADED, \"\")", refusal)
        }
    }

    // REVERT R11 (move the missing-file check after owner.use) turns this red.
    @Test
    fun fileValidationPrecedesOwnerAdmission() {
        val transcribe = body("private fun transcribeFromFile(")
        val admission = transcribe.indexOf("owner.use(")
        assertTrue("the file path is admitted through the owner", admission >= 0)
        listOf("AsrFailureReason.AUDIO_MISSING", "AsrFailureReason.OVER_LIMIT").forEach { reason ->
            val at = transcribe.indexOf(reason)
            assertTrue("$reason is answered in transcribeFromFile", at >= 0)
            assertTrue("$reason is answered before the owner admits the request", at < admission)
        }
    }
}
