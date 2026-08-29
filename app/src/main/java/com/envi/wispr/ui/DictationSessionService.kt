package com.envi.wispr.ui

import android.app.Service
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.ServiceInfo
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.VibrationEffect
import android.os.VibratorManager
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
import com.envi.wispr.paste.PasteAccessibilityService
import com.envi.wispr.polish.IPolishCallback
import com.envi.wispr.polish.IPolishService
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
    private var recordingStartedAtMs = 0L
    @Volatile private var recordingDurationMs = 0L
    private var draftCreation: Deferred<Long>? = null
    private var lastElapsedSecond = -1
    @Volatile private var structuredTerms: List<CustomTerm> = emptyList()
    @Volatile private var vocabularyEnabled = true
    @Volatile private var cleanupOptions = CleanupOptions()
    @Volatile private var clipboardPolicy = ClipboardInsertionPolicy()
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
        PasteAccessibilityService.pinTargetForDictation()
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
                    clipboard = clipboardPolicy,
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
                        polishEngine = "",
                        polishLatencyMs = 0L,
                        insertionResult = "pending",
                        status = TranscriptEntity.STATUS_DRAFT,
                    ),
                )
            }
            DictationSurfaceState.update(this, DictationSurfaceState.Phase.LISTENING)
            RecordingOverlayState.show()
            vibrate(confirm = true)
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
                            markDraftInterrupted()
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
        vibrate(confirm = true)
        DebugLogger.log(TAG, "Stopping recording and starting transcription")

        Thread({
            var audioReady = false
            try {
                audioService?.stopCapture()
                audioReady = runCatching { audioService?.waitForFileReady(2_000L) == true }.getOrDefault(false)
                if (!audioReady) {
                    stopAudioCaptureService()
                    markDraftInterrupted()
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
            updateDraftStatus(TranscriptEntity.STATUS_NO_SPEECH, insertionResult = "no_speech")
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
        val finalEngine = if (text.isBlank() && rawTranscript.isNotBlank()) "Raw fallback" else engine
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
            val scheduled = if (route == HistoryPublicationPolicy.Route.AUTO_INSERT) {
                PasteAccessibilityService.pasteWhenTargetReturns(
                    persistedId,
                    finalText,
                    policy = sessionPreferences.clipboard,
                )
            } else {
                false
            }
            if (!scheduled) {
                PasteAccessibilityService.releasePinnedTarget()
                val mustPreventDataLoss = persistedId <= 0L
                if (sessionPreferences.clipboard.autoCopyToClipboard || mustPreventDataLoss) {
                    val clipboard = getSystemService(ClipboardManager::class.java)
                    keepOnClipboard(clipboard, persistedId, finalText)
                } else {
                    keepInHistoryOnly(persistedId)
                    mainHandler.post {
                        Toast.makeText(
                            this@DictationSessionService,
                            "Automatic insertion unavailable. Transcript saved in History.",
                            Toast.LENGTH_LONG,
                        ).show()
                    }
                }
            }
            DebugLogger.log(
                TAG,
                when {
                    scheduled -> "Auto-insert handed to accessibility target tracker"
                    route == HistoryPublicationPolicy.Route.COPY_ONLY ->
                        "History persistence unavailable; transcript kept on clipboard only"
                    sessionPreferences.clipboard.autoCopyToClipboard || persistedId <= 0L ->
                        "Accessibility unavailable; transcript kept on clipboard"
                    else -> "Accessibility unavailable; transcript retained in History"
                },
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

    private suspend fun keepOnClipboard(
        clipboard: ClipboardManager,
        transcriptId: Long,
        text: String,
    ) {
        val copied = runCatching {
            clipboard.setPrimaryClip(ClipData.newPlainText("EnviousWispr", text))
        }.isSuccess
        if (transcriptId <= 0L) return

        runCatching {
            transcriptRepository.finalizeInsertionOutcome(
                transcriptId,
                TranscriptEntity.STATUS_INSERTION_INTERRUPTED,
                if (copied) "clipboard" else "insertion_failed",
                interrupted = true,
            )
        }.onFailure { error ->
            DebugLogger.warn(TAG, "Unable to finalize clipboard-only history: ${error.message}")
        }
    }

    private suspend fun keepInHistoryOnly(transcriptId: Long) {
        if (transcriptId <= 0L) return
        runCatching {
            transcriptRepository.finalizeInsertionOutcome(
                transcriptId,
                TranscriptEntity.STATUS_INSERTION_INTERRUPTED,
                "history_only",
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
        vibrate(confirm = false)
        serviceScope.launch {
            val ready = runCatching {
                audioService?.stopCapture()
                audioService?.waitForFileReady(2_000L) == true
            }.getOrDefault(false)
            if (!ready) {
                stopAudioCaptureService()
                markDraftInterrupted()
                showError("Audio capture did not finish safely. Try again.")
                return@launch
            }
            setDraftStatus(TranscriptEntity.STATUS_CANCELED, insertionResult = "canceled")
            deleteCapturedAudio(runCatching { audioService?.audioFilePath }.getOrNull())
            stopAudioCaptureService()
            finishSession()
        }
    }

    private fun cancelStarting() {
        if (!state.compareAndSet(SessionState.STARTING, SessionState.CANCELLING)) return
        PasteAccessibilityService.releasePinnedTarget()
        DictationSurfaceState.update(this, DictationSurfaceState.Phase.IDLE)
        vibrate(confirm = false)
        finishSession()
    }

    private fun showError(message: String) {
        if (state.getAndSet(SessionState.ERROR) == SessionState.ERROR) return
        publicationStarted.set(true)
        RecordingOverlayState.hide()
        PasteAccessibilityService.releasePinnedTarget()
        DictationSurfaceState.update(this, DictationSurfaceState.Phase.IDLE)
        vibrate(confirm = false)
        mainHandler.post { Toast.makeText(this, message, Toast.LENGTH_LONG).show() }
        stopAudioCaptureService()
        finishSession()
    }

    private fun handleServiceFailure(message: String) {
        if (state.get() == SessionState.RECORDING) markDraftInterrupted()
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
            DictationNotificationController.listening(this)
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

    private fun markDraftInterrupted() {
        updateDraftStatus(TranscriptEntity.STATUS_INTERRUPTED, interrupted = true)
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

    private fun vibrate(confirm: Boolean) {
        runCatching {
            val vibrator = getSystemService(VibratorManager::class.java).defaultVibrator
            if (vibrator.hasVibrator()) {
                vibrator.vibrate(
                    VibrationEffect.createOneShot(
                        if (confirm) 28L else 45L,
                        if (confirm) 120 else 180,
                    ),
                )
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
