package com.envi.wispr.ui

import com.envi.wispr.asr.AsrFailureReason
import com.envi.wispr.audio.AudioCaptureService
import com.envi.wispr.audio.CaptureEnding
import com.envi.wispr.audio.InputRouteKind
import com.envi.wispr.audio.InputRouteReason
import com.envi.wispr.audio.LiveGate
import com.envi.wispr.audio.PcmAudio
import com.envi.wispr.audio.RecordingLimits
import com.envi.wispr.audio.SpeechEvidence
import com.envi.wispr.cleanup.LanguageDetector
import com.envi.wispr.cleanup.TextSafety
import com.envi.wispr.history.HistoryPublicationPolicy
import com.envi.wispr.history.TranscriptEntity
import com.envi.wispr.history.TranscriptRepository
import com.envi.wispr.insertion.ClipboardOutcome
import com.envi.wispr.insertion.FallbackAnnouncement
import com.envi.wispr.insertion.InsertionResults
import com.envi.wispr.paste.DictationTargetPin
import com.envi.wispr.paste.InsertionHandoff
import com.envi.wispr.paste.InsertionJudgement
import com.envi.wispr.polish.PolishContext
import com.envi.wispr.polish.PolishEngineLabels
import com.envi.wispr.polish.PolishFallback
import com.envi.wispr.polish.PolishOutcome
import com.envi.wispr.polish.PolishPolicy
import com.envi.wispr.polish.PolishPublicationFacts
import com.envi.wispr.polish.PolishReason
import com.envi.wispr.shortcuts.BubbleRequestLedger
import com.envi.wispr.shortcuts.BubbleRequestToken
import com.envi.wispr.shortcuts.BubbleRequests
import com.envi.wispr.shortcuts.DictationSurfaceState
import com.envi.wispr.telemetry.AnalyticsEvent
import com.envi.wispr.telemetry.AppDefect
import com.envi.wispr.telemetry.InsertionResultKind
import com.envi.wispr.telemetry.InsertionRouteKind
import com.envi.wispr.telemetry.TakeFacts
import com.envi.wispr.telemetry.TakeStage
import com.envi.wispr.telemetry.Telemetry
import com.envi.wispr.telemetry.TelemetryChannels
import com.envi.wispr.vocabulary.CustomTerm
import com.envi.wispr.vocabulary.StructuredTermRestorer
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * The one owner of a dictation take (#186): its state machine, its arbiter and facts, every transition,
 * the three worker threads, the speech and polish callbacks, publication, History and insertion decisions,
 * the cancel paths and teardown. Reached only through seams ([SessionHost], [RecorderSurface],
 * [InsertionGateway], [SessionLog], [PipelineController]) so a JVM test runs it without a `Service`.
 *
 * Every statement here ran in `DictationSessionService` before #186, in this order; the Service is now the
 * Android adapter that builds this object in `onCreate`, forwards each command, implements the host, and
 * tears it down in `onDestroy`. The Service still stops itself after every take, so a fresh coordinator
 * begins IDLE with the next command; nothing here survives a take on purpose.
 */
internal class DictationSessionCoordinator(
    private val host: SessionHost,
    private val surface: RecorderSurface,
    private val insertion: InsertionGateway,
    private val log: SessionLog,
    private val preferences: SessionPreferencesSource,
    private val transcripts: TranscriptRepository,
    private val languageDetector: LanguageDetector,
    private val loadPolicy: suspend () -> PolishPolicy,
    private val pipeline: PipelineController,
    /** The one session scope; the Service's `SupervisorJob() + Dispatchers.IO`. Its job is joined on destroy. */
    private val scope: CoroutineScope,
    /** `Dispatchers.Main.immediate` in production; a JVM test passes its single owner-thread dispatcher. */
    private val mainDispatcher: CoroutineDispatcher,
    private val polishTimeout: PolishTimeout = DelayPolishTimeout,
    /** How long a take waits for the settings and custom words before failing; a test shortens it (#193 will remove the gate). */
    private val settingsWaitMs: Long = SETTINGS_WAIT_MS,
    /** Process-scoped on purpose: the tip's once-per-process allowance outlives the Service instance. */
    private val tipGate: BluetoothTipGate = BluetoothTipGate.PROCESS,
    /** The one first-wins gate on the polish answer; production mints ids off the device clock, a test off the JVM's. */
    private val polishLedger: PolishRequestLedger = PolishRequestLedger(),
    /** The arbiter's sink: where a committed ending goes. Production records it to telemetry. */
    private val endingSink: (TakeFacts, TerminalReason) -> Unit = ::recordTakeEnding,
) : PipelineController.Listener {
    companion object {
        /**
         * macOS's own sentence for this state, reused rather than reinvented. Android writing its own
         * words for a state macOS has already worded is how the two products drift apart.
         */
        private const val SILENCE_UNAVAILABLE_NOTICE = "Auto-stop on silence is unavailable right now"

        /**
         * Shown on the recorder in the last minute of a take, so the user can finish the sentence
         * they are in rather than discover the cap by losing the end of it.
         */
        private val DURATION_WARNING_NOTICE =
            "Recording stops in under a minute " +
                "(${RecordingLimits.MAX_DURATION_MINUTES} minute limit)"

        /** Shown after the cap has stopped a take. The recorder is already gone by then. */
        private val DURATION_REACHED_NOTICE =
            "Reached the ${RecordingLimits.MAX_DURATION_MINUTES} minute limit. " +
                "Working on what you said."

        /** About thirty pictures a second: a syllable is about 100 ms and the analyser refreshes every 32 ms. */
        private const val METER_INTERVAL_MS = 33L

        /** How long a take waits for its journal admission before starting anyway (a limb, never a gate). */
        const val JOURNAL_ADMISSION_DEADLINE_MS = 300L

        /** How long a take waits for the settings and custom words before failing (#193 will change this). */
        const val SETTINGS_WAIT_MS = 10_000L
    }

    private enum class SessionState { IDLE, STARTING, RECORDING, PROCESSING, CANCELLING, FINISHING, ERROR }

    /** How often the live waiter asks the capture process. */
    private val LIVE_POLL_MS = 20L

    /**
     * The STARTING bound: two live deadlines (one reset) plus a second, after which a take that never
     * went live fails rather than spins.
     */
    private val LIVE_WAIT_BOUND_MS = 2 * LiveGate.DEADLINE_MS + 1_000L

    private val state = AtomicReference(SessionState.IDLE)

    /**
     * The one referee of how this take ends (issue #176). Every terminal route reserves or commits
     * through it; a route that loses does no History, notification, insertion or terminal work. It
     * replaced the `publicationStarted` flag, which guarded publication only and let a late ASR error
     * announce over a cancel. A fresh arbiter per admitted take; `closed()` refuses everything before one.
     */
    @Volatile private var arbiter: TakeArbiter = TakeArbiter.closed()

    /**
     * This take's id, minted at admission and carried on every request that leaves this process
     * (audio start, the speech request, the polish request), so a helper's records can name the take
     * (issue #176). Never persisted by the helpers; chunk B carries it to telemetry.
     */
    @Volatile private var takeId = ""

    /** The take's peak loudness, read ONCE at stop like the device label; null when it could not be read. */
    @Volatile private var takePeakAmplitude: Float? = null

    /**
     * What this take measured, for its one `dictation.terminal` row (issue #176). A fresh holder per
     * admitted take; every fact is written where it becomes known and read once, at the commit.
     */
    @Volatile private var facts = TakeFacts("", TriggerSource.UNKNOWN)

    /** The surface named on the last START or TOGGLE command; consumed at admission. */
    @Volatile private var pendingTrigger = TriggerSource.UNKNOWN

    /** Names for the two reservations whose outcome is unknown at the claim. */
    private object Claimants {
        const val PUBLICATION = "publication"
        const val CANCEL = "cancel"
    }

    /**
     * Serialises the final state check, the ledger open, the watchdog launch and the binder call against
     * `cancelProcessing` (#75): without it the transcription thread can read PROCESSING, lose the CPU to a
     * cancel that closes an empty ledger, and then send a request nothing will ever cancel.
     */
    private val polishSubmissionLock = Any()
    private val teardownStarted = AtomicBoolean(false)
    private val draftId = AtomicLong(0L)
    /** The injected scope's job, read once; `destroy` cancels and joins exactly this. */
    private val serviceJob: Job = requireNotNull(scope.coroutineContext[Job]) { "the session scope needs a Job" }
    private val pendingHistoryUpdates = java.util.Collections.synchronizedList(mutableListOf<Job>())

    private var rawTranscript = ""
    // What the START of this dictation saw when it tried to pin an editor. Read at the end,
    // by which time the service may have been replaced. NO_TARGET until a session begins, so
    // a value that outlived its session can only ever suppress an announcement, never invent
    // one.
    @Volatile private var targetPinAtStart = DictationTargetPin.NO_TARGET

    /** The bubble request this take was admitted for, or null for a take started elsewhere. */
    @Volatile private var admittedRequest: BubbleRequestToken? = null

    /**
     * A release for this take arrived before capture was running. Consumed at the RECORDING
     * transition: the take stops the moment it can, instead of cancelling as an unmarked STOP would
     * from STARTING (issue #135 plan §3 "Stop and cancel").
     */
    @Volatile private var stopAfterRecording = false
    private var recordingStartedAtMs = 0L
    @Volatile private var recordingDurationMs = 0L
    private var draftCreation: Deferred<Long>? = null
    private var lastElapsedSecond = -1
    @Volatile private var sessionPreferences = SessionPreferences()
    @Volatile private var silenceNoticeShown = false
    /** The take proceeded on earbuds that sent nothing; said once, before any other microphone line. */
    @Volatile private var forcedNoticeShown = false

    /**
     * Serialises the one STARTING→RECORDING publication (the CAS, the pill, the haptic, the surface) with
     * teardown's invalidation, so a live waiter that won its CAS cannot publish after `onDestroy` hid the
     * overlay, and teardown cannot interleave between the CAS and the publication.
     */
    private val publishLock = Any()
    /**
     * What captured the take, read ONCE at stop after `waitForFileReady`, when the capture thread has
     * exited and the record is complete. Empty means unknown and is stored as such (#26).
     */
    @Volatile private var captureDeviceLabel = ""
    /** One warning per take, latched so the last minute is not announced ten times a second. */
    @Volatile private var durationWarningShown = false

    /** True while a take is in PROCESSING; the Service picks the processing notification on a foreground command. */
    val isProcessing: Boolean get() = state.get() == SessionState.PROCESSING

    /** The Service's `onCreate` work that is the session's: stale-row recovery and the preference collectors. */
    fun onCreated() {
        scope.launch {
            runCatching { transcripts.recoverStaleOpenRows(System.currentTimeMillis()) }
                .onSuccess { recovered -> Telemetry.insertionsRecovered(recovered.readyRowIds) }
                .onFailure { error -> log.warn("Unable to recover stale history: ${error.message}") }
        }
        preferences.start(scope)
    }

    /**
     * One command from `onStartCommand`, on the main thread, in arrival order. [action] is one of the
     * Service's four actions; [request] the bubble's token when the bubble sent it; [trigger] the surface.
     */
    fun handleCommand(action: String, request: BubbleRequestToken?, trigger: TriggerSource) {
        pendingTrigger = trigger
        if (request != null && !admitBubbleCommand(action, request)) {
            stopIfIdle()
            return
        }
        when (action) {
            DictationSessionService.ACTION_CANCEL -> when (state.get()) {
                SessionState.STARTING -> cancelStarting()
                SessionState.RECORDING -> cancelRecording()
                SessionState.PROCESSING -> cancelProcessing()
                else -> stopIfIdle()
            }
            DictationSessionService.ACTION_STOP -> when (state.get()) {
                SessionState.STARTING -> cancelStarting()
                SessionState.RECORDING -> stopAndTranscribe()
                else -> stopIfIdle()
            }
            DictationSessionService.ACTION_TOGGLE -> when (state.get()) {
                SessionState.IDLE -> beginSession()
                SessionState.STARTING -> cancelStarting()
                SessionState.RECORDING -> stopAndTranscribe()
                else -> Unit
            }
            DictationSessionService.ACTION_START -> if (state.get() == SessionState.IDLE) {
                beginSession()
            } else {
                Telemetry.capture(AnalyticsEvent.DictationRefused("busy", pendingTrigger))
            }
        }
    }

    /**
     * Resolve a bubble-marked command against the request ledger BEFORE the ordinary dispatch. Returns
     * true when the ordinary dispatch should now run for this action, false when the command was
     * consumed (noted, retired, stale, refused) and nothing else must happen.
     *
     * Every command reaches this method on the main thread in arrival order, which is the whole
     * reason the ledger lives here and not in the bubble (issue #135 plan §3 "Requests").
     */
    private fun admitBubbleCommand(action: String, request: BubbleRequestToken): Boolean {
        return when (action) {
            DictationSessionService.ACTION_START -> when (val decision = BubbleRequests.resolveStart(request, ownerIdle = state.get() == SessionState.IDLE)) {
                BubbleRequestLedger.StartDecision.Stale -> {
                    log.log("Bubble start refused as stale")
                    Telemetry.capture(AnalyticsEvent.DictationRefused("stale", bubbleTrigger(request)))
                    false
                }
                BubbleRequestLedger.StartDecision.RefusedBusy -> {
                    log.log("Bubble start refused: a take is active")
                    Telemetry.capture(AnalyticsEvent.DictationRefused("busy", bubbleTrigger(request)))
                    false
                }
                is BubbleRequestLedger.StartDecision.Admitted -> {
                    admittedRequest = request
                    stopAfterRecording = decision.stopAfterRecording
                    beginSession()
                    if (decision.cancelAtOnce) cancelStarting()
                    false
                }
            }
            DictationSessionService.ACTION_STOP, DictationSessionService.ACTION_CANCEL -> {
                val cancel = action == DictationSessionService.ACTION_CANCEL
                when (BubbleRequests.resolveCommand(request, cancel, admittedSeq = admittedRequest?.seq)) {
                    BubbleRequestLedger.CommandDecision.ApplyToAdmitted -> {
                        if (!cancel && state.get() == SessionState.STARTING) {
                            // The hold was released before capture started: finish, never cancel.
                            stopAfterRecording = true
                            false
                        } else {
                            true
                        }
                    }
                    BubbleRequestLedger.CommandDecision.Noted -> {
                        log.log("Bubble ${if (cancel) "cancel" else "release"} noted ahead of its start")
                        false
                    }
                    BubbleRequestLedger.CommandDecision.Ignored,
                    BubbleRequestLedger.CommandDecision.Rejected -> false
                }
            }
            else -> true
        }
    }

    /** The bubble's surface from its own token: a hold and a tap are different surfaces (issue #176). */
    private fun bubbleTrigger(request: BubbleRequestToken): TriggerSource =
        if (request.held) TriggerSource.BUBBLE_HOLD else TriggerSource.BUBBLE_TAP

    private fun beginSession() {
        if (!state.compareAndSet(SessionState.IDLE, SessionState.STARTING)) return
        takeId = UUID.randomUUID().toString().lowercase()
        takePeakAmplitude = null
        val trigger = admittedRequest?.let(::bubbleTrigger) ?: pendingTrigger
        pendingTrigger = TriggerSource.UNKNOWN
        val takeFacts = TakeFacts(takeId, trigger)
        takeFacts.inputDevice = TakeFacts.inputDeviceToken(preferences.inputDevicePick)
        facts = takeFacts
        // The referee for THIS take, in memory, before anything else (G2 D2). Its sink is a limb: it
        // hands the committed reason to telemetry and never waits on storage or the network.
        arbiter = TakeArbiter { reason -> endingSink(takeFacts, reason) }
        Telemetry.takeStarted(takeId)
        Telemetry.breadcrumb("take", "admitted", mapOf("take_id" to takeId, "trigger_source" to trigger.wire))
        // Queued HERE, on the main thread, before any command can end this take: the journal applies
        // writes in arrival order, so a cancel that lands during the settings wait can never queue its
        // ending ahead of the admission and leave an open row (code review round 1, F2). The wait for
        // it happens below, before capture starts, under a deadline that never gates the take.
        val admission = Telemetry.journal?.admit(takeId, trigger)
        surface.showStarting(admittedRequest)
        host.promoteToForeground(processing = false)
        // Kept for the whole session. Android may rebind the accessibility service while the user
        // is still speaking, so the state insertion finds minutes later cannot say whether this
        // dictation ever had a field to aim at (`InsertionJudgement.handoffToJudge`).
        targetPinAtStart = insertion.pinTargetForDictation()
        // Name the field this take aims at, for a reader that only wants takes aimed at ITS field.
        surface.nameTarget(if (targetPinAtStart == DictationTargetPin.PINNED) insertion.pinnedFieldId() else null)
        teardownStarted.set(false)
        draftId.set(0L)
        draftCreation = null
        rawTranscript = ""
        recordingDurationMs = 0L
        lastElapsedSecond = -1
        scope.launch {
            val ready = preferences.awaitReady(settingsWaitMs)
            if (!ready) {
                withContext(mainDispatcher) {
                    if (state.get() == SessionState.STARTING) {
                        showError(TerminalReason.SETTINGS_UNAVAILABLE)
                    }
                }
                return@launch
            }
            val termsSnapshot: List<CustomTerm> = preferences.structuredTerms
            val matcher = withContext(Dispatchers.Default) {
                StructuredTermRestorer.compile(termsSnapshot)
            }
            val policy = withContext(Dispatchers.IO) { loadPolicy() }
            // Admission is written before capture starts, under a deadline that never gates the take:
            // the queued write still lands in order if this stops waiting (issue #176, plan §3.3).
            if (admission != null && withTimeoutOrNull(JOURNAL_ADMISSION_DEADLINE_MS) { admission.await() } == null) {
                log.warn("Journal admission did not land within $JOURNAL_ADMISSION_DEADLINE_MS ms; starting anyway")
            }
            withContext(mainDispatcher) {
                if (state.get() != SessionState.STARTING) return@withContext
                sessionPreferences = preferences.freeze(termsSnapshot, matcher, policy)
                bindPipelineServices()
            }
        }
    }

    private fun bindPipelineServices() {
        when (pipeline.bind(this)) {
            PipelineController.BindResult.BOUND -> Unit
            PipelineController.BindResult.AUDIO_BIND_FAILED -> {
                pipeline.stopAudioService()
                showError(TerminalReason.AUDIO_BIND_FAILED)
            }
            PipelineController.BindResult.ASR_BIND_FAILED -> handleServiceFailure(TerminalReason.ASR_BIND_FAILED)
            PipelineController.BindResult.POLISH_BIND_FAILED -> handleServiceFailure(TerminalReason.POLISH_BIND_FAILED)
        }
    }

    override fun onCaptureConnected() {
        log.log("Audio capture connected")
        tryStartRecording()
    }

    override fun onCaptureDisconnected() {
        log.warn("Audio capture disconnected")
        // STARTING too: since the live gate, capture runs while the lips spin, and a waiter whose
        // binder vanished returns without ending the take (Codex review 2, 2026-09-18).
        val seen = state.get()
        if (seen == SessionState.RECORDING || seen == SessionState.STARTING) {
            handleServiceFailure(TerminalReason.AUDIO_PROCESS_DIED)
        }
    }

    override fun onSpeechConnected() {
        log.log("Speech service connected")
    }

    override fun onSpeechDisconnected() {
        log.warn("Speech service disconnected")
        if (state.get() == SessionState.PROCESSING) {
            if (rawTranscript.isNotBlank()) {
                publishFallback(rawTranscript, sessionPreferences, PolishReason.SERVICE_DIED)
            } else if (arbiter.commitNow(TerminalReason.ASR_PROCESS_DIED)) {
                updateDraftStatus(TranscriptEntity.STATUS_ASR_ERROR, insertionResult = "asr_error")
                endAsFailure(TerminalReason.ASR_PROCESS_DIED)
            }
        }
    }

    override fun onPolishConnected() {
        // Warm at connect, measured and decided (#72): every later moment ends with the same two
        // models resident, because the speech model stays loaded after it transcribes, and costs the
        // user 0.9 to 3.1 s of wait. `architecture-rules.md` RULE: isolate-limbs carries the numbers.
        runCatching { pipeline.polish?.warmUpWithPolicy(sessionPreferences.policy) }
        log.log("Polish service connected")
    }

    override fun onPolishDisconnected() {
        log.warn("Polish service disconnected")
        if (state.get() == SessionState.PROCESSING) {
            if (rawTranscript.isNotBlank()) {
                publishFallback(rawTranscript, sessionPreferences, PolishReason.SERVICE_DIED)
            } else if (arbiter.commitNow(TerminalReason.POLISH_PROCESS_DIED)) {
                updateDraftStatus(TranscriptEntity.STATUS_ASR_ERROR, insertionResult = "asr_error")
                endAsFailure(TerminalReason.POLISH_PROCESS_DIED)
            }
        }
    }

    private fun tryStartRecording() {
        if (state.get() != SessionState.STARTING) return
        var captureStarted = false
        try {
            silenceNoticeShown = false
            durationWarningShown = false
            forcedNoticeShown = false
            captureDeviceLabel = ""
            val started = runCatching {
                pipeline.capture?.startCaptureForTake(
                    preferences.autoStopOnSilence,
                    preferences.silencePauseSeconds,
                    preferences.inputDevicePick,
                    preferences.keepEarbudsReady,
                    takeId,
                )
            }.getOrNull()
            if (started != true) {
                // The one start failure with its own sentence is "nothing can record at all" (macOS copy).
                val failure = runCatching { pipeline.capture?.lastStartFailure() }.getOrNull()
                    ?: AudioCaptureService.START_FAILURE_OTHER
                pipeline.stopAudioService()
                showError(TakeNotices.startFailureReason(failure))
                return
            }
            captureStarted = true
            // The take is STARTING until the chosen route delivers sound: the lips spin, no pill, no
            // timer, nothing written. The waiter performs the RECORDING transition when the capture
            // process reports live (issue #26, 2026-09-18).
            Thread({ waitForLive() }, "LiveWaiter").start()
        } catch (error: Exception) {
            if (captureStarted) {
                val capture = pipeline.capture
                Thread({
                    runCatching { capture?.stopCapture() }
                    runCatching { capture?.waitForFileReady(2_000L) }
                    pipeline.stopAudioService()
                }, "StartCaptureFailureCleanup").start()
            }
            log.error("Failed to start recording", error)
            showError(TerminalReason.START_EXCEPTION)
        }
    }

    /**
     * Polls the capture process until the take is live, then publishes RECORDING. Four ways out without
     * publishing: the state left STARTING (a cancel or teardown won), the binder is gone, capture ended
     * during the wait (a deadline failure or a capture error), or the STARTING bound passed. None of
     * them can publish a pill for an ended take, and none waits forever.
     */
    private fun waitForLive() {
        val startedAt = host.elapsedRealtimeMs()
        while (true) {
            if (state.get() != SessionState.STARTING) return
            val service = pipeline.capture ?: return // onServiceDisconnected ends the take for STARTING too.
            val live = try {
                service.liveState()
            } catch (_: Exception) {
                return // A dead binder: its ServiceConnection callback ends the take.
            }
            if (live != AudioCaptureService.LIVE_WAITING) {
                // Published on the MAIN thread, where every command is dispatched and where the
                // bubble's early release sets its flag: the old start published there too, so a stop,
                // cancel or release can never read STARTING and then act against a take this thread
                // published in between (Codex review 4, 2026-09-18).
                val forced = live == AudioCaptureService.LIVE_FORCED
                host.postToMain { publishLive(forced) }
                return
            }
            val capturing = runCatching { service.isCapturing() }.getOrDefault(false)
            if (!capturing) {
                val failure = runCatching { service.lastStartFailure() }.getOrDefault(AudioCaptureService.START_FAILURE_OTHER)
                val reason = if (failure == AudioCaptureService.START_FAILURE_EARBUDS) {
                    TerminalReason.CAPTURE_START_EARBUDS_REFUSED
                } else {
                    TerminalReason.CAPTURE_ENDED_BEFORE_LIVE
                }
                // Claim first: a cancel that stopped capture between the two checks owns the take, and
                // its stop must not read as a microphone failure.
                if (!failWhileStarting(reason)) return
                log.warn("Capture ended while waiting for the route to go live (failure=$failure)")
                runCatching { service.waitForFileReady(2_000L) }
                pipeline.stopAudioService()
                return
            }
            if (host.elapsedRealtimeMs() - startedAt > LIVE_WAIT_BOUND_MS) {
                if (!failWhileStarting(TerminalReason.LIVE_WAIT_DEADLINE)) return
                log.error("The route never went live within $LIVE_WAIT_BOUND_MS ms")
                runCatching { service.stopCapture() }
                runCatching { service.waitForFileReady(2_000L) }
                pipeline.stopAudioService()
                return
            }
            Thread.sleep(LIVE_POLL_MS)
        }
    }

    /** The one STARTING→RECORDING publication: main thread, under [publishLock]. */
    private fun publishLive(forced: Boolean) {
        check(host.onMainThread()) { "publishLive runs on the main thread" }
        synchronized(publishLock) {
            if (!state.compareAndSet(SessionState.STARTING, SessionState.RECORDING)) {
                pipeline.capture?.let { runCatching { it.stopCapture() } }
                return
            }
            recordingStartedAtMs = System.currentTimeMillis()
            val takeFacts = facts
            pipeline.capture?.let { service ->
                takeFacts.routeKind = runCatching { InputRouteKind.fromCode(service.inputRouteKind()) }.getOrNull()
                takeFacts.routeReason = runCatching { InputRouteReason.fromCode(service.inputRouteReason()) }.getOrNull()
                takeFacts.liveAfterMs = runCatching { service.liveAfterMs() }.getOrNull()
            }
            takeFacts.liveState = if (forced) "forced" else "ready"
            Telemetry.journal?.advance(takeId, TakeStage.RECORDING)
            Telemetry.breadcrumb(
                "take", "live",
                mapOf("take_id" to takeId, "route_kind" to takeFacts.routeKind?.name?.lowercase(), "live_after_ms" to takeFacts.liveAfterMs, "live_state" to takeFacts.liveState),
            )
            draftCreation = scope.async {
                val id = transcripts.insert(
                    TranscriptEntity(
                        originalText = "",
                        finalText = "",
                        createdAtMs = System.currentTimeMillis(),
                        durationMs = 0L,
                        speechEngine = "Parakeet",
                        polishEngine = PolishEngineLabels.NOT_RECORDED,
                        polishLatencyMs = 0L,
                        insertionResult = "pending",
                        status = TranscriptEntity.STATUS_DRAFT,
                    ),
                )
                // The row's identity goes out on the bridge so a reader judges THIS take's row, never a
                // row it guessed at by time or order (onboarding practice; Codex reviews 2 to 9).
                surface.attachTranscript(id)
                Telemetry.journal?.associate(takeFacts.takeId, id)
                id
            }
            host.updateSurfacePhase(DictationSurfaceState.Phase.LISTENING)
            surface.show()
            host.vibrate(HapticCue.SESSION_TRANSITION)
            log.log("Recording started (live after ${runCatching { pipeline.capture?.liveAfterMs() }.getOrNull() ?: -1} ms, forced=$forced)")
            if (forced) {
                // Said first, so neither the tip nor a pick-missing line can take the slot from it.
                forcedNoticeShown = true
                sayWhileRecording(CaptureNotices.EARBUDS_SILENT)
            }
            startPolling()
            if (stopAfterRecording) {
                // The bubble's hold was already released. Consumed here, at the one transition the
                // early release waits for, so a short hold keeps its words instead of losing them.
                stopAfterRecording = false
                log.log("Early release applied: stopping as soon as capture started")
                stopAndTranscribe()
            }
        }
    }

    private fun startPolling() {
        Thread({
            while (state.get() == SessionState.RECORDING) {
                try {
                    val service = pipeline.capture ?: break
                    val elapsedMs = service.elapsedMs()
                    val second = (elapsedMs / 1_000L).toInt().coerceAtLeast(0)
                    if (second != lastElapsedSecond) {
                        lastElapsedSecond = second
                        surface.updateElapsed(second)
                    }
                    publishSilenceNoticeIfNeeded(service)
                    publishMicrophoneNoticesIfNeeded(service)
                    if (!service.isCapturing() && state.get() == SessionState.RECORDING) {
                        val ending = service.terminalReason()
                        // The ending as a fact for the take's row, stamped here at the ONE place it is
                        // classified; a stop the owner itself requested never reaches this branch and is
                        // stamped `manual` at the stop (issue #176).
                        facts.captureTerminal = TakeFacts.captureEndingToken(ending)
                        // Exhaustive over CaptureEnding with no `else`, so a reason this build does not
                        // know cannot fall through into an ordinary transcription.
                        when (CaptureEnding.fromAidl(ending)) {
                            // StillRunning belongs HERE. Capture that stopped without publishing a
                            // reason has no successful ending to report, and the type says so:
                            // StillRunning.transcribes is false. Grouping it with the successes would
                            // send partial audio on as though it were a finished take.
                            CaptureEnding.Failure -> {
                                log.error("Audio capture ended without a successful reason")
                                discardDraft()
                                showError(TerminalReason.CAPTURE_FAILED_MID_TAKE)
                            }

                            CaptureEnding.StillRunning -> {
                                log.error("Audio capture stopped without publishing a reason")
                                discardDraft()
                                showError(TerminalReason.CAPTURE_STILL_RUNNING_AFTER_STOP)
                            }

                            // The words up to the cap are kept and transcribed. What the user needs
                            // to be told is why the recording ended without them asking, because a take
                            // that stops on its own with no sentence reads as a fault.
                            // Transcribe FIRST, then say why. The words are the thing that must
                            // survive; the sentence explaining the ending is a limb, and putting it
                            // ahead of the transition would let a failure in it cost the take.
                            CaptureEnding.MaxDuration -> {
                                stopAndTranscribe()
                                log.log("Take ended at the duration cap")
                                sayAfterRecording(DURATION_REACHED_NOTICE)
                            }

                            CaptureEnding.Manual,
                            CaptureEnding.Silence -> stopAndTranscribe()
                        }
                        break
                    }
                    // Both of these sit BELOW the terminal check, and the position is the isolation.
                    // The warning is a limb: it tells the user something useful and nothing depends on
                    // it, so a failure in it must not carry the loop past the check that starts
                    // transcription. Above the check, a throw here would cost the take.
                    publishDurationWarningIfNeeded(elapsedMs)
                } catch (_: Exception) {
                    // A binder disconnect is handled by its ServiceConnection callback.
                }
                Thread.sleep(100)
            }
        }, "DictationPollingThread").start()
        startMeter()
    }

    /**
     * The recorder's live picture, on its own thread, for exactly as long as the take is open.
     *
     * Its own thread rather than a step in the polling tick, because the picture is a limb and the
     * tick is the heart: a slow, throwing or blocked reading here can delay nothing the take depends on
     * (`architecture-rules.md` RULE: isolate-limbs). It is also the ONLY reader of the picture in the
     * app. The recorder is pushed finished numbers rather than reaching for the capture service, so a
     * second surface cannot become a second reader (RULE: no-idle-cost); the thread exists only while a
     * take is open, so idle cost is unchanged.
     *
     * Take identity is the snapshot's serial, captured here after `show()` stamped it. A reading that
     * returns after the take ended carries a stale serial, which `updateBands` refuses under its lock,
     * and the loop leaves as soon as it sees the serial move on. A THROWING reading publishes the empty
     * picture so the rail rests; a reading that never returns (a wedged audio process, #115) reaches no
     * branch at all and the rail holds its last picture until the pill hides, as the timer holds its
     * last second.
     *
     * Failing to start this thread costs the picture only: the take and its polling thread carry on.
     */
    private fun startMeter() {
        val takeSerial = surface.currentTakeSerial()
        runCatching {
            Thread({
                while (state.get() == SessionState.RECORDING) {
                    val service = pipeline.capture ?: break
                    val bands = runCatching { service.spectrumBands() }.getOrElse { surface.emptyBands() }
                    if (surface.currentTakeSerial() != takeSerial) break
                    surface.updateBands(takeSerial, bands)
                    Thread.sleep(METER_INTERVAL_MS)
                }
            }, "DictationMeterThread").start()
        }.onFailure { log.warn("Live picture unavailable for this take: ${it.message}") }
    }

    /**
     * Tell the user once, and only when auto-stop never became available for a take they had it on for.
     *
     * Losing the detector after it was already working leaves a correct recording, and a message
     * several seconds into one is an interruption for nothing. The floating recorder only exists while
     * the accessibility service runs, so clipboard-only mode gets the same sentence as a toast instead.
     */
    private fun publishSilenceNoticeIfNeeded(service: CaptureLink) {
        if (!preferences.autoStopOnSilence || silenceNoticeShown) return
        val status = runCatching { service.silenceStopStatus() }.getOrNull() ?: return
        if (status != AudioCaptureService.SILENCE_STATUS_UNAVAILABLE) return
        silenceNoticeShown = true
        sayWhileRecording(SILENCE_UNAVAILABLE_NOTICE)
    }

    /**
     * The one-time line about the microphone, decided from the kind code the capture process reports:
     * the Bluetooth tip (once per app process, tips on, take started on Bluetooth). It never reads the
     * display label. A pick that was not connected has no line of its own (#173, the Mac rule): the
     * take records through Auto, the History card names what recorded, and a Bluetooth take reached
     * that way is an ordinary Bluetooth take for the tip.
     *
     * The recorder has ONE notice slot and the last write wins, so the tip is never said in a take that
     * already carries the auto-stop warning or the forced notice: a capture warning outranks a nudge.
     * The tip's once-per-process allowance is spent only when the tip is actually said, so a take that
     * had to say something else leaves it for the next Bluetooth take (Codex review 5, 2026-09-17).
     */
    private fun publishMicrophoneNoticesIfNeeded(service: CaptureLink) {
        if (silenceNoticeShown || forcedNoticeShown) return
        val kind = runCatching { service.inputRouteKind() }.getOrNull() ?: return
        if (tipGate.shouldShow(kind, preferences.showBluetoothTips)) {
            log.log("Bluetooth tip shown")
            sayWhileRecording(CaptureNotices.BLUETOOTH_TIP)
        }
    }

    /**
     * Warn once, in the last minute of a take, that the cap is about to stop it.
     *
     * The moment comes from `RecordingLimits`, the same object the capture process stops the take with.
     * An earlier revision asked the capture service for it over the binder, for authority across the
     * process boundary. That bought nothing and cost something: both processes compile the SAME
     * constant, so there was no drift to catch, while the call added a place this thread could hang
     * before its loop had started even once (issue #115). The call is gone rather than guarded.
     */
    private fun publishDurationWarningIfNeeded(elapsedMs: Long) {
        if (durationWarningShown || elapsedMs < RecordingLimits.WARNING_AT_MS) return
        durationWarningShown = true
        log.log("Duration warning shown at ${elapsedMs}ms")
        sayWhileRecording(DURATION_WARNING_NOTICE)
    }

    /**
     * Say one line to a user who is mid-dictation, wherever they can actually see it.
     *
     * The floating recorder exists only while the accessibility service is bound. In clipboard-only
     * mode there is no recorder at all, so the same sentence has to arrive as a toast instead. Both
     * callers want that decision made identically, and making it in one place is what stops the next
     * message being announced on a surface that is not there.
     */
    private fun sayWhileRecording(line: String) {
        if (insertion.isBound()) {
            surface.showNotice(line)
        } else {
            sayAfterRecording(line)
        }
    }

    /** Say one line when the recorder has already gone. A toast is the only surface left. */
    private fun sayAfterRecording(line: String) {
        scope.launch(mainDispatcher) {
            runCatching {
                host.toastFromApplication(line)
            }
        }
    }

    private fun stopAndTranscribe() {
        // Under publishLock: the live waiter publishes RECORDING (pill, draft) under the same lock, so a
        // stop that follows its CAS cannot run ahead of its publication (Codex review 3, 2026-09-18).
        synchronized(publishLock) {
            if (!state.compareAndSet(SessionState.RECORDING, SessionState.PROCESSING)) return
            surface.showProcessing()
        }
        host.updateSurfacePhase(DictationSurfaceState.Phase.PROCESSING)
        host.promoteToForeground(processing = true)
        host.vibrate(HapticCue.SESSION_TRANSITION)
        log.log("Stopping recording and starting transcription")

        Thread({
            var audioReady = false
            try {
                pipeline.capture?.stopCapture()
                audioReady = runCatching { pipeline.capture?.waitForFileReady(2_000L) == true }.getOrDefault(false)
                if (!audioReady) {
                    pipeline.stopAudioService()
                    discardDraft()
                    showError(TerminalReason.CAPTURE_CLOSE_UNSAFE)
                    return@Thread
                }
                val audioFilePath = pipeline.capture?.audioFilePath()
                // The duration is the audio's own length, read from the finished file NOW, before
                // transcription deletes it: the elapsed getter is 0 once capture stops, and the wall
                // clock counted the wait for the earbuds.
                recordingDurationMs = runCatching {
                    audioFilePath?.let { (PcmAudio.durationSeconds(File(it).length()) * 1000f).toLong() }
                }.getOrNull()?.coerceAtLeast(0L) ?: 0L
                // Complete once the capture thread has exited (waitForFileReady above joined it): the
                // final route was observed before the recorder stopped, and the label persists in the
                // capture process until its next start.
                captureDeviceLabel = runCatching { pipeline.capture?.effectiveInputDevice() }.getOrNull().orEmpty()
                // Same moment, same reason: the capture thread has exited, so the peak is the whole take's.
                takePeakAmplitude = runCatching { pipeline.capture?.takePeakAmplitude() }.getOrNull()
                val takeFacts = facts
                takeFacts.peakAmplitude = takePeakAmplitude
                takeFacts.recordingSeconds = recordingDurationMs / 1000.0
                // Stamped by the polling loop when capture ended on its own; otherwise this stop is the
                // owner's own request, which the capture process reports as a manual ending.
                if (takeFacts.captureTerminal == null) takeFacts.captureTerminal = TakeFacts.MANUAL_ENDING
                pipeline.capture?.let { service ->
                    takeFacts.silenceStopStatus = runCatching { TakeFacts.silenceStatusToken(service.silenceStopStatus()) }.getOrNull()
                }
                Telemetry.journal?.advance(takeId, TakeStage.PROCESSING)
                Telemetry.breadcrumb(
                    "take", "stopped",
                    mapOf("take_id" to takeId, "capture_terminal" to takeFacts.captureTerminal, "recording_s" to takeFacts.recordingSeconds, "silence_stop_status" to takeFacts.silenceStopStatus),
                )
                finishTakeOrStop()

                val readyDraftId = runCatching { runBlocking { draftCreation?.await() ?: 0L } }.getOrDefault(0L)
                if (readyDraftId > 0L) {
                    draftId.set(readyDraftId)
                    updateDraftStatus(TranscriptEntity.STATUS_PROCESSING)
                }
                if (audioFilePath.isNullOrBlank()) {
                    updateDraftStatus(TranscriptEntity.STATUS_ASR_ERROR, insertionResult = "asr_error")
                    showError(TerminalReason.AUDIO_FILE_MISSING)
                    return@Thread
                }
                val speechService = pipeline.speech
                if (speechService == null) {
                    deleteCapturedAudio(audioFilePath)
                    updateDraftStatus(TranscriptEntity.STATUS_ASR_ERROR, insertionResult = "asr_error")
                    showError(TerminalReason.ASR_NOT_READY)
                    return@Thread
                }
                log.mark("asr_request")
                val asrRequestedAtMs = host.elapsedRealtimeMs()
                speechService.transcribeFileForTake(audioFilePath, takeId, object : SpeechListener {
                    override fun onResult(text: String?) {
                        deleteCapturedAudio(audioFilePath)
                        takeFacts.asrMs = host.elapsedRealtimeMs() - asrRequestedAtMs
                        takeFacts.asrChars = text?.length ?: 0
                        log.log("Transcription result received (chars=${text?.length ?: 0})")
                        log.mark("result_received")
                        Telemetry.breadcrumb("take", "asr_done", mapOf("take_id" to takeId, "asr_ms" to takeFacts.asrMs, "asr_chars" to takeFacts.asrChars))
                        polishAndPublish(text.orEmpty())
                    }

                    /** The versioned request never answers this; a legacy sentence here is a service defect. */
                    override fun onError(message: String?) {
                        deleteCapturedAudio(audioFilePath)
                        log.error("Legacy onError on a versioned request")
                        // The fact is written before the claim so the ending's row carries it; a claim
                        // that loses leaves an unread fact, never a rewritten row (G1 D2).
                        takeFacts.asrFailure = AsrFailureReason.UNKNOWN
                        takeFacts.asrMs = host.elapsedRealtimeMs() - asrRequestedAtMs
                        if (!arbiter.commitNow(TerminalReason.ASR_FAILED)) return
                        updateDraftStatus(TranscriptEntity.STATUS_ASR_ERROR, insertionResult = "asr_error")
                        endAsFailure(TerminalReason.ASR_FAILED)
                    }

                    override fun onFailure(reason: Int, detail: String?) {
                        deleteCapturedAudio(audioFilePath)
                        val failure = AsrFailureReason.fromCode(reason)
                        takeFacts.asrFailure = failure
                        takeFacts.asrMs = host.elapsedRealtimeMs() - asrRequestedAtMs
                        // Claim FIRST: a cancel that already owns the take must not see its History row
                        // rewritten or a failure toast over its acknowledgement (G1 D2).
                        if (!arbiter.commitNow(TerminalReason.ASR_FAILED)) return
                        updateDraftStatus(TranscriptEntity.STATUS_ASR_ERROR, insertionResult = "asr_error")
                        // The detail is local diagnostics and stops here: never a toast, never the wire.
                        log.error("ASR failed: ${failure.name} (code $reason) ${detail.orEmpty()}")
                        endAsFailure(TerminalReason.ASR_FAILED)
                    }
                })
            } catch (error: Exception) {
                pipeline.stopAudioService()
                if (audioReady) deleteCapturedAudio(runCatching { pipeline.capture?.audioFilePath() }.getOrNull())
                log.error("Transcription failed", error)
                updateDraftStatus(TranscriptEntity.STATUS_ASR_ERROR, insertionResult = "asr_error")
                showError(TerminalReason.ASR_CALLBACK_EXCEPTION)
            }
        }, "TranscribeThread").start()
    }

    private fun polishAndPublish(rawText: String) {
        rawTranscript = rawText
        if (rawText.isBlank()) {
            // Committed before the draft is discarded (G2 D2). The peak read at stop decides which of the
            // three empty endings this is; no reading stays unmeasured, never "silence".
            if (!arbiter.commitNow(SpeechEvidence.emptyTranscriptReason(takePeakAmplitude))) return
            discardDraft()
            insertion.releasePinnedTarget()
            finishSession()
            return
        }
        scope.launch {
            val takePreferences = sessionPreferences
            val preparedRaw = restoreTakeVocabulary(rawText, takePreferences)
            val service = pipeline.polish
            if (service == null) {
                publishFallback(rawText, takePreferences, PolishReason.SERVICE_UNAVAILABLE)
                return@launch
            }
            // The state check and the ledger open are one step under the submission lock, so a cancel
            // either precedes them (no request is sent) or finds the open id and closes it. The binder call
            // itself runs outside the lock: the lock is also taken on the main thread by Cancel, and a
            // synchronous transaction to a stalled engine must not be able to hold the main thread.
            val requestId = synchronized(polishSubmissionLock) {
                if (state.get() != SessionState.PROCESSING || !arbiter.isOpen) {
                    log.log("Transcript arrived after the session ended; not polishing")
                    return@launch
                }
                val opened = polishLedger.open()
                // The watchdog is armed BEFORE the binder call so the call itself is inside the budget.
                // The ledger is the only first-wins gate: an outcome that arrives first closes it.
                scope.launch {
                    polishTimeout.await(takePreferences.policy)
                    if (!polishLedger.claim(opened)) return@launch
                    log.warn("Polish watchdog fired for request $opened; cancelling on the engine")
                    runCatching { pipeline.polish?.cancel(opened) }
                    publishFallback(rawText, takePreferences, PolishReason.WATCHDOG_TIMEOUT)
                }
                opened
            }
            try {
                service.polishRequestForTake(
                    requestId,
                    preparedRaw,
                    takePreferences.cleanup.removeFillers,
                    takePreferences.cleanup.spokenEmoji,
                    takePreferences.cleanup.spokenPunctuation,
                    takePreferences.policy,
                    takeId,
                    object : PolishListener {
                        override fun onOutcome(outcome: PolishOutcome?) {
                            // This callback belongs to ONE request and the engine answers it once, so an
                            // empty or misnamed outcome is the only answer this request will get: fail
                            // open now rather than leave the session in Processing forever.
                            if (outcome == null || outcome.requestId != requestId) {
                                if (polishLedger.claim(requestId)) {
                                    log.warn("Invalid polish outcome for request $requestId")
                                    Telemetry.defect(AppDefect.PolishProtocolViolation, mapOf("take_id" to takeId, "shape" to if (outcome == null) "null" else "mismatched"))
                                    publishFallback(rawText, takePreferences, PolishReason.CALL_FAILED)
                                }
                                return
                            }
                            if (!polishLedger.claim(outcome.requestId)) {
                                log.warn(
                                    "Ignoring polish outcome for request ${outcome.requestId}: not the open request (reason=${outcome.reason})",
                                )
                                return
                            }
                            log.log("Polish outcome ${outcome.requestId}: reason=${outcome.reason} status=${outcome.statusCode}")
                            publishResult(
                                restoreTakeVocabulary(outcome.text, takePreferences),
                                outcome.engine,
                                outcome.latencyMs,
                                outcome.reason,
                                outcome.statusCode,
                                PolishContext.from(takePreferences.policy),
                            )
                        }

                        // v1 answers are never produced for a v2 request. If one ever arrives it is an
                        // engine defect, and the session still fails open to the deterministic text.
                        override fun onResult(text: String?, engine: String?, latencyMs: Long) {
                            if (polishLedger.claim(requestId)) {
                                Telemetry.defect(AppDefect.PolishProtocolViolation, mapOf("take_id" to takeId, "shape" to "v1_result"))
                                publishFallback(rawText, takePreferences, PolishReason.CALL_FAILED)
                            }
                        }

                        override fun onError(message: String?) {
                            if (polishLedger.claim(requestId)) {
                                Telemetry.defect(AppDefect.PolishProtocolViolation, mapOf("take_id" to takeId, "shape" to "v1_error"))
                                publishFallback(rawText, takePreferences, PolishReason.CALL_FAILED)
                            }
                        }
                    },
                )
            } catch (error: Exception) {
                log.error("Unable to call polish service", error)
                if (polishLedger.claim(requestId)) publishFallback(rawText, takePreferences, PolishReason.CALL_FAILED)
            }
            // A cancel that landed between the ledger open and the engine's registration found nothing to
            // cancel on the engine. Now the request is registered, so send it again; on a delivered or
            // never-registered id the engine treats it as a no-op. A read, never a claim: on a normal day
            // the ledger is still open here and must stay open for the outcome.
            if (polishLedger.openId != requestId) runCatching { service.cancel(requestId) }
        }
    }

    /**
     * The session owner's own fallback, published under the deterministic label with the reason
     * logged by name. Closes the ledger and cancels the open request first, so a late engine outcome
     * cannot be accepted after it and an abandoned cloud call does not run on.
     */
    private fun publishFallback(rawText: String, takePreferences: SessionPreferences, reason: PolishReason) {
        cancelOpenPolishRequest()
        log.warn("Polish fell back on the session owner: reason=$reason")
        publishResult(
            deterministicFallback(rawText, takePreferences),
            PolishEngineLabels.DETERMINISTIC,
            0,
            reason,
            0,
            PolishContext.from(takePreferences.policy),
        )
    }

    /**
     * The same deterministic pipeline the engine runs with polish off, so the text a user gets
     * cannot depend on which side failed (issue #69; the regex polisher that used to run here
     * capitalised sentences and appended a period the engine never did).
     */
    private fun deterministicFallback(
        rawText: String,
        takePreferences: SessionPreferences,
    ): String {
        val prepared = restoreTakeVocabulary(rawText, takePreferences)
        // The engine resolves the same answer on its own side. Detecting here too is what keeps this
        // terminal from being the one that still applies English rules to foreign words when the engine
        // is the side that failed (#107); the alternative was a new AIDL transaction to carry it across,
        // which `workflow-process.md` RULE: tier-routing classifies as REFACTOR for a limb feature.
        val cleaned = PolishFallback.deterministic(prepared, takePreferences.cleanup, languageDetector)
        return restoreTakeVocabulary(cleaned, takePreferences)
    }

    /**
     * Closes the ledger and cancels exactly the request that was open, if any. Called as every
     * terminal transition BEGINS, before the wait for pending History work, and again from
     * the unbind as an idempotent backstop.
     */
    private fun cancelOpenPolishRequest() {
        val requestId = polishLedger.close() ?: return
        runCatching { pipeline.polish?.cancel(requestId) }
            .onFailure { error -> log.warn("Unable to cancel polish request $requestId: ${error.message}") }
    }

    private fun restoreTakeVocabulary(text: String, preferences: SessionPreferences): String {
        val restored = preferences.matcher.restore(text)
        return if (TextSafety.isSafe(text, restored)) restored else text
    }

    /**
     * The one place a polish outcome becomes a History row and, when it did not do its job, a sentence
     * (#77). Two routes reach it, the outcome callback and `publishFallback`; the facts are derived once
     * here, and the notice is posted BEFORE persistence and insertion begin so it precedes the delivery
     * line when both fire.
     */
    private fun publishResult(
        text: String,
        engine: String,
        latencyMs: Long,
        reason: PolishReason,
        statusCode: Int,
        polishContext: PolishContext,
    ) {
        // The polish facts, written before the reservation so an ending committed by anyone after this
        // point carries them; the reason arrives once per take through the ledger, so the defect it may
        // name is raised once here, whoever ends up owning the take (issue #176, plan §3.6).
        val takeFacts = facts
        takeFacts.polishProvider = polishContext.encode()
        takeFacts.polishReason = reason
        takeFacts.polishMs = latencyMs
        takeFacts.polishStatus = statusCode
        Telemetry.breadcrumb("take", "polish_done", mapOf("take_id" to takeId, "polish_reason" to reason.name, "polish_ms" to latencyMs, "polish_provider" to takeFacts.polishProvider))
        TelemetryChannels.defectOf(reason)?.let { Telemetry.defect(it, mapOf("take_id" to takeId, "polish_status" to statusCode)) }
        // RESERVE, never commit: `completed` is unknown until the History save returns (G2 D2). A cancel
        // that already owns the take, or a second final callback, loses here and does nothing.
        val publication = arbiter.reserve(Claimants.PUBLICATION)
        if (publication == null) {
            log.warn("Ignoring a final transcript that arrived after the take was claimed")
            return
        }
        val finalText = text.ifBlank { rawTranscript }
        val finalEngine = if (text.isBlank() && rawTranscript.isNotBlank()) PolishEngineLabels.RAW_FALLBACK else engine
        log.log("Polish result received ($finalEngine, ${latencyMs}ms, chars=${finalText.length})")
        if (finalText.isBlank()) {
            if (arbiter.commit(publication, TerminalReason.FINAL_TEXT_EMPTY)) finishSession()
            return
        }
        val polishFacts = PolishPublicationFacts.from(reason, statusCode, polishContext)
        polishFacts.notice?.let { notice ->
            log.log("Polish notice shown: ${polishFacts.failure}")
            host.postToMain {
                host.toastFromService(notice.toastLine)
                host.showPolishNotice(notice)
            }
        }

        scope.launch {
            val saveResult = runCatching {
                val existingId = draftId.get()
                val persistedId = if (existingId > 0L) {
                    val updated = transcripts.finalize(
                        id = existingId,
                        originalText = rawTranscript,
                        finalText = finalText,
                        speechEngine = "Parakeet",
                        polishEngine = finalEngine,
                        polishLatencyMs = latencyMs,
                        insertionResult = "pending",
                        durationMs = recordingDurationMs,
                        polishReason = polishFacts.reasonToken,
                        polishStatus = polishFacts.statusCode,
                        polishContext = polishFacts.contextToken,
                        captureDevice = captureDeviceLabel,
                    )
                    if (updated > 0) existingId else insertReadyTranscript(finalText, finalEngine, latencyMs, polishFacts)
                } else {
                    insertReadyTranscript(finalText, finalEngine, latencyMs, polishFacts)
                }
                draftId.set(persistedId)
                persistedId
            }
            val persistedId = saveResult.getOrNull() ?: 0L
            takeFacts.historySave = if (saveResult.isSuccess) "ok" else "failed"
            saveResult.exceptionOrNull()?.let { error ->
                log.warn("Unable to save transcript history: ${error.message}")
                // Storage being full or locked is the world (a breadcrumb); a constraint or an illegal
                // statement is our schema contract (a defect). The message never leaves either way.
                Telemetry.breadcrumb("take", "history_save_failed", mapOf("take_id" to takeId, "error_type" to error.javaClass.simpleName))
                TelemetryChannels.historySaveDefect(error)?.let { Telemetry.defect(it, mapOf("take_id" to takeId)) }
            }
            // COMMIT now that the save result is known and BEFORE the insertion handoff: completed means
            // the text finalised, never that insertion succeeded. A revoked reservation (the owner was
            // destroyed while the save ran) stops here: no handoff, no announcement, no terminal; the
            // teardown's own History write is the last word on that row (G2 D2).
            if (!arbiter.commit(publication, TerminalReason.COMPLETED)) {
                log.warn("Publication revoked before the handoff; not inserting")
                return@launch
            }
            val route = HistoryPublicationPolicy.route(
                persistedId = persistedId,
                persistenceSucceeded = saveResult.isSuccess,
            )
            // Corrected once, here, so the announcement, the History row and the log all read the
            // same handoff. Deriving it twice is how the two surfaces started disagreeing.
            val handoff = InsertionJudgement.handoffToJudge(
                startPin = targetPinAtStart,
                insertionHandoff = if (route == HistoryPublicationPolicy.Route.AUTO_INSERT) {
                    insertion.pasteWhenTargetReturns(
                        persistedId,
                        finalText,
                        sessionPreferences.clipboard,
                        takeId,
                    )
                } else {
                    InsertionHandoff.HISTORY_NOT_DURABLE
                },
            )
            if (handoff != InsertionHandoff.SCHEDULED) {
                insertion.releasePinnedTarget()
                val mustPreventDataLoss = persistedId <= 0L
                // Three outcomes, not two. A copy that was never attempted is the user's own
                // auto-copy setting and History is then the destination; a copy that was attempted
                // and failed is a fault whatever else was true.
                val clipboard =
                    if (sessionPreferences.clipboard.autoCopyToClipboard || mustPreventDataLoss) {
                        if (keepOnClipboard(persistedId, finalText)) {
                            ClipboardOutcome.COPIED
                        } else {
                            ClipboardOutcome.WRITE_FAILED
                        }
                    } else {
                        keepInHistoryOnly(persistedId)
                        ClipboardOutcome.NOT_ATTEMPTED
                    }
                // Nothing was handed to the accessibility service on this branch, so it will never
                // speak: the announcement has to originate here so insertion fails safe, never
                // silently (enviouswispr-android-parity-spec.md PAR-081). The routes
                // where the service DID accept the text and then failed announce themselves, in
                // PasteAccessibilityService.recordAndAnnounce.
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
            }
            log.log(
                when {
                    handoff == InsertionHandoff.SCHEDULED ->
                        "Auto-insert handed to accessibility target tracker"
                    route == HistoryPublicationPolicy.Route.COPY_ONLY ->
                        "History persistence unavailable; transcript kept on clipboard only"
                    sessionPreferences.clipboard.autoCopyToClipboard || persistedId <= 0L ->
                        "Accessibility unavailable; transcript kept on clipboard"
                    else -> "Accessibility unavailable; transcript retained in History"
                } + " (handoff=$handoff)",
            )
            log.log(log.pipelineSummary())
            finishSession()
        }
    }

    private suspend fun insertReadyTranscript(finalText: String, engine: String, latencyMs: Long, polishFacts: PolishPublicationFacts): Long {
        return transcripts.insert(
            TranscriptEntity(
                originalText = rawTranscript,
                finalText = finalText,
                createdAtMs = System.currentTimeMillis(),
                durationMs = recordingDurationMs,
                speechEngine = "Parakeet",
                polishEngine = engine,
                polishLatencyMs = latencyMs,
                insertionResult = "pending",
                status = TranscriptEntity.STATUS_READY_FOR_INSERTION,
                polishReason = polishFacts.reasonToken,
                polishStatus = polishFacts.statusCode,
                polishContext = polishFacts.contextToken,
                captureDevice = captureDeviceLabel,
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

        runCatching {
            transcripts.finalizeInsertionOutcome(
                transcriptId,
                TranscriptEntity.STATUS_INSERTION_INTERRUPTED,
                if (copied) InsertionResults.CLIPBOARD else InsertionResults.INSERTION_FAILED,
                interrupted = true,
            )
        }.onFailure { error ->
            log.warn("Unable to finalize clipboard-only history: ${error.message}")
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

    private suspend fun keepInHistoryOnly(transcriptId: Long) {
        if (transcriptId <= 0L) return
        runCatching {
            transcripts.finalizeInsertionOutcome(
                transcriptId,
                TranscriptEntity.STATUS_INSERTION_INTERRUPTED,
                InsertionResults.HISTORY_ONLY,
                interrupted = true,
            )
        }.onFailure { error ->
            log.warn("Unable to finalize history-only transcript: ${error.message}")
        }
    }

    private fun cancelRecording() {
        val cancel = synchronized(publishLock) {
            if (!state.compareAndSet(SessionState.RECORDING, SessionState.CANCELLING)) return
            surface.showProcessing()
            arbiter.reserve(Claimants.CANCEL)
        } ?: return
        cancelCaptureAndFinish(cancel, TerminalReason.CANCELLED_RECORDING)
    }

    /**
     * Shared by a cancel during RECORDING and during STARTING: since the live gate, capture is already
     * running while the lips spin, so a cancelled start stops it, discards the file and still hands the
     * service its `finishTake` (a cancelled take leaves the earbuds warm like a finished one).
     */
    private fun cancelCaptureAndFinish(cancel: TakeArbiter.Token, cancelled: TerminalReason) {
        surface.showProcessing()
        insertion.releasePinnedTarget()
        host.updateSurfacePhase(DictationSurfaceState.Phase.IDLE)
        host.vibrate(HapticCue.SESSION_CANCELED)
        scope.launch {
            // A cancel before the capture process was even bound has nothing to stop: the quiet finish
            // the old cancelStarting always took. Committed BEFORE the draft goes (G2 D2).
            val service = pipeline.capture
            if (service == null) {
                if (!arbiter.commit(cancel, cancelled)) return@launch
                discardDraft()
                finishSession()
                return@launch
            }
            val ready = runCatching {
                service.stopCapture()
                service.waitForFileReady(2_000L)
            }.getOrDefault(false)
            // The outcome is known only now: a close that failed is a failure, not a cancel.
            if (!ready) {
                if (!arbiter.commit(cancel, TerminalReason.CAPTURE_CLOSE_UNSAFE_ON_CANCEL)) return@launch
                pipeline.stopAudioService()
                discardDraft()
                endAsFailure(TerminalReason.CAPTURE_CLOSE_UNSAFE_ON_CANCEL)
                return@launch
            }
            if (!arbiter.commit(cancel, cancelled)) return@launch
            discardDraft()
            deleteCapturedAudio(runCatching { service.audioFilePath() }.getOrNull())
            finishTakeOrStop()
            finishSession()
        }
    }

    /**
     * The take is over: let the capture service keep the earbuds warm if it can (it then owns its own
     * lifetime and the unbind must not stop it), otherwise stop it as before.
     */
    private fun finishTakeOrStop() {
        val held = runCatching { pipeline.capture?.finishTake() == true }.getOrDefault(false)
        if (!held) pipeline.stopAudioService()
    }

    /**
     * Cancel while the words are being transcribed or polished (#75). Claims publication first: if the
     * text is already on its way the cancel is too late and does nothing. Otherwise it takes the submission
     * lock so it either precedes the ledger open (no request is sent) or follows it (the open id is closed
     * and cancelled on the engine, and the submitter re-sends that cancel once the engine has registered).
     */
    private fun cancelProcessing() {
        val cancel = synchronized(polishSubmissionLock) {
            // The reservation IS the publication check: a publication already holding the ending makes
            // this cancel too late, exactly as the old flag did, and a cancel that wins keeps a later
            // callback from ever publishing (G2 D2).
            if (state.get() != SessionState.PROCESSING) return
            val reserved = arbiter.reserve(Claimants.CANCEL) ?: return
            state.set(SessionState.CANCELLING)
            log.log("Cancelled while processing; open polish request: ${polishLedger.openId != null}")
            cancelOpenPolishRequest()
            reserved
        }
        surface.showProcessing()
        insertion.releasePinnedTarget()
        host.updateSurfacePhase(DictationSurfaceState.Phase.IDLE)
        host.vibrate(HapticCue.SESSION_CANCELED)
        // Committed before the draft goes (G2 D2); a destruction that revoked it owns the row instead.
        if (!arbiter.commit(cancel, TerminalReason.CANCELLED_PROCESSING)) return
        discardDraft()
        finishSession()
    }

    private fun cancelStarting() {
        val cancel = synchronized(publishLock) {
            if (!state.compareAndSet(SessionState.STARTING, SessionState.CANCELLING)) return
            surface.showProcessing()
            arbiter.reserve(Claimants.CANCEL)
        } ?: return
        cancelCaptureAndFinish(cancel, TerminalReason.CANCELLED_STARTING)
    }

    /**
     * Ends the take as the failure [reason] when nothing else has claimed it: the arbiter is the guard,
     * so a failure observed after a cancel or a publication owns the take does nothing at all (the
     * old `ERROR` check let it announce over them). The sentence is [TakeNotices]'s, never the caller's.
     */
    private fun showError(reason: TerminalReason) {
        if (!arbiter.commitNow(reason)) {
            log.log("Ignoring $reason: the take already has an ending")
            return
        }
        endAsFailure(reason)
    }

    /** Teardown for a failure ALREADY committed by the caller; [TakeNotices] supplies the sentence. */
    private fun endAsFailure(reason: TerminalReason) {
        endAsFailure(reason, TakeNotices.line(reason))
    }

    private fun endAsFailure(reason: TerminalReason, line: String?) {
        state.set(SessionState.ERROR)
        log.warn("Take ended: $reason")
        announceError(line)
    }

    /**
     * The live waiter's failure: claims ERROR only while the take is still STARTING, under the lock, so
     * a cancel that already owns the take is not overwritten with a failure toast (Codex review 3).
     * Returns false when something else owns the take; the waiter then does nothing.
     */
    private fun failWhileStarting(reason: TerminalReason): Boolean {
        synchronized(publishLock) {
            if (!state.compareAndSet(SessionState.STARTING, SessionState.ERROR)) return false
            // The CAS above and this commit move together under the one lock, so they cannot disagree:
            // a cancel that owns the take already moved the state, and a destruction already committed.
            if (!arbiter.commitNow(reason)) return false
        }
        log.warn("Take ended while starting: $reason")
        announceError(TakeNotices.line(reason))
        return true
    }

    /** Tears the take down as a failure; says [line] when there is one. Teardown never depends on copy. */
    private fun announceError(line: String?) {
        cancelOpenPolishRequest()
        surface.showProcessing()
        insertion.releasePinnedTarget()
        host.updateSurfacePhase(DictationSurfaceState.Phase.IDLE)
        host.vibrate(HapticCue.FAILURE)
        if (line != null) host.postToMain { host.toastFromService(line) }
        pipeline.stopAudioService()
        finishSession()
    }

    private fun handleServiceFailure(reason: TerminalReason) {
        if (state.get() == SessionState.RECORDING) discardDraft()
        showError(reason)
    }

    private fun finishSession() {
        if (state.getAndSet(SessionState.FINISHING) == SessionState.FINISHING) return
        cancelOpenPolishRequest()
        surface.showProcessing()
        host.updateSurfacePhase(DictationSurfaceState.Phase.IDLE)
        val historyUpdates = synchronized(pendingHistoryUpdates) { pendingHistoryUpdates.toList() }
        scope.launch {
            historyUpdates.joinAll()
            host.postToMain {
                cancelOpenPolishRequest()
                pipeline.unbind()
                host.removeForegroundAndDismiss()
                // IDLE is published at the last moment this instance can still refuse a start: the
                // next command creates a fresh instance whose state begins IDLE.
                admittedRequest = null
                stopAfterRecording = false
                surface.hide()
                host.stopSelfNow()
            }
        }
    }

    private fun stopIfIdle() {
        if (state.get() == SessionState.IDLE) host.stopSelfNow()
    }

    private fun deleteCapturedAudio(path: String?) {
        if (path.isNullOrBlank()) return
        runCatching {
            val file = File(path)
            if (file.exists() && !file.delete()) {
                log.warn("Unable to delete captured audio after terminal processing")
            }
        }.onFailure { error -> log.warn("Unable to delete captured audio: ${error.message}") }
    }

    private fun updateDraftStatus(status: String, interrupted: Boolean = false, insertionResult: String? = null) {
        val update = scope.launch(start = CoroutineStart.LAZY) {
            setDraftStatus(status, interrupted, insertionResult)
        }
        pendingHistoryUpdates += update
        update.start()
    }

    private suspend fun setDraftStatus(status: String, interrupted: Boolean = false, insertionResult: String? = null) {
        val id = draftId.get().takeIf { it > 0L } ?: runCatching { draftCreation?.await() ?: 0L }.getOrDefault(0L)
        if (id > 0L) transcripts.updateStatus(id, status, interrupted, insertionResult)
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
     * row: this owner's own `destroy` teardown, and `TranscriptDao.recoverStaleDrafts` on the
     * next start. Both run when the app was killed with a dictation live, so nobody told the user
     * anything and the row is the only signal that words were lost. That is why the prune leaves
     * `interrupted` rows alone.
     *
     * The id is cleared after the delete. That does not make a late write impossible — `setDraftStatus`
     * can still resolve the completed `draftCreation` to the old id — it makes one harmless: the
     * `UPDATE` matches zero rows and cannot bring the draft back.
     */
    private fun discardDraft() {
        val discard = scope.launch(start = CoroutineStart.LAZY) {
            val id = draftId.get().takeIf { it > 0L }
                ?: runCatching { draftCreation?.await() ?: 0L }.getOrDefault(0L)
            if (id > 0L) {
                transcripts.discard(id)
                draftId.set(0L)
            }
        }
        pendingHistoryUpdates += discard
        discard.start()
    }

    /**
     * The Service's `onDestroy` body, in the order it always ran: invalidate under [publishLock], close
     * the ledger, resolve the interrupted row while its creation job is alive, cancel and join the
     * session job, write `interrupted`, then clean up capture on its own thread or unbind here. The
     * Service stops foreground and dismisses the notification after this returns. The `runBlocking`
     * calls are a known conflict with `kotlin-patterns.md` RULE: never-block-a-binder-or-ui-thread,
     * carried unchanged; #115 owns their removal.
     */
    fun destroy() {
        // Invalidate a live wait or a running take BEFORE any blocking cleanup, under the same lock the
        // waiter publishes under: after this, no pill can appear for a take being torn down.
        val destroyedState = synchronized(publishLock) {
            val seen = state.get()
            if (seen == SessionState.STARTING || seen == SessionState.RECORDING) state.set(SessionState.ERROR)
            surface.hide()
            // The synchronous lifecycle decision (G2 D2): `interrupted` is committed only when nothing
            // was, and it revokes an outstanding publication or cancel reservation so the displaced
            // worker can no longer commit, announce or hand off. Nothing here waits on storage.
            when (seen) {
                SessionState.STARTING -> arbiter.interrupt(TerminalReason.INTERRUPTED_STARTING)
                SessionState.RECORDING -> arbiter.interrupt(TerminalReason.INTERRUPTED_RECORDING)
                SessionState.PROCESSING -> arbiter.interrupt(TerminalReason.INTERRUPTED_PROCESSING)
                SessionState.CANCELLING -> arbiter.interrupt(TerminalReason.INTERRUPTED_CANCELLING)
                SessionState.IDLE, SessionState.FINISHING, SessionState.ERROR -> false
            }
            seen
        }
        cancelOpenPolishRequest()
        val sessionWasOpen = destroyedState == SessionState.STARTING ||
            destroyedState == SessionState.RECORDING ||
            destroyedState == SessionState.PROCESSING ||
            destroyedState == SessionState.CANCELLING
        val interruptedDraftId = if (sessionWasOpen) {
            runCatching {
                runBlocking(Dispatchers.IO) {
                    draftId.get().takeIf { it > 0L }
                        ?: draftCreation?.await()?.takeIf { it > 0L }
                        ?: 0L
                }
            }.onFailure { error ->
                log.warn("Unable to resolve interrupted history row during teardown: ${error.message}")
            }.getOrDefault(0L)
        } else {
            0L
        }
        // Stop any in-flight finalization before writing the terminal teardown state.
        // This keeps a late polish callback from changing an interrupted row back to ready.
        runBlocking(Dispatchers.IO) {
            serviceJob.cancel()
            serviceJob.join()
        }
        if (interruptedDraftId > 0L) {
            runCatching {
                runBlocking(Dispatchers.IO) {
                    transcripts.updateStatus(
                        interruptedDraftId,
                        TranscriptEntity.STATUS_INTERRUPTED,
                        interrupted = true,
                        insertionResult = "not_attempted",
                    )
                }
            }.onFailure { error ->
                log.warn("Unable to mark interrupted session during teardown: ${error.message}")
            }
        }
        val captureRunning = destroyedState == SessionState.RECORDING || destroyedState == SessionState.STARTING
        if (captureRunning && teardownStarted.compareAndSet(false, true)) {
            // Captures the link and the controller only: the Service is dead once `onDestroy` returns,
            // and this thread outlives it (`PipelineBindings` binds through the application context).
            val capture = pipeline.capture
            val pipeline = pipeline
            Thread({
                runCatching { capture?.stopCapture() }
                val ready = runCatching { capture?.waitForFileReady(2_000L) == true }.getOrDefault(false)
                if (ready) deleteCapturedAudio(runCatching { capture?.audioFilePath() }.getOrNull())
                pipeline.stopAudioService()
                pipeline.postUnbindToMain()
            }, "DestroyedSessionCleanup").start()
        } else {
            if (sessionWasOpen) pipeline.stopAudioService()
            pipeline.unbind()
        }
    }
}

/**
 * The arbiter's sink: the one place a committed ending becomes telemetry (issue #176). A breadcrumb
 * always; a Sentry defect only when the channel table says the cause is ours; the journal commit,
 * which captures the `dictation.terminal` row after its own Room transaction; then the take leaves
 * the error scope. Every call is a limb that returns at once.
 */
internal fun recordTakeEnding(takeFacts: TakeFacts, reason: TerminalReason) {
    DebugSessionLog.log("Take terminal: ${reason.name} (${reason.result.wire})")
    Telemetry.breadcrumb("take", "terminal", mapOf("take_id" to takeFacts.takeId, "reason" to reason.name, "result" to reason.result.wire))
    TelemetryChannels.defectOf(reason, takeFacts.asrFailure)?.let { defect ->
        Telemetry.defect(defect, mapOf("take_id" to takeFacts.takeId, "reason" to reason.name, "asr_failure_reason" to takeFacts.asrFailure?.name))
    }
    Telemetry.journal?.terminal(takeFacts.terminal(reason))
    Telemetry.takeEnded(takeFacts.takeId)
}
