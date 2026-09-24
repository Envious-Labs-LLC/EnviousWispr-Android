package com.envi.wispr.ui

import com.envi.wispr.asr.AsrFailureReason
import com.envi.wispr.audio.AudioCaptureService
import com.envi.wispr.audio.CaptureEnding
import com.envi.wispr.audio.InputRouteKind
import com.envi.wispr.audio.InputRouteReason
import com.envi.wispr.audio.PcmAudio
import com.envi.wispr.audio.RecordingLimits
import com.envi.wispr.audio.SpeechEvidence
import com.envi.wispr.cleanup.LanguageDetector
import com.envi.wispr.history.HistoryWriteQueue
import com.envi.wispr.history.TranscriptEntity
import com.envi.wispr.history.TranscriptRepository
import com.envi.wispr.paste.DictationTargetPin
import com.envi.wispr.polish.PolishContext
import com.envi.wispr.polish.PolishEngineLabels
import com.envi.wispr.polish.PolishPolicy
import com.envi.wispr.polish.PolishPublicationFacts
import com.envi.wispr.polish.PolishReason
import com.envi.wispr.providers.PolicyRead
import com.envi.wispr.providers.ProviderConfigurationRepository
import com.envi.wispr.shortcuts.BubbleRequestLedger
import com.envi.wispr.shortcuts.BubbleRequestToken
import com.envi.wispr.shortcuts.BubbleRequests
import com.envi.wispr.shortcuts.DictationSurfaceState
import com.envi.wispr.telemetry.AnalyticsEvent
import com.envi.wispr.telemetry.AppDefect
import com.envi.wispr.telemetry.TakeFacts
import com.envi.wispr.telemetry.TakeStage
import com.envi.wispr.telemetry.Telemetry
import com.envi.wispr.telemetry.TelemetryChannels
import com.envi.wispr.vocabulary.CustomTerm
import com.envi.wispr.vocabulary.StructuredTermRestorer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * The one owner of a dictation take (#186): command admission, its state machine, its arbiter, every
 * transition, the speech and polish callbacks, the publication decision, the cancel paths and teardown.
 * It delegates downward (#216): [CaptureSessionController] talks to the capture process and reports
 * [CaptureEvent]s, [SessionFinalizer] writes the History row and delivers the words once this owner has
 * committed; neither can see the state or decide an ending. The take itself is one [TakeContext]. Reached
 * only through seams ([SessionHost], [RecorderSurface], [InsertionGateway], [SessionLog],
 * [PipelineController]) so a JVM test runs it without a `Service`.
 *
 * The Service is the Android adapter: it builds this owner in `onCreate`, forwards commands, implements the
 * host and calls `destroy` in `onDestroy`. Existing take behaviour keeps its call order across the owner and
 * its collaborators; #216 adds the event handler and the read-only phase view. The Service still stops
 * itself after every take, so a fresh owner begins IDLE with the next command; nothing here survives a take
 * on purpose.
 */
internal class DictationSessionCoordinator(
    private val host: SessionHost,
    private val surface: RecorderSurface,
    /** Where a recorder notice is said: the pill or a toast (#256). The owner only picks which notice. */
    private val notices: SessionNoticePresenter,
    private val insertion: InsertionGateway,
    private val log: SessionLog,
    private val preferences: SessionPreferencesSource,
    /** Every per-take History write goes through here, in enqueue order, on the application's worker (#115). */
    private val historyWrites: HistoryWriteQueue,
    /** For the start-up recovery ONLY, on the session scope: a stalled recovery must not sit ahead of a take's writes on the queue. */
    private val transcripts: TranscriptRepository,
    private val languageDetector: LanguageDetector,
    private val loadPolicy: suspend () -> PolicyRead,
    private val pipeline: PipelineController,
    /** The one session scope; the Service's `SupervisorJob() + Dispatchers.IO`. Its job is cancelled on destroy, never joined (#115). */
    private val scope: CoroutineScope,
    /** `Dispatchers.Main.immediate` in production; a JVM test passes its single owner-thread dispatcher. */
    private val mainDispatcher: CoroutineDispatcher,
    private val polishTimeout: PolishTimeout = DelayPolishTimeout,
    /** How long a take waits for the settings readers to answer before starting on the last values; a test shortens it (#193). */
    private val answerBoundMs: Long = SETTINGS_ANSWER_BOUND_MS,
    /**
     * How long the words may wait for their History save (#235). A healthy save is milliseconds; past this
     * the words go to the clipboard and the save only reconciles its row. A product ceiling, not a Room time.
     */
    private val historySaveBoundMs: Long = HISTORY_SAVE_BOUND_MS,
    /** Process-scoped on purpose: the tip's once-per-process allowance outlives the Service instance. */
    private val tipGate: BluetoothTipGate = BluetoothTipGate.PROCESS,
    /** The one first-wins gate on the polish answer; production mints ids off the device clock, a test off the JVM's. */
    private val polishLedger: PolishRequestLedger = PolishRequestLedger(),
    /** The arbiter's sink: where a committed ending goes. Production records it to telemetry. */
    private val endingSink: (TakeFacts, TerminalReason) -> Unit = ::recordTakeEnding,
    /** Where the owner's defects go. Production sends them to telemetry; a test counts them (#214). */
    private val defectSink: (AppDefect, Map<String, Any?>) -> Unit = Telemetry::defect,
    /** Queues the take's journal admission (#176); a test controls its completion (#258). */
    private val admitTake: (String, TriggerSource) -> Deferred<Boolean>? = { takeId, trigger -> Telemetry.journal?.admit(takeId, trigger) },
    /**
     * Where a take's captured-audio delete runs (#253): a process-owned worker, never the session scope, so a
     * Service teardown right after a take's ending cannot cancel the delete. A test holds it.
     */
    private val audioCleanup: (Runnable) -> Unit = CapturedAudioCleanup::execute,
) : PipelineController.Listener {
    companion object {
        /** The words' longest wait for their History save (#235). */
        const val HISTORY_SAVE_BOUND_MS = 1_000L

        /** How long a take waits for its journal admission before starting anyway (a limb, never a gate). */
        const val JOURNAL_ADMISSION_DEADLINE_MS = 300L

        /**
         * How long a take waits for the two settings readers to ANSWER (#193). Not a gate: a reader still
         * silent at the deadline is a failed read for this take and the take starts on the last values.
         * The wait exists only so an ordinary cold start runs on the user's real values, which DataStore
         * and Room deliver in milliseconds; a hung store cannot hold the microphone longer than this.
         */
        const val SETTINGS_ANSWER_BOUND_MS = 2_000L

    }

    private enum class SessionState { IDLE, STARTING, RECORDING, PROCESSING, CANCELLING, FINISHING, ERROR }

    private val state = AtomicReference(SessionState.IDLE)

    /**
     * The admitted take (#216): its id, facts, arbiter (the one referee of how it ends, issue #176), the
     * field it aims at and its History row. [TakeContext.NONE] before admission, whose arbiter refuses
     * everything. Published once, in `beginSession`.
     */
    @Volatile private var take: TakeContext = TakeContext.NONE

    /** The take's peak loudness, read ONCE at stop like the device label; null when it could not be read. */
    @Volatile private var takePeakAmplitude: Float? = null

    /** The surface named on the last START or TOGGLE command; consumed at admission. */
    @Volatile private var pendingTrigger = TriggerSource.UNKNOWN

    /** Names for the two reservations whose outcome is unknown at the claim. */
    private object Claimants {
        const val PUBLICATION = "publication"
        const val CANCEL = "cancel"
    }

    /**
     * Serialises the final state check, the ledger open and the watchdog launch against `cancelProcessing`
     * (#75): without it the transcription thread can read PROCESSING, lose the CPU to a cancel that closes an
     * empty ledger, and then send a request nothing will ever cancel. Shared with [polish] (#237); no binder
     * call is ever made under it.
     */
    private val polishSubmissionLock = Any()
    private val teardownStarted = AtomicBoolean(false)
    /** The injected scope's job, read once; `destroy` cancels exactly this and never joins it (#115). */
    private val serviceJob: Job = requireNotNull(scope.coroutineContext[Job]) { "the session scope needs a Job" }

    /** The take's raw words, written under [polishSubmissionLock] as soon as speech answers. */
    private var rawTranscript = ""

    /**
     * This take's polish (#237): built at admission beside [take], published after it, null before. An owner
     * admits one take, so it is never replaced.
     */
    @Volatile private var polish: TakePolishController? = null

    /** The bubble request this take was admitted for, or null for a take started elsewhere. */
    @Volatile private var admittedRequest: BubbleRequestToken? = null

    /**
     * A release for this take arrived before capture was running. Consumed at the RECORDING
     * transition: the take stops the moment it can, instead of cancelling as an unmarked STOP would
     * from STARTING (issue #135 plan §3 "Stop and cancel").
     */
    @Volatile private var stopAfterRecording = false
    @Volatile private var recordingDurationMs = 0L
    /** Set by [destroy]; the owner's surface is never touched from the application queue after it. */
    private val destroyed = AtomicBoolean(false)
    private var lastElapsedSecond = -1
    /**
     * The take's cancel, reserved under [publishLock] and committed when the capture process publishes
     * the ending (#115): a cancel no longer waits for the file itself.
     */
    @Volatile private var pendingCancel: TakeArbiter.Token? = null
    @Volatile private var pendingCancelReason: TerminalReason = TerminalReason.CANCELLED_RECORDING
    /** The capture process's side of the take: the lane, the listener and both timers (#216). */
    private val capture = CaptureSessionController(host, surface, log, pipeline, ::onCaptureEvent)

    /** The History row and the delivery, after this owner has decided (#216). */
    private val finalizer = SessionFinalizer(host, insertion, log, historyWrites)

    /** What the start's lane may read of this owner: two answers, never the state (#216). */
    private val phaseView = object : TakePhaseView {
        override fun isStarting(takeId: String): Boolean =
            state.get() == SessionState.STARTING && take.takeId == takeId

        override fun hasEnded(takeId: String): Boolean =
            take.takeId != takeId || state.get().let { it == SessionState.IDLE || it == SessionState.FINISHING || it == SessionState.ERROR }
    }

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
     * What captured the take, carried on the ending the capture process publishes once its thread has
     * exited and the record is complete (#115). Empty means unknown and is stored as such (#26).
     */
    @Volatile private var captureDeviceLabel = ""
    /** One warning per take, latched so the last minute is not announced ten times a second. */
    @Volatile private var durationWarningShown = false

    /** True while a take is in PROCESSING; the Service picks the processing notification on a foreground command. */
    val isProcessing: Boolean get() = state.get() == SessionState.PROCESSING

    /** The Service's `onCreate` work that is the session's: stale-row recovery and the preference collectors. */
    fun onCreated() {
        // On its own scope, never on the per-take queue (#115 review): a recovery stalled on the disk
        // would otherwise sit ahead of every write of the take that follows. It closes rows an EARLIER
        // process left open; the new take's rows are not among them.
        scope.launch {
            runCatching { transcripts.recoverStaleOpenRows(System.currentTimeMillis()) }
                .onSuccess { recovered ->
                    Telemetry.insertionsRecovered(recovered.readyRowIds)
                    Telemetry.deliveryUnknownRecovered(recovered.unknownCount)
                }
                .onFailure { error ->
                    // A command that finds the owner IDLE stops the Service within milliseconds of this
                    // launch (`stopIfIdle`); that cancellation is the ordinary case, not a failure, and the
                    // next instance runs the recovery again (measured on the emulator 2026-09-21).
                    if (error !is kotlinx.coroutines.CancellationException) log.warn("Unable to recover stale history: ${error.javaClass.simpleName}")
                }
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
                SessionState.IDLE, SessionState.CANCELLING, SessionState.FINISHING, SessionState.ERROR -> stopIfIdle()
            }
            DictationSessionService.ACTION_STOP -> when (state.get()) {
                SessionState.STARTING -> cancelStarting()
                SessionState.RECORDING -> stopAndTranscribe()
                SessionState.IDLE, SessionState.PROCESSING, SessionState.CANCELLING, SessionState.FINISHING, SessionState.ERROR -> stopIfIdle()
            }
            DictationSessionService.ACTION_TOGGLE -> when (state.get()) {
                SessionState.IDLE -> beginSession()
                SessionState.STARTING -> cancelStarting()
                SessionState.RECORDING -> stopAndTranscribe()
                SessionState.PROCESSING, SessionState.CANCELLING, SessionState.FINISHING, SessionState.ERROR -> Unit
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
        // The pre-capture chain's origin (#258): the accepted start command, right after IDLE -> STARTING.
        val acceptedAtMs = host.elapsedRealtimeMs()
        val takeId = UUID.randomUUID().toString().lowercase()
        takePeakAmplitude = null
        val trigger = admittedRequest?.let(::bubbleTrigger) ?: pendingTrigger
        pendingTrigger = TriggerSource.UNKNOWN
        val takeFacts = TakeFacts(takeId, trigger)
        fun sinceAccepted() = host.elapsedRealtimeMs() - acceptedAtMs
        // The referee for THIS take, in memory, before anything else (G2 D2). Its sink is a limb: it
        // hands the committed reason to telemetry and never waits on storage or the network.
        val arbiter = TakeArbiter { reason -> endingSink(takeFacts, reason) }
        Telemetry.takeStarted(takeId)
        Telemetry.breadcrumb("take", "admitted", mapOf("take_id" to takeId, "trigger_source" to trigger.wire))
        // Queued HERE, on the main thread, before any command can end this take: the journal applies
        // writes in arrival order, so a cancel that lands during the settings wait can never queue its
        // ending ahead of the admission and leave an open row (code review round 1, F2). The wait for
        // it happens below, before capture starts, under a deadline that never gates the take.
        val admission = admitTake(takeId, trigger)
        // Where the admission is observed to have landed, not where the wait below returns (#258).
        // An optional measurement: nothing in this handler may throw into the journal writer.
        admission?.invokeOnCompletion { cause ->
            if (cause == null) runCatching {
                if (admission.getCompleted()) takeFacts.admissionObservedMs = sinceAccepted()
            }
        }
        surface.showStarting(admittedRequest)
        host.promoteToForeground(processing = false)
        // Kept for the whole session. Android may rebind the accessibility service while the user
        // is still speaking, so the state insertion finds minutes later cannot say whether this
        // dictation ever had a field to aim at (`InsertionJudgement.handoffToJudge`).
        val targetPin = insertion.pinTargetForDictation()
        // Name the field this take aims at, for a reader that only wants takes aimed at ITS field.
        surface.nameTarget(if (targetPin == DictationTargetPin.PINNED) insertion.pinnedFieldId() else null)
        // The take, published once (#216). `beginSession` is one uninterrupted main-thread call, and nothing
        // is bound and no coroutine launched before this line, so nothing reads half of one take.
        take = TakeContext(takeId, trigger, takeFacts, arbiter, targetPin, TakeHistory(historyWrites), acceptedAtMs)
        // After the take, in the same main-thread call, and before anything is bound (#237).
        polish = TakePolishController(
            lock = polishSubmissionLock,
            ledger = polishLedger,
            timeout = polishTimeout,
            scope = scope,
            link = { pipeline.polish },
            languageDetector = languageDetector,
            log = log,
            takeId = takeId,
            defectSink = ::reportDefect,
            preferences = { sessionPreferences },
            transcript = { rawTranscript },
            isProcessing = { state.get() == SessionState.PROCESSING && take.arbiter.isOpen },
            isLive = ::polishStillWanted,
            onPrepared = ::publishPrepared,
            mainDispatcher = mainDispatcher,
            stillPublishable = { !destroyed.get() && take.arbiter.isOpen },
        )
        capture.begin(takeId, phaseView)
        teardownStarted.set(false)
        recordingDurationMs = 0L
        lastElapsedSecond = -1
        pendingCancel = null
        scope.launch {
            // The readers are limbs (#193): a failed or silent read never ends the take. The start carries
            // both outcomes and the values that came with them, taken by one atomic read each, and the
            // take is built from it alone; nothing below rereads the live source after suspending.
            val start = preferences.awaitAnswers(answerBoundMs)
            takeFacts.settingsAnswerMs = sinceAccepted()
            start.fallbackToken()?.let { token ->
                takeFacts.settingsFallback = token
                log.warn("Settings reader fell back; the take runs on the last values: $token")
                Telemetry.breadcrumb("take", "settings_fallback", mapOf("take_id" to takeId, "settings_fallback" to token))
            }
            takeFacts.inputDevice = TakeFacts.inputDeviceToken(start.settings.inputDevicePick)
            val termsSnapshot: List<CustomTerm> = start.terms.structuredTerms
            val matcher = withContext(Dispatchers.Default) {
                StructuredTermRestorer.compile(termsSnapshot)
            }
            takeFacts.matcherReadyMs = sinceAccepted()
            val policy = takePolicy(withContext(Dispatchers.IO) { loadPolicy() })
            takeFacts.policyLoadedMs = sinceAccepted()
            // Admission is written before capture starts, under a deadline that never gates the take:
            // the queued write still lands in order if this stops waiting (issue #176, plan §3.3).
            if (admission != null && withTimeoutOrNull(JOURNAL_ADMISSION_DEADLINE_MS) { admission.await() } == null) {
                log.warn("Journal admission did not land within $JOURNAL_ADMISSION_DEADLINE_MS ms; starting anyway")
            }
            withContext(mainDispatcher) {
                if (state.get() != SessionState.STARTING) return@withContext
                sessionPreferences = preferences.freeze(start, matcher, policy)
                takeFacts.bindRequestedMs = sinceAccepted()
                bindPipelineServices()
            }
        }
    }

    /**
     * The policy this take runs on (#278). A store that cannot be read is never the user's Off: the take
     * runs on the last policy this process read, or, with none, on the shipped default with polish lost, so
     * it publishes the deterministic text with the polish notice. Either way the controller raises one defect.
     */
    private fun takePolicy(read: PolicyRead): PolishPolicy = when (read) {
        is PolicyRead.Fresh -> read.policy
        is PolicyRead.Failed -> {
            val lastRead = read.lastRead
            log.warn(if (lastRead != null) "Polish policy unreadable; this take runs on the last read policy" else "Polish policy unreadable and never read; this take publishes the deterministic text")
            polish?.policyReadFailed(usedLastRead = lastRead != null)
            lastRead ?: ProviderConfigurationRepository.DECLARED_DEFAULT_POLICY
        }
    }

    private fun bindPipelineServices() {
        val outcome = pipeline.bind(this)
        when (outcome.result) {
            PipelineController.BindResult.BOUND -> if (!outcome.polishBound) {
                // Polish is a limb (#234): the take records and transcribes, and publishes the deterministic
                // text with the polish notice. The refusal is a defect, so it gets fixed rather than counted.
                log.warn("Polish service did not bind; this take publishes the deterministic text")
                polish?.bindRefused()
            }
            PipelineController.BindResult.AUDIO_BIND_FAILED -> {
                pipeline.stopAudioService()
                showError(TerminalReason.AUDIO_BIND_FAILED)
            }
            PipelineController.BindResult.ASR_BIND_FAILED -> handleServiceFailure(TerminalReason.ASR_BIND_FAILED)
        }
    }

    /** Not destroyed and still live: the polish warm-up is still worth sending (#236). */
    /**
     * Every defect the owner raises goes through here (#252): a sink that throws is logged and never stops the
     * publication or delivery that follows the report.
     */
    private fun reportDefect(defect: AppDefect, data: Map<String, Any?>) {
        try {
            defectSink(defect, data)
        } catch (error: Exception) {
            log.warn("Defect ${defect.semanticId} not reported: ${error.javaClass.simpleName}")
        }
    }

    private fun polishStillWanted(): Boolean {
        val seen = state.get()
        return !destroyed.get() &&
            (seen == SessionState.STARTING || seen == SessionState.RECORDING || seen == SessionState.PROCESSING)
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
            // Read under the lock the speech answer is written under (#234), so an answer already handed off
            // is never seen as blank here.
            val text = synchronized(polishSubmissionLock) { rawTranscript }
            if (text.isNotBlank()) {
                // The text is here; polish may still answer it. One decision picks the winner (#234).
                polish?.claimSpeechLossFallback(text)
            } else if (take.arbiter.commitNow(TerminalReason.ASR_PROCESS_DIED)) {
                take.history.markStatus(TranscriptEntity.STATUS_ASR_ERROR, insertionResult = "asr_error")
                endAsFailure(TerminalReason.ASR_PROCESS_DIED)
            }
        }
    }

    override fun onPolishConnected() {
        polish?.connected(sessionPreferences.policy)
    }

    /**
     * Polish died (#234). It is a limb, so the take never ends for it; [TakePolishController.disconnected]
     * latches and reports the loss. A first report after the take is already ending (a teardown's own
     * callback) is ignored; a loss observed earlier stays recorded.
     */
    override fun onPolishDisconnected() {
        log.warn("Polish service disconnected")
        val seen = state.get()
        if (seen != SessionState.STARTING && seen != SessionState.RECORDING && seen != SessionState.PROCESSING) return
        // A take already destroyed or committed is past failing: a teardown's own callback reports nothing.
        if (destroyed.get() || take.arbiter.committed != null) return
        polish?.disconnected()
    }

    private fun tryStartRecording() {
        if (state.get() != SessionState.STARTING) return
        var captureStarted = false
        try {
            silenceNoticeShown = false
            durationWarningShown = false
            forcedNoticeShown = false
            captureDeviceLabel = ""
            // The take is STARTING until the capture process publishes live: the lips spin, no pill, no
            // timer, nothing written. The frozen snapshot, never the live source: a settings emission after
            // the take's answer belongs to the next take (#193).
            val preferences = sessionPreferences
            captureStarted = capture.start(preferences)
        } catch (error: Exception) {
            if (captureStarted) {
                capture.stop("stop after a failed start")
                pipeline.stopAudioService()
            }
            log.error("Failed to start recording", error)
            showError(TerminalReason.START_EXCEPTION)
        }
    }

    /**
     * Main thread. One fact from the capture side (#216), decided here: the controller reports, this owner
     * transitions. Exhaustive with no `else`, so a new kind of fact cannot fall through unhandled.
     */
    private fun onCaptureEvent(event: CaptureEvent) {
        when (event) {
            is CaptureEvent.Live -> publishLive(event.forced, event.routeKind, event.routeReason, event.liveAfterMs)
            is CaptureEvent.Tick -> onTakeTick(event.elapsedMs)
            is CaptureEvent.SilenceStatus -> publishSilenceNoticeIfNeeded(event.status)
            is CaptureEvent.Ended -> onTakeEnded(event.ending)
            CaptureEvent.Silent -> onCaptureSilent()
            CaptureEvent.LiveDeadlinePassed -> onLiveDeadline()
            is CaptureEvent.StartFailed ->
                if (event.processDied) handleServiceFailure(TerminalReason.AUDIO_PROCESS_DIED) else showError(TerminalReason.START_EXCEPTION)
        }
    }

    /**
     * Main thread. The capture process published nothing for the silence bound: frozen, wedged or gone
     * without its ServiceConnection noticing. The hang becomes a reported ending (#115): a take still
     * waiting for sound or recording ends as unresponsive; a stop or a cancel still waiting for the file
     * ends as the close that never came; a take past its ending has its words already and only the
     * binding is released. The capture process is never called again: `announceError` stops the service
     * by intent and `finishSession` unbinds, and a frozen process cannot run either, so the next take
     * waits for the OS or the user to kill it.
     */
    private fun onCaptureSilent() {
        when (state.get()) {
            SessionState.STARTING -> failWhileStarting(TerminalReason.AUDIO_PROCESS_UNRESPONSIVE)
            SessionState.RECORDING -> handleServiceFailure(TerminalReason.AUDIO_PROCESS_UNRESPONSIVE)
            SessionState.PROCESSING -> if (!capture.endingArrived) {
                take.history.discard()
                showError(TerminalReason.CAPTURE_CLOSE_UNSAFE)
            }
            SessionState.CANCELLING -> pendingCancel?.let { cancel ->
                pendingCancel = null
                if (take.arbiter.commit(cancel, TerminalReason.CAPTURE_CLOSE_UNSAFE_ON_CANCEL)) {
                    pipeline.stopAudioService()
                    take.history.discard()
                    endAsFailure(TerminalReason.CAPTURE_CLOSE_UNSAFE_ON_CANCEL)
                }
            }
            SessionState.IDLE, SessionState.FINISHING, SessionState.ERROR -> Unit
        }
    }

    /** Main thread. The STARTING bound passed with no live event. */
    private fun onLiveDeadline() {
        if (state.get() != SessionState.STARTING) return
        if (!failWhileStarting(TerminalReason.LIVE_WAIT_DEADLINE)) return
        log.error("The route never went live within ${CaptureSessionController.LIVE_WAIT_BOUND_MS} ms")
        capture.stop("stop at the live deadline")
    }

    /** Main thread. A heartbeat: the timer while RECORDING; liveness in every state. */
    private fun onTakeTick(elapsedMs: Long) {
        if (state.get() != SessionState.RECORDING) return
        val second = (elapsedMs / 1_000L).toInt().coerceAtLeast(0)
        if (second != lastElapsedSecond) {
            lastElapsedSecond = second
            surface.updateElapsed(second)
        }
        // Below the timer, and a limb: a failure here must not cost the take.
        runCatching { publishDurationWarningIfNeeded(elapsedMs) }
    }

    /**
     * Main thread. The take is over on the capture side and the file is closed (#115). Delivered once
     * (the controller claims the first ending): the capture process publishes one ending per take, and a
     * start refused as busy publishes for the refused start, which this take (still STARTING) reads as its
     * own ending before live.
     */
    private fun onTakeEnded(ending: TakeEnding) {
        val takeFacts = take.facts
        captureDeviceLabel = ending.effectiveInputDevice
        takePeakAmplitude = ending.takePeakAmplitude
        takeFacts.peakAmplitude = ending.takePeakAmplitude
        takeFacts.silenceStopStatus = runCatching { TakeFacts.silenceStatusToken(ending.silenceStatus) }.getOrNull()
        when (state.get()) {
            SessionState.STARTING -> {
                // Ended before live: the earbud deadline, a start refused before capture began (no
                // reason and no file: the one start failure with its own sentence is "nothing can record
                // at all", macOS copy), or a capture that started and ended before the route went live.
                val reason = when {
                    ending.startFailure == AudioCaptureService.START_FAILURE_EARBUDS -> TerminalReason.CAPTURE_START_EARBUDS_REFUSED
                    ending.terminalReason == AudioCaptureService.TERMINAL_REASON_NONE && ending.audioFilePath == null ->
                        TakeNotices.startFailureReason(ending.startFailure)
                    else -> TerminalReason.CAPTURE_ENDED_BEFORE_LIVE
                }
                if (!failWhileStarting(reason)) return
                log.warn("Capture ended while waiting for the route to go live (failure=${ending.startFailure})")
            }
            SessionState.RECORDING -> {
                // The ending as a fact for the take's row, stamped here at the ONE place it is
                // classified; a stop the owner itself requested never reaches this branch and is
                // stamped `manual` at the stop (issue #176).
                takeFacts.captureTerminal = TakeFacts.captureEndingToken(ending.terminalReason)
                // Exhaustive over CaptureEnding with no `else`, so a reason this build does not
                // know cannot fall through into an ordinary transcription.
                when (CaptureEnding.fromAidl(ending.terminalReason)) {
                    // StillRunning belongs HERE. Capture that stopped without publishing a
                    // reason has no successful ending to report, and the type says so:
                    // StillRunning.transcribes is false. Grouping it with the successes would
                    // send partial audio on as though it were a finished take.
                    CaptureEnding.Failure -> {
                        log.error("Audio capture ended without a successful reason")
                        take.history.discard()
                        showError(TerminalReason.CAPTURE_FAILED_MID_TAKE)
                    }

                    CaptureEnding.StillRunning -> {
                        log.error("Audio capture stopped without publishing a reason")
                        take.history.discard()
                        showError(TerminalReason.CAPTURE_STILL_RUNNING_AFTER_STOP)
                    }

                    // The words up to the cap are kept and transcribed. What the user needs
                    // to be told is why the recording ended without them asking, because a take
                    // that stops on its own with no sentence reads as a fault.
                    // Transcribe FIRST, then say why. The words are the thing that must
                    // survive; the sentence explaining the ending is a limb, and putting it
                    // ahead of the transition would let a failure in it cost the take.
                    CaptureEnding.MaxDuration -> {
                        if (enterProcessing()) {
                            continueAfterEnding(ending)
                            log.log("Take ended at the duration cap")
                            notices.say(SessionNotice.DURATION_REACHED)
                        }
                    }

                    CaptureEnding.Manual,
                    CaptureEnding.Silence -> if (enterProcessing()) continueAfterEnding(ending)
                }
            }
            SessionState.PROCESSING -> continueAfterEnding(ending)
            SessionState.CANCELLING -> pendingCancel?.let { cancel ->
                pendingCancel = null
                val cancelled = pendingCancelReason
                scope.launch {
                    if (!take.arbiter.commit(cancel, cancelled)) return@launch
                    take.history.discard()
                    deleteCapturedAudio(ending.audioFilePath)
                    capture.finishTakeOrStop()
                    finishSession()
                }
            }
            SessionState.IDLE, SessionState.FINISHING, SessionState.ERROR -> Unit
        }
    }

    /** The one STARTING→RECORDING publication: main thread, under [publishLock]. */
    private fun publishLive(forced: Boolean, routeKind: Int, routeReason: Int, liveAfterMs: Long) {
        check(host.onMainThread()) { "publishLive runs on the main thread" }
        synchronized(publishLock) {
            if (!state.compareAndSet(SessionState.STARTING, SessionState.RECORDING)) {
                capture.stop("stop a take that went live after its ending")
                return
            }
            capture.cancelLiveDeadline()
            val current = take
            val takeId = current.takeId
            val takeFacts = current.facts
            takeFacts.routeKind = runCatching { InputRouteKind.fromCode(routeKind) }.getOrNull()
            takeFacts.routeReason = runCatching { InputRouteReason.fromCode(routeReason) }.getOrNull()
            takeFacts.liveAfterMs = liveAfterMs
            takeFacts.liveReceivedMs = host.elapsedRealtimeMs() - current.acceptedAtMs
            takeFacts.liveState = if (forced) "forced" else "ready"
            Telemetry.journal?.advance(takeId, TakeStage.RECORDING)
            Telemetry.breadcrumb(
                "take", "live",
                mapOf("take_id" to takeId, "route_kind" to takeFacts.routeKind?.name?.lowercase(), "live_after_ms" to takeFacts.liveAfterMs, "live_state" to takeFacts.liveState),
            )
            // The FIRST queued write of the take (#115), on the application's worker (`TakeHistory`). The id
            // comes back through the deferred; the still-live owner attaches it to its surface from MAIN (a
            // dead surface is never called from the queue).
            val draft = current.history.insertDraft(takeFacts.takeId, System.currentTimeMillis())
            draft.invokeOnCompletion { cause ->
                if (cause != null) return@invokeOnCompletion
                val id = draft.getCompleted()
                // The row's identity goes out on the bridge so a reader judges THIS take's row, never a
                // row it guessed at by time or order (onboarding practice; Codex reviews 2 to 9).
                host.postToMain { if (!destroyed.get() && current.history.isCurrent(draft)) surface.attachTranscript(id) }
            }
            host.updateSurfacePhase(DictationSurfaceState.Phase.LISTENING)
            surface.show()
            host.vibrate(HapticCue.SESSION_TRANSITION)
            log.log("Recording started (live after $liveAfterMs ms, forced=$forced)")
            if (forced) {
                // Said first, so neither the tip nor a pick-missing line can take the slot from it.
                forcedNoticeShown = true
                notices.say(SessionNotice.EARBUDS_SILENT)
            }
            // Once, at live, from the pushed route kind (#115): the tip needs nothing more.
            publishMicrophoneNoticesIfNeeded(routeKind)
            // After show() stamped the take's serial, which the picture is judged against.
            capture.listenForPicture()
            if (stopAfterRecording) {
                // The bubble's hold was already released. Consumed here, at the one transition the
                // early release waits for, so a short hold keeps its words instead of losing them.
                stopAfterRecording = false
                log.log("Early release applied: stopping as soon as capture started")
                stopAndTranscribe()
            }
        }
    }

    /**
     * Tell the user once, and only when auto-stop never became available for a take they had it on for.
     *
     * Losing the detector after it was already working leaves a correct recording, and a message
     * several seconds into one is an interruption for nothing. The floating recorder only exists while
     * the accessibility service runs, so clipboard-only mode gets the same sentence as a toast instead.
     */
    private fun publishSilenceNoticeIfNeeded(status: Int) {
        if (!sessionPreferences.autoStopOnSilence || silenceNoticeShown) return
        if (state.get() != SessionState.RECORDING) return
        if (status != AudioCaptureService.SILENCE_STATUS_UNAVAILABLE) return
        silenceNoticeShown = true
        notices.say(SessionNotice.SILENCE_UNAVAILABLE)
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
    private fun publishMicrophoneNoticesIfNeeded(kind: Int) {
        if (silenceNoticeShown || forcedNoticeShown) return
        if (tipGate.shouldShow(kind, sessionPreferences.showBluetoothTips)) {
            log.log("Bluetooth tip shown")
            notices.say(SessionNotice.BLUETOOTH_TIP)
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
        notices.say(SessionNotice.DURATION_WARNING)
    }

    /**
     * RECORDING → PROCESSING, the user's stop. The capture process is told to stop; the transcription
     * continues in [continueAfterEnding] when it publishes the ending with the closed file (#115). If it
     * never does, the silence bound ends the take as the close that never came.
     */
    private fun stopAndTranscribe() {
        if (!enterProcessing()) return
        capture.stop("stop")
    }

    /** The one RECORDING → PROCESSING transition, under [publishLock] (Codex review 3, 2026-09-18). */
    private fun enterProcessing(): Boolean {
        // Under publishLock: live publishes RECORDING (pill, draft) under the same lock, so a stop that
        // follows its CAS cannot run ahead of its publication.
        synchronized(publishLock) {
            if (!state.compareAndSet(SessionState.RECORDING, SessionState.PROCESSING)) return false
            surface.showProcessing()
        }
        host.updateSurfacePhase(DictationSurfaceState.Phase.PROCESSING)
        host.promoteToForeground(processing = true)
        host.vibrate(HapticCue.SESSION_TRANSITION)
        log.log("Stopping recording and starting transcription")
        return true
    }

    /**
     * PROCESSING, with the capture process's ending in hand: the file is closed and every fact about the
     * take is on the event (#115). Runs on the owner's scope; nothing here asks the capture process.
     */
    private fun continueAfterEnding(ending: TakeEnding) {
        scope.launch {
            try {
                val audioFilePath = ending.audioFilePath
                // The duration is the audio's own length, read from the finished file NOW, before
                // transcription deletes it: the wall clock counted the wait for the earbuds.
                recordingDurationMs = runCatching {
                    audioFilePath?.let { (PcmAudio.durationSeconds(File(it).length()) * 1000f).toLong() }
                }.getOrNull()?.coerceAtLeast(0L) ?: 0L
                val current = take
                val takeId = current.takeId
                val takeFacts = current.facts
                takeFacts.recordingSeconds = recordingDurationMs / 1000.0
                // Stamped by the ending handler when capture ended on its own; otherwise this stop is the
                // owner's own request, which the capture process reports as a manual ending.
                if (takeFacts.captureTerminal == null) takeFacts.captureTerminal = TakeFacts.MANUAL_ENDING
                Telemetry.journal?.advance(takeId, TakeStage.PROCESSING)
                Telemetry.breadcrumb(
                    "take", "stopped",
                    mapOf("take_id" to takeId, "capture_terminal" to takeFacts.captureTerminal, "recording_s" to takeFacts.recordingSeconds, "silence_stop_status" to takeFacts.silenceStopStatus),
                )
                capture.finishTakeOrStop()

                // Queued behind the draft insert; the id is resolved on the worker (#115). Nothing here
                // waits on storage.
                current.history.markStatus(TranscriptEntity.STATUS_PROCESSING)
                if (audioFilePath.isNullOrBlank()) {
                    current.history.markStatus(TranscriptEntity.STATUS_ASR_ERROR, insertionResult = "asr_error")
                    showError(TerminalReason.AUDIO_FILE_MISSING)
                    return@launch
                }
                val speechService = pipeline.speech
                if (speechService == null) {
                    deleteCapturedAudio(audioFilePath)
                    current.history.markStatus(TranscriptEntity.STATUS_ASR_ERROR, insertionResult = "asr_error")
                    showError(TerminalReason.ASR_NOT_READY)
                    return@launch
                }
                log.mark("asr_request")
                val asrRequestedAtMs = host.elapsedRealtimeMs()
                speechService.transcribeFileForTake(audioFilePath, takeId, object : SpeechListener {
                    override fun onResult(text: String?) {
                        // Posted to main by the speech proxy (#253); the file delete runs on its own worker.
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
                        if (!current.arbiter.commitNow(TerminalReason.ASR_FAILED)) return
                        current.history.markStatus(TranscriptEntity.STATUS_ASR_ERROR, insertionResult = "asr_error")
                        endAsFailure(TerminalReason.ASR_FAILED)
                    }

                    override fun onFailure(reason: Int, detail: String?) {
                        deleteCapturedAudio(audioFilePath)
                        val failure = AsrFailureReason.fromCode(reason)
                        takeFacts.asrFailure = failure
                        takeFacts.asrMs = host.elapsedRealtimeMs() - asrRequestedAtMs
                        // Claim FIRST: a cancel that already owns the take must not see its History row
                        // rewritten or a failure toast over its acknowledgement (G1 D2).
                        if (!current.arbiter.commitNow(TerminalReason.ASR_FAILED)) return
                        current.history.markStatus(TranscriptEntity.STATUS_ASR_ERROR, insertionResult = "asr_error")
                        // The detail is local diagnostics and stops here: never a toast, never the wire.
                        log.error("ASR failed: ${failure.name} (code $reason) ${detail.orEmpty()}")
                        endAsFailure(TerminalReason.ASR_FAILED)
                    }
                })
            } catch (error: Exception) {
                pipeline.stopAudioService()
                deleteCapturedAudio(ending.audioFilePath)
                log.error("Transcription failed", error)
                take.history.markStatus(TranscriptEntity.STATUS_ASR_ERROR, insertionResult = "asr_error")
                showError(TerminalReason.ASR_CALLBACK_EXCEPTION)
            }
        }
    }

    private fun polishAndPublish(rawText: String) {
        synchronized(polishSubmissionLock) { rawTranscript = rawText }
        if (rawText.isBlank()) {
            // Committed before the draft is discarded (G2 D2). The peak read at stop decides which of the
            // three empty endings this is; no reading stays unmeasured, never "silence".
            if (!take.arbiter.commitNow(SpeechEvidence.emptyTranscriptReason(takePeakAmplitude))) return
            take.history.discard()
            insertion.releasePinnedTarget()
            finishSession()
            return
        }
        checkNotNull(polish).prepare(rawText, sessionPreferences)
    }

    /** The controller's text, published: the engine's answer, or the deterministic fallback under its label. */
    private fun publishPrepared(prepared: PreparedText) = when (prepared) {
        is PreparedText.Polished ->
            publishResult(prepared.text, prepared.engine, prepared.latencyMs, prepared.reason, prepared.statusCode, prepared.context)
        is PreparedText.Fallback ->
            publishResult(prepared.text, PolishEngineLabels.DETERMINISTIC, 0, prepared.reason, 0, prepared.context)
    }

    /**
     * The one place a polish outcome becomes a History row and, when it did not do its job, a sentence
     * (#77). Two routes reach it through [publishPrepared], an answer and a fallback; the facts are derived once
     * here. The History write is enqueued WITH the reservation (#115); the notice is posted before the
     * owner's continuation (the commit, the handoff) starts, so it precedes the delivery line when both
     * fire. The row and the delivery are [SessionFinalizer]'s; the reservation and the commits are this
     * owner's (#216).
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
        val current = take
        val takeId = current.takeId
        val takeFacts = current.facts
        takeFacts.polishProvider = polishContext.encode()
        takeFacts.polishReason = reason
        takeFacts.polishMs = latencyMs
        takeFacts.polishStatus = statusCode
        Telemetry.breadcrumb("take", "polish_done", mapOf("take_id" to takeId, "polish_reason" to reason.name, "polish_ms" to latencyMs, "polish_provider" to takeFacts.polishProvider))
        TelemetryChannels.defectOf(reason)?.let { reportDefect(it, mapOf("take_id" to takeId, "polish_status" to statusCode)) }
        // The immutable payload FIRST, so the reservation and its write can be one operation below.
        val payload = Publication(
            finalText = text.ifBlank { rawTranscript },
            engine = if (text.isBlank() && rawTranscript.isNotBlank()) PolishEngineLabels.RAW_FALLBACK else engine,
            originalText = rawTranscript,
            latencyMs = latencyMs,
            durationMs = recordingDurationMs,
            captureDevice = captureDeviceLabel,
            polishFacts = PolishPublicationFacts.from(reason, statusCode, polishContext),
        )
        val finalText = payload.finalText
        // RESERVE, never commit: `completed` is unknown until the History save returns (G2 D2). A cancel
        // that already owns the take, or a second final callback, loses here and does nothing. Under
        // publishLock, and the write is ENQUEUED in the same operation (#115): destroy takes the same lock
        // before enqueueing `interrupted`, so a reserved finalization is always queued ahead of it and
        // `interrupted` is the last word on the row.
        val saved = CompletableDeferred<SaveOutcome>()
        val publication = synchronized(publishLock) {
            val reserved = current.arbiter.reserve(Claimants.PUBLICATION) ?: return@synchronized null
            if (finalText.isNotBlank()) finalizer.enqueueSave(current.history, payload, saved)
            reserved
        }
        if (publication == null) {
            log.warn("Ignoring a final transcript that arrived after the take was claimed")
            return
        }
        log.log("Polish result received (${payload.engine}, ${latencyMs}ms, chars=${finalText.length})")
        if (finalText.isBlank()) {
            if (current.arbiter.commit(publication, TerminalReason.FINAL_TEXT_EMPTY)) finishSession()
            return
        }
        payload.polishFacts.notice?.let { notice ->
            log.log("Polish notice shown: ${payload.polishFacts.failure}")
            host.postToMain {
                host.toastFromService(notice.toastLine)
                host.showPolishNotice(notice)
            }
        }

        watchSave(saved, takeId)
        scope.launch {
            // The words never wait on History (#277): insertion is the heart, History a limb. The save's answer is
            // recorded if it is already in, else `pending`; the committed facts are never changed afterwards, and
            // a slow or failed save is reported by [watchSave] alone.
            takeFacts.historySave = when (saved.takeIf { it.isCompleted }?.getCompleted()) {
                is SaveOutcome.Saved -> "ok"
                is SaveOutcome.Failed -> "failed"
                null -> "pending"
            }
            // COMMIT BEFORE the insertion handoff: completed means the text finalised, never that insertion
            // succeeded. A revoked reservation (the owner was destroyed) stops here: no handoff, no announcement,
            // no terminal; the teardown's own History write is the last word on that row (G2 D2).
            if (!current.arbiter.commit(publication, TerminalReason.COMPLETED)) {
                log.warn("Publication revoked before the handoff; not inserting")
                return@launch
            }
            finalizer.deliver(takeId, current.targetPin, payload, current.history, sessionPreferences.clipboard)
            log.log(log.pipelineSummary())
            finishSession()
        }
    }

    /**
     * The History save's diagnostics (#277), independent of delivery: the bound starts when the save is enqueued;
     * a save still unanswered at it raises one `HistorySaveTimedOut`, and a failure, early or late, raises its
     * breadcrumb and defect once. Nothing here routes, inserts or changes the take's facts.
     */
    private fun watchSave(saved: CompletableDeferred<SaveOutcome>, takeId: String) {
        scope.launch {
            val answer = withTimeoutOrNull(historySaveBoundMs) { saved.await() } ?: run {
                log.warn("History save did not answer in $historySaveBoundMs ms; the words did not wait for it")
                reportDefect(AppDefect.HistorySaveTimedOut, mapOf("take_id" to takeId))
                saved.await()
            }
            if (answer is SaveOutcome.Failed) {
                val error = answer.cause
                log.warn("Unable to save transcript history: ${error.javaClass.simpleName}")
                // Storage being full or locked is the world (a breadcrumb); a constraint or an illegal statement is
                // our schema contract (a defect). The message never leaves either way.
                Telemetry.breadcrumb("take", "history_save_failed", mapOf("take_id" to takeId, "error_type" to error.javaClass.simpleName))
                TelemetryChannels.historySaveDefect(error)?.let { reportDefect(it, mapOf("take_id" to takeId)) }
            }
        }
    }

    private fun cancelRecording() {
        val cancel = synchronized(publishLock) {
            if (!state.compareAndSet(SessionState.RECORDING, SessionState.CANCELLING)) return
            surface.showProcessing()
            take.arbiter.reserve(Claimants.CANCEL)
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
        // A cancel before the capture process was even bound has nothing to stop: the quiet finish the
        // old cancelStarting always took. Committed BEFORE the draft goes (G2 D2).
        if (!capture.isBound) {
            scope.launch {
                if (!take.arbiter.commit(cancel, cancelled)) return@launch
                take.history.discard()
                finishSession()
            }
            return
        }
        // Otherwise the capture process is told to stop and the cancel is committed when it publishes the
        // ending with the closed file (`onTakeEnded`, #115); a close that never comes is ended by the
        // silence bound as CAPTURE_CLOSE_UNSAFE_ON_CANCEL, a failure and not a cancel, as before.
        if (capture.endingArrived) {
            // The ending already arrived (a take that ended on its own as the cancel landed): commit now.
            scope.launch {
                if (!take.arbiter.commit(cancel, cancelled)) return@launch
                take.history.discard()
                capture.finishTakeOrStop()
                finishSession()
            }
            return
        }
        pendingCancelReason = cancelled
        pendingCancel = cancel
        capture.stop("stop for a cancel")
    }

    /**
     * Cancel while the words are being transcribed or polished (#75). Claims publication first: if the
     * text is already on its way the cancel is too late and does nothing. Otherwise it takes the submission
     * lock so it either precedes the ledger open (no request is sent) or follows it (the open id is closed
     * and cancelled on the engine, and the submitter re-sends that cancel once the engine has registered).
     */
    private fun cancelProcessing() {
        var closed: Long? = null
        val cancel = synchronized(polishSubmissionLock) {
            // The reservation IS the publication check: a publication already holding the ending makes
            // this cancel too late, exactly as the old flag did, and a cancel that wins keeps a later
            // callback from ever publishing (G2 D2).
            if (state.get() != SessionState.PROCESSING) return
            val reserved = take.arbiter.reserve(Claimants.CANCEL) ?: return
            state.set(SessionState.CANCELLING)
            log.log("Cancelled while processing; open polish request: ${polish?.openRequest == true}")
            closed = polish?.closeOpen()
            reserved
        }
        // The engine hears the cancel after the lock is released (#237).
        closed?.let { requestId -> polish?.sendCancel(requestId) }
        surface.showProcessing()
        insertion.releasePinnedTarget()
        host.updateSurfacePhase(DictationSurfaceState.Phase.IDLE)
        host.vibrate(HapticCue.SESSION_CANCELED)
        // Committed before the draft goes (G2 D2); a destruction that revoked it owns the row instead.
        if (!take.arbiter.commit(cancel, TerminalReason.CANCELLED_PROCESSING)) return
        take.history.discard()
        finishSession()
    }

    private fun cancelStarting() {
        val cancel = synchronized(publishLock) {
            if (!state.compareAndSet(SessionState.STARTING, SessionState.CANCELLING)) return
            surface.showProcessing()
            take.arbiter.reserve(Claimants.CANCEL)
        } ?: return
        cancelCaptureAndFinish(cancel, TerminalReason.CANCELLED_STARTING)
    }

    /**
     * Ends the take as the failure [reason] when nothing else has claimed it: the arbiter is the guard,
     * so a failure observed after a cancel or a publication owns the take does nothing at all (the
     * old `ERROR` check let it announce over them). The sentence is [TakeNotices]'s, never the caller's.
     */
    private fun showError(reason: TerminalReason) {
        if (!take.arbiter.commitNow(reason)) {
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
            if (!take.arbiter.commitNow(reason)) return false
        }
        log.warn("Take ended while starting: $reason")
        announceError(TakeNotices.line(reason))
        return true
    }

    /** Tears the take down as a failure; says [line] when there is one. Teardown never depends on copy. */
    private fun announceError(line: String?) {
        polish?.cancelOpen()
        surface.showProcessing()
        insertion.releasePinnedTarget()
        host.updateSurfacePhase(DictationSurfaceState.Phase.IDLE)
        host.vibrate(HapticCue.FAILURE)
        if (line != null) host.postToMain { host.toastFromService(line) }
        pipeline.stopAudioService()
        finishSession()
    }

    private fun handleServiceFailure(reason: TerminalReason) {
        if (state.get() == SessionState.RECORDING) take.history.discard()
        showError(reason)
    }

    private fun finishSession() {
        if (state.getAndSet(SessionState.FINISHING) == SessionState.FINISHING) return
        polish?.cancelOpen()
        surface.showProcessing()
        host.updateSurfacePhase(DictationSurfaceState.Phase.IDLE)
        // The take's History writes are on the application's queue, in order; nothing here waits for
        // them (#115). The Service may stop while the last of them is still landing.
        scope.launch {
            host.postToMain {
                polish?.cancelOpen()
                // Both subscriptions go with the binding; nothing is called on the capture process here,
                // which may be the unresponsive process this take just ended over (#115).
                capture.disarm()
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

    /** Queues the delete on [audioCleanup] and returns: never on main's time, never cancelled by teardown (#253). */
    private fun deleteCapturedAudio(path: String?) {
        if (path.isNullOrBlank()) return
        audioCleanup(Runnable {
            runCatching {
                val file = File(path)
                if (file.exists() && !file.delete()) {
                    log.warn("Unable to delete captured audio after terminal processing")
                }
            }.onFailure { error -> log.warn("Unable to delete captured audio: ${error.javaClass.simpleName}") }
        })
    }

    /**
     * The Service's `onDestroy` body, on the main thread and never waiting on anything (#115): invalidate
     * and claim the arbiter under [publishLock], and in the SAME operation enqueue the `interrupted` write
     * when the interrupt won; disarm the bound; cancel the session job without joining it; cancel polish;
     * then clean up capture on its own thread or unbind here. The Service stops foreground and dismisses
     * the notification after this returns. A finalization reserved before this took the lock is already
     * queued ahead of `interrupted`, so `interrupted` is the last word on the row; one not yet reserved
     * loses the arbiter to this interrupt and never enqueues.
     */
    fun destroy() {
        destroyed.set(true)
        // Invalidate a live wait or a running take BEFORE any cleanup, under the same lock the live
        // transition publishes under: after this, no pill can appear for a take being torn down.
        val destroyedState = synchronized(publishLock) {
            val seen = state.get()
            if (seen == SessionState.STARTING || seen == SessionState.RECORDING) state.set(SessionState.ERROR)
            surface.hide()
            // The synchronous lifecycle decision (G2 D2): `interrupted` is committed only when nothing
            // was, and it revokes an outstanding publication or cancel reservation so the displaced
            // worker can no longer commit, announce or hand off. Nothing here waits on storage.
            val interrupted = when (seen) {
                SessionState.STARTING -> take.arbiter.interrupt(TerminalReason.INTERRUPTED_STARTING)
                SessionState.RECORDING -> take.arbiter.interrupt(TerminalReason.INTERRUPTED_RECORDING)
                SessionState.PROCESSING -> take.arbiter.interrupt(TerminalReason.INTERRUPTED_PROCESSING)
                SessionState.CANCELLING -> take.arbiter.interrupt(TerminalReason.INTERRUPTED_CANCELLING)
                SessionState.IDLE, SessionState.FINISHING, SessionState.ERROR -> false
            }
            if (interrupted) {
                // Only when the interrupt WON: every terminal commit site owns its own later status or
                // discard write, and a reserved finalization is already queued (plan §3 C1).
                take.history.markInterrupted()
            }
            seen
        }
        // Both delayed callbacks go now, on main, before any blocking cleanup: a bound firing into a
        // destroyed owner would act on a Service that is gone (#115 review round 1, F4).
        capture.disarm()
        polish?.cancelOpen()
        val sessionWasOpen = destroyedState == SessionState.STARTING ||
            destroyedState == SessionState.RECORDING ||
            destroyedState == SessionState.PROCESSING ||
            destroyedState == SessionState.CANCELLING
        // Cancelled, never joined (#115): a late polish callback cannot restore `ready` after
        // `interrupted` because the finalization it would write either lost the arbiter above or was
        // already queued ahead of the `interrupted` write.
        serviceJob.cancel()
        val captureRunning = destroyedState == SessionState.RECORDING || destroyedState == SessionState.STARTING
        if (captureRunning && teardownStarted.compareAndSet(false, true)) {
            // Down the lane, like every other call into the capture process (#115): the lane's daemon
            // thread outlives the Service, and the link it captured stays valid after the unbind below.
            // The file is the capture process's one cache file and the next take overwrites it; nothing
            // waits for it here.
            capture.stop("stop at destroy")
        }
        // The service is stopped by intent whether or not that stop is ever delivered (a wedged process
        // gets neither, and the OS or the user ends it); the bindings go now, on main, with nothing waited
        // for.
        if (sessionWasOpen) pipeline.stopAudioService()
        polish?.cancelOpen()
        pipeline.unbind()
        // No further command is accepted; the stop above, if queued, still runs on the lane's own thread.
        capture.shutdown()
    }
}

/**
 * The arbiter's sink: the one place a committed ending becomes telemetry (issue #176). A breadcrumb
 * always; a Sentry defect only when the channel table says the cause is ours; the journal commit,
 * which captures the `dictation.terminal` row after its own Room transaction; then the take leaves
 * the error scope. Every call is a limb that returns at once.
 */
internal fun recordTakeEnding(takeFacts: TakeFacts, reason: TerminalReason) {
    // The pre-capture chain (#258), in ms since the accepted start command, so a UAT reads it without PostHog.
    val start = with(takeFacts) { "settings=$settingsAnswerMs matcher=$matcherReadyMs policy=$policyLoadedMs admission=$admissionObservedMs bind=$bindRequestedMs live=$liveReceivedMs" }
    DebugSessionLog.log("Take terminal: ${reason.name} (${reason.result.wire}) start: $start")
    Telemetry.breadcrumb("take", "terminal", mapOf("take_id" to takeFacts.takeId, "reason" to reason.name, "result" to reason.result.wire))
    TelemetryChannels.defectOf(reason, takeFacts.asrFailure)?.let { defect ->
        Telemetry.defect(defect, mapOf("take_id" to takeFacts.takeId, "reason" to reason.name, "asr_failure_reason" to takeFacts.asrFailure?.name))
    }
    Telemetry.journal?.terminal(takeFacts.terminal(reason))
    Telemetry.takeEnded(takeFacts.takeId)
}
