package com.envi.wispr.feedback

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.regex.Pattern

/** Product Outcome: invalid or unsaved feedback never disappears, and resending a crash-restored draft cannot duplicate admission. */
class FeedbackStoreTest {
    @get:Rule val temp = TemporaryFolder()
    private fun record(message: String = "Android feedback marker") = FeedbackRecord(UUID.randomUUID().toString().replace("-", ""), 1000, message, null, null, null, context())
    private fun store(file: File = File(temp.root, "feedback.json")) = FeedbackStore(file)
    @Test fun draftSurvivesRestart() {
        val draft = FeedbackDraft(1, "My last word was lost.\nPlease take a look.", "")
        store().saveDraft(draft)
        assertEquals(draft, store().read().draft)
    }
    @Test fun admissionAtomicallyClearsOnlySubmittedDraftAndKeepsIdAfterDelivery() {
        val draft = FeedbackDraft(1, "My message", "reply@example.invalid")
        val report = record(draft.message)
        store().saveDraft(draft)
        assertEquals(FeedbackStore.Admission.SAVED, store().admit(draft, report))
        val restarted = store().read()
        assertEquals("", restarted.draft.message)
        assertEquals(report.id, restarted.admittedId)
        assertEquals(report, restarted.records.single())
        store().settle(report.id, FeedbackReply(FeedbackReply.Kind.ACCEPTED), "launch")
        assertEquals(FeedbackStore.Admission.ALREADY_SAVED, store().admit(draft, record()))
        assertTrue(store().read().records.isEmpty())
        assertEquals(report.id, store().read().admittedId)
    }
    @Test fun admissionPreservesNewerEditsAndLateWritesCannotResurrectSentText() {
        val submitted = FeedbackDraft(1, "first", "")
        val newer = FeedbackDraft(2, "second", "")
        store().saveDraft(submitted); store().saveDraft(newer)
        store().admit(submitted, record("first"))
        store().saveDraft(submitted)
        assertEquals(newer, store().read().draft)
    }
    @Test fun oldRevisionAfterTwoAdmissionsAndRestartCannotCreateThirdReport() {
        val first = FeedbackDraft(1, "first", "")
        val second = FeedbackDraft(2, "second", "")
        val a = record("first"); val b = record("second")
        store().admit(first, a)
        store().settle(a.id, FeedbackReply(FeedbackReply.Kind.ACCEPTED), "launch")
        store().admit(second, b)
        assertEquals(FeedbackStore.Admission.STALE, store().admit(first, record("first")))
        assertEquals(listOf(b), store().read().records)
    }
    @Test fun draftWritesOutOfOrderKeepLatest() {
        store().saveDraft(FeedbackDraft(3, "latest", ""))
        store().saveDraft(FeedbackDraft(2, "old", ""))
        assertEquals("latest", store().read().draft.message)
    }
    @Test fun fullQueueRetainsDraft() {
        val store = store()
        repeat(FeedbackStore.MAX_REPORTS) { store.admit(FeedbackDraft(it.toLong(), "queued", ""), record()) }
        val draft = FeedbackDraft(100, "keep me", "")
        store.saveDraft(draft)
        assertEquals(FeedbackStore.Admission.FULL, store.admit(draft, record()))
        assertEquals(draft, store.read().draft)
    }
    @Test fun failedCommitDoesNotClearDraftOrConfirmAdmission() {
        val file = File(temp.root, "state.json")
        val store = store(file)
        val draft = FeedbackDraft(1, "keep me", "")
        store.saveDraft(draft)
        val failing = FeedbackStore(file) { _, _ -> throw IOException("Fixture refuses commit") }
        assertThrows(IOException::class.java) { failing.admit(draft, record()) }
        assertEquals(draft, store.read().draft)
        assertTrue(store.read().records.isEmpty())
    }
    @Test fun corruptStateIsPreservedAndNotOverwritten() {
        val file = File(temp.root, "state.json").apply { writeText("not-json") }
        assertThrows(Exception::class.java) { store(file).admit(FeedbackDraft(1, "keep", ""), record()) }
        assertEquals("not-json", file.readText())
    }
    @Test fun successfulResponseRemovesOwnRecordAndCommitsGlobalHoldTogether() {
        val a = record(); val b = record()
        store().admit(FeedbackDraft(1, "a", ""), a); store().admit(FeedbackDraft(2, "b", ""), b)
        store().settle(a.id, FeedbackReply(FeedbackReply.Kind.ACCEPTED, 99_000), "launch")
        val state = store().read()
        assertEquals(listOf(b), state.records)
        assertEquals(99_000L, state.holdUntilMs)
    }
    @Test fun permanentRejectionRetainsReportAndConfigPauseReopensOnlyOnNewLaunch() {
        val report = record()
        store().admit(FeedbackDraft(1, "report", ""), report)
        store().settle(report.id, FeedbackReply(FeedbackReply.Kind.CONFIGURATION, 50_000), "launch1")
        assertEquals("launch1", store().recover("launch1").pausedLaunch)
        val recovered = store().recover("launch2")
        assertNull(recovered.pausedLaunch)
        assertEquals(50_000L, recovered.holdUntilMs)
        assertEquals(FeedbackRecordState.PENDING, recovered.records.single().state)
        store().settle(report.id, FeedbackReply(FeedbackReply.Kind.REJECTED), "launch2")
        assertEquals(FeedbackRecordState.REJECTED, store().recover("launch3").records.single().state)
    }
    @Test fun responseWriteFailureBlocksNextSendUntilSettlementSucceeds() {
        val file = File(temp.root, "state.json")
        val good = store(file)
        val report = record()
        good.admit(FeedbackDraft(1, "report", ""), report)
        val failing = FeedbackStore(file) { _, _ -> throw IOException("Fixture refuses commit") }
        try {
            assertThrows(IOException::class.java) { FeedbackDelivery.responseReceived(failing, report.id, FeedbackReply(FeedbackReply.Kind.ACCEPTED, 99_000)) }
            assertFalse(FeedbackDelivery.retrySettlement(failing))
            assertEquals(1, good.read().records.size)
            assertTrue(FeedbackDelivery.retrySettlement(good))
            assertTrue(good.read().records.isEmpty())
            assertEquals(99_000L, good.read().holdUntilMs)
        } finally { FeedbackDelivery.retrySettlement(good) }
    }
}

