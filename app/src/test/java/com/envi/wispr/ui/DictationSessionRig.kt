package com.envi.wispr.ui

import com.envi.wispr.audio.AudioCaptureService
import com.envi.wispr.cleanup.LanguageDetector
import com.envi.wispr.history.TranscriptDao
import com.envi.wispr.history.TranscriptEntity
import com.envi.wispr.history.TranscriptRepository
import com.envi.wispr.insertion.ClipboardInsertionPolicy
import com.envi.wispr.paste.AutoPasteAvailability
import com.envi.wispr.paste.DictationTargetPin
import com.envi.wispr.paste.InsertionHandoff
import com.envi.wispr.polish.PolishFailureNotice
import com.envi.wispr.polish.PolishOutcome
import com.envi.wispr.polish.PolishPolicy
import com.envi.wispr.polish.PolishRequestIdSource
import com.envi.wispr.settings.AppPreferencesState
import com.envi.wispr.shortcuts.BubbleRequestToken
import com.envi.wispr.shortcuts.DictationSurfaceState
import com.envi.wispr.telemetry.TakeFacts
import com.envi.wispr.vocabulary.CustomTerm
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.CoroutineContext

/**
 * Harness Contract: the fakes that stand in for Android and the three helper processes so
 * [DictationSessionCoordinator] runs on the JVM (#186). Every fake records what it was asked and answers
 * what the test configured; none of them decides anything.
 *
 * One thread stands in for main: [mainDispatcher] runs inline when already on it (as `Main.immediate`
 * does on the main looper) and the host's `postToMain` always enqueues on it (as a `Handler` does).
 */
