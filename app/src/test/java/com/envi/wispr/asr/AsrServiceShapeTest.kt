package com.envi.wispr.asr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Drift Guard (#212) on how `AsrService` is wired to its [RecognizerOwner]: teardown only closes the
 * owner, an unavailable model answers `MODEL_NOT_LOADED`, and guarded file checks keep their precedence. Source-level
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
                "owner.close { AsrDiagnostics.ownerRetired(owner.generationIdentity) }",
                "super.onDestroy()",
                "DebugLogger.log(TAG, \"AsrService destroyed; recognizer release queued\")",
            ),
            statements,
        )
    }

    // Ordinary model absence remains a failure; cancellation now retires without a fabricated failure callback.
    @Test
    fun ordinaryModelAbsenceStillReportsModelNotLoadedBeforeDecoding() {
        val decode = body("private fun doTranscribe(")
        val absent = decode.indexOf("if (rec == null)")
        val failure = decode.indexOf("failure.report(AsrFailureReason.MODEL_NOT_LOADED, \"\")")
        assertTrue(absent >= 0 && failure > absent && failure < decode.indexOf("PcmAudio.toFloatSamples"))
    }

    // C2 deliberately moves metadata to the guarded worker, while retaining missing/oversize-before-decode priority.
    @Test
    fun fileValidationIsGuardedBeforeReadingAndDecoding() {
        val transcribe = body("private fun transcribeFromFile(")
        val admission = transcribe.indexOf("owner.use(")
        assertTrue("the file path is admitted through the owner", admission >= 0)
        listOf("AsrFailureReason.AUDIO_MISSING", "AsrFailureReason.OVER_LIMIT").forEach { reason ->
            val at = transcribe.indexOf(reason)
            assertTrue("$reason is answered in transcribeFromFile", at >= 0)
            assertTrue("$reason is classified inside the owned task", at > admission)
            assertTrue("$reason precedes file reading and decoding", at < transcribe.indexOf("file.readBytes()"))
        }
        assertTrue(transcribe.contains("owner.use(transcriptionBoundMs(RecordingLimits.MAX_AUDIO_BYTES), entry.request)"))
        val metadata = transcribe.indexOf("val audioBytes = file.length()")
        val tighten = transcribe.indexOf("entry.request.tighten(transcriptionBoundMs(audioBytes))")
        assertTrue(metadata > admission && tighten > metadata && tighten < transcribe.indexOf("file.readBytes()"))
    }
}
