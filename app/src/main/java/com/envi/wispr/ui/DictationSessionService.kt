package com.envi.wispr.ui

import android.app.Service
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.envi.wispr.asr.IAsrCallback
import com.envi.wispr.asr.IAsrService
import com.envi.wispr.audio.AudioCaptureService
import com.envi.wispr.audio.InputDevicePick
import com.envi.wispr.audio.CaptureEnding
import com.envi.wispr.audio.InputRouteKind
import com.envi.wispr.audio.InputRouteReason
import com.envi.wispr.audio.IAudioCaptureService
import com.envi.wispr.audio.LiveGate
import com.envi.wispr.audio.PcmAudio
import com.envi.wispr.audio.RecordingLimits
import com.envi.wispr.audio.SpeechEvidence
import com.envi.wispr.asr.AsrFailureReason
import com.envi.wispr.cleanup.CleanupOptions
import com.envi.wispr.cleanup.LanguageDetector
import com.envi.wispr.cleanup.TextSafety
import com.envi.wispr.debug.DebugLogger
import com.envi.wispr.history.EnviousWisprDatabase
import com.envi.wispr.history.HistoryPublicationPolicy
import com.envi.wispr.history.TranscriptEntity
import com.envi.wispr.history.TranscriptRepository
import com.envi.wispr.insertion.ClipboardInsertionPolicy
import com.envi.wispr.insertion.ClipboardOutcome
import com.envi.wispr.insertion.FallbackAnnouncement
import com.envi.wispr.insertion.InsertionResults
import com.envi.wispr.paste.AccessibilityPermission
import com.envi.wispr.paste.AutoPasteAvailability
import com.envi.wispr.paste.AutoPasteReadiness
import com.envi.wispr.paste.DictationTargetPin
import com.envi.wispr.paste.InsertionHandoff
import com.envi.wispr.paste.InsertionJudgement
import com.envi.wispr.paste.PasteAccessibilityService
import com.envi.wispr.polish.IPolishCallback
import com.envi.wispr.polish.IPolishService
import com.envi.wispr.polish.PolishContext
import com.envi.wispr.telemetry.AnalyticsEvent
import com.envi.wispr.telemetry.AppDefect
import com.envi.wispr.telemetry.InsertionResultKind
import com.envi.wispr.telemetry.InsertionRouteKind
import com.envi.wispr.telemetry.TakeFacts
import com.envi.wispr.telemetry.TakeStage
import com.envi.wispr.telemetry.Telemetry
import com.envi.wispr.telemetry.TelemetryChannels
import com.envi.wispr.polish.PolishEngineLabels
import com.envi.wispr.polish.PolishPublicationFacts
import com.envi.wispr.polish.MlKitLanguageDetector
import com.envi.wispr.polish.PolishFallback
import com.envi.wispr.polish.PolishOutcome
import com.envi.wispr.polish.PolishPolicy
import com.envi.wispr.polish.PolishReason
import com.envi.wispr.polish.PolishService
import com.envi.wispr.providers.ProviderConfigurationRepository
import com.envi.wispr.settings.AppPreferences
import com.envi.wispr.vad.SilenceStopDetector
import com.envi.wispr.settings.cleanupOptions
import com.envi.wispr.settings.clipboardInsertionPolicy
import com.envi.wispr.shortcuts.DictationNotificationController
import com.envi.wispr.shortcuts.DictationSurfaceState
import com.envi.wispr.shortcuts.BubbleRequestLedger
import com.envi.wispr.shortcuts.BubbleRequestToken
import com.envi.wispr.shortcuts.BubbleRequests
import com.envi.wispr.shortcuts.RecordingOverlayState
import com.envi.wispr.vocabulary.CustomTerm
import com.envi.wispr.vocabulary.CustomTermRepository
import com.envi.wispr.vocabulary.BuiltinVocabulary
import com.envi.wispr.vocabulary.StructuredTermRestorer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.delay
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

/** Owns a dictation session without placing an Activity above the user's typing app. */
class DictationSessionService : Service() {
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

        private const val TAG = "DictationSession"

        /** About thirty pictures a second: a syllable is about 100 ms and the analyser refreshes every 32 ms. */
        private const val METER_INTERVAL_MS = 33L
        const val ACTION_START = "com.envi.wispr.action.START_DICTATION"
        const val ACTION_TOGGLE = "com.envi.wispr.action.TOGGLE_DICTATION"
        const val ACTION_STOP = "com.envi.wispr.action.STOP_DICTATION"
        const val ACTION_CANCEL = "com.envi.wispr.action.CANCEL_DICTATION"
        private const val EXTRA_FOREGROUND_COMMAND = "foreground_command"

        /** The floating bubble's request token (`BubbleRequestToken.encode`), on START, STOP and CANCEL. */
        const val EXTRA_REQUEST = "bubble_request"

        /**
         * `TriggerSource.wire` on a START or TOGGLE: which surface asked (issue #176). A bubble request
         * carries its own answer in its token; every other surface names itself here or reads `unknown`.
         */
        const val EXTRA_TRIGGER_SOURCE = "trigger_source"

        /** How long a take waits for its journal admission before starting anyway (a limb, never a gate). */
        const val JOURNAL_ADMISSION_DEADLINE_MS = 300L

