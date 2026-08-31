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
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.envi.wispr.asr.IAsrCallback
import com.envi.wispr.asr.IAsrService
import com.envi.wispr.audio.AudioCaptureService
import com.envi.wispr.audio.IAudioCaptureService
import com.envi.wispr.cleanup.DeterministicCleanup
import com.envi.wispr.cleanup.CleanupOptions
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
import com.envi.wispr.polish.PolishEngineLabels
import com.envi.wispr.polish.PolishService
import com.envi.wispr.polish.RegexPolisher
import com.envi.wispr.settings.AppPreferences
import com.envi.wispr.settings.cleanupOptions
import com.envi.wispr.settings.clipboardInsertionPolicy
import com.envi.wispr.shortcuts.DictationNotificationController
import com.envi.wispr.shortcuts.DictationSurfaceState
import com.envi.wispr.shortcuts.RecordingOverlayState
import com.envi.wispr.vocabulary.CustomTerm
import com.envi.wispr.vocabulary.CustomTermRepository
import com.envi.wispr.vocabulary.BuiltinVocabulary
import com.envi.wispr.vocabulary.StructuredTermRestorer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
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
import java.util.concurrent.atomic.AtomicReference

/** Owns a dictation session without placing an Activity above the user's typing app. */
class DictationSessionService : Service() {
    companion object {
        private const val TAG = "DictationSession"
        const val ACTION_START = "com.envi.wispr.action.START_DICTATION"
        const val ACTION_TOGGLE = "com.envi.wispr.action.TOGGLE_DICTATION"
        const val ACTION_STOP = "com.envi.wispr.action.STOP_DICTATION"
        const val ACTION_CANCEL = "com.envi.wispr.action.CANCEL_DICTATION"
        private const val EXTRA_FOREGROUND_COMMAND = "foreground_command"

        fun sendCommand(context: Context, action: String) {
            val intent = Intent(context, DictationSessionService::class.java).setAction(action)
            if (action == ACTION_START || action == ACTION_TOGGLE) {
                intent.putExtra(EXTRA_FOREGROUND_COMMAND, true)
                ContextCompat.startForegroundService(context, intent)
            } else {
                context.startService(intent)
            }
        }
    }

    private enum class SessionState { IDLE, STARTING, RECORDING, PROCESSING, CANCELLING, FINISHING, ERROR }

    private data class SessionPreferences(
        val cleanup: CleanupOptions = CleanupOptions(),
        val vocabularyEnabled: Boolean = true,
        val terms: List<CustomTerm> = emptyList(),
        val matcher: StructuredTermRestorer.Matcher = StructuredTermRestorer.compile(emptyList()),
        val clipboard: ClipboardInsertionPolicy = ClipboardInsertionPolicy(),
    )

    private val state = AtomicReference(SessionState.IDLE)
    private val publicationStarted = AtomicBoolean(false)
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
    private var recordingStartedAtMs = 0L
    @Volatile private var recordingDurationMs = 0L
    private var draftCreation: Deferred<Long>? = null
    private var lastElapsedSecond = -1
    @Volatile private var structuredTerms: List<CustomTerm> = emptyList()
    @Volatile private var vocabularyEnabled = true
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
    private val cleanupPreferencesReady = CompletableDeferred<Unit>()
    private val structuredTermsReady = CompletableDeferred<Unit>()

    private val transcriptRepository by lazy {
        TranscriptRepository(EnviousWisprDatabase.get(applicationContext).transcriptDao())
    }
    private val customTermRepository by lazy { CustomTermRepository(applicationContext) }

