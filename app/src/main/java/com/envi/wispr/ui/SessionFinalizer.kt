package com.envi.wispr.ui

import com.envi.wispr.history.HistoryRow
import com.envi.wispr.history.HistoryWriteQueue
import com.envi.wispr.history.TranscriptEntity
import com.envi.wispr.history.TranscriptRepository
import com.envi.wispr.insertion.ClipboardInsertionPolicy
import com.envi.wispr.insertion.ClipboardOutcome
import com.envi.wispr.insertion.FallbackAnnouncement
import com.envi.wispr.insertion.InsertionResults
import com.envi.wispr.paste.DictationTargetPin
import com.envi.wispr.paste.InsertionHandoff
import com.envi.wispr.paste.InsertionJudgement
import com.envi.wispr.polish.PolishEngineLabels
import com.envi.wispr.polish.PolishPublicationFacts
import com.envi.wispr.telemetry.AnalyticsEvent
import com.envi.wispr.telemetry.InsertionResultKind
import com.envi.wispr.telemetry.InsertionRouteKind
import com.envi.wispr.telemetry.Telemetry
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicLong

/**
 * One take's History row, from the draft inserted at live to the last status (#216). Every write goes
 * through the application's [HistoryWriteQueue] in enqueue order (#115), so a later write of the row is
 * always queued behind the draft insert and [resolvedId] never waits on anything but a write already
 * applied. It never touches the recorder: the owner attaches the row to its surface.
 *
 * [historyWrites] is null only for [TakeContext.NONE], before any take is admitted; every write is then
 * a no-op, as the old `draftId` of 0 made it.
 */
internal class TakeHistory(private val historyWrites: HistoryWriteQueue?) : HistoryRow {
    private val draftId = AtomicLong(0L)

    /**
     * The row's id once its save answered SAVED (#277), else 0. A draft id after a failed save is not a saved
     * row, so insertion outcomes resolve against this, never against [draftId].
     */
    @Volatile private var savedId = 0L

    /**
     * The row's id, completed by the queued draft insert ON THE QUEUE'S WORKER (#115). Every later write of
     * the row is queued after that insert, so awaiting this inside a write never waits on anything but a
     * write already applied; the owner itself never awaits it on main.
     */
    private var draftCreation: CompletableDeferred<Long>? = null

    /**
     * The FIRST queued write of the take (#115): it runs on the application's worker, under application
     * ownership, so destroy cannot cancel it and every later write of the row is queued behind it. The id
     * comes back through the returned deferred.
     */
    fun insertDraft(takeId: String, createdAtMs: Long): CompletableDeferred<Long> {
        val draft = CompletableDeferred<Long>()
        draftCreation = draft
        historyWrites?.enqueue("draft insert") { repository ->
            val id = try {
                repository.insert(
                    TranscriptEntity(
                        originalText = "",
                        finalText = "",
                        createdAtMs = createdAtMs,
                        durationMs = 0L,
                        speechEngine = "Parakeet",
                        polishEngine = PolishEngineLabels.NOT_RECORDED,
                        polishLatencyMs = 0L,
                        insertionResult = "pending",
                        status = TranscriptEntity.STATUS_DRAFT,
                    ),
                )
            } catch (error: Exception) {
                draft.completeExceptionally(error)
                throw error
            }
            draftId.set(id)
            Telemetry.journal?.associate(takeId, id)
            draft.complete(id)
        }
        return draft
    }

    /** Whether [draft] is still this take's draft; the owner attaches a row only when it is. */
    fun isCurrent(draft: CompletableDeferred<Long>): Boolean = draftCreation === draft

    fun markStatus(status: String, interrupted: Boolean = false, insertionResult: String? = null) {
        historyWrites?.enqueue("draft status") { repository ->
            val id = resolvedId()
            if (id > 0L) repository.updateStatus(id, status, interrupted, insertionResult)
        }
    }

