package com.envi.wispr.ui

import com.envi.wispr.cleanup.LanguageDetector
import com.envi.wispr.cleanup.TextSafety
import com.envi.wispr.polish.PolishContext
import com.envi.wispr.polish.PolishEngineLabels
import com.envi.wispr.polish.PolishFallback
import com.envi.wispr.polish.PolishOutcome
import com.envi.wispr.polish.PolishPolicy
import com.envi.wispr.polish.PolishReason
import com.envi.wispr.telemetry.AppDefect
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The text a take's polish hands back to the session owner (#237): the engine's answer, or the owner's
 * deterministic fallback with the reason it was chosen. The owner publishes it; nothing here does.
 */
internal sealed interface PreparedText {
    val text: String
    val context: PolishContext

    data class Polished(
        override val text: String,
        val engine: String,
        val latencyMs: Long,
        val reason: PolishReason,
        val statusCode: Int,
        override val context: PolishContext,
    ) : PreparedText

    data class Fallback(
        override val text: String,
        val reason: PolishReason,
        override val context: PolishContext,
    ) : PreparedText
}

/**
 * One take's polish (#237), below [DictationSessionCoordinator]: the request ledger, the first-wins decision,
 * the loss latch and its once-per-take defect (#234), the warm-up (#236), the watchdog, the request listener
 * and its validation, vocabulary restoration and the deterministic fallback. It emits at most one
 * [PreparedText] for an admitted nonblank speech answer, and none when the owner ends the take first; a loss
 * before speech is latched and becomes a fallback only if nonblank speech arrives. Take admission, the
 * terminal arbiter, publication, History and insertion stay with the owner (`architecture-rules.md` RULE:
 * one-owner-for-the-session).
 *
 * An owner admits exactly one take, so a controller lives exactly as long as its take and nothing is reset
 * (`SessionOwnerShapeTest` pins the one-take admission). [lock] is the owner's submission lock, shared so a
 * cancel's reservation and this controller's ledger open, claim and close are one step. Nothing is sent to the
 * engine, cleaned, reported or handed to [onPrepared] while [lock] is held: those run on binder threads and
 * main too, and a synchronous transaction to a stalled engine must never hold the lock.
 */
