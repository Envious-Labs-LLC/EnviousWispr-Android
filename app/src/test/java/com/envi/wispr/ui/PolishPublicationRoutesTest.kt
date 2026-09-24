package com.envi.wispr.ui

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Drift Guard (#77), read off the session owner's source because the service has no JVM harness: the
 * outcome callback and the fallback are the only two routes into `publishResult` (since #237 both reach it
 * as a `PreparedText` from `TakePolishController`, through the owner's `publishPrepared`); the polish facts
 * are derived exactly once, there; the notice is posted before the persistence coroutine starts; the
 * nine fallback producers carry the reasons the plans enumerated (#77's eight, plus #214's blank answer); and the ready-row insert stores all
 * three facts. When this fails, a new direct publisher, a second derivation, or a dropped fact has
 * appeared and the History row and the completion surface can disagree about one dictation.
 */
class PolishPublicationRoutesTest {
    /** The owner since #186; the notice reaches the notification through the `host` seam. */
    private val source = SessionSources.coordinator
    /** The History row and the delivery since #216. */
    private val finalizer = SessionSources.finalizer
    /** The request, its listener and the fallback since #237. */
    private val polish = SessionSources.polish

    private fun section(start: String, end: String, text: String = source): String {
        val from = text.indexOf(start)
        val to = text.indexOf(end, from + start.length)
        require(from >= 0 && to > from) { "Missing source section: $start" }
        return text.substring(from, to)
    }

    @Test fun publishResultIsCalledFromExactlyTheTwoRoutes() {
        val calls = Regex("""(^|[^A-Za-z0-9_.])publishResult\(""").findAll(SessionSources.all).count()
        // The declaration plus the two branches of `publishPrepared`, the owner's one door for the controller.
        assertEquals(3, calls)
        val prepared = section("private fun publishPrepared(", "\n\n")
        assertEquals(2, Regex("""\bpublishResult\(""").findAll(prepared).count())
        // The controller hands text back through ONE main-thread door, `handBack` (#253), reached from exactly
        // the outcome callback and its fallback.
        assertEquals(1, Regex("""(^|[^A-Za-z0-9_.])onPrepared\(""").findAll(polish).count())
        assertEquals(1, Regex("""\bonPrepared\(""").findAll(section("private fun handBack(", "\n    }\n", polish)).count())
        val outcome = section("override fun onOutcome", "override fun onResult", polish)
        val fallback = section("private fun fallBack", "private fun handBack(", polish)
        assertEquals(1, Regex("""\bhandBack \{""").findAll(outcome).count())
        assertEquals(1, Regex("""\bhandBack \{""").findAll(fallback).count())
    }

    @Test fun theFactsAreDerivedOnceTheWriteIsEnqueuedWithTheReservationAndTheNoticePrecedesTheContinuation() {
        assertEquals(1, Regex("""PolishPublicationFacts\.from\(""").findAll(SessionSources.all).count())
        val publication = section("private fun publishResult(", "private fun cancelRecording()")
        // Since #293 the notice is the presenter's, which still posts the toast then the notification.
        val notice = publication.indexOf("notices.sayPolishFailure(notice)")
        val presenter = section("fun sayPolishFailure(", "\n    }\n", SessionSources.notices)
        assertTrue(presenter.indexOf("host.toastFromService(notice.toastLine)") in 0 until presenter.indexOf("host.showPolishNotice(notice)"))
        // The polish facts, their breadcrumb and their defect keep their place before the payload and the reservation.
        val order = listOf(
            "takeFacts.recordPolish(reason, latencyMs, statusCode, polishContext.encode())",
            "Telemetry.breadcrumb(\"take\", \"polish_done\", polishRecord.breadcrumb)",
            "reportDefect(it, polishRecord.defectData)",
            "val payload = Publication(",
            "synchronized(publishLock)",
        ).map { publication.indexOf(it) }
        assertTrue("recordPolish < breadcrumb < defect < payload < reservation: $order", order.all { it >= 0 } && order == order.sorted())
        // The host delegate must still reach the notification controller (Codex review C1, 2026-09-20).
        assertTrue(File("src/main/java/com/envi/wispr/ui/DictationSessionService.kt").readText().contains("DictationNotificationController.showPolishNotice(this@DictationSessionService, notice)"))
        // Since #115 the History write is ENQUEUED in the same operation as the reservation, under the
        // publish lock, so it precedes the notice in the source; the notice still precedes the owner's
        // continuation coroutine, which is where the save's result is consumed. Since #216 the write is the
        // finalizer's, asked for inside the owner's lock, and it must still BE the finalize enqueue.
        val head = "val publication = synchronized(publishLock) {"
        assertTrue(publication.contains(head))
        val locked = publication.substringAfter(head).substringBefore("\n        }\n")
        assertTrue(
            "the reservation and the save are one operation under the lock",
            locked.contains("current.arbiter.reserve(Claimants.PUBLICATION)") &&
                locked.contains("finalizer.enqueueSave(current.history, payload, saved, takeId)"),
        )
        // Since #304 the save's diagnostics are the application's observer, asked for by the finalizer; the owner
        // no longer watches the save itself.
        assertFalse("the owner does not watch the save", SessionSources.coordinator.contains("watchSaveBound("))
        assertTrue(section("fun enqueueSave(", "\n    }\n", finalizer).contains("historySaves.observe(saved, enqueuedAtMs, takeId)"))
        val reservation = publication.indexOf("finalizer.enqueueSave(current.history, payload, saved, takeId)")
        assertTrue(
            "the finalizer's save is the finalize write",
            section("fun enqueueSave(", "\n    }\n", finalizer).contains("historyWrites.enqueue(\"finalize\", WriteKind.TERMINAL)"),
        )
        val continuation = publication.indexOf("scope.launch")
        assertTrue("the write is enqueued with the reservation", reservation >= 0 && reservation < notice)
        assertTrue("the notice is posted before the continuation coroutine starts", notice >= 0 && notice < continuation)
    }

    @Test fun theNineFallbackProducersCarryTheirReasonsAndTheReadyInsertStoresAllThreeFacts() {
        assertEquals(2, Regex("""fallBack\([^\n]*PolishReason\.SERVICE_DIED\)""").findAll(polish).count())
        // Since #234 the lost-polish producer publishes the take's latched reason: SERVICE_UNAVAILABLE (refused
        // at bind, or never connected) or SERVICE_DIED (its process died before the request was sent).
        assertEquals(1, Regex("""fallBack\(rawText, takePreferences, checkNotNull\(fallback\)\)""").findAll(polish).count())
        assertEquals(1, Regex("""fallBack\([^\n]*PolishReason\.WATCHDOG_TIMEOUT\)""").findAll(polish).count())
        // Five since #214: the four protocol violations and the call that threw, plus a blank answer over real words.
        assertEquals(5, Regex("""fallBack\([^\n]*PolishReason\.CALL_FAILED\)""").findAll(polish).count())
        val ready = section("private suspend fun TranscriptRepository.insertSavedTranscript", "/** @return whether", finalizer)
        assertTrue(ready.contains("polishReason = polishFacts.reasonToken"))
        assertTrue(ready.contains("polishStatus = polishFacts.statusCode"))
        assertTrue(ready.contains("polishContext = polishFacts.contextToken"))
    }
}