    /**
     * A dictation that produced no words leaves nothing behind.
     *
     * The draft row is created the moment recording starts, so that a session killed mid-flight is
     * still recoverable. Every caller here reaches a terminal state with no transcript WORDS — the
     * microphone heard nothing, or the session ended before transcription could produce any — so
     * that row has never held a word and never will, and keeping it turns History into a list the
     * user has to scroll past to reach their own dictations (founder, 2026-08-31: "we shouldn't log
     * 'no speech' logs -> that's a waste of history space"; issue #19 says the same about
     * cancelling).
     *
     * **The line is whether the outcome was already ACCOUNTED FOR while the app was alive**, and it
     * is reached three different ways here. A failure the app survived shows the user a message: a
     * terminal capture failure, a capture that would not close before transcription, a service
     * failure while recording, and a cancel whose audio did not close cleanly. A successful cancel
     * shows no message and does not need one — the user pressed cancel, and the haptic and the
     * overlay closing acknowledge it. Nothing heard is silent on purpose, and leaves nothing behind
     * for the same reason: hearing nothing is not an event worth reporting twice. In all three, a
     * blank History card adds nothing.
     *
     * The two writers of `STATUS_INTERRUPTED` that REMAIN are the opposite case, and both keep their
     * row: the owner's own `destroy` teardown ([markInterrupted]), and `TranscriptDao.recoverStaleDrafts`
     * on the next start. Both run when the app was killed with a dictation live, so nobody told the user
     * anything and the row is the only signal that words were lost. That is why the prune leaves
     * `interrupted` rows alone.
     *
     * The id is cleared after the delete. That does not make a late write impossible — a later queued
     * write can still resolve the completed `draftCreation` to the old id — it makes one harmless: the
     * `UPDATE` matches zero rows and cannot bring the draft back.
     */
    fun discard() {
        historyWrites?.enqueue("discard") { repository ->
            val id = resolvedId()
            if (id > 0L) {
                repository.discard(id)
                draftId.set(0L)
            }
        }
    }

    /**
     * The destroy teardown's `interrupted` write, enqueued only when the interrupt WON the arbiter: every
     * terminal commit site owns its own later status or discard write, and a reserved finalization is
     * already queued ahead of this (#115 plan §3 C1).
     */
    fun markInterrupted() {
        historyWrites?.enqueue("interrupted") { repository ->
            val id = resolvedId()
            if (id > 0L) {
                repository.updateStatus(
                    id,
                    TranscriptEntity.STATUS_INTERRUPTED,
                    interrupted = true,
                    insertionResult = "not_attempted",
                )
            }
        }
    }

    /** Records the finalized row's id, on the queue's worker: the save answered SAVED. */
    internal fun remember(id: Long) {
        draftId.set(id)
        savedId = id
    }

    override fun resolveOnQueue(): Long = savedId

    override val savedNow: Boolean get() = savedId > 0L

    /**
     * The row's id, ON THE QUEUE'S WORKER: the draft insert is queued before every other write of the row,
     * so this awaits at most a write already applied; a failed insert or a take that never went live is 0.
     */
    suspend fun resolvedId(): Long =
        draftId.get().takeIf { it > 0L } ?: runCatching { draftCreation?.await() ?: 0L }.getOrDefault(0L)
}

/** The immutable payload of one publication, read before the owner's reservation (#115, #216). */
internal class Publication(
    val finalText: String,
    val engine: String,
    val originalText: String,
    val latencyMs: Long,
    val durationMs: Long,
    val captureDevice: String,
    val polishFacts: PolishPublicationFacts,
)

/** Where a delivered take's words went, as the owner logs it; [clipboard] is the measured copy, when one was made. */
internal class Delivery(val handoff: InsertionHandoff, val clipboard: ClipboardOutcome?)

/** The History save's answer (#235), for the take's facts and diagnostics only: it never decides delivery (#277). */
internal sealed interface SaveOutcome {
    data class Saved(val id: Long) : SaveOutcome
    data class Failed(val cause: Throwable) : SaveOutcome
}

