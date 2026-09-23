package com.envi.wispr.ui

import com.envi.wispr.history.HistoryPublicationPolicy
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
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * One take's History row, from the draft inserted at live to the last status (#216). Every write goes
 * through the application's [HistoryWriteQueue] in enqueue order (#115), so a later write of the row is
 * always queued behind the draft insert and [resolvedId] never waits on anything but a write already
 * applied. It never touches the recorder: the owner attaches the row to its surface.
 *
 * [historyWrites] is null only for [TakeContext.NONE], before any take is admitted; every write is then
 * a no-op, as the old `draftId` of 0 made it.
 */
internal class TakeHistory(private val historyWrites: HistoryWriteQueue?) {
    private val draftId = AtomicLong(0L)

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

    /** Records the finalized row's id, on the queue's worker. */
    internal fun remember(id: Long) = draftId.set(id)

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
internal class Delivery(val route: HistoryPublicationPolicy.Route, val handoff: InsertionHandoff, val clipboard: ClipboardOutcome?)

/** What the owner got from the History save within its bound (#235). */
internal sealed interface SaveOutcome {
    data class Saved(val id: Long) : SaveOutcome
    data class Failed(val cause: Throwable) : SaveOutcome
    data object TimedOut : SaveOutcome
}

/**
 * One take's History save decision (#235): the save's answer and the owner's bound compete ONCE, and the
 * winner decides the route. The save writes a neutral `saved_unrouted` row first, so whoever wins, no row
 * can claim a paste that was never handed off. After a timeout the late row and the owner's measured copy
 * meet here, and whichever arrives second reconciles the row; neither ever inserts or announces.
 */
internal class HistorySaveGate {
    private sealed interface State {
        data object Pending : State
        data class Answered(val outcome: SaveOutcome) : State
        data class TimedOut(val clipboard: ClipboardOutcome?, val lateRowId: Long?) : State
    }

    private val state = AtomicReference<State>(State.Pending)

    /** The History worker: the save answered. True when it won; false when the owner's bound already had. */
    fun saveAnswered(outcome: SaveOutcome): Boolean = state.compareAndSet(State.Pending, State.Answered(outcome))

    /** The owner, when its bound expired: true when the timeout won; else [answered] holds the save's outcome. */
    fun claimTimeout(): Boolean = state.compareAndSet(State.Pending, State.TimedOut(clipboard = null, lateRowId = null))

    /** The save's outcome when the save won; null while pending or after a timeout. */
    fun answered(): SaveOutcome? = (state.get() as? State.Answered)?.outcome

    /**
     * The History worker, after a timeout: the late row's id. Returns the owner's measured copy when it is
     * already recorded, so the worker reconciles the row now; null means the owner will.
     */
    fun lateRow(id: Long): ClipboardOutcome? {
        while (true) {
            val current = state.get() as? State.TimedOut ?: return null
            if (state.compareAndSet(current, current.copy(lateRowId = id))) return current.clipboard
        }
    }

