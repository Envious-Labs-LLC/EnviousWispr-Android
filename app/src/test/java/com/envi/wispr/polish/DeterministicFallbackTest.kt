package com.envi.wispr.polish

import com.envi.wispr.cleanup.CleanupOptions
import com.envi.wispr.cleanup.DetectedLanguage
import com.envi.wispr.cleanup.LanguageDetector
import com.envi.wispr.cleanup.PolishPipeline
import com.envi.wispr.ui.SessionSources
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Product Outcome: when this fails, the words a user gets depend on which side of the binder
 * failed. The retired regex polisher capitalised sentences and appended a period, so "hello world"
 * came back as "Hello world." from the session owner and "hello world" from the engine.
 */
class DeterministicFallbackTest {

    /** A detector with no answer, so cleanup behaves exactly as an English-only build did. */
    private val silent = LanguageDetector { null }

    @Test fun sessionOwnerFallbackAndEngineOffPathReturnTheSameLiteral() {
        val options = CleanupOptions()
        assertEquals("hello world", PolishFallback.deterministic("hello world", options, silent))
        assertEquals("hello world", PolishPipeline.run("hello world", options).text)
    }

    /**
     * Drift Guard, not product coverage, and a SOURCE-SHAPE check rather than a proof: the two rows above
     * exercise the shared helper's text, and this one checks by current source spelling that the session
     * owner still calls it. A matching call left in dead code would satisfy it. Restoring the regex
     * polisher only inside `TakePolishController.deterministic` would leave the rows above green, which is the
     * drift it is here for. (The owner moved from the Service to the coordinator in #186, and its fallback to
     * the take's polish controller in #237.)
     */
    @Test fun sessionOwnerUsesTheSharedDeterministicFallback() {
        val source = java.io.File("src/main/java/com/envi/wispr/ui/TakePolishController.kt").readText()
        // Since #252 the call goes through the controller's `cleanup` seam, whose default IS the shared fallback.
        assertTrue(source.contains("private val cleanup: (String, CleanupOptions, LanguageDetector) -> String = PolishFallback::deterministic,"))
        assertTrue(source.contains("cleanup(prepared, takePreferences.cleanup, languageDetector)"))
        // Every file of the session owner (#216): a regex polisher in a collaborator is the same drift.
        assertFalse(SessionSources.all.contains("RegexPolisher"))
    }

    /**
     * Product Outcome. The shared fallback is what BOTH cleanup terminals run, so if it ignores the
     * language, a German or Dutch dictation gets English number rules applied whichever side failed.
     *
     * This replaced a source-text drift guard that review round 2 defeated: it searched each service
     * file for a detector expression, so a terminal could hard-code an abstention and stay green as long
     * as the expression appeared somewhere else in the file. The detector-taking signature removed the
     * constant-`CleanupLanguage` argument that made that edit easy; an abstaining detector is still
     * expressible. These two rows exercise the shared fallback's BEHAVIOUR, which is the part a source
     * match cannot reach.
     */
    @Test fun theSharedFallbackHonoursAConfidentNonEnglishAnswer() {
        val dutch = "Dit is ten minste duidelijk."
        val sure = LanguageDetector { DetectedLanguage("nl", 1.0f) }
        assertEquals(dutch, PolishFallback.deterministic(dutch, CleanupOptions(), sure))
    }

    /**
     * The two-way control. Without it the row above would pass against a fallback that never cleans
     * anything, because it asserts the input is unchanged.
     */
    @Test fun theSharedFallbackStillAppliesEnglishRulesWhenNothingIsEstablished() {
        val dutch = "Dit is ten minste duidelijk."
        val silent = LanguageDetector { null }
        assertEquals("Dit is 10 minste duidelijk.", PolishFallback.deterministic(dutch, CleanupOptions(), silent))
    }

    /**
     * Drift Guard, and a SMOKE TEST of the current call shape rather than a proof. The two rows above
     * prove the shared fallback's behaviour; this checks each terminal still hands it a detector it
     * built, by matching source text.
     *
     * It cannot prove detector BEHAVIOUR. An abstaining detector written any other way passes, and
     * review round 3 named `LanguageDetector { _ -> null }` as one spelling that does. What it does
     * catch is the call shape drifting away from the production wiring, which is the realistic accident;
     * deliberately substituting a dead detector is not something a source match can stop.
     */
    @Test fun bothTerminalsPassTheirConfiguredDetectorByCurrentSourceShape() {
        // Since #186 the session side is two files, three since #237: the take's polish controller makes the
        // call with the detector the coordinator handed it, and the Service builds the real one in `onCreate`.
        val terminals = listOf(
            Triple(
                "src/main/java/com/envi/wispr/ui/TakePolishController.kt",
                "cleanup(prepared, takePreferences.cleanup, languageDetector)",
                "src/main/java/com/envi/wispr/ui/DictationSessionService.kt",
            ),
            Triple(
                "src/main/java/com/envi/wispr/polish/PolishService.kt",
                "PolishFallback.deterministic(raw, options, languageDetector)",
                "src/main/java/com/envi/wispr/polish/PolishService.kt",
            ),
        )
        terminals.forEach { (path, call, builder) ->
            val source = java.io.File(path).readText()
            assertTrue("$path no longer passes its detector to the shared fallback", source.contains(call))
            assertTrue(
                "$builder no longer builds the real detector",
                java.io.File(builder).readText().contains("MlKitLanguageDetector(applicationContext)"),
            )
        }
        assertTrue(
            "the Service no longer hands its detector to the coordinator",
            java.io.File("src/main/java/com/envi/wispr/ui/DictationSessionService.kt").readText().contains("languageDetector = languageDetector,"),
        )
        assertTrue(
            "the coordinator no longer hands its detector to the take's polish controller",
            java.io.File("src/main/java/com/envi/wispr/ui/DictationSessionCoordinator.kt").readText().contains("languageDetector = languageDetector,"),
        )
    }

    @Test fun fallbackStillAppliesTheTakeCleanupOptions() {
        assertEquals("hello world", PolishFallback.deterministic("uh hello world", CleanupOptions(removeFillers = true), silent))
        assertEquals("uh hello world", PolishFallback.deterministic("uh hello world", CleanupOptions(removeFillers = false), silent))
    }

    /**
     * #278: a request with no policy is a protocol fault, never the user's Off. The engine answers the
     * deterministic text as UNEXPECTED (a defect-channel reason) before it registers the request or runs any
     * pipeline. Source shape: the service needs its own process. MUTATION m6: restore `policy ?: PolishPolicy.Off`.
     */
    @Test fun aRequestWithNoPolicyAnswersTheDeterministicTextAsUnexpected() {
        val source = java.io.File("src/main/java/com/envi/wispr/polish/PolishService.kt").readText()
        assertFalse("a null policy never reads as Off", source.contains("policy ?: PolishPolicy.Off"))
        val guard = source.indexOf("if (policy == null) {")
        assertTrue("the null guard exists", guard >= 0)
        val body = source.substring(guard, source.indexOf("return", guard))
        assertTrue(body.contains("PolishOutcome(requestId, fallbackText(raw, options), PolishEngineLabels.DETERMINISTIC, PolishReason.UNEXPECTED, 0, 0)"))
        assertTrue("before the request is registered", guard < source.indexOf("registry.register(requestId)"))
        assertTrue("before any pipeline work", guard < source.indexOf("PolishPipeline.run("))
    }
}