/** The save's answer and the monotonic time the History worker produced it (#277), never the time someone saw it. */
internal class SaveAnswer(val outcome: SaveOutcome, val answeredAtMs: Long)

/**
 * One take's save answer (#277). The History worker stamps and publishes it in ONE step under [lock], so a reader
 * that finds no answer under the same lock knows any stamp still to come is later than that moment. That is what
 * lets [watchSaveBound] decide "late" exactly, whichever of the timer and the save wins a race.
 */
internal class SaveSlot {
    private val lock = Any()
    private var answer: SaveAnswer? = null
    private val done = CompletableDeferred<SaveAnswer>()

    fun answer(outcome: SaveOutcome, clock: () -> Long) {
        val stamped = synchronized(lock) { SaveAnswer(outcome, clock()).also { answer = it } }
        done.complete(stamped)
    }

    /** The answer if it has been stamped by now; null means any stamp to come is taken after this call. */
    fun answeredNow(): SaveAnswer? = synchronized(lock) { answer }

    suspend fun await(): SaveAnswer = done.await()
}

/**
 * The save's diagnostic bound (#277), exact whichever of the timer and the save wins: [clock] is read BEFORE
 * [SaveSlot.answeredNow], so an answer not stamped yet will be stamped later than that reading. [onTimeout] runs at
 * most once: when the bound has certainly passed with no answer, or when the answer's own stamp is past it. Waiting
 * uses the real timer only to wake up; no decision reads when this function happened to run. Returns the answer.
 */
internal suspend fun watchSaveBound(slot: SaveSlot, enqueuedAtMs: Long, boundMs: Long, clock: () -> Long, onTimeout: () -> Unit): SaveAnswer {
    var timedOut = false
    fun timeout() {
        if (!timedOut) {
            timedOut = true
            onTimeout()
        }
    }
    var seen: SaveAnswer? = null
    while (true) {
        val now = clock()
        seen = slot.answeredNow()
        if (seen != null || now - enqueuedAtMs > boundMs) break
        withTimeoutOrNull(boundMs - (now - enqueuedAtMs) + 1L) { slot.await() }
    }
    if (saveMissedBound(enqueuedAtMs, seen?.answeredAtMs, boundMs)) timeout()
    val answer = seen ?: slot.await()
    if (saveMissedBound(enqueuedAtMs, answer.answeredAtMs, boundMs)) timeout()
    return answer
}

/**
 * Whether a History save missed its diagnostic bound (#277): measured from the enqueue to the answer as the History
 * worker produced it; null means it had not answered when the deadline passed. Nobody's observation time enters it.
 */
internal fun saveMissedBound(enqueuedAtMs: Long, answeredAtMs: Long?, boundMs: Long): Boolean =
    answeredAtMs == null || answeredAtMs - enqueuedAtMs > boundMs

/**
 * The History and insertion half of a publication (#216). The session owner decides: it reserves the
 * ending, asks for the save with [enqueueSave] in the same operation, commits COMPLETED, and asks for
 * [deliver] without waiting for the save (#277): insertion is the heart, History a limb. Nothing here
 * reserves, commits or ends a take.
 */