/** Product Outcome: feedback fields use the Mac's Unicode and optional-email limits, never truncate a report. */
class FeedbackValidationTest {
    private val graphemes: (String) -> Int = { text ->
        val matcher = Pattern.compile("\\X").matcher(text)
        var count = 0
        while (matcher.find()) count++
        count
    }
    @Test fun optionalReplyAddressIsOptionalButBadAddressIsRefused() {
        assertNull(FeedbackValidation.issue(FeedbackDraft(message = "hello"), graphemes))
        assertEquals(FeedbackValidation.Issue.EMAIL, FeedbackValidation.issue(FeedbackDraft(message = "hello", email = "a..b@example.com"), graphemes))
        assertNull(FeedbackValidation.issue(FeedbackDraft(message = " hello ", email = " José@bücher.de "), graphemes))
    }
    @Test fun bothUnicodeLimitsApplyIndependently() {
        assertNull(FeedbackValidation.issue(FeedbackDraft(message = "a".repeat(4000)), graphemes))
        assertEquals(FeedbackValidation.Issue.TOO_LONG, FeedbackValidation.issue(FeedbackDraft(message = "a".repeat(4001)), graphemes))
        assertNull(FeedbackValidation.issue(FeedbackDraft(message = "a\u0301".repeat(2048)), graphemes))
        assertEquals(FeedbackValidation.Issue.TOO_LONG, FeedbackValidation.issue(FeedbackDraft(message = "a\u0301".repeat(2048) + "b"), graphemes))
        assertNull(FeedbackValidation.issue(FeedbackDraft(message = "👨‍👩‍👧‍👦".repeat(585)), graphemes))
        assertEquals(FeedbackValidation.Issue.TOO_LONG, FeedbackValidation.issue(FeedbackDraft(message = "👨‍👩‍👧‍👦".repeat(586)), graphemes))
        assertEquals(FeedbackValidation.Issue.EMPTY, FeedbackValidation.issue(FeedbackDraft(message = " \n "), graphemes))
    }
}