internal class DictationSessionRig {
    private val mainExecutor = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "fake-main") }
    val mainThread: Thread = mainExecutor.submit<Thread> { Thread.currentThread() }.get()

    /**
     * Which kind of task the fake main thread is running: `post` for a `postToMain` runnable, `dispatch`
     * for a coroutine the dispatcher had to enqueue, `test` for [onMain]. A call that lands INSIDE a posted
     * runnable reads `post`, which is the inline-versus-dispatched oracle the plan asked for.
     */
    val taskKind = ThreadLocal<String>()

    private fun run(kind: String, block: Runnable) {
        val previous = taskKind.get()
        taskKind.set(kind)
        try {
            block.run()
        } finally {
            taskKind.set(previous)
        }
    }

    val mainDispatcher: CoroutineDispatcher = object : CoroutineDispatcher() {
        override fun isDispatchNeeded(context: CoroutineContext): Boolean = Thread.currentThread() !== mainThread
        override fun dispatch(context: CoroutineContext, block: Runnable) {
            mainExecutor.execute { run("dispatch", block) }
        }
    }

    /**
     * One ordered record across the fakes, so a row can assert the order of events that different fakes
     * see (the picture subscription: show, listen, a picture, capture stop, stopListening, owner stop; #187).
     * Each fake keeps its own `events` too.
     */
    val timeline = CopyOnWriteArrayList<String>()

    val log = FakeLog()
    val host = FakeHost()
    val surface = FakeSurface()
    val insertion = FakeInsertion()
    val capture = FakeCapture()
    val speech = FakeSpeech()
    val polish = FakePolish()
    val pipeline = FakePipeline(capture, speech, polish)
    val dao = FakeTranscriptDao()
    val transcripts = TranscriptRepository(dao, clock = { 1_000L })
    val polishTimeout = FakePolishTimeout()
    val endings = Endings()
    val preferenceStates = MutableStateFlow(AppPreferencesState())
    val terms = MutableStateFlow<List<CustomTerm>>(emptyList())
    /** Anything a session coroutine threw: production has no handler, so a JVM-only throw would otherwise vanish. */
    val uncaught = CopyOnWriteArrayList<Throwable>()
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, error -> uncaught += error })

    var preferencesSource: SessionPreferencesSource = SessionPreferencesSource(
        preferenceStates = preferenceStates,
        terms = terms,
        migrateLegacyTerms = {},
        log = log,
    )

    fun coordinator(
        preferences: SessionPreferencesSource = preferencesSource,
        answerBoundMs: Long = 5_000L,
    ): DictationSessionCoordinator = DictationSessionCoordinator(
        host = host,
        surface = surface,
        insertion = insertion,
        log = log,
        preferences = preferences,
        transcripts = transcripts,
        languageDetector = LanguageDetector { null },
        loadPolicy = { PolishPolicy.Off },
        pipeline = pipeline,
        scope = scope,
        mainDispatcher = mainDispatcher,
        polishTimeout = polishTimeout,
        answerBoundMs = answerBoundMs,
        tipGate = BluetoothTipGate(),
        polishLedger = PolishRequestLedger(PolishRequestIdSource { System.nanoTime() }),
        endingSink = endings::record,
    )

    /** Runs [block] on the fake main thread and waits for it, as `onStartCommand` arrives on main. */
    fun onMain(block: () -> Unit) {
        mainExecutor.submit { run("test", block) }.get(5, TimeUnit.SECONDS)
    }

    /** Posts [block] to the fake main thread without waiting, for a call that blocks main on purpose (`destroy`). */
    fun postMain(block: () -> Unit) {
        mainExecutor.execute { run("test", block) }
    }

    /** A command as the Service forwards it. */
    fun command(coordinator: DictationSessionCoordinator, action: String, request: BubbleRequestToken? = null) {
        onMain { coordinator.handleCommand(action, request, TriggerSource.UNKNOWN) }
    }

    fun close() {
        mainExecutor.shutdownNow()
        capture.audioFile?.delete()
    }

    class Endings {
        val reasons = CopyOnWriteArrayList<TerminalReason>()
        /** The facts each ending was committed with (#193 reads `settingsFallback` off them). */
        val facts = CopyOnWriteArrayList<TakeFacts>()
        private val latch = CountDownLatch(1)

        fun record(facts: TakeFacts, reason: TerminalReason) {
            reasons += reason
            this.facts += facts
            latch.countDown()
        }

        /** The one reason the take ended with, or a named failure: never elapsed time as the oracle. */
        fun awaitOne(): TerminalReason {
            check(latch.await(10, TimeUnit.SECONDS)) { "the take never committed an ending; the owner is still open" }
            check(reasons.size == 1) { "the take committed ${reasons.size} endings: $reasons" }
            return reasons.single()
        }
    }

    class FakeLog : SessionLog {
        val lines = CopyOnWriteArrayList<String>()

        /** Waits for the subject to log a line containing [fragment]; the line is the subject's own signal. */
        fun awaitLine(fragment: String) {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
            while (lines.none { it.contains(fragment) }) {
                check(System.nanoTime() < deadline) { "no log line containing '$fragment'; lines: $lines" }
                Thread.sleep(5)
            }
        }
        override fun log(message: String) { lines += "I $message" }
        override fun warn(message: String) { lines += "W $message" }
        override fun error(message: String, throwable: Throwable?) { lines += "E $message" }
        override fun mark(event: String) { lines += "M $event" }
        override fun pipelineSummary(): String = "Pipeline: summary"
    }

    inner class FakeHost : SessionHost {
        val events = CopyOnWriteArrayList<String>()
        val stopped = CountDownLatch(1)
        @Volatile var clipboardWorks = true
        @Volatile var autoPaste = AutoPasteAvailability.LIVE

        override fun promoteToForeground(processing: Boolean) { events += "foreground:$processing" }
        override fun updateSurfacePhase(phase: DictationSurfaceState.Phase) { events += "phase:${phase.name}" }
        override fun vibrate(cue: HapticCue) { events += "vibrate:${cue.name}" }
        override fun toastFromService(line: String) { events += "toast:$line" }
        private val applicationToast = CountDownLatch(1)
        override fun toastFromApplication(line: String) {
            events += "toast-app:$line@${taskKind.get()}"
            applicationToast.countDown()
        }

        /** A line was said after the recorder had gone; the inline-versus-posted row waits on it. */
        fun awaitApplicationToast() {
            check(applicationToast.await(10, TimeUnit.SECONDS)) { "the application toast never fired; events: $events" }
        }
        override fun showPolishNotice(notice: PolishFailureNotice) { events += "polish-notice" }
        override fun copyToClipboard(text: String): Boolean {
            events += "clipboard:$text"
            return clipboardWorks
        }
        override fun autoPasteAvailability(): AutoPasteAvailability = autoPaste
        override fun removeForegroundAndDismiss() { events += "foreground-removed" }
        override fun stopSelfNow() {
            events += "stopSelf"
            timeline += "owner-stop"
            stopped.countDown()
        }
        override fun postToMain(runnable: Runnable) { mainExecutor.execute { run("post", runnable) } }

        /** Delayed posts, in order, never fired by a clock: [fireDelayed] runs them on the fake main thread. */
        val delayed = CopyOnWriteArrayList<Pair<Long, Runnable>>()
        /** Every delayed post ever made, by delay; a re-arm is a cancel and a NEW post with the same delay. */
        val delayedPostLog = CopyOnWriteArrayList<Long>()
        fun postsWithDelay(delayMs: Long): Int = delayedPostLog.count { it == delayMs }
        override fun postToMainDelayed(delayMs: Long, runnable: Runnable) { delayedPostLog += delayMs; delayed += delayMs to runnable }
        override fun cancelMainDelayed(runnable: Runnable) { delayed.removeIf { it.second === runnable } }

        /**
         * Test time passes: every delayed post with [delayMs] due fires, on main, in the order it was
         * posted. Selected and removed ON main, as a Handler would, so a re-arm racing the test thread
         * cannot leave the fired entry behind or fire the re-posted one twice.
         */
        fun fireDelayed(delayMs: Long) {
            onMain {
                val due = delayed.filter { it.first == delayMs }
                delayed.removeAll(due)
                due.forEach { (_, runnable) -> runnable.run() }
            }
        }
        override fun onMainThread(): Boolean = Thread.currentThread() === mainThread
        override fun elapsedRealtimeMs(): Long = System.nanoTime() / 1_000_000L

        /** The Service stopped itself, which every terminal path ends in. */
        fun awaitStopped() {
            check(stopped.await(10, TimeUnit.SECONDS)) { "the owner never stopped the Service; events so far: $events" }
        }
    }

    inner class FakeSurface : RecorderSurface {
        val events = CopyOnWriteArrayList<String>()
        private val serial = AtomicLong(0L)
        private val shown = CountDownLatch(1)

        /** The pill appeared, which `publishLive` does on the RECORDING transition. */
        fun awaitShown() {
            check(shown.await(10, TimeUnit.SECONDS)) { "the take never went live; surface events so far: $events" }
        }
        override fun showStarting(token: BubbleRequestToken?) { events += "starting" }
        override fun nameTarget(fieldId: String?) { events += "target:$fieldId" }
        override fun attachTranscript(id: Long) { events += "transcript:$id" }
        override fun show() {
            serial.incrementAndGet()
            events += "show"
            timeline += "show"
            shown.countDown()
        }
        override fun showProcessing() { events += "processing" }
        override fun showNotice(text: String) { events += "notice:$text" }
        /** Every picture the owner published, with the serial it stamped. */
        val pictures = CopyOnWriteArrayList<Pair<Long, FloatArray>>()
        override fun updateElapsed(seconds: Int) {}
        override fun updateBands(takeSerial: Long, bands: FloatArray) {
            pictures += takeSerial to bands.copyOf()
            timeline += "updateBands:$takeSerial"
        }
        override fun hide() { events += "hide" }
        override fun currentTakeSerial(): Long = serial.get()
    }

    class FakeInsertion : InsertionGateway {
        @Volatile var pin = DictationTargetPin.PINNED
        @Volatile var handoff = InsertionHandoff.SCHEDULED
        @Volatile var bound = true
        val pastes = CopyOnWriteArrayList<Pair<Long, String>>()
        val releases = AtomicLong(0L)
        /** Every pin the owner takes; the owner's contract is exactly one per admitted take (#192). */
        val pins = AtomicLong(0L)
        override fun pinTargetForDictation(): DictationTargetPin {
            pins.incrementAndGet()
            return pin
        }
        override fun pinnedFieldId(): String? = "field-1"
        override fun releasePinnedTarget() { releases.incrementAndGet() }
        override fun pasteWhenTargetReturns(transcriptId: Long, text: String, policy: ClipboardInsertionPolicy, takeId: String): InsertionHandoff {
            pastes += transcriptId to text
            return handoff
        }
        override fun isBound(): Boolean = bound
    }

    /**
     * The capture process as the owner sees it (#115): three commands in, the take's events out. Like the
     * audio process it PUSHES: a successful start publishes live (unless [liveStateAfterStart] holds it
     * WAITING), a stop or a cancel publishes the ending with the closed file, and a test can end the take on
     * its own or go silent. Every push runs on its own thread, as a binder thread would.
     * `startCaptureForTake` writes a small PCM file for the stop path to measure.
     */
    inner class FakeCapture : CaptureLink {
        @Volatile var startResult = true
        @Volatile var startFailure = AudioCaptureService.START_FAILURE_NONE
        @Volatile var liveStateAfterStart = AudioCaptureService.LIVE_READY
        /** When set, the ending is held until the test opens it: holds a cancel or a stop waiting for the file. */
        @Volatile var endingGate: CountDownLatch? = null
        /** When true the process is WEDGED: no event leaves it after live, whatever the owner asks. */
        @Volatile var silent = false
        @Volatile var ending = AudioCaptureService.TERMINAL_REASON_MANUAL
        @Volatile var peak: Float = 0.5f
        @Volatile var capturing = false
        @Volatile var audioFile: File? = null
        val events = CopyOnWriteArrayList<String>()
        private val started = CountDownLatch(1)
        private val stopRequested = CountDownLatch(1)
        @Volatile private var takeListener: TakeListener? = null
        private val ended = java.util.concurrent.atomic.AtomicBoolean(false)

        /** The owner asked capture to start. */
        fun awaitStarted() {
            check(started.await(10, TimeUnit.SECONDS)) { "capture was never asked to start; events: $events" }
        }

        /** The owner asked capture to stop. */
        fun awaitStopRequested() {
            check(stopRequested.await(10, TimeUnit.SECONDS)) { "capture was never asked to stop; events: $events" }
        }

        /** The settings each start call carried, as one literal per call (#193). */
        val startArguments = CopyOnWriteArrayList<String>()
        override fun startCaptureForTake(autoStopOnSilence: Boolean, pauseSeconds: Float, inputDevicePick: String, keepEarbudsReady: Boolean, takeId: String): Boolean {
            events += "start"
            startArguments += "start(autoStop=$autoStopOnSilence, pause=$pauseSeconds)"
            if (!startResult) {
                started.countDown()
                // A refused start publishes its own ending with the failure code and no file (#115).
                push { it.onEnded(TakeEnding(AudioCaptureService.TERMINAL_REASON_NONE, startFailure, null, AudioCaptureService.SILENCE_STATUS_DISABLED, 0f, "")) }
                return false
            }
            audioFile = File.createTempFile("take", ".pcm").apply { writeBytes(ByteArray(32_000)) }
            capturing = true
            started.countDown()
            when (liveStateAfterStart) {
                AudioCaptureService.LIVE_READY -> push { it.onLive(false, 0, 0, 120L); it.onTick(0L) }
                AudioCaptureService.LIVE_FORCED -> push { it.onLive(true, 0, 0, 120L); it.onTick(0L) }
                else -> Unit
            }
            return true
        }

        override fun stopCapture() {
            events += "stop"
            timeline += "capture-stop"
            capturing = false
            stopRequested.countDown()
            publishEnding(ending)
        }

        override fun finishTake(): Boolean {
            events += "finishTake"
            return false
        }

        /** The capture loop ended the take itself: silence, the cap, an error, or one before live. */
        fun endOnItsOwn(reason: Int) {
            capturing = false
            publishEnding(reason)
        }

        /** A heartbeat from the capture loop, as the audio process sends one each second. */
        fun tick(elapsedMs: Long) {
            push { it.onTick(elapsedMs) }
        }

        /** The silence detector's status changed. */
        fun silenceStatus(status: Int) {
            push { it.onSilenceStatus(status) }
        }

        private fun publishEnding(reason: Int) {
            if (!ended.compareAndSet(false, true)) return
            push {
                endingGate?.await(10, TimeUnit.SECONDS)
                it.onEnded(TakeEnding(reason, startFailure, audioFile?.path, AudioCaptureService.SILENCE_STATUS_DISABLED, peak, "Phone microphone"))
            }
        }

        private fun push(event: (TakeListener) -> Unit) {
            if (silent) return
            val target = takeListener ?: return
            Thread({ event(target) }, "fake-audio-binder").start()
        }

        /** The listener the owner registered, so a test can push a picture through it as the audio process would. */
        @Volatile var spectrumListener: SpectrumListener? = null
        private val listening = CountDownLatch(1)

        /** The listener the owner registered, once `listenForPicture` ran; a loud failure if it never did. */
        fun awaitListener(): SpectrumListener {
            check(listening.await(10, TimeUnit.SECONDS)) { "the owner never registered for the picture; events: $events" }
            return checkNotNull(spectrumListener)
        }
        override fun listenForSpectrum(listener: SpectrumListener) {
            spectrumListener = listener
            events += "listen"
            timeline += "listen"
            listening.countDown()
        }
        override fun listenForTake(listener: TakeListener) {
            takeListener = listener
            events += "listenForTake"
        }
    }

    /** The speech process: the test answers the request by hand, as a binder thread would. */
    class FakeSpeech : SpeechLink {
        @Volatile var listener: SpeechListener? = null
        private val requested = CountDownLatch(1)
        override fun transcribeFileForTake(audioFilePath: String, takeId: String, listener: SpeechListener) {
            this.listener = listener
            requested.countDown()
        }
        fun awaitRequest(): SpeechListener {
            check(requested.await(10, TimeUnit.SECONDS)) { "the owner never asked the speech process" }
            return checkNotNull(listener)
        }
    }

    /** The polish process: the test answers the request by hand. */
    class FakePolish : PolishLink {
        @Volatile var listener: PolishListener? = null
        @Volatile var requestId = 0L
        @Volatile var throwOnRequest = false
        val cancelled = CopyOnWriteArrayList<Long>()
        val warmed = CopyOnWriteArrayList<PolishPolicy>()
        private val requested = CountDownLatch(1)
        /** The raw text the owner handed the engine, after vocabulary restoration (#193). */
        @Volatile var lastRawText: String? = null
        override fun warmUpWithPolicy(policy: PolishPolicy) { warmed += policy }
        override fun polishRequestForTake(requestId: Long, rawText: String, removeFillers: Boolean, spokenEmoji: Boolean, spokenPunctuation: Boolean, policy: PolishPolicy, takeId: String, listener: PolishListener) {
            if (throwOnRequest) throw IllegalStateException("engine gone")
            lastRawText = rawText
            this.requestId = requestId
            this.listener = listener
            requested.countDown()
        }
        override fun cancel(requestId: Long) { cancelled += requestId }
        fun awaitRequest(diagnostics: () -> String = { "" }): PolishListener {
            check(requested.await(10, TimeUnit.SECONDS)) { "the owner never asked the polish process. ${diagnostics()}" }
            return checkNotNull(listener)
        }
        fun outcome(text: String, reason: com.envi.wispr.polish.PolishReason = com.envi.wispr.polish.PolishReason.POLISHED) =
            PolishOutcome(requestId = requestId, text = text, engine = "Fake engine", reason = reason, statusCode = 0, latencyMs = 12L)
    }

    /** The three connections: `bind` connects all three on the fake main thread, as the platform would. */
    inner class FakePipeline(
        override val capture: CaptureLink?,
        @Volatile override var speech: SpeechLink?,
        override val polish: PolishLink?,
    ) : PipelineController {
        @Volatile var bindResult = PipelineController.BindResult.BOUND
        @Volatile var connectSpeech = true
        @Volatile var listener: PipelineController.Listener? = null
        val events = CopyOnWriteArrayList<String>()

        override fun bind(listener: PipelineController.Listener): PipelineController.BindResult {
            this.listener = listener
            events += "bind"
            if (bindResult != PipelineController.BindResult.BOUND) return bindResult
            mainExecutor.execute {
                run("post") {
                    listener.onCaptureConnected()
                    if (connectSpeech) listener.onSpeechConnected()
                    listener.onPolishConnected()
                }
            }
            return bindResult
        }
        override fun unbind() {
            events += "unbind"
            timeline += "unbind"
        }
        override fun postUnbindToMain(beforeUnbind: () -> Unit) { mainExecutor.execute { run("post") { beforeUnbind(); unbind() } } }
        override fun stopAudioService() { events += "stopAudioService" }

        /** The platform reporting a helper's death, on main. */
        fun disconnect(which: String) = onMain {
            when (which) {
                "capture" -> listener?.onCaptureDisconnected()
                "speech" -> listener?.onSpeechDisconnected()
                "polish" -> listener?.onPolishDisconnected()
                else -> error(which)
            }
        }
    }

    class FakePolishTimeout : PolishTimeout {
        private val release = CountDownLatch(1)
        override suspend fun await(policy: PolishPolicy) {
            kotlinx.coroutines.runInterruptible { release.await() }
        }
        fun fire() = release.countDown()
    }

    /**
     * History as rows in memory; the repository above is the real one. Every UPDATE is one atomic
     * `computeIfPresent`, as a Room UPDATE is: a read-then-put here let a status update racing a
     * delete resurrect the deleted row on a slow runner (2026-09-20), which SQLite cannot do.
     */
    class FakeTranscriptDao : TranscriptDao {
        val rows = java.util.concurrent.ConcurrentHashMap<Long, TranscriptEntity>()
        private val nextId = AtomicLong(1L)
        @Volatile var failInserts = false

        override fun observeAll(): Flow<List<TranscriptEntity>> = flowOf(rows.values.toList())
        override suspend fun insert(transcript: TranscriptEntity): Long {
            if (failInserts) throw IllegalStateException("disk full")
            val id = nextId.getAndIncrement()
            rows[id] = transcript.copy(id = id)
            return id
        }
        override suspend fun setKept(id: Long, kept: Boolean) { rows.computeIfPresent(id) { _, row -> row.copy(kept = kept) } }
        override suspend fun delete(transcript: TranscriptEntity) { rows.remove(transcript.id) }
        override suspend fun deleteAll() = rows.clear()
        override suspend fun deleteById(id: Long): Int = if (rows.remove(id) != null) 1 else 0
        override suspend fun deleteWordlessRows(): Int = 0
        override suspend fun updateStatus(id: Long, status: String, stateChangedAtMs: Long, interrupted: Boolean, insertionResult: String?): Int {
            return if (rows.computeIfPresent(id) { _, row -> row.copy(status = status, stateChangedAtMs = stateChangedAtMs, interrupted = interrupted, insertionResult = insertionResult ?: row.insertionResult) } != null) 1 else 0
        }
        override suspend fun finalize(id: Long, originalText: String, finalText: String, speechEngine: String, polishEngine: String, polishLatencyMs: Long, insertionResult: String, durationMs: Long, stateChangedAtMs: Long, polishReason: String, polishStatus: Int, polishContext: String, captureDevice: String, status: String, interrupted: Boolean): Int {
            if (failInserts) throw IllegalStateException("disk full")
            return if (rows.computeIfPresent(id) { _, row -> row.copy(originalText = originalText, finalText = finalText, speechEngine = speechEngine, polishEngine = polishEngine, polishLatencyMs = polishLatencyMs, insertionResult = insertionResult, durationMs = durationMs, stateChangedAtMs = stateChangedAtMs, polishReason = polishReason, polishStatus = polishStatus, polishContext = polishContext, captureDevice = captureDevice, status = status, interrupted = interrupted) } != null) 1 else 0
        }
        override suspend fun finalizeInsertionOutcome(id: Long, status: String, result: String, stateChangedAtMs: Long, interrupted: Boolean): Int {
            var updated = 0
            rows.computeIfPresent(id) { _, row ->
                if (row.status != TranscriptEntity.STATUS_READY_FOR_INSERTION || row.insertionResult != "pending") {
                    row
                } else {
                    updated = 1
                    row.copy(status = status, insertionResult = result, stateChangedAtMs = stateChangedAtMs, interrupted = interrupted)
                }
            }
            return updated
        }
        override suspend fun recoverStaleDrafts(cutoffMs: Long, nowMs: Long): Int = 0
        override suspend fun recoverStaleReadyRows(cutoffMs: Long, nowMs: Long): Int = 0
        override suspend fun staleReadyRowIds(cutoffMs: Long): List<Long> = emptyList()
    }
}