internal class SessionFinalizer(
    private val host: SessionHost,
    private val insertion: InsertionGateway,
    private val log: SessionLog,
    private val historyWrites: HistoryWriteQueue,
) {
    /**
     * The finalize-or-insert write, ENQUEUED inside the owner's reservation (#115): destroy takes the same
     * lock before enqueueing `interrupted`, so a reserved finalization is always queued ahead of it and
     * `interrupted` is the last word on the row. [saved] completes with the row id or the failure.
     */
    fun enqueueSave(
        history: TakeHistory,
        publication: Publication,
        saved: SaveSlot,
    ) {
        historyWrites.enqueue("finalize") { repository ->
            val answer = runCatching {
                    val existingId = history.resolvedId()
                    val persistedId = if (existingId > 0L) {
                        val updated = repository.finalize(
                            id = existingId,
                            originalText = publication.originalText,
                            finalText = publication.finalText,
                            speechEngine = "Parakeet",
                            polishEngine = publication.engine,
                            polishLatencyMs = publication.latencyMs,
                            insertionResult = "pending",
                            durationMs = publication.durationMs,
                            polishReason = publication.polishFacts.reasonToken,
                            polishStatus = publication.polishFacts.statusCode,
                            polishContext = publication.polishFacts.contextToken,
                            captureDevice = publication.captureDevice,
                            // Neutral until the route is recorded (#235): never ready before a handoff.
                            status = TranscriptEntity.STATUS_SAVED_UNROUTED,
                        )
                        if (updated > 0) existingId else repository.insertSavedTranscript(publication)
                    } else {
                        repository.insertSavedTranscript(publication)
                    }
                    history.remember(persistedId)
                    persistedId
                }.fold({ SaveOutcome.Saved(it) }, { SaveOutcome.Failed(it) })
            saved.answer(answer, host::elapsedRealtimeMs)
        }
    }

    /**
     * After the owner committed COMPLETED, and only then: where the words go. Completed means the text
     * finalised, never that insertion succeeded. The save may not have answered yet (#277): the words go to
     * insertion regardless, and every outcome write resolves [row] on the History queue, behind the save.
     */
    fun deliver(
        takeId: String,
        targetPin: DictationTargetPin,
        publication: Publication,
        row: HistoryRow,
        clipboardPolicy: ClipboardInsertionPolicy,
    ): Delivery {
        val finalText = publication.finalText
        // Corrected once, here, so the announcement, the History row and the log all read the
        // same handoff. Deriving it twice is how the two surfaces started disagreeing.
        val handoff = InsertionJudgement.handoffToJudge(
            startPin = targetPin,
            insertionHandoff = insertion.pasteWhenTargetReturns(row, finalText, clipboardPolicy, takeId),
        )
        var measuredCopy: ClipboardOutcome? = null
        // Read once: the copy, the announcement and the log agree on whether History already holds the words.
        val saved = row.savedNow
        if (handoff != InsertionHandoff.SCHEDULED) {
            insertion.releasePinnedTarget()
            // Three outcomes, not two. A copy that was never attempted is the user's own auto-copy setting
            // with History already holding the words; until the save has answered SAVED the copy is forced,
            // so the words are never only in a write that may still fail (#277). A copy that was attempted
            // and failed is a fault whatever else was true.
            val clipboard =
                if (clipboardPolicy.autoCopyToClipboard || !saved) {
                    if (keepOnClipboard(row, finalText)) {
                        ClipboardOutcome.COPIED
                    } else {
                        ClipboardOutcome.WRITE_FAILED
                    }
                } else {
                    keepInHistoryOnly(row)
                    ClipboardOutcome.NOT_ATTEMPTED
                }
            measuredCopy = clipboard
            // Nothing was handed to the accessibility service on this branch, so it will never
            // speak: the announcement has to originate here so insertion fails safe, never
            // silently (enviouswispr-android-parity-spec.md PAR-081). The routes
            // where the service DID accept the text and then failed announce themselves, in
            // AccessibilityInsertionRunner.recordAndAnnounce.
            announceInsertionFallback(
                handoff = handoff,
                clipboard = clipboard,
                savedInHistory = saved,
            )
            // The owner is one of the three insertion writers (G1 D3): nothing was handed off, so
            // this is where the words ended up, as the same values the History row received.
            val resultKind = when {
                clipboard == ClipboardOutcome.NOT_ATTEMPTED -> InsertionResultKind.HISTORY_ONLY
                clipboard == ClipboardOutcome.COPIED -> InsertionResultKind.CLIPBOARD
                else -> InsertionResultKind.INSERTION_FAILED
            }
            Telemetry.capture(
                AnalyticsEvent.InsertionTerminal(
                    takeId = takeId, handoff = handoff, result = resultKind, route = InsertionRouteKind.of(resultKind),
                    targetApp = null, latencyMs = null, clipboard = clipboard.name.lowercase(), recovered = false,
                ),
            )
        } else {
            Telemetry.breadcrumb("take", "insertion_handed_off", mapOf("take_id" to takeId))
            // Promoted from neutral to ready AFTER the handoff and never awaited (#235), resolved on the queue
            // behind the save (#277): an outcome that lands first wins the conditional update.
            historyWrites.enqueue("promote to ready") { repository ->
                val id = row.resolveOnQueue()
                if (id > 0L) repository.promoteUnroutedToReady(id)
            }
        }
        log.log(
            when {
                handoff == InsertionHandoff.SCHEDULED ->
                    "Auto-insert handed to accessibility target tracker"
                clipboardPolicy.autoCopyToClipboard || !saved ->
                    "Accessibility unavailable; transcript kept on clipboard"
                else -> "Accessibility unavailable; transcript retained in History"
            } + " (handoff=$handoff)",
        )
        return Delivery(handoff, measuredCopy)
    }

    /** The neutral saved row when there is no draft to finalize; every value is the payload's, read before the reservation. */
    private suspend fun TranscriptRepository.insertSavedTranscript(publication: Publication): Long {
        val polishFacts = publication.polishFacts
        return insert(
            TranscriptEntity(
                originalText = publication.originalText,
                finalText = publication.finalText,
                createdAtMs = System.currentTimeMillis(),
                durationMs = publication.durationMs,
                speechEngine = "Parakeet",
                polishEngine = publication.engine,
                polishLatencyMs = publication.latencyMs,
                insertionResult = "pending",
                status = TranscriptEntity.STATUS_SAVED_UNROUTED,
                polishReason = polishFacts.reasonToken,
                polishStatus = polishFacts.statusCode,
                polishContext = polishFacts.contextToken,
                captureDevice = publication.captureDevice,
            ),
        )
    }

    /** @return whether the words actually reached the clipboard, which the copy depends on. */
    private fun keepOnClipboard(
        row: HistoryRow,
        text: String,
    ): Boolean {
        val copied = host.copyToClipboard(text)
        historyWrites.enqueue("clipboard-only outcome") { repository ->
            val id = row.resolveOnQueue()
            if (id <= 0L) return@enqueue
            repository.finalizeInsertionOutcome(
                id,
                TranscriptEntity.STATUS_INSERTION_INTERRUPTED,
                if (copied) InsertionResults.CLIPBOARD else InsertionResults.INSERTION_FAILED,
                interrupted = true,
            )
        }
        return copied
    }

    /**
     * Tells the user where their words went, in one calm line and nothing else.
     *
     * Whether to speak at all is `FallbackAnnouncement`'s decision, not this method's: a user
     * who never granted the permission is in clipboard-only mode by choice and gets nothing. What
     * it says is a measured destination and never an inferred fault, which is why there is no
     * failure haptic and nothing left in the shade: this is an ordinary outcome of a working
     * product, not an error.
     */
    private fun announceInsertionFallback(
        handoff: InsertionHandoff,
        clipboard: ClipboardOutcome,
        savedInHistory: Boolean,
    ) {
        val announcement = FallbackAnnouncement.fallbackAnnouncement(
            autoPaste = host.autoPasteAvailability(),
            handoff = handoff,
            clipboard = clipboard,
            savedInHistory = savedInHistory,
        ) ?: return
        host.postToMain {
            host.toastFromService(announcement.line)
        }
    }

    /** History is the destination by the user's own auto-copy setting: the row records it. */
    private fun keepInHistoryOnly(row: HistoryRow) {
        historyWrites.enqueue("history-only outcome") { repository ->
            val id = row.resolveOnQueue()
            if (id <= 0L) return@enqueue
            repository.finalizeInsertionOutcome(
                id,
                TranscriptEntity.STATUS_INSERTION_INTERRUPTED,
                InsertionResults.HISTORY_ONLY,
                interrupted = true,
            )
        }
    }
}