internal class TakePolishController(
    private val lock: Any,
    /** The owner's injected ledger: one take per owner, so one ledger per take. */
    private val ledger: PolishRequestLedger,
    private val timeout: PolishTimeout,
    private val scope: CoroutineScope,
    /** `pipeline.polish`; the warm-up and each request capture it once, as before (#236). */
    private val link: () -> PolishLink?,
    private val languageDetector: LanguageDetector,
    private val log: SessionLog,
    private val takeId: String,
    private val defectSink: (AppDefect, Map<String, Any?>) -> Unit,
    /** The owner's frozen preferences, read when a disconnect's fallback needs them. */
    private val preferences: () -> SessionPreferences,
    /** The owner's raw words, read under [lock] where the owner writes them. */
    private val transcript: () -> String,
    /** PROCESSING and the arbiter open; read under [lock]. */
    private val isProcessing: () -> Boolean,
    /** Not destroyed and a live state; the warm-up's pre-send check. */
    private val isLive: () -> Boolean,
    private val onPrepared: (PreparedText) -> Unit,
) {
    /**
     * Who answers this take's polish (#234), decided once under [lock]: nobody yet, a request that is open, or
     * an answer or fallback that has claimed it. Every ledger claim updates it in the same operation, so a
     * polish disconnect and the speech answer can never both publish.
     */
    private sealed interface Decision {
        data object Undecided : Decision
        data class Open(val requestId: Long) : Decision
        data object Claimed : Decision
    }

    /** Guarded by [lock]. */
    private var decision: Decision = Decision.Undecided

    /**
     * Polish was lost for this take (#234): refused at bind, never connected, or its process died while the
     * take was live. Written under [lock]; the take publishes the deterministic text with this reason and a
     * reconnect does not un-lose it.
     */
    @Volatile private var lost: PolishReason? = null

    /** One polish-failure defect per take, whichever failure is observed first (#234). */
    private val failureReported = AtomicBoolean(false)

    /** A request is open on the ledger; for the owner's cancel log line. */
    val openRequest: Boolean get() = ledger.openId != null

    /** Polish did not bind for this take (#234): latched and reported once. */
    fun bindRefused() = recordLoss(PolishReason.SERVICE_UNAVAILABLE, AppDefect.PolishServiceUnavailable)

    /**
     * Polish connected. A take that lost polish keeps its deterministic text (#234): a reconnect is for the
     * next take. Warm at connect, measured and decided (#72): every later moment ends with the same two
     * models resident, because the speech model stays loaded after it transcribes, and costs the user 0.9 to
     * 3.1 s of wait (`architecture-rules.md` RULE: isolate-limbs carries the numbers). On IO, never on main
     * (#236): the call is a synchronous transaction into `:polish`, and a stalled polish process must not hold
     * a stop, a cancel or a publication. The link is captured here; the pre-send check is best effort, and
     * nothing ever waits for the call.
     */
    fun connected(policy: PolishPolicy) {
        if (lost != null) {
            log.log("Polish service reconnected; this take already lost it and keeps the deterministic text")
            return
        }
        val warmed = link()
        if (warmed != null) {
            scope.launch(Dispatchers.IO) {
                if (!(isLive() && lost == null)) {
                    log.log("Polish warm-up not sent: take $takeId is no longer live")
                    return@launch
                }
                runCatching { warmed.warmUpWithPolicy(policy) }
                    .onFailure { error -> log.warn("Polish warm-up failed for take $takeId: ${error.javaClass.simpleName}") }
            }
        }
        log.log("Polish service connected")
    }

    /**
     * Polish died (#234), after the owner's gate (a live state, not destroyed, nothing committed). It is a
     * limb, so the take never ends for it: the loss is latched and reported once, and the deterministic text
     * is prepared now if a request was open (this path claims it first), else when speech answers.
     */
    fun disconnected() {
        var text = ""
        val claimedOpen = synchronized(lock) {
            if (lost == null) lost = PolishReason.SERVICE_DIED
            text = transcript()
            when (val seen = decision) {
                is Decision.Open -> ledger.claim(seen.requestId).also { claimed ->
                    if (claimed) decision = Decision.Claimed
                }
                Decision.Undecided, Decision.Claimed -> false
            }
        }
        reportFailure(AppDefect.PolishServiceDied)
        if (claimedOpen) fallBack(text, preferences(), PolishReason.SERVICE_DIED)
    }

    /**
     * Speech died after it answered nonblank words: polish may still answer them, and one decision picks the
     * winner (#234). A lost claim does nothing; the owner alone decides a blank transcript's ending.
     */
    fun claimSpeechLossFallback(rawText: String) {
        val claimed = claimFallback() ?: return
        // A claimed request is still running on a live engine: stop it, outside the lock.
        if (claimed != NO_REQUEST) sendCancel(claimed)
        fallBack(rawText, preferences(), PolishReason.SERVICE_DIED)
    }

    /**
     * Polishes nonblank speech on the owner's scope. The state check, the loss check and the ledger open are
     * one step under [lock] (#75, #234), so a cancel either precedes them (no request is sent) or finds the
     * open id and closes it, and a polish disconnect either latched its loss before this (the fallback is
     * chosen here) or finds the open request and claims it. The binder call itself runs outside the lock.
     */
    fun prepare(rawText: String, takePreferences: SessionPreferences) {
        scope.launch {
            val preparedRaw = restoreVocabulary(rawText, takePreferences)
            val service = link()
            var fallback: PolishReason? = null
            var unansweredLink = false
            var ended = false
            val requestId = synchronized(lock) {
                if (!isProcessing()) {
                    ended = true
                    return@synchronized null
                }
                if (decision != Decision.Undecided) return@launch
                val seenLoss = lost
                if (service == null || seenLoss != null) {
                    // Refused at bind, never connected, or died: this take publishes the deterministic text.
                    decision = Decision.Claimed
                    unansweredLink = seenLoss == null
                    if (seenLoss == null) lost = PolishReason.SERVICE_UNAVAILABLE
                    fallback = seenLoss ?: PolishReason.SERVICE_UNAVAILABLE
                    return@synchronized null
                }
                val opened = ledger.open()
                decision = Decision.Open(opened)
                // The watchdog is armed BEFORE the binder call so the call itself is inside the budget.
                // The ledger is the only first-wins gate: an outcome that arrives first closes it.
                scope.launch {
                    timeout.await(takePreferences.policy)
                    if (!claim(opened)) return@launch
                    log.warn("Polish watchdog fired for request $opened; cancelling on the engine")
                    sendCancel(opened)
                    fallBack(rawText, takePreferences, PolishReason.WATCHDOG_TIMEOUT)
                }
                opened
            }
            if (ended) {
                log.log("Transcript arrived after the session ended; not polishing")
                return@launch
            }
            if (requestId == null) {
                // A bound polish that never connected before speech answered is a failure too (#234).
                if (unansweredLink) {
                    log.warn("Polish service never connected; this take publishes the deterministic text")
                    reportFailure(AppDefect.PolishServiceUnavailable)
                }
                fallBack(rawText, takePreferences, checkNotNull(fallback))
                return@launch
            }
            try {
                checkNotNull(service).polishRequestForTake(
                    requestId,
                    preparedRaw,
                    takePreferences.cleanup.removeFillers,
                    takePreferences.cleanup.spokenEmoji,
                    takePreferences.cleanup.spokenPunctuation,
                    takePreferences.policy,
                    takeId,
                    listener(requestId, rawText, takePreferences),
                )
            } catch (error: Exception) {
                log.error("Unable to call polish service", error)
                // The service can throw while alive, so the call itself is reported; a disconnect that
                // follows shares the once gate and raises nothing more (#234).
                if (claim(requestId)) {
                    reportFailure(AppDefect.PolishCallFailed)
                    fallBack(rawText, takePreferences, PolishReason.CALL_FAILED)
                }
            }
            // A cancel that landed between the ledger open and the engine's registration found nothing to
            // cancel on the engine. Now the request is registered, so send it again on the link this request
            // used; on a delivered or never-registered id the engine treats it as a no-op. A read, never a
            // claim: on a normal day the ledger is still open here and must stay open for the outcome.
            if (ledger.openId != requestId) runCatching { service?.cancel(requestId) }
        }
    }

    /**
     * Closes the ledger and returns the id that was open, or null. The owner calls this under [lock] with its
     * cancel reservation and sends the cancel with [sendCancel] after releasing it.
     */
    fun closeOpen(): Long? = ledger.close()

    /** Cancels [requestId] on the engine. Never called with [lock] held. */
    fun sendCancel(requestId: Long) {
        runCatching { link()?.cancel(requestId) }
            .onFailure { error -> log.warn("Unable to cancel polish request $requestId: ${error.javaClass.simpleName}") }
    }

    /**
     * Closes the ledger and cancels exactly the request that was open, if any. Called as every terminal
     * transition BEGINS and again from the unbind as an idempotent backstop. Never called with [lock] held.
     */
    fun cancelOpen() {
        closeOpen()?.let(::sendCancel)
    }

    private fun listener(requestId: Long, rawText: String, takePreferences: SessionPreferences) = object : PolishListener {
        override fun onOutcome(outcome: PolishOutcome?) {
            // This callback belongs to ONE request and the engine answers it once, so an empty or misnamed
            // outcome is the only answer this request will get: fail open now rather than leave the session
            // in Processing forever.
            if (outcome == null || outcome.requestId != requestId) {
                if (claim(requestId)) {
                    log.warn("Invalid polish outcome for request $requestId")
                    defectSink(AppDefect.PolishProtocolViolation, mapOf("take_id" to takeId, "shape" to if (outcome == null) "null" else "mismatched"))
                    fallBack(rawText, takePreferences, PolishReason.CALL_FAILED)
                }
                return
            }
            if (!claim(outcome.requestId)) {
                log.warn("Ignoring polish outcome for request ${outcome.requestId}: not the open request (reason=${outcome.reason})")
                return
            }
            log.log("Polish outcome ${outcome.requestId}: reason=${outcome.reason} status=${outcome.statusCode}")
            // No correct engine answer is blank for a nonblank request: deterministic cleanup recovers the
            // original rather than erase it, and every fallback keeps that text (#214). A blank answer is a
            // broken engine: the owner's floor, never the raw transcript.
            if (outcome.text.isBlank() && rawText.isNotBlank()) {
                log.warn("Blank polish outcome for request ${outcome.requestId} (reason=${outcome.reason}); publishing the owner's fallback")
                defectSink(AppDefect.PolishProtocolViolation, mapOf("take_id" to takeId, "shape" to "blank"))
                fallBack(rawText, takePreferences, PolishReason.CALL_FAILED)
                return
            }
            onPrepared(
                PreparedText.Polished(
                    restoreVocabulary(outcome.text, takePreferences),
                    outcome.engine,
                    outcome.latencyMs,
                    outcome.reason,
                    outcome.statusCode,
                    PolishContext.from(takePreferences.policy),
                ),
            )
        }

        // v1 answers are never produced for a v2 request. If one ever arrives it is an engine defect, and the
        // session still fails open to the deterministic text.
        override fun onResult(text: String?, engine: String?, latencyMs: Long) {
            if (claim(requestId)) {
                defectSink(AppDefect.PolishProtocolViolation, mapOf("take_id" to takeId, "shape" to "v1_result"))
                fallBack(rawText, takePreferences, PolishReason.CALL_FAILED)
            }
        }

        override fun onError(message: String?) {
            if (claim(requestId)) {
                defectSink(AppDefect.PolishProtocolViolation, mapOf("take_id" to takeId, "shape" to "v1_error"))
                fallBack(rawText, takePreferences, PolishReason.CALL_FAILED)
            }
        }
    }

    /** Latches this take's polish loss (the first reason wins) and reports it once (#234). */
    private fun recordLoss(reason: PolishReason, defect: AppDefect) {
        synchronized(lock) { if (lost == null) lost = reason }
        reportFailure(defect)
    }

    /** One polish-failure defect per take, raised when the failure is observed, never again at publication. */
    private fun reportFailure(defect: AppDefect) {
        if (failureReported.compareAndSet(false, true)) defectSink(defect, mapOf("take_id" to takeId))
    }

    /**
     * Claims [requestId] on the ledger and marks the decision in ONE operation under [lock] (#234): every
     * answer, watchdog and error exit goes through here, so a polish disconnect sees who won.
     */
    private fun claim(requestId: Long): Boolean = synchronized(lock) {
        ledger.claim(requestId).also { claimed -> if (claimed) decision = Decision.Claimed }
    }

    /**
     * A fallback that is not an answer to the open request (a speech drop) wins only from an undecided take or
     * by claiming the open request, in ONE operation under [lock] (#234). Returns null when another path
     * already decided, else the request id it claimed (to cancel on the engine), or [NO_REQUEST].
     */
    private fun claimFallback(): Long? = synchronized(lock) {
        when (val seen = decision) {
            Decision.Undecided -> NO_REQUEST
            is Decision.Open -> seen.requestId.takeIf { ledger.claim(it) }
            Decision.Claimed -> null
        }?.also { decision = Decision.Claimed }
    }

    /**
     * The owner's own fallback, handed back under the deterministic label with the reason logged by name.
     * Closes the ledger and cancels the open request first, so a late engine outcome cannot be accepted after
     * it and an abandoned cloud call does not run on.
     */
    private fun fallBack(rawText: String, takePreferences: SessionPreferences, reason: PolishReason) {
        cancelOpen()
        log.warn("Polish fell back on the session owner: reason=$reason")
        onPrepared(PreparedText.Fallback(deterministic(rawText, takePreferences), reason, PolishContext.from(takePreferences.policy)))
    }

    /**
     * The same deterministic pipeline the engine runs with polish off, so the text a user gets cannot depend
     * on which side failed (issue #69; the regex polisher that used to run here capitalised sentences and
     * appended a period the engine never did).
     */
    private fun deterministic(rawText: String, takePreferences: SessionPreferences): String {
        val prepared = restoreVocabulary(rawText, takePreferences)
        // The engine resolves the same answer on its own side. Detecting here too is what keeps this terminal
        // from being the one that still applies English rules to foreign words when the engine is the side
        // that failed (#107); the alternative was a new AIDL transaction to carry it across, which
        // `workflow-process.md` RULE: tier-routing classifies as REFACTOR for a limb feature.
        val cleaned = PolishFallback.deterministic(prepared, takePreferences.cleanup, languageDetector)
        return restoreVocabulary(cleaned, takePreferences)
    }

    private fun restoreVocabulary(text: String, takePreferences: SessionPreferences): String {
        val restored = takePreferences.matcher.restore(text)
        return if (TextSafety.isSafe(text, restored)) restored else text
    }

    private companion object {
        /** [claimFallback]'s answer when no request was open; ledger ids are never 0. */
        const val NO_REQUEST = 0L
    }
}
