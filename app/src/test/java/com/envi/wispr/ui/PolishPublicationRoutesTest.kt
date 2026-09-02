package com.envi.wispr.ui

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Drift Guard (#77), read off the session owner's source because the service has no JVM harness: the
 * outcome callback and `publishFallback` are the only two routes into `publishResult`; the polish facts
 * are derived exactly once, there; the notice is posted before the persistence coroutine starts; the
 * eight fallback producers carry the reasons the plan enumerated; and the ready-row insert stores all
 * three facts. When this fails, a new direct publisher, a second derivation, or a dropped fact has
 * appeared and the History row and the completion surface can disagree about one dictation.
 */
class PolishPublicationRoutesTest {
    private val source = File("src/main/java/com/envi/wispr/ui/DictationSessionService.kt").readText()

    private fun section(start: String, end: String): String {
        val from = source.indexOf(start)
        val to = source.indexOf(end, from + start.length)
        require(from >= 0 && to > from) { "Missing source section: $start" }
        return source.substring(from, to)
    }

    @Test fun publishResultIsCalledFromExactlyTheTwoRoutes() {
        val calls = Regex("""(^|[^A-Za-z0-9_.])publishResult\(""").findAll(source).count()
        // The declaration plus the outcome callback and publishFallback.
        assertEquals(3, calls)
        val outcome = section("override fun onOutcome", "override fun onResult")
        val fallback = section("private fun publishFallback", "private fun deterministicFallback")
        assertEquals(1, Regex("""\bpublishResult\(""").findAll(outcome).count())
        assertEquals(1, Regex("""\bpublishResult\(""").findAll(fallback).count())
    }

    @Test fun theFactsAreDerivedExactlyOnceAndTheNoticePrecedesPersistence() {
        assertEquals(1, Regex("""PolishPublicationFacts\.from\(""").findAll(source).count())
        val publication = section("private fun publishResult(", "private suspend fun insertReadyTranscript")
        val notice = publication.indexOf("DictationNotificationController.showPolishNotice(this, notice)")
        val persistence = publication.indexOf("serviceScope.launch")
        assertTrue("the notice is posted before the persistence coroutine starts", notice >= 0 && notice < persistence)
    }

    @Test fun theEightFallbackProducersCarryTheirReasonsAndTheReadyInsertStoresAllThreeFacts() {
        assertEquals(2, Regex("""publishFallback\([^\n]*PolishReason\.SERVICE_DIED\)""").findAll(source).count())
        assertEquals(1, Regex("""publishFallback\([^\n]*PolishReason\.SERVICE_UNAVAILABLE\)""").findAll(source).count())
        assertEquals(1, Regex("""publishFallback\([^\n]*PolishReason\.WATCHDOG_TIMEOUT\)""").findAll(source).count())
        assertEquals(4, Regex("""publishFallback\([^\n]*PolishReason\.CALL_FAILED\)""").findAll(source).count())
        val ready = section("private suspend fun insertReadyTranscript", "/** @return whether")
        assertTrue(ready.contains("polishReason = polishFacts.reasonToken"))
        assertTrue(ready.contains("polishStatus = polishFacts.statusCode"))
        assertTrue(ready.contains("polishContext = polishFacts.contextToken"))
    }
}