        fun sendCommand(context: Context, action: String, requestToken: String? = null, trigger: TriggerSource? = null) {
            val intent = Intent(context, DictationSessionService::class.java).setAction(action)
            if (requestToken != null) intent.putExtra(EXTRA_REQUEST, requestToken)
            if (trigger != null) intent.putExtra(EXTRA_TRIGGER_SOURCE, trigger.wire)
            if (action == ACTION_START || action == ACTION_TOGGLE) {
                intent.putExtra(EXTRA_FOREGROUND_COMMAND, true)
                ContextCompat.startForegroundService(context, intent)
            } else {
                context.startService(intent)
            }
        }
    }

    private enum class SessionState { IDLE, STARTING, RECORDING, PROCESSING, CANCELLING, FINISHING, ERROR }

    /** How often the live waiter asks the capture process. */
    private val LIVE_POLL_MS = 20L

    /**
     * The STARTING bound: two live deadlines (one reset) plus a second, after which a take that never
     * went live fails rather than spins.
     */
    private val LIVE_WAIT_BOUND_MS = 2 * LiveGate.DEADLINE_MS + 1_000L

    private data class SessionPreferences(
        val cleanup: CleanupOptions = CleanupOptions(),
        val terms: List<CustomTerm> = emptyList(),
        val matcher: StructuredTermRestorer.Matcher = StructuredTermRestorer.compile(emptyList()),
        val clipboard: ClipboardInsertionPolicy = ClipboardInsertionPolicy(),
        /** Latched once per session; a settings change applies from the next session (issue #69). */
        val policy: PolishPolicy = PolishPolicy.Off,
    )

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
     * Reads the dictation's language off the finished transcript for this side's deterministic fallback
     * (#107). Built in `onCreate`, not as a field initializer and NOT lazily, for the reason
     * `PolishService` gives: a field initializer has no context yet, and a `Lazy` reopens the
     * close-versus-first-use race above the detector's own lock. Constructing loads no model; the bundled
     * model is loaded on the first detection and released in `onDestroy`, which is what keeps this
     * long-running process free of a resident model between dictations.
     */
    private lateinit var languageDetector: MlKitLanguageDetector

    private val polishLedger = PolishRequestLedger()
    /**
     * Serialises the final state check, the ledger open, the watchdog launch and the binder call against
     * `cancelProcessing` (#75): without it the transcription thread can read PROCESSING, lose the CPU to a
     * cancel that closes an empty ledger, and then send a request nothing will ever cancel.
     */
    private val polishSubmissionLock = Any()
    private val teardownStarted = AtomicBoolean(false)
    private val draftId = AtomicLong(0L)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(serviceJob + Dispatchers.IO)
    private val pendingHistoryUpdates = java.util.Collections.synchronizedList(mutableListOf<Job>())

    private var audioService: IAudioCaptureService? = null
    private var asrService: IAsrService? = null
    private var polishService: IPolishService? = null
    private var audioBound = false
    private var asrBound = false
    private var polishBound = false
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
    @Volatile private var structuredTerms: List<CustomTerm> = emptyList()
    @Volatile private var cleanupOptions = CleanupOptions()
    /**
     * Null until `AppPreferences` delivers the user's real values, which on a cold start is AFTER
     * the listening notification is built. A `ClipboardInsertionPolicy()` stand-in here reads as a
     * decided answer and its auto-copy default is `true`, so the notification promised the
     * clipboard to a user who had turned auto-copy off and whose words went to History only
     * (`validation-discipline.md` FACT: silent-empty-traps, plausible-value traps).
     */
    @Volatile private var clipboardPolicy: ClipboardInsertionPolicy? = null
    @Volatile private var sessionPreferences = SessionPreferences()

    /**
     * Frozen at the moment a take starts, never read again during it. That is the same contract macOS
     * uses, and it is why changing the slider mid-dictation does not move the goalposts under you.
     *
     * Written in the same collector block as the cleanup options, BEFORE its readiness signal completes,
     * because `beginSession` awaits that signal before binding anything. Written anywhere else and a
     * user who had enabled auto-stop would silently get a manual take after every cold start.
     */
    @Volatile private var autoStopOnSilence = false
    @Volatile private var silencePauseSeconds = SilenceStopDetector.DEFAULT_PAUSE_SECONDS
    @Volatile private var silenceNoticeShown = false
    /** The stored pick, frozen per take like the silence setting; crosses the binder as a string. */
    @Volatile private var inputDevicePick = InputDevicePick.AUTO
    @Volatile private var showBluetoothTips = true
    /** The 30 s earbud hold, frozen per take and carried on the start call. */
    @Volatile private var keepEarbudsReady = true
    /** The take proceeded on earbuds that sent nothing; said once, before any other microphone line. */
    @Volatile private var forcedNoticeShown = false

    /**
     * Serialises the one STARTING→RECORDING publication (the CAS, the pill, the haptic, the surface) with
     * teardown's invalidation, so a live waiter that won its CAS cannot publish after `onDestroy` hid the
     * overlay, and teardown cannot interleave between the CAS and the publication.
     */
    private val publishLock = Any()
    /** Process-scoped on purpose: this service stops itself after every take. */
    private val bluetoothTipGate = BluetoothTipGate.PROCESS
    /**
     * What captured the take, read ONCE at stop after `waitForFileReady`, when the capture thread has
     * exited and the record is complete. Empty means unknown and is stored as such (#26).
     */
    @Volatile private var captureDeviceLabel = ""
    /** One warning per take, latched so the last minute is not announced ten times a second. */
    @Volatile private var durationWarningShown = false
    private val cleanupPreferencesReady = CompletableDeferred<Unit>()
    private val structuredTermsReady = CompletableDeferred<Unit>()

    private val transcriptRepository by lazy {
        TranscriptRepository(EnviousWisprDatabase.get(applicationContext).transcriptDao())
    }
    private val customTermRepository by lazy { CustomTermRepository(applicationContext) }
    private val providerConfiguration by lazy { ProviderConfigurationRepository(applicationContext) }

    private val audioConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            audioService = IAudioCaptureService.Stub.asInterface(binder)
            DebugLogger.log(TAG, "Audio capture connected")
            tryStartRecording()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            audioService = null
            DebugLogger.warn(TAG, "Audio capture disconnected")
            // STARTING too: since the live gate, capture runs while the lips spin, and a waiter whose
            // binder vanished returns without ending the take (Codex review 2, 2026-09-18).
            val seen = state.get()
            if (seen == SessionState.RECORDING || seen == SessionState.STARTING) {
                handleServiceFailure(TerminalReason.AUDIO_PROCESS_DIED)
            }
        }
    }

    private val asrConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            asrService = IAsrService.Stub.asInterface(binder)
            DebugLogger.log(TAG, "Speech service connected")
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            asrService = null
            DebugLogger.warn(TAG, "Speech service disconnected")
            if (state.get() == SessionState.PROCESSING) {
                if (rawTranscript.isNotBlank()) {
                    publishFallback(rawTranscript, sessionPreferences, PolishReason.SERVICE_DIED)
                } else if (arbiter.commitNow(TerminalReason.ASR_PROCESS_DIED)) {
                    updateDraftStatus(TranscriptEntity.STATUS_ASR_ERROR, insertionResult = "asr_error")
                    endAsFailure(TerminalReason.ASR_PROCESS_DIED)
                }
            }
        }
    }

    private val polishConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            polishService = IPolishService.Stub.asInterface(binder)
            // Warm at connect, measured and decided (#72): every later moment ends with the same two
            // models resident, because the speech model stays loaded after it transcribes, and costs the
            // user 0.9 to 3.1 s of wait. `architecture-rules.md` RULE: isolate-limbs carries the numbers.
            runCatching { polishService?.warmUpWithPolicy(sessionPreferences.policy) }
            DebugLogger.log(TAG, "Polish service connected")
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            polishService = null
            DebugLogger.warn(TAG, "Polish service disconnected")
            if (state.get() == SessionState.PROCESSING) {
                if (rawTranscript.isNotBlank()) {
                    publishFallback(rawTranscript, sessionPreferences, PolishReason.SERVICE_DIED)
                } else if (arbiter.commitNow(TerminalReason.POLISH_PROCESS_DIED)) {
                    updateDraftStatus(TranscriptEntity.STATUS_ASR_ERROR, insertionResult = "asr_error")
                    endAsFailure(TerminalReason.POLISH_PROCESS_DIED)
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        languageDetector = MlKitLanguageDetector(applicationContext)
        serviceScope.launch {
            runCatching { transcriptRepository.recoverStaleOpenRows(System.currentTimeMillis()) }
                .onSuccess { recovered -> Telemetry.insertionsRecovered(recovered.readyRowIds) }
                .onFailure { error -> DebugLogger.warn(TAG, "Unable to recover stale history: ${error.message}") }
        }
        serviceScope.launch {
            try {
                try {
                    customTermRepository.migrateLegacySharedPreferences(applicationContext)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    DebugLogger.warn(TAG, "Unable to migrate custom terms: ${error.message}")
                }
                customTermRepository.observeTerms().collect { terms ->
                    structuredTerms = BuiltinVocabulary.withUserTerms(terms)
                    structuredTermsReady.complete(Unit)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                DebugLogger.warn(TAG, "Unable to load custom terms: ${error.message}")
            }
        }
        serviceScope.launch {
            try {
                AppPreferences(applicationContext).authoritativeState.collect { preferences ->
                    cleanupOptions = preferences.cleanupOptions()
                    clipboardPolicy = preferences.clipboardInsertionPolicy()
                    autoStopOnSilence = preferences.autoStopOnSilenceEnabled
                    silencePauseSeconds = preferences.silencePauseSeconds
                    inputDevicePick = preferences.inputDevicePick
                    showBluetoothTips = preferences.showBluetoothTips
                    keepEarbudsReady = preferences.keepEarbudsReady
                    cleanupPreferencesReady.complete(Unit)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                DebugLogger.warn(TAG, "Unable to load cleanup preferences: ${error.message}")
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.getBooleanExtra(EXTRA_FOREGROUND_COMMAND, false) == true) {
            promoteToForeground(state.get() == SessionState.PROCESSING)
        }
        val request = BubbleRequestToken.parse(intent?.getStringExtra(EXTRA_REQUEST))
        pendingTrigger = TriggerSource.fromExtra(intent?.getStringExtra(EXTRA_TRIGGER_SOURCE))
        if (request != null && !admitBubbleCommand(intent?.action ?: ACTION_START, request)) {
            stopIfIdle()
            return START_NOT_STICKY
        }
        when (intent?.action ?: ACTION_START) {
            ACTION_CANCEL -> when (state.get()) {
                SessionState.STARTING -> cancelStarting()
                SessionState.RECORDING -> cancelRecording()
                SessionState.PROCESSING -> cancelProcessing()
                else -> stopIfIdle()
            }
            ACTION_STOP -> when (state.get()) {
                SessionState.STARTING -> cancelStarting()
                SessionState.RECORDING -> stopAndTranscribe()
                else -> stopIfIdle()
            }
            ACTION_TOGGLE -> when (state.get()) {
                SessionState.IDLE -> beginSession()
                SessionState.STARTING -> cancelStarting()
                SessionState.RECORDING -> stopAndTranscribe()
                else -> Unit
            }
            ACTION_START -> if (state.get() == SessionState.IDLE) {
                beginSession()
            } else {
                Telemetry.capture(AnalyticsEvent.DictationRefused("busy", pendingTrigger))
            }
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

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
            ACTION_START -> when (val decision = BubbleRequests.resolveStart(request, ownerIdle = state.get() == SessionState.IDLE)) {
                BubbleRequestLedger.StartDecision.Stale -> {
                    DebugLogger.log(TAG, "Bubble start refused as stale")
                    Telemetry.capture(AnalyticsEvent.DictationRefused("stale", bubbleTrigger(request)))
                    false
                }
                BubbleRequestLedger.StartDecision.RefusedBusy -> {
                    DebugLogger.log(TAG, "Bubble start refused: a take is active")
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
            ACTION_STOP, ACTION_CANCEL -> {
                val cancel = action == ACTION_CANCEL
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
                        DebugLogger.log(TAG, "Bubble ${if (cancel) "cancel" else "release"} noted ahead of its start")
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
        takeFacts.inputDevice = TakeFacts.inputDeviceToken(inputDevicePick)
        facts = takeFacts
        // The referee for THIS take, in memory, before anything else (G2 D2). Its sink is a limb: it
        // hands the committed reason to telemetry and never waits on storage or the network.
        arbiter = TakeArbiter { reason -> recordEnding(takeFacts, reason) }
        Telemetry.takeStarted(takeId)
        Telemetry.breadcrumb("take", "admitted", mapOf("take_id" to takeId, "trigger_source" to trigger.wire))
        RecordingOverlayState.showStarting(admittedRequest)
        promoteToForeground(processing = false)
        // Kept for the whole session. Android may rebind the accessibility service while the user
        // is still speaking, so the state insertion finds minutes later cannot say whether this
        // dictation ever had a field to aim at (`InsertionJudgement.handoffToJudge`).
        targetPinAtStart = PasteAccessibilityService.pinTargetForDictation()
        // Name the field this take aims at, for a reader that only wants takes aimed at ITS field.
        RecordingOverlayState.nameTarget(if (targetPinAtStart == DictationTargetPin.PINNED) PasteAccessibilityService.pinnedFieldId() else null)
        teardownStarted.set(false)
        draftId.set(0L)
        draftCreation = null
        rawTranscript = ""
        recordingDurationMs = 0L
        lastElapsedSecond = -1
        serviceScope.launch {
            val ready = withTimeoutOrNull(10_000L) {
                cleanupPreferencesReady.await()
                structuredTermsReady.await()
                true
            } == true
            if (!ready) {
                withContext(Dispatchers.Main.immediate) {
                    if (state.get() == SessionState.STARTING) {
                        showError(TerminalReason.SETTINGS_UNAVAILABLE)
                    }
                }
                return@launch
            }
            val termsSnapshot = structuredTerms
            val matcher = withContext(Dispatchers.Default) {
                StructuredTermRestorer.compile(termsSnapshot)
            }
            val policy = withContext(Dispatchers.IO) { providerConfiguration.loadPolicy() }
            // Admission is written before capture starts, under a deadline that never gates the take:
            // the queued write still lands in order if this stops waiting (issue #176, plan §3.3).
            val admission = Telemetry.journal?.admit(takeId, trigger)
            if (admission != null && withTimeoutOrNull(JOURNAL_ADMISSION_DEADLINE_MS) { admission.await() } == null) {
                DebugLogger.warn(TAG, "Journal admission did not land within ${JOURNAL_ADMISSION_DEADLINE_MS} ms; starting anyway")
            }
            withContext(Dispatchers.Main.immediate) {
                if (state.get() != SessionState.STARTING) return@withContext
                sessionPreferences = SessionPreferences(
                    cleanup = cleanupOptions,
                    terms = termsSnapshot,
                    matcher = matcher,
                    // Non-null by construction: cleanupPreferencesReady, awaited above, is
                    // completed only after the line that writes this field.
                    clipboard = clipboardPolicy ?: ClipboardInsertionPolicy(),
                    policy = policy,
                )
                bindPipelineServices()
            }
        }
    }

    private fun bindPipelineServices() {
        val audioIntent = Intent(this, AudioCaptureService::class.java)
        audioBound = runCatching {
            bindService(audioIntent, audioConnection, Context.BIND_AUTO_CREATE)
        }.getOrDefault(false)
        if (!audioBound) {
            stopAudioCaptureService()
            showError(TerminalReason.AUDIO_BIND_FAILED)
            return
        }

        asrBound = runCatching {
            bindService(Intent(this, com.envi.wispr.asr.AsrService::class.java), asrConnection, Context.BIND_AUTO_CREATE)
        }.getOrDefault(false)
        if (!asrBound) {
            handleServiceFailure(TerminalReason.ASR_BIND_FAILED)
            return
        }

        polishBound = runCatching {
            bindService(Intent(this, PolishService::class.java), polishConnection, Context.BIND_AUTO_CREATE)
        }.getOrDefault(false)
        if (!polishBound) handleServiceFailure(TerminalReason.POLISH_BIND_FAILED)
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
                audioService?.startCaptureForTake(autoStopOnSilence, silencePauseSeconds, inputDevicePick, keepEarbudsReady, takeId)
            }.getOrNull()
            if (started != true) {
                // The one start failure with its own sentence is "nothing can record at all" (macOS copy).
                val failure = runCatching { audioService?.lastStartFailure }.getOrNull()
                    ?: AudioCaptureService.START_FAILURE_OTHER
                stopAudioCaptureService()
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
                val capture = audioService
                Thread({
                    runCatching { capture?.stopCapture() }
                    runCatching { capture?.waitForFileReady(2_000L) }
                    stopAudioCaptureService()
                }, "StartCaptureFailureCleanup").start()
            }
            DebugLogger.error(TAG, "Failed to start recording", error)
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
        val startedAt = SystemClock.elapsedRealtime()
        while (true) {
            if (state.get() != SessionState.STARTING) return
            val service = audioService ?: return // onServiceDisconnected ends the take for STARTING too.
            val live = try {
                service.liveState
            } catch (_: Exception) {
                return // A dead binder: its ServiceConnection callback ends the take.
            }
            if (live != AudioCaptureService.LIVE_WAITING) {
                // Published on the MAIN thread, where every command is dispatched and where the
                // bubble's early release sets its flag: the old start published there too, so a stop,
                // cancel or release can never read STARTING and then act against a take this thread
                // published in between (Codex review 4, 2026-09-18).
                val forced = live == AudioCaptureService.LIVE_FORCED
                mainHandler.post { publishLive(forced) }
                return
            }
            val capturing = runCatching { service.isCapturing }.getOrDefault(false)
            if (!capturing) {
                val failure = runCatching { service.lastStartFailure }.getOrDefault(AudioCaptureService.START_FAILURE_OTHER)
                val reason = if (failure == AudioCaptureService.START_FAILURE_EARBUDS) {
                    TerminalReason.CAPTURE_START_EARBUDS_REFUSED
                } else {
                    TerminalReason.CAPTURE_ENDED_BEFORE_LIVE
                }
                // Claim first: a cancel that stopped capture between the two checks owns the take, and
                // its stop must not read as a microphone failure.
                if (!failWhileStarting(reason)) return
                DebugLogger.warn(TAG, "Capture ended while waiting for the route to go live (failure=$failure)")
                runCatching { service.waitForFileReady(2_000L) }
                stopAudioCaptureService()
                return
            }
            if (SystemClock.elapsedRealtime() - startedAt > LIVE_WAIT_BOUND_MS) {
                if (!failWhileStarting(TerminalReason.LIVE_WAIT_DEADLINE)) return
                DebugLogger.error(TAG, "The route never went live within ${LIVE_WAIT_BOUND_MS} ms")
                runCatching { service.stopCapture() }
                runCatching { service.waitForFileReady(2_000L) }
                stopAudioCaptureService()
                return
            }
            Thread.sleep(LIVE_POLL_MS)
        }
    }

    /** The one STARTING→RECORDING publication: main thread, under [publishLock]. */
    private fun publishLive(forced: Boolean) {
        check(Looper.myLooper() == Looper.getMainLooper()) { "publishLive runs on the main thread" }
        synchronized(publishLock) {
            if (!state.compareAndSet(SessionState.STARTING, SessionState.RECORDING)) {
                audioService?.let { runCatching { it.stopCapture() } }
                return
            }
            recordingStartedAtMs = System.currentTimeMillis()
            val takeFacts = facts
            audioService?.let { service ->
                takeFacts.routeKind = runCatching { InputRouteKind.fromCode(service.inputRouteKind) }.getOrNull()
                takeFacts.routeReason = runCatching { InputRouteReason.fromCode(service.inputRouteReason) }.getOrNull()
                takeFacts.liveAfterMs = runCatching { service.liveAfterMs }.getOrNull()
            }
            takeFacts.liveState = if (forced) "forced" else "ready"
            Telemetry.journal?.advance(takeId, TakeStage.RECORDING)
            Telemetry.breadcrumb(
                "take", "live",
                mapOf("take_id" to takeId, "route_kind" to takeFacts.routeKind?.name?.lowercase(), "live_after_ms" to takeFacts.liveAfterMs, "live_state" to takeFacts.liveState),
            )
            draftCreation = serviceScope.async {
                val id = transcriptRepository.insert(
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
                RecordingOverlayState.attachTranscript(id)
                Telemetry.journal?.associate(takeFacts.takeId, id)
                id
            }
            DictationSurfaceState.update(this, DictationSurfaceState.Phase.LISTENING)
            RecordingOverlayState.show()
            vibrate(HapticCue.SESSION_TRANSITION)
            DebugLogger.log(TAG, "Recording started (live after ${runCatching { audioService?.liveAfterMs }.getOrNull() ?: -1} ms, forced=$forced)")
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
                DebugLogger.log(TAG, "Early release applied: stopping as soon as capture started")
                stopAndTranscribe()
            }
        }
    }

    private fun startPolling() {
        Thread({
            while (state.get() == SessionState.RECORDING) {
                try {
                    val service = audioService ?: break
                    val elapsedMs = service.elapsedMs
                    val second = (elapsedMs / 1_000L).toInt().coerceAtLeast(0)
                    if (second != lastElapsedSecond) {
                        lastElapsedSecond = second
                        RecordingOverlayState.updateElapsed(second)
                    }
                    publishSilenceNoticeIfNeeded(service)
                    publishMicrophoneNoticesIfNeeded(service)
                    if (!service.isCapturing && state.get() == SessionState.RECORDING) {
                        val ending = service.terminalReason
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
                                DebugLogger.error(TAG, "Audio capture ended without a successful reason")
                                discardDraft()
                                showError(TerminalReason.CAPTURE_FAILED_MID_TAKE)
                            }

                            CaptureEnding.StillRunning -> {
                                DebugLogger.error(TAG, "Audio capture stopped without publishing a reason")
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
                                DebugLogger.log(TAG, "Take ended at the duration cap")
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
        val takeSerial = RecordingOverlayState.snapshots.value.takeSerial
        runCatching {
            Thread({
                while (state.get() == SessionState.RECORDING) {
                    val service = audioService ?: break
                    val bands = runCatching { service.spectrumBands }.getOrElse { RecordingOverlayState.NO_BANDS }
                    if (RecordingOverlayState.snapshots.value.takeSerial != takeSerial) break
                    RecordingOverlayState.updateBands(takeSerial, bands)
                    Thread.sleep(METER_INTERVAL_MS)
                }
            }, "DictationMeterThread").start()
        }.onFailure { DebugLogger.warn(TAG, "Live picture unavailable for this take: ${it.message}") }
    }

    /**
     * Tell the user once, and only when auto-stop never became available for a take they had it on for.
     *
     * Losing the detector after it was already working leaves a correct recording, and a message
     * several seconds into one is an interruption for nothing. The floating recorder only exists while
     * the accessibility service runs, so clipboard-only mode gets the same sentence as a toast instead.
     */
    private fun publishSilenceNoticeIfNeeded(service: IAudioCaptureService) {
        if (!autoStopOnSilence || silenceNoticeShown) return
        val status = runCatching { service.silenceStopStatus }.getOrNull() ?: return
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
    private fun publishMicrophoneNoticesIfNeeded(service: IAudioCaptureService) {
        if (silenceNoticeShown || forcedNoticeShown) return
        val kind = runCatching { service.inputRouteKind }.getOrNull() ?: return
        if (bluetoothTipGate.shouldShow(kind, showBluetoothTips)) {
            DebugLogger.log(TAG, "Bluetooth tip shown")
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
        DebugLogger.log(TAG, "Duration warning shown at ${elapsedMs}ms")
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
        if (PasteAccessibilityService.isBound.value) {
            RecordingOverlayState.showNotice(line)
        } else {
            sayAfterRecording(line)
        }
    }

    /** Say one line when the recorder has already gone. A toast is the only surface left. */
    private fun sayAfterRecording(line: String) {
        serviceScope.launch(Dispatchers.Main.immediate) {
            runCatching {
                Toast.makeText(applicationContext, line, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun stopAndTranscribe() {
        // Under publishLock: the live waiter publishes RECORDING (pill, draft) under the same lock, so a
        // stop that follows its CAS cannot run ahead of its publication (Codex review 3, 2026-09-18).
        synchronized(publishLock) {
            if (!state.compareAndSet(SessionState.RECORDING, SessionState.PROCESSING)) return
            RecordingOverlayState.showProcessing()
        }
        DictationSurfaceState.update(this, DictationSurfaceState.Phase.PROCESSING)
        promoteToForeground(processing = true)
        vibrate(HapticCue.SESSION_TRANSITION)
        DebugLogger.log(TAG, "Stopping recording and starting transcription")

        Thread({
            var audioReady = false
            try {
                audioService?.stopCapture()
                audioReady = runCatching { audioService?.waitForFileReady(2_000L) == true }.getOrDefault(false)
                if (!audioReady) {
                    stopAudioCaptureService()
                    discardDraft()
                    showError(TerminalReason.CAPTURE_CLOSE_UNSAFE)
                    return@Thread
                }
                val audioFilePath = audioService?.audioFilePath
                // The duration is the audio's own length, read from the finished file NOW, before
                // transcription deletes it: the elapsed getter is 0 once capture stops, and the wall
                // clock counted the wait for the earbuds.
                recordingDurationMs = runCatching {
                    audioFilePath?.let { (PcmAudio.durationSeconds(File(it).length()) * 1000f).toLong() }
                }.getOrNull()?.coerceAtLeast(0L) ?: 0L
                // Complete once the capture thread has exited (waitForFileReady above joined it): the
                // final route was observed before the recorder stopped, and the label persists in the
                // capture process until its next start.
                captureDeviceLabel = runCatching { audioService?.effectiveInputDevice }.getOrNull().orEmpty()
                // Same moment, same reason: the capture thread has exited, so the peak is the whole take's.
                takePeakAmplitude = runCatching { audioService?.takePeakAmplitude }.getOrNull()
                val takeFacts = facts
                takeFacts.peakAmplitude = takePeakAmplitude
                takeFacts.recordingSeconds = recordingDurationMs / 1000.0
                // Stamped by the polling loop when capture ended on its own; otherwise this stop is the
                // owner's own request, which the capture process reports as a manual ending.
                if (takeFacts.captureTerminal == null) takeFacts.captureTerminal = TakeFacts.MANUAL_ENDING
                audioService?.let { service ->
                    takeFacts.silenceStopStatus = runCatching { TakeFacts.silenceStatusToken(service.silenceStopStatus) }.getOrNull()
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
                val speechService = asrService
                if (speechService == null) {
                    deleteCapturedAudio(audioFilePath)
                    updateDraftStatus(TranscriptEntity.STATUS_ASR_ERROR, insertionResult = "asr_error")
                    showError(TerminalReason.ASR_NOT_READY)
                    return@Thread
                }
                DebugLogger.mark(TAG, "asr_request")
                val asrRequestedAtMs = SystemClock.elapsedRealtime()
                speechService.transcribeFileForTake(audioFilePath, takeId, object : IAsrCallback.Stub() {
                    override fun onResult(text: String?) {
                        deleteCapturedAudio(audioFilePath)
                        takeFacts.asrMs = SystemClock.elapsedRealtime() - asrRequestedAtMs
                        takeFacts.asrChars = text?.length ?: 0
                        DebugLogger.log(TAG, "Transcription result received (chars=${text?.length ?: 0})")
                        DebugLogger.mark(TAG, "result_received")
                        Telemetry.breadcrumb("take", "asr_done", mapOf("take_id" to takeId, "asr_ms" to takeFacts.asrMs, "asr_chars" to takeFacts.asrChars))
                        polishAndPublish(text.orEmpty())
                    }

                    /** The versioned request never answers this; a legacy sentence here is a service defect. */
                    override fun onError(message: String?) {
                        deleteCapturedAudio(audioFilePath)
                        DebugLogger.error(TAG, "Legacy onError on a versioned request")
                        // The fact is written before the claim so the ending's row carries it; a claim
                        // that loses leaves an unread fact, never a rewritten row (G1 D2).
                        takeFacts.asrFailure = AsrFailureReason.UNKNOWN
                        takeFacts.asrMs = SystemClock.elapsedRealtime() - asrRequestedAtMs
                        if (!arbiter.commitNow(TerminalReason.ASR_FAILED)) return
                        updateDraftStatus(TranscriptEntity.STATUS_ASR_ERROR, insertionResult = "asr_error")
                        endAsFailure(TerminalReason.ASR_FAILED)
                    }

                    override fun onFailure(reason: Int, detail: String?) {
                        deleteCapturedAudio(audioFilePath)
                        val failure = AsrFailureReason.fromCode(reason)
                        takeFacts.asrFailure = failure
                        takeFacts.asrMs = SystemClock.elapsedRealtime() - asrRequestedAtMs
                        // Claim FIRST: a cancel that already owns the take must not see its History row
                        // rewritten or a failure toast over its acknowledgement (G1 D2).
                        if (!arbiter.commitNow(TerminalReason.ASR_FAILED)) return
                        updateDraftStatus(TranscriptEntity.STATUS_ASR_ERROR, insertionResult = "asr_error")
                        // The detail is local diagnostics and stops here: never a toast, never the wire.
                        DebugLogger.error(TAG, "ASR failed: ${failure.name} (code $reason) ${detail.orEmpty()}")
                        endAsFailure(TerminalReason.ASR_FAILED)
                    }
                })
            } catch (error: Exception) {
                stopAudioCaptureService()
                if (audioReady) deleteCapturedAudio(runCatching { audioService?.audioFilePath }.getOrNull())
                DebugLogger.error(TAG, "Transcription failed", error)
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
            PasteAccessibilityService.releasePinnedTarget()
            finishSession()
            return
        }
        serviceScope.launch {
            val takePreferences = sessionPreferences
            val preparedRaw = restoreTakeVocabulary(rawText, takePreferences)
            val service = polishService
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
                    DebugLogger.log(TAG, "Transcript arrived after the session ended; not polishing")
                    return@launch
                }
                val opened = polishLedger.open()
                // The watchdog is armed BEFORE the binder call so the call itself is inside the budget.
                // The ledger is the only first-wins gate: an outcome that arrives first closes it.
                serviceScope.launch {
                    delay(PolishWatchdogBudget.forPolicy(takePreferences.policy))
                    if (!polishLedger.claim(opened)) return@launch
                    DebugLogger.warn(TAG, "Polish watchdog fired for request $opened; cancelling on the engine")
                    runCatching { polishService?.cancel(opened) }
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
                    object : IPolishCallback.Stub() {
                        override fun onOutcome(outcome: PolishOutcome?) {
                            // This callback belongs to ONE request and the engine answers it once, so an
                            // empty or misnamed outcome is the only answer this request will get: fail
                            // open now rather than leave the session in Processing forever.
                            if (outcome == null || outcome.requestId != requestId) {
                                if (polishLedger.claim(requestId)) {
                                    DebugLogger.warn(TAG, "Invalid polish outcome for request $requestId")
                                    Telemetry.defect(AppDefect.PolishProtocolViolation, mapOf("take_id" to takeId, "shape" to if (outcome == null) "null" else "mismatched"))
                                    publishFallback(rawText, takePreferences, PolishReason.CALL_FAILED)
                                }
                                return
                            }
                            if (!polishLedger.claim(outcome.requestId)) {
                                DebugLogger.warn(
                                    TAG,
                                    "Ignoring polish outcome for request ${outcome.requestId}: not the open request (reason=${outcome.reason})",
                                )
                                return
                            }
                            DebugLogger.log(TAG, "Polish outcome ${outcome.requestId}: reason=${outcome.reason} status=${outcome.statusCode}")
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
                DebugLogger.error(TAG, "Unable to call polish service", error)
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
        DebugLogger.warn(TAG, "Polish fell back on the session owner: reason=$reason")
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
     * `unbindPipelineServices` as an idempotent backstop.
     */
    private fun cancelOpenPolishRequest() {
        val requestId = polishLedger.close() ?: return
        runCatching { polishService?.cancel(requestId) }
            .onFailure { error -> DebugLogger.warn(TAG, "Unable to cancel polish request $requestId: ${error.message}") }
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
            DebugLogger.warn(TAG, "Ignoring a final transcript that arrived after the take was claimed")
            return
        }
        val finalText = text.ifBlank { rawTranscript }
        val finalEngine = if (text.isBlank() && rawTranscript.isNotBlank()) PolishEngineLabels.RAW_FALLBACK else engine
        DebugLogger.log(TAG, "Polish result received ($finalEngine, ${latencyMs}ms, chars=${finalText.length})")
        if (finalText.isBlank()) {
            if (arbiter.commit(publication, TerminalReason.FINAL_TEXT_EMPTY)) finishSession()
            return
        }
        val polishFacts = PolishPublicationFacts.from(reason, statusCode, polishContext)
        polishFacts.notice?.let { notice ->
            DebugLogger.log(TAG, "Polish notice shown: ${polishFacts.failure}")
            mainHandler.post {
                Toast.makeText(this, notice.toastLine, Toast.LENGTH_LONG).show()
                DictationNotificationController.showPolishNotice(this, notice)
            }
        }

        serviceScope.launch {
            val saveResult = runCatching {
                val existingId = draftId.get()
                val persistedId = if (existingId > 0L) {
                    val updated = transcriptRepository.finalize(
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
                DebugLogger.warn(TAG, "Unable to save transcript history: ${error.message}")
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
                DebugLogger.warn(TAG, "Publication revoked before the handoff; not inserting")
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
                    PasteAccessibilityService.pasteWhenTargetReturns(
                        persistedId,
                        finalText,
                        policy = sessionPreferences.clipboard,
                        takeId = takeId,
                    )
                } else {
                    InsertionHandoff.HISTORY_NOT_DURABLE
                },
            )
            if (handoff != InsertionHandoff.SCHEDULED) {
                PasteAccessibilityService.releasePinnedTarget()
                val mustPreventDataLoss = persistedId <= 0L
                // Three outcomes, not two. A copy that was never attempted is the user's own
                // auto-copy setting and History is then the destination; a copy that was attempted
                // and failed is a fault whatever else was true.
                val clipboard =
                    if (sessionPreferences.clipboard.autoCopyToClipboard || mustPreventDataLoss) {
                        if (
                            keepOnClipboard(
                                getSystemService(ClipboardManager::class.java),
                                persistedId,
                                finalText,
                            )
                        ) {
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
            DebugLogger.log(
                TAG,
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
            DebugLogger.log(TAG, DebugLogger.pipelineSummary())
            finishSession()
        }
    }

    private suspend fun insertReadyTranscript(finalText: String, engine: String, latencyMs: Long, polishFacts: PolishPublicationFacts): Long {
        return transcriptRepository.insert(
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
        clipboard: ClipboardManager,
        transcriptId: Long,
        text: String,
    ): Boolean {
        val copied = runCatching {
            clipboard.setPrimaryClip(ClipData.newPlainText("EnviousWispr", text))
        }.isSuccess
        if (transcriptId <= 0L) return copied

        runCatching {
            transcriptRepository.finalizeInsertionOutcome(
                transcriptId,
                TranscriptEntity.STATUS_INSERTION_INTERRUPTED,
                if (copied) InsertionResults.CLIPBOARD else InsertionResults.INSERTION_FAILED,
                interrupted = true,
            )
        }.onFailure { error ->
            DebugLogger.warn(TAG, "Unable to finalize clipboard-only history: ${error.message}")
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
            autoPaste = autoPasteAvailability(),
            handoff = handoff,
            clipboard = clipboard,
            savedInHistory = savedInHistory,
        ) ?: return
        mainHandler.post {
            Toast.makeText(this, announcement.line, Toast.LENGTH_LONG).show()
        }
    }

    /**
     * Liveness is a volatile read. The permission half is a `Settings.Secure` lookup, which the
     * platform serves from a per-process cache after the first call. Read only when a session
     * starts and when a dictation falls back, never at idle (`architecture-rules.md`
     * RULE: no-idle-cost). The setting alone cannot answer this: it still names a crashed service.
     */
    private fun autoPasteAvailability(): AutoPasteAvailability = AutoPasteReadiness.evaluate(
        permittedInSettings = AccessibilityPermission.isGranted(this),
        serviceBound = PasteAccessibilityService.isBound.value,
    )

    private suspend fun keepInHistoryOnly(transcriptId: Long) {
        if (transcriptId <= 0L) return
        runCatching {
            transcriptRepository.finalizeInsertionOutcome(
                transcriptId,
                TranscriptEntity.STATUS_INSERTION_INTERRUPTED,
                InsertionResults.HISTORY_ONLY,
                interrupted = true,
            )
        }.onFailure { error ->
            DebugLogger.warn(TAG, "Unable to finalize history-only transcript: ${error.message}")
        }
    }

    private fun cancelRecording() {
        val cancel = synchronized(publishLock) {
            if (!state.compareAndSet(SessionState.RECORDING, SessionState.CANCELLING)) return
            RecordingOverlayState.showProcessing()
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
        RecordingOverlayState.showProcessing()
        PasteAccessibilityService.releasePinnedTarget()
        DictationSurfaceState.update(this, DictationSurfaceState.Phase.IDLE)
        vibrate(HapticCue.SESSION_CANCELED)
        serviceScope.launch {
            // A cancel before the capture process was even bound has nothing to stop: the quiet finish
            // the old cancelStarting always took. Committed BEFORE the draft goes (G2 D2).
            val service = audioService
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
                stopAudioCaptureService()
                discardDraft()
                endAsFailure(TerminalReason.CAPTURE_CLOSE_UNSAFE_ON_CANCEL)
                return@launch
            }
            if (!arbiter.commit(cancel, cancelled)) return@launch
            discardDraft()
            deleteCapturedAudio(runCatching { service.audioFilePath }.getOrNull())
            finishTakeOrStop()
            finishSession()
        }
    }

    /**
     * The take is over: let the capture service keep the earbuds warm if it can (it then owns its own
     * lifetime and the unbind must not stop it), otherwise stop it as before.
     */
    private fun finishTakeOrStop() {
        val held = runCatching { audioService?.finishTake() == true }.getOrDefault(false)
        if (!held) stopAudioCaptureService()
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
            DebugLogger.log(TAG, "Cancelled while processing; open polish request: ${polishLedger.openId != null}")
            cancelOpenPolishRequest()
            reserved
        }
        RecordingOverlayState.showProcessing()
        PasteAccessibilityService.releasePinnedTarget()
        DictationSurfaceState.update(this, DictationSurfaceState.Phase.IDLE)
        vibrate(HapticCue.SESSION_CANCELED)
        // Committed before the draft goes (G2 D2); a destruction that revoked it owns the row instead.
        if (!arbiter.commit(cancel, TerminalReason.CANCELLED_PROCESSING)) return
        discardDraft()
        finishSession()
    }

    private fun cancelStarting() {
        val cancel = synchronized(publishLock) {
            if (!state.compareAndSet(SessionState.STARTING, SessionState.CANCELLING)) return
            RecordingOverlayState.showProcessing()
            arbiter.reserve(Claimants.CANCEL)
        } ?: return
        cancelCaptureAndFinish(cancel, TerminalReason.CANCELLED_STARTING)
    }

    /**
     * The arbiter's sink: the one place a committed ending becomes telemetry (issue #176). A breadcrumb
     * always; a Sentry defect only when the channel table says the cause is ours; the journal commit,
     * which captures the `dictation.terminal` row after its own Room transaction; then the take leaves
     * the error scope. Every call is a limb that returns at once.
     */
    private fun recordEnding(takeFacts: TakeFacts, reason: TerminalReason) {
        DebugLogger.log(TAG, "Take terminal: ${reason.name} (${reason.result.wire})")
        Telemetry.breadcrumb("take", "terminal", mapOf("take_id" to takeFacts.takeId, "reason" to reason.name, "result" to reason.result.wire))
        TelemetryChannels.defectOf(reason, takeFacts.asrFailure)?.let { defect ->
            Telemetry.defect(defect, mapOf("take_id" to takeFacts.takeId, "reason" to reason.name, "asr_failure_reason" to takeFacts.asrFailure?.name))
        }
        Telemetry.journal?.terminal(takeFacts.terminal(reason))
        Telemetry.takeEnded(takeFacts.takeId)
    }

    /**
     * Ends the take as the failure [reason] when nothing else has claimed it: the arbiter is the guard,
     * so a failure observed after a cancel or a publication owns the take does nothing at all (the
     * old `ERROR` check let it announce over them). The sentence is [TakeNotices]'s, never the caller's.
     */
    private fun showError(reason: TerminalReason) {
        if (!arbiter.commitNow(reason)) {
            DebugLogger.log(TAG, "Ignoring $reason: the take already has an ending")
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
        DebugLogger.warn(TAG, "Take ended: $reason")
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
        DebugLogger.warn(TAG, "Take ended while starting: $reason")
        announceError(TakeNotices.line(reason))
        return true
    }

    /** Tears the take down as a failure; says [line] when there is one. Teardown never depends on copy. */
    private fun announceError(line: String?) {
        cancelOpenPolishRequest()
        RecordingOverlayState.showProcessing()
        PasteAccessibilityService.releasePinnedTarget()
        DictationSurfaceState.update(this, DictationSurfaceState.Phase.IDLE)
        vibrate(HapticCue.FAILURE)
        if (line != null) mainHandler.post { Toast.makeText(this, line, Toast.LENGTH_LONG).show() }
        stopAudioCaptureService()
        finishSession()
    }

    private fun handleServiceFailure(reason: TerminalReason) {
        if (state.get() == SessionState.RECORDING) discardDraft()
        showError(reason)
    }

    private fun finishSession() {
        if (state.getAndSet(SessionState.FINISHING) == SessionState.FINISHING) return
        cancelOpenPolishRequest()
        RecordingOverlayState.showProcessing()
        DictationSurfaceState.update(this, DictationSurfaceState.Phase.IDLE)
        val historyUpdates = synchronized(pendingHistoryUpdates) { pendingHistoryUpdates.toList() }
        serviceScope.launch {
            historyUpdates.joinAll()
            mainHandler.post {
                unbindPipelineServices()
                stopForeground(STOP_FOREGROUND_REMOVE)
                DictationNotificationController.dismiss(this@DictationSessionService)
                // IDLE is published at the last moment this instance can still refuse a start: the
                // next command creates a fresh instance whose state begins IDLE.
                admittedRequest = null
                stopAfterRecording = false
                RecordingOverlayState.hide()
                stopSelf()
            }
        }
    }

    private fun promoteToForeground(processing: Boolean) {
        val notification = if (processing) {
            DictationNotificationController.processing(this)
        } else {
            DictationNotificationController.listening(
                context = this,
                autoPaste = autoPasteAvailability(),
                // The live field, not the session snapshot: this runs before `beginSession`
                // freezes one, and it is the field that snapshot is taken from. It is null on a
                // cold start, which is the state the notification has to be able to say nothing
                // about rather than guess at.
                clipboard = clipboardPolicy,
            )
        }
        startForeground(
            DictationNotificationController.NOTIFICATION_ID,
            notification,
            if (processing) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            } else {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            },
        )
    }

    private fun stopIfIdle() {
        if (state.get() == SessionState.IDLE) stopSelf()
    }

    private fun deleteCapturedAudio(path: String?) {
        if (path.isNullOrBlank()) return
        runCatching {
            val file = File(path)
            if (file.exists() && !file.delete()) {
                DebugLogger.warn(TAG, "Unable to delete captured audio after terminal processing")
            }
        }.onFailure { error -> DebugLogger.warn(TAG, "Unable to delete captured audio: ${error.message}") }
    }

    private fun updateDraftStatus(status: String, interrupted: Boolean = false, insertionResult: String? = null) {
        val update = serviceScope.launch(start = CoroutineStart.LAZY) {
            setDraftStatus(status, interrupted, insertionResult)
        }
        pendingHistoryUpdates += update
        update.start()
    }

    private suspend fun setDraftStatus(status: String, interrupted: Boolean = false, insertionResult: String? = null) {
        val id = draftId.get().takeIf { it > 0L } ?: runCatching { draftCreation?.await() ?: 0L }.getOrDefault(0L)
        if (id > 0L) transcriptRepository.updateStatus(id, status, interrupted, insertionResult)
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
     * row: this service's own `onDestroy` teardown, and `TranscriptDao.recoverStaleDrafts` on the
     * next start. Both run when the app was killed with a dictation live, so nobody told the user
     * anything and the row is the only signal that words were lost. That is why the prune leaves
     * `interrupted` rows alone.
     *
     * The id is cleared after the delete. That does not make a late write impossible — `setDraftStatus`
     * can still resolve the completed `draftCreation` to the old id — it makes one harmless: the
     * `UPDATE` matches zero rows and cannot bring the draft back.
     */
    private fun discardDraft() {
        val discard = serviceScope.launch(start = CoroutineStart.LAZY) {
            val id = draftId.get().takeIf { it > 0L }
                ?: runCatching { draftCreation?.await() ?: 0L }.getOrDefault(0L)
            if (id > 0L) {
                transcriptRepository.discard(id)
                draftId.set(0L)
            }
        }
        pendingHistoryUpdates += discard
        discard.start()
    }

    private fun stopAudioCaptureService() {
        runCatching { stopService(Intent(this, AudioCaptureService::class.java)) }
            .onFailure { error -> DebugLogger.warn(TAG, "Unable to stop audio capture service: ${error.message}") }
    }

    private fun unbindPipelineServices() {
        cancelOpenPolishRequest()
        if (audioBound) runCatching { unbindService(audioConnection) }
        if (asrBound) runCatching { unbindService(asrConnection) }
        if (polishBound) runCatching { unbindService(polishConnection) }
        audioBound = false
        asrBound = false
        polishBound = false
        audioService = null
        asrService = null
        polishService = null
    }

    /**
     * The cues this service fires, and whether each one is the user's to switch off.
     *
     * The gate belongs to the CUE, not to `vibrate`, because the two kinds answer to different
     * settings. `Settings.System.HAPTIC_FEEDBACK_ENABLED` governs touch and long-press feedback,
     * so honouring it for a RESULT cue is parity with `PasteAccessibilityService.performResultHaptic`.
     * A session cue is not feedback on a touch: on the side-button path there is no window, the
     * user's eyes are on another app's text field, and the buzz is the only signal that recording
     * started or stopped. Gating those on the touch-feedback switch silences the whole product for
     * a user who turned off keyboard clicks.
     */
    private enum class HapticCue(
        val durationMs: Long,
        val amplitude: Int,
        val honoursSystemHapticSetting: Boolean,
    ) {
        /** Recording started, or stopped for transcription. The only cue on a windowless path. */
        SESSION_TRANSITION(28L, 120, honoursSystemHapticSetting = false),

        /** The user cancelled. Also a windowless acknowledgement, with the heavier waveform. */
        SESSION_CANCELED(45L, 180, honoursSystemHapticSetting = false),

        /** A result cue: the dictation did not land. Parity with performResultHaptic. */
        FAILURE(45L, 180, honoursSystemHapticSetting = true),
    }

    private fun vibrate(cue: HapticCue) {
        if (cue.honoursSystemHapticSetting &&
            Settings.System.getInt(contentResolver, Settings.System.HAPTIC_FEEDBACK_ENABLED, 1) != 1
        ) {
            return
        }
        runCatching {
            // VibratorManager is API 31 against minSdk 33. Guarded here as well as in
            // PasteAccessibilityService.performResultHaptic: the runCatching only degrades to no
            // haptics at all on the oldest supported phone, which is a silent loss of every cue.
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                getSystemService(VibratorManager::class.java)?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                getSystemService(Vibrator::class.java)
            } ?: return
            if (vibrator.hasVibrator()) {
                vibrator.vibrate(VibrationEffect.createOneShot(cue.durationMs, cue.amplitude))
            }
        }
    }

    override fun onDestroy() {
        if (::languageDetector.isInitialized) languageDetector.close()
        // Invalidate a live wait or a running take BEFORE any blocking cleanup, under the same lock the
        // waiter publishes under: after this, no pill can appear for a take being torn down.
        val destroyedState = synchronized(publishLock) {
            val seen = state.get()
            if (seen == SessionState.STARTING || seen == SessionState.RECORDING) state.set(SessionState.ERROR)
            RecordingOverlayState.hide()
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
                DebugLogger.warn(TAG, "Unable to resolve interrupted history row during teardown: ${error.message}")
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
                    transcriptRepository.updateStatus(
                        interruptedDraftId,
                        TranscriptEntity.STATUS_INTERRUPTED,
                        interrupted = true,
                        insertionResult = "not_attempted",
                    )
                }
            }.onFailure { error ->
                DebugLogger.warn(TAG, "Unable to mark interrupted session during teardown: ${error.message}")
            }
        }
        val captureRunning = destroyedState == SessionState.RECORDING || destroyedState == SessionState.STARTING
        if (captureRunning && teardownStarted.compareAndSet(false, true)) {
            val capture = audioService
            Thread({
                runCatching { capture?.stopCapture() }
                val ready = runCatching { capture?.waitForFileReady(2_000L) == true }.getOrDefault(false)
                if (ready) deleteCapturedAudio(runCatching { capture?.audioFilePath }.getOrNull())
                stopAudioCaptureService()
                mainHandler.post { unbindPipelineServices() }
            }, "DestroyedSessionCleanup").start()
        } else {
            if (sessionWasOpen) stopAudioCaptureService()
            unbindPipelineServices()
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        DictationNotificationController.dismiss(this)
        super.onDestroy()
    }
}