    private val audioConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            audioService = IAudioCaptureService.Stub.asInterface(binder)
            DebugLogger.log(TAG, "Audio capture connected")
            tryStartRecording()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            audioService = null
            DebugLogger.warn(TAG, "Audio capture disconnected")
            if (state.get() == SessionState.RECORDING) {
                handleServiceFailure("Microphone service stopped unexpectedly")
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
                    publishResult(
                        regexFallback(rawTranscript, sessionPreferences),
                        "Regex fallback",
                        0,
                    )
                } else if (publicationStarted.compareAndSet(false, true)) {
                    updateDraftStatus(TranscriptEntity.STATUS_ASR_ERROR, insertionResult = "asr_error")
                    showError("Speech service stopped before transcription finished")
                }
            }
        }
    }

    private val polishConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            polishService = IPolishService.Stub.asInterface(binder)
            runCatching { polishService?.warmUp() }
            DebugLogger.log(TAG, "Polish service connected")
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            polishService = null
            DebugLogger.warn(TAG, "Polish service disconnected")
            if (state.get() == SessionState.PROCESSING) {
                if (rawTranscript.isNotBlank()) {
                    publishResult(
                        regexFallback(rawTranscript, sessionPreferences),
                        "Regex fallback",
                        0,
                    )
                } else if (publicationStarted.compareAndSet(false, true)) {
                    updateDraftStatus(TranscriptEntity.STATUS_ASR_ERROR, insertionResult = "asr_error")
                    showError("Polish service stopped before cleanup finished")
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        serviceScope.launch {
            runCatching { transcriptRepository.recoverStaleOpenRows(System.currentTimeMillis()) }
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
                    vocabularyEnabled = preferences.vocabularyEnabled
                    cleanupOptions = preferences.cleanupOptions()
                    clipboardPolicy = preferences.clipboardInsertionPolicy()
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
        when (intent?.action ?: ACTION_START) {
            ACTION_CANCEL -> when (state.get()) {
                SessionState.STARTING -> cancelStarting()
                SessionState.RECORDING -> cancelRecording()
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
            ACTION_START -> if (state.get() == SessionState.IDLE) beginSession()
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun beginSession() {
        if (!state.compareAndSet(SessionState.IDLE, SessionState.STARTING)) return
        promoteToForeground(processing = false)
        // Kept for the whole session. Android may rebind the accessibility service while the user
        // is still speaking, so the state insertion finds minutes later cannot say whether this
        // dictation ever had a field to aim at (`InsertionJudgement.handoffToJudge`).
        targetPinAtStart = PasteAccessibilityService.pinTargetForDictation()
        publicationStarted.set(false)
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
                        showError("Settings could not be loaded. Try again.")
                    }
                }
                return@launch
            }
            val termsSnapshot = structuredTerms
            val matcher = withContext(Dispatchers.Default) {
                StructuredTermRestorer.compile(termsSnapshot)
            }
            withContext(Dispatchers.Main.immediate) {
                if (state.get() != SessionState.STARTING) return@withContext
                sessionPreferences = SessionPreferences(
                    cleanup = cleanupOptions,
                    vocabularyEnabled = vocabularyEnabled,
                    terms = termsSnapshot,
                    matcher = matcher,
                    // Non-null by construction: cleanupPreferencesReady, awaited above, is
                    // completed only after the line that writes this field.
                    clipboard = clipboardPolicy ?: ClipboardInsertionPolicy(),
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
            showError("Microphone service could not be connected")
            return
        }

        asrBound = runCatching {
            bindService(Intent(this, com.envi.wispr.asr.AsrService::class.java), asrConnection, Context.BIND_AUTO_CREATE)
        }.getOrDefault(false)
        if (!asrBound) {
            handleServiceFailure("Speech service could not be connected")
            return
        }

        polishBound = runCatching {
            bindService(Intent(this, PolishService::class.java), polishConnection, Context.BIND_AUTO_CREATE)
        }.getOrDefault(false)
        if (!polishBound) handleServiceFailure("Polish service could not be connected")
    }

    private fun tryStartRecording() {
        if (state.get() != SessionState.STARTING) return
        var captureStarted = false
        try {
            if (audioService?.startCapture() != true) {
                stopAudioCaptureService()
                showError("Microphone capture could not start safely")
                return
            }
            captureStarted = true
            if (!state.compareAndSet(SessionState.STARTING, SessionState.RECORDING)) {
                audioService?.stopCapture()
                return
            }
            recordingStartedAtMs = System.currentTimeMillis()
            draftCreation = serviceScope.async {
                transcriptRepository.insert(
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
            }
            DictationSurfaceState.update(this, DictationSurfaceState.Phase.LISTENING)
            RecordingOverlayState.show()
            vibrate(HapticCue.SESSION_TRANSITION)
            DebugLogger.log(TAG, "Recording started")
            startPolling()
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
            showError("Failed to start recording")
        }
    }

    private fun startPolling() {
        Thread({
            while (state.get() == SessionState.RECORDING) {
                try {
                    val service = audioService ?: break
                    val second = (service.elapsedMs / 1_000L).toInt().coerceAtLeast(0)
                    if (second != lastElapsedSecond) {
                        lastElapsedSecond = second
                        RecordingOverlayState.updateElapsed(second)
                    }
                    if (!service.isCapturing && state.get() == SessionState.RECORDING) {
                        if (service.terminalReason == AudioCaptureService.TERMINAL_REASON_ERROR) {
                            DebugLogger.error(TAG, "Audio capture ended with a terminal failure")
                            discardDraft()
                            showError("Microphone capture stopped unexpectedly. Try again.")
                        } else {
                            stopAndTranscribe()
                        }
                        break
                    }
                } catch (_: Exception) {
                    // A binder disconnect is handled by its ServiceConnection callback.
                }
                Thread.sleep(100)
            }
        }, "DictationPollingThread").start()
    }

    private fun stopAndTranscribe() {
        if (!state.compareAndSet(SessionState.RECORDING, SessionState.PROCESSING)) return
        RecordingOverlayState.hide()
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
                    showError("Audio capture did not finish safely. Try again.")
                    return@Thread
                }
                recordingDurationMs = runCatching {
                    audioService?.elapsedMs?.takeIf { it > 0L }
                        ?: (System.currentTimeMillis() - recordingStartedAtMs)
                }.getOrDefault(0L).coerceAtLeast(0L)
                val audioFilePath = audioService?.audioFilePath
                stopAudioCaptureService()

                val readyDraftId = runCatching { runBlocking { draftCreation?.await() ?: 0L } }.getOrDefault(0L)
                if (readyDraftId > 0L) {
                    draftId.set(readyDraftId)
                    updateDraftStatus(TranscriptEntity.STATUS_PROCESSING)
                }
                if (audioFilePath.isNullOrBlank()) {
                    updateDraftStatus(TranscriptEntity.STATUS_ASR_ERROR, insertionResult = "asr_error")
                    showError("No audio captured")
                    return@Thread
                }
                val speechService = asrService
                if (speechService == null) {
                    deleteCapturedAudio(audioFilePath)
                    updateDraftStatus(TranscriptEntity.STATUS_ASR_ERROR, insertionResult = "asr_error")
                    showError("Speech model is still loading. Try again in a moment.")
                    return@Thread
                }
                DebugLogger.mark(TAG, "asr_request")
                speechService.transcribeFile(audioFilePath, object : IAsrCallback.Stub() {
                    override fun onResult(text: String?) {
                        deleteCapturedAudio(audioFilePath)
                        DebugLogger.log(TAG, "Transcription result received (chars=${text?.length ?: 0})")
                        DebugLogger.mark(TAG, "result_received")
                        polishAndPublish(text.orEmpty())
                    }

                    override fun onError(message: String?) {
                        deleteCapturedAudio(audioFilePath)
                        updateDraftStatus(TranscriptEntity.STATUS_ASR_ERROR, insertionResult = "asr_error")
                        DebugLogger.error(TAG, "ASR failed")
                        showError(message?.takeIf(String::isNotBlank) ?: "Speech recognition failed")
                    }
                })
            } catch (error: Exception) {
                stopAudioCaptureService()
                if (audioReady) deleteCapturedAudio(runCatching { audioService?.audioFilePath }.getOrNull())
                DebugLogger.error(TAG, "Transcription failed", error)
                updateDraftStatus(TranscriptEntity.STATUS_ASR_ERROR, insertionResult = "asr_error")
                showError("Transcription failed")
            }
        }, "TranscribeThread").start()
    }

    private fun polishAndPublish(rawText: String) {
        rawTranscript = rawText
        if (rawText.isBlank()) {
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
                publishResult(regexFallback(rawText, takePreferences), "Regex fallback", 0)
                return@launch
            }
            try {
                service.polish(
                    preparedRaw,
                    takePreferences.cleanup.removeFillers,
                    takePreferences.cleanup.spokenEmoji,
                    takePreferences.cleanup.spokenPunctuation,
                    object : IPolishCallback.Stub() {
                        override fun onResult(text: String?, engine: String?, latencyMs: Long) {
                            publishResult(
                                restoreTakeVocabulary(text.orEmpty(), takePreferences),
                                engine.orEmpty(),
                                latencyMs,
                            )
                        }

                        override fun onError(message: String?) {
                            DebugLogger.error(TAG, "Polish failed")
                            publishResult(regexFallback(rawText, takePreferences), "Regex fallback", 0)
                        }
                    },
                )
            } catch (error: Exception) {
                DebugLogger.error(TAG, "Unable to call polish service", error)
                publishResult(regexFallback(rawText, takePreferences), "Regex fallback", 0)
            }
        }
    }

    private fun regexFallback(
        rawText: String,
        takePreferences: SessionPreferences,
    ): String {
        val prepared = restoreTakeVocabulary(rawText, takePreferences)
        val cleaned = DeterministicCleanup.apply(prepared, takePreferences.cleanup).text
        val preferred = RegexPolisher.polish(cleaned, removeFillers = takePreferences.cleanup.removeFillers)
        return restoreTakeVocabulary(preferred, takePreferences)
    }

    private fun restoreTakeVocabulary(text: String, preferences: SessionPreferences): String {
        if (!preferences.vocabularyEnabled) return text
        val restored = preferences.matcher.restore(text)
        return if (TextSafety.isSafe(text, restored)) restored else text
    }

    private fun publishResult(text: String, engine: String, latencyMs: Long) {
        if (!publicationStarted.compareAndSet(false, true)) {
            DebugLogger.warn(TAG, "Ignoring duplicate final transcript callback")
            return
        }
        val finalText = text.ifBlank { rawTranscript }
        val finalEngine = if (text.isBlank() && rawTranscript.isNotBlank()) PolishEngineLabels.RAW_FALLBACK else engine
        DebugLogger.log(TAG, "Polish result received ($finalEngine, ${latencyMs}ms, chars=${finalText.length})")
        if (finalText.isBlank()) {
            finishSession()
            return
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
                    )
                    if (updated > 0) existingId else insertReadyTranscript(finalText, finalEngine, latencyMs)
                } else {
                    insertReadyTranscript(finalText, finalEngine, latencyMs)
                }
                draftId.set(persistedId)
                persistedId
            }
            val persistedId = saveResult.getOrNull() ?: 0L
            saveResult.exceptionOrNull()?.let { error ->
                DebugLogger.warn(TAG, "Unable to save transcript history: ${error.message}")
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
                // speak: the announcement has to originate here
                // (`architecture-rules.md` RULE: insertion-fails-safe-never-silently). The routes
                // where the service DID accept the text and then failed announce themselves, in
                // PasteAccessibilityService.recordAndAnnounce.
                announceInsertionFallback(
                    handoff = handoff,
                    clipboard = clipboard,
                    savedInHistory = persistedId > 0L,
                )
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

    private suspend fun insertReadyTranscript(finalText: String, engine: String, latencyMs: Long): Long {
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
        if (!state.compareAndSet(SessionState.RECORDING, SessionState.CANCELLING)) return
        RecordingOverlayState.hide()
        PasteAccessibilityService.releasePinnedTarget()
        DictationSurfaceState.update(this, DictationSurfaceState.Phase.IDLE)
        vibrate(HapticCue.SESSION_CANCELED)
        serviceScope.launch {
            val ready = runCatching {
                audioService?.stopCapture()
                audioService?.waitForFileReady(2_000L) == true
            }.getOrDefault(false)
            if (!ready) {
                stopAudioCaptureService()
                discardDraft()
                showError("Audio capture did not finish safely. Try again.")
                return@launch
            }
            discardDraft()
            deleteCapturedAudio(runCatching { audioService?.audioFilePath }.getOrNull())
            stopAudioCaptureService()
            finishSession()
        }
    }

    private fun cancelStarting() {
        if (!state.compareAndSet(SessionState.STARTING, SessionState.CANCELLING)) return
        PasteAccessibilityService.releasePinnedTarget()
        DictationSurfaceState.update(this, DictationSurfaceState.Phase.IDLE)
        vibrate(HapticCue.SESSION_CANCELED)
        finishSession()
    }

    private fun showError(message: String) {
        if (state.getAndSet(SessionState.ERROR) == SessionState.ERROR) return
        publicationStarted.set(true)
        RecordingOverlayState.hide()
        PasteAccessibilityService.releasePinnedTarget()
        DictationSurfaceState.update(this, DictationSurfaceState.Phase.IDLE)
        vibrate(HapticCue.FAILURE)
        mainHandler.post { Toast.makeText(this, message, Toast.LENGTH_LONG).show() }
        stopAudioCaptureService()
        finishSession()
    }

    private fun handleServiceFailure(message: String) {
        if (state.get() == SessionState.RECORDING) discardDraft()
        showError(message)
    }

    private fun finishSession() {
        if (state.getAndSet(SessionState.FINISHING) == SessionState.FINISHING) return
        RecordingOverlayState.hide()
        DictationSurfaceState.update(this, DictationSurfaceState.Phase.IDLE)
        val historyUpdates = synchronized(pendingHistoryUpdates) { pendingHistoryUpdates.toList() }
        serviceScope.launch {
            historyUpdates.joinAll()
            mainHandler.post {
                unbindPipelineServices()
                stopForeground(STOP_FOREGROUND_REMOVE)
                DictationNotificationController.dismiss(this@DictationSessionService)
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
            // VibratorManager is API 31 against minSdk 30. Guarded here as well as in
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
        RecordingOverlayState.hide()
        publicationStarted.set(true)
        val destroyedState = state.get()
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
        if (destroyedState == SessionState.RECORDING && teardownStarted.compareAndSet(false, true)) {
            state.set(SessionState.ERROR)
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