    /**
     * The owner, after its copy on a timeout: the measured outcome. Returns the late row's id when the
     * worker already wrote it, so the owner enqueues the reconciliation; null means the worker will.
     */
    fun copied(outcome: ClipboardOutcome): Long? {
        while (true) {
            val current = state.get() as? State.TimedOut ?: return null
            if (state.compareAndSet(current, current.copy(clipboard = outcome))) return current.lateRowId
        }
    }
}

/**
 * The History and insertion half of a publication (#216). The session owner decides: it reserves the
 * ending, asks for the save with [enqueueSave] in the same operation, commits COMPLETED once the save
 * answered, and only then asks for [deliver]. Nothing here reserves, commits or ends a take.
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
        gate: HistorySaveGate,
        saved: CompletableDeferred<SaveOutcome>,
        /** A save that fails after the owner's bound already won: diagnostics only, never the delivery (#235). */
        onLateFailure: (Throwable) -> Unit,
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
            if (gate.saveAnswered(answer)) {
                saved.complete(answer)
                return@enqueue
            }
            // The owner's bound already won and the words went to the clipboard: this save only reconciles
            // its row, and never inserts or announces (#235).
            when (answer) {
                is SaveOutcome.Saved -> gate.lateRow(answer.id)?.let { copy -> reconcileCopy(repository, answer.id, copy) }
                is SaveOutcome.Failed -> onLateFailure(answer.cause)
                SaveOutcome.TimedOut -> Unit
            }
            saved.complete(answer)
        }
    }

    /** A timed-out take's late row, reconciled to the copy the user actually got (#235); conditional on neutral/pending. */
    private suspend fun reconcileCopy(repository: TranscriptRepository, id: Long, copy: ClipboardOutcome) {
        repository.finalizeInsertionOutcome(
            id,
            TranscriptEntity.STATUS_INSERTION_INTERRUPTED,
            if (copy == ClipboardOutcome.COPIED) InsertionResults.CLIPBOARD else InsertionResults.INSERTION_FAILED,
            interrupted = true,
        )
    }

    /**
     * After the owner committed COMPLETED, and only then: where the words go. Completed means the text
     * finalised, never that insertion succeeded.
     */
    suspend fun deliver(
        takeId: String,
        targetPin: DictationTargetPin,
        publication: Publication,
        saveOutcome: SaveOutcome,
        clipboardPolicy: ClipboardInsertionPolicy,
        gate: HistorySaveGate,
    ): Delivery {
        val finalText = publication.finalText
        val persistedId = (saveOutcome as? SaveOutcome.Saved)?.id ?: 0L
        val route = HistoryPublicationPolicy.route(
            persistedId = persistedId,
            persistenceSucceeded = saveOutcome is SaveOutcome.Saved,
        )
        // Corrected once, here, so the announcement, the History row and the log all read the
        // same handoff. Deriving it twice is how the two surfaces started disagreeing.
        val handoff = InsertionJudgement.handoffToJudge(
            startPin = targetPin,
            insertionHandoff = if (route == HistoryPublicationPolicy.Route.AUTO_INSERT) {
                insertion.pasteWhenTargetReturns(
                    persistedId,
                    finalText,
                    clipboardPolicy,
                    takeId,
                )
            } else {
                InsertionHandoff.HISTORY_NOT_DURABLE
            },
        )
        var measuredCopy: ClipboardOutcome? = null
        if (handoff != InsertionHandoff.SCHEDULED) {
            insertion.releasePinnedTarget()
            val mustPreventDataLoss = persistedId <= 0L
            // Three outcomes, not two. A copy that was never attempted is the user's own
            // auto-copy setting and History is then the destination; a copy that was attempted
            // and failed is a fault whatever else was true.
            val clipboard =
                if (clipboardPolicy.autoCopyToClipboard || mustPreventDataLoss) {
                    if (keepOnClipboard(persistedId, finalText)) {
                        ClipboardOutcome.COPIED
                    } else {
                        ClipboardOutcome.WRITE_FAILED
                    }
                } else {
                    keepInHistoryOnly(persistedId)
                    ClipboardOutcome.NOT_ATTEMPTED
                }
            measuredCopy = clipboard
            // A timed-out take's row lands later (#235): the measured copy is recorded for it, and if the row
            // is already written, its reconciliation is queued now, never awaited.
            if (saveOutcome == SaveOutcome.TimedOut) {
                gate.copied(clipboard)?.let { lateId ->
                    historyWrites.enqueue("timed-out copy outcome") { repository -> reconcileCopy(repository, lateId, clipboard) }
                }
            }
            // Nothing was handed to the accessibility service on this branch, so it will never
            // speak: the announcement has to originate here so insertion fails safe, never
            // silently (enviouswispr-android-parity-spec.md PAR-081). The routes
            // where the service DID accept the text and then failed announce themselves, in
            // AccessibilityInsertionRunner.recordAndAnnounce.
            announceInsertionFallback(
                handoff = handoff,
                clipboard = clipboard,
                savedInHistory = persistedId > 0L,
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
            // Promoted from neutral to ready AFTER the handoff and never awaited (#235): a stalled write cannot
            // hold the words, and an outcome that lands first wins the conditional update.
            historyWrites.enqueue("promote to ready") { repository -> repository.promoteUnroutedToReady(persistedId) }
        }
        log.log(
            when {
                handoff == InsertionHandoff.SCHEDULED ->
                    "Auto-insert handed to accessibility target tracker"
                route == HistoryPublicationPolicy.Route.COPY_ONLY ->
                    "History persistence unavailable; transcript kept on clipboard only"
                clipboardPolicy.autoCopyToClipboard || persistedId <= 0L ->
                    "Accessibility unavailable; transcript kept on clipboard"
                else -> "Accessibility unavailable; transcript retained in History"
            } + " (handoff=$handoff)",
        )
        return Delivery(route, handoff, measuredCopy)
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
    private suspend fun keepOnClipboard(
        transcriptId: Long,
        text: String,
    ): Boolean {
        val copied = host.copyToClipboard(text)
        if (transcriptId <= 0L) return copied

        historyWrites.enqueue("clipboard-only outcome") { repository ->
            repository.finalizeInsertionOutcome(
                transcriptId,
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
    private fun keepInHistoryOnly(transcriptId: Long) {
        if (transcriptId <= 0L) return
        historyWrites.enqueue("history-only outcome") { repository ->
            repository.finalizeInsertionOutcome(
                transcriptId,
                TranscriptEntity.STATUS_INSERTION_INTERRUPTED,
                InsertionResults.HISTORY_ONLY,
                interrupted = true,
            )
        }
    }
}