/** Observability Contract: only Android routing and explicit correspondence/consent cross the wire; byte lengths are real UTF-8 lengths. */
class FeedbackEnvelopeTest {
    private val destination = FeedbackDestination.parse("https://abc123@${FeedbackDestination.HOST}/${FeedbackDestination.PROJECT}")!!
    private fun parts(bytes: ByteArray): List<Pair<JSONObject, ByteArray>> {
        val input = ByteArrayInputStream(bytes)
        fun line(): JSONObject {
            val data = ArrayList<Byte>()
            while (true) { val next = input.read(); if (next == 10 || next == -1) break; data.add(next.toByte()) }
            return JSONObject(data.toByteArray().toString(Charsets.UTF_8))
        }
        val envelope = line()
        assertEquals(destination.dsn, envelope.getString("dsn"))
        val list = ArrayList<Pair<JSONObject, ByteArray>>()
        while (input.available() > 0) {
            val header = line()
            val body = input.readNBytes(header.getInt("length"))
            assertEquals(header.getInt("length"), body.size)
            assertEquals(10, input.read())
            list.add(header to body)
        }
        return list
    }
    @Test fun wrongProjectHostOrUnencryptedDestinationCannotBeUsed() {
        assertNull(FeedbackDestination.parse("http://abc@${FeedbackDestination.HOST}/${FeedbackDestination.PROJECT}"))
        assertNull(FeedbackDestination.parse("https://abc@${FeedbackDestination.HOST}/123"))
        assertNull(FeedbackDestination.parse("https://abc@evil.example/${FeedbackDestination.PROJECT}"))
        assertNull(FeedbackDestination.parse("https://abc@${FeedbackDestination.HOST}/${FeedbackDestination.PROJECT}?redirect=1"))
    }
    @Test fun longMessageAndUsageLinkArriveIntactWithNoUnconsentedAttachmentOrSdkScope() {
        val message = "Android marker: " + "é".repeat(3884)
        val id = UUID.randomUUID().toString()
        val record = FeedbackRecord("1".repeat(32), 1000, message, null, null, id, context())
        val items = parts(FeedbackSender.envelope(record, destination, 2000))
        assertEquals(1, items.size)
        val json = JSONObject(items.single().second.toString(Charsets.UTF_8))
        assertEquals(message, json.getJSONObject("contexts").getJSONObject("feedback").getString("message"))
        assertEquals("Android", json.getJSONObject("contexts").getJSONObject("os").getString("name"))
        assertEquals("enviouswispr-android", json.getJSONObject("tags").getString("app"))
        assertEquals("android", json.getJSONObject("tags").getString("product.platform"))
        assertEquals(id, json.getJSONObject("tags").getString("analytics.distinct_id"))
        assertEquals("error", json.getString("level"))
        assertEquals("never", json.getJSONObject("sdk").getJSONObject("settings").getString("infer_ip"))
        assertFalse(json.has("user")); assertFalse(json.has("breadcrumbs")); assertFalse(json.has("extra"))
        assertFalse(json.getJSONObject("contexts").getJSONObject("feedback").has("contact_email"))
    }
    @Test fun consentedAttachmentIsExactlyThePreviewIncludingUnicodeAndLineBreaks() {
        val preview = "{\n  \"platform\": \"android\",\n  \"marker\": \"é\"\n}"
        val record = FeedbackRecord("2".repeat(32), 1000, "report", "reply@example.invalid", preview, null, context())
        val items = parts(FeedbackSender.envelope(record, destination, 2000))
        assertEquals(2, items.size)
        assertEquals(FeedbackSender.FILENAME, items[1].first.getString("filename"))
        assertArrayEquals(preview.toByteArray(Charsets.UTF_8), items[1].second)
        assertEquals("reply@example.invalid", JSONObject(items[0].second.toString(Charsets.UTF_8)).getJSONObject("contexts").getJSONObject("feedback").getString("contact_email"))
    }
    @Test fun rateLimitsApplyByCategoryEvenOnSuccessAndRetryAfterAcceptsHttpDates() {
        assertEquals(61_000L, FeedbackReply.classify(200, mapOf("X-Sentry-Rate-Limits" to "60:feedback:organization,120:error:organization"), 1000).holdUntilMs)
        assertEquals(121_000L, FeedbackReply.classify(429, mapOf("Retry-After" to "120"), 1000).holdUntilMs)
        assertEquals(120_000L, FeedbackReply.classify(429, mapOf("Retry-After" to "Thu, 01 Jan 1970 00:02:00 GMT"), 1000).holdUntilMs)
        assertEquals(FeedbackReply.Kind.CONFIGURATION, FeedbackReply.classify(403, emptyMap(), 1000).kind)
        assertEquals(FeedbackReply.Kind.REJECTED, FeedbackReply.classify(413, emptyMap(), 1000).kind)
        assertEquals(FeedbackReply.Kind.RETRY, FeedbackReply.classify(503, emptyMap(), 1000).kind)
    }
}

private fun context() = FeedbackContext("0.1.0", "1", "development", "16", "build", "phone-model")
