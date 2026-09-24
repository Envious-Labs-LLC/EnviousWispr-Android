package com.envi.wispr.ui

import com.envi.wispr.history.HistoryRow
import com.envi.wispr.providers.PolicyRead
import com.envi.wispr.audio.AudioCaptureService
import com.envi.wispr.cleanup.LanguageDetector
import com.envi.wispr.history.HistoryWriteQueue
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
    /** The application-owned History queue (#115), here on its own scope like the real one; tests drain it through [awaitHistoryIdle]. */
    val historyWrites = HistoryWriteQueue(transcripts, scope = CoroutineScope(SupervisorJob() + Dispatchers.IO), warn = { line -> log.warn(line) })

    /**
     * Every History write queued so far has been applied: a marker write is queued and awaited, and the
     * queue is one worker in enqueue order. The rows' assertions read the DAO after this.
     */
    fun awaitHistoryIdle() {
        val landed = CountDownLatch(1)
        check(historyWrites.enqueue("test marker", com.envi.wispr.history.WriteKind.TERMINAL) { landed.countDown() } == com.envi.wispr.history.Enqueued.ACCEPTED) { "the History queue refused a write" }
        check(landed.await(10, TimeUnit.SECONDS)) { "the History queue never drained; log: ${log.lines}" }
    }
    val polishTimeout = FakePolishTimeout()
    val endings = Endings()
    val preferenceStates = MutableStateFlow(AppPreferencesState())
    val terms = MutableStateFlow<List<CustomTerm>>(emptyList())
    /** Anything a session coroutine threw: production has no handler, so a JVM-only throw would otherwise vanish. */
    val uncaught = CopyOnWriteArrayList<Throwable>()
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, error -> uncaught += error })

    /**
     * The History save observer's own scope (#304), never the owner's: production's observer is application-owned,
     * so destroying the owner must not end a save's diagnostics.
     */
    val saveScope = CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, error -> uncaught += error })

    /** The observer's breadcrumbs, as (message, data) (#304). */
    val breadcrumbs = java.util.concurrent.CopyOnWriteArrayList<Pair<String, Map<String, Any?>>>()

    /** When set, the observer's warn sink throws (#304 row 2c). */
    @Volatile var throwOnSaveWarn = false

    var preferencesSource: SessionPreferencesSource = SessionPreferencesSource(
        preferenceStates = preferenceStates,
        terms = terms,
        migrateLegacyTerms = {},
        log = log,
    )

    fun coordinator(
        preferences: SessionPreferencesSource = preferencesSource,
        answerBoundMs: Long = 5_000L,
        /** The observer's bound (#304); generous by default so no healthy row races it; the #235 rows set a short one. */
        historySaveBoundMs: Long = 5_000L,
        /** The observer's scope: the rig's own by default, never the owner's (#304). */
        historySaveScope: CoroutineScope = saveScope,
        /** Abstains by default; the #252 row passes one that throws. */
        languageDetector: LanguageDetector = LanguageDetector { null },
        /** Runs each captured-audio delete at once by default; the #253 row holds it past a teardown. */
        audioCleanup: (Runnable) -> Unit = { it.run() },
        /** No journal by default, as before; the #258 rows pass an admission they complete themselves. */
        admit: (String, TriggerSource) -> kotlinx.coroutines.Deferred<Boolean>? = { _, _ -> null },
        /** Generous by default; the #290 rows shorten it against a held matcher or policy. */
        preparationBoundMs: Long = 5_000L,
        /** Production's compile by default; the #290 rows throw or hold it. */
        compileMatcher: (List<CustomTerm>) -> com.envi.wispr.vocabulary.StructuredTermRestorer.Matcher = com.envi.wispr.vocabulary.StructuredTermRestorer::compile,
    ): DictationSessionCoordinator = DictationSessionCoordinator(
        host = host,
        surface = surface,
        notices = SessionNoticePresenter(surface, insertion, host, scope, mainDispatcher, log, BluetoothTipGate()),
        insertion = insertion,
        log = log,
        preferences = preferences,
        historyWrites = historyWrites,
        historySaves = HistorySaveObserver(
            scope = historySaveScope,
            clock = { host.elapsedRealtimeMs() },
            warn = { line -> if (throwOnSaveWarn) throw IllegalStateException("warn broke"); log.warn(line) },
            defectSink = { defect, data -> if (throwOnDefect) throw IllegalStateException("sink broke"); defects += defect.fingerprint to data },
            breadcrumb = { _, message, data -> breadcrumbs += message to data },
            boundMs = historySaveBoundMs,
        ),
        transcripts = transcripts,
        languageDetector = languageDetector,
        loadPolicy = {
            try {
                policyEntered.countDown()
                policyHold?.await()
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                policyCancelled = true
                throw cancelled
            }
            policyAnswering?.invoke()
            policyRead ?: PolicyRead.Fresh(polishPolicy)
        },
        lastReadPolicy = { lastReadPolicy },
        pipeline = pipeline,
        scope = scope,
        mainDispatcher = mainDispatcher,
        polishTimeout = polishTimeout,
        answerBoundMs = answerBoundMs,
        polishLedger = PolishRequestLedger(PolishRequestIdSource { System.nanoTime() }),
        endingSink = endings::record,
        defectSink = { defect, data -> if (throwOnDefect) throw IllegalStateException("sink broke"); defects += defect.fingerprint to data },
        audioCleanup = audioCleanup,
        admitTake = admit,
        preparationBoundMs = preparationBoundMs,
        compileMatcher = compileMatcher,
    )

    /** When set, the owner's defect sink throws (#252: a broken report must never stop the words). */
    @Volatile var throwOnDefect = false

    /** The polish policy each take loads; Off unless a test sets another (#234 notice rows). */
    @Volatile var polishPolicy: PolishPolicy = PolishPolicy.Off
    /** When set, the policy read suspends on it before answering (#290: a read that never answers). */
    @Volatile var policyHold: kotlinx.coroutines.CompletableDeferred<Unit>? = null
    /** Counted down when the policy read has started (#290 review round 2: a row cancels only once it is in its held read). */
    val policyEntered = CountDownLatch(1)
    /** Set when a held policy read was cancelled (#290 review: a cancel of the starting take stops it). */
    @Volatile var policyCancelled = false
    /** Runs as the policy read answers, on its thread (#290 review: a row moves the clock past the deadline there). */
    @Volatile var policyAnswering: (() -> Unit)? = null
    /** The process's last read policy the owner falls back to when the read misses its bound (#290). */
    @Volatile var lastReadPolicy: PolishPolicy? = null
    /** When set, the read the owner gets instead of `Fresh(polishPolicy)` (#278). */
    @Volatile var policyRead: PolicyRead? = null

    /** Every defect the owner raised, by fingerprint, with its data (#214). */
    val defects = CopyOnWriteArrayList<Pair<String, Map<String, Any?>>>()

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
        saveScope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
        mainExecutor.shutdownNow()
        capture.close()
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

        private val appended = Object()

        private fun append(line: String) {
            synchronized(appended) {
                lines += line
                appended.notifyAll()
            }
        }

        /**
         * Waits for the subject to log [count] lines containing [fragment]; the line is the subject's own signal.
         * Woken by every append, never by a clock; the deadline only makes a regression fail instead of hang.
         */
        fun awaitLine(fragment: String, count: Int = 1) {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
            synchronized(appended) {
                while (count(fragment) < count) {
                    val left = deadline - System.nanoTime()
                    check(left > 0) { "no log line containing '$fragment'; lines: $lines" }
                    TimeUnit.NANOSECONDS.timedWait(appended, left)
                }
            }
        }
        fun count(fragment: String) = lines.count { it.contains(fragment) }
        override fun log(message: String) = append("I $message")
        override fun warn(message: String) = append("W $message")
        override fun error(message: String, throwable: Throwable?) = append("E $message")
        override fun mark(event: String) = append("M $event")
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
        /** When set, every clock read answers from it, so a row can script time without a real clock (#258). */
        @Volatile var clock: (() -> Long)? = null
        override fun elapsedRealtimeMs(): Long = clock?.invoke() ?: (System.nanoTime() / 1_000_000L)

        /**
         * The Service stopped itself, which every terminal path ends in. Then the History queue is drained:
         * since #115 the Service may stop while the take's last write is still landing on the application's
         * worker, and a row read before that is a row read too early.
         */
        fun awaitStopped() {
            awaitServiceStopped()
            awaitHistoryIdle()
        }

        /** The Service stopped, without waiting for History: for the #235 rows that hold a save on the queue. */
        fun awaitServiceStopped() {
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
        /** Every elapsed second the owner published, apart from [events] so no existing event list changes (#280). */
        val elapsedUpdates = CopyOnWriteArrayList<Int>()
        override fun updateElapsed(seconds: Int) { elapsedUpdates += seconds }
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
        /** Every handoff, with the take's History handle as the owner passed it (#277). */
        val requests = CopyOnWriteArrayList<Pair<HistoryRow, String>>()
        /** Each handoff's saved row id, resolved when read (as the paste service resolves it on the queue), and its text. */
        val pastes: List<Pair<Long, String>> get() = requests.map { (row, text) -> row.resolveOnQueue() to text }
        /** Whether the save had answered SAVED at the moment of each handoff (#277: the owner no longer waits for it). */
        val savedAtHandoff = CopyOnWriteArrayList<Boolean>()
        val releases = AtomicLong(0L)
        /** Every pin the owner takes; the owner's contract is exactly one per admitted take (#192). */
        val pins = AtomicLong(0L)
        override fun pinTargetForDictation(): DictationTargetPin {
            pins.incrementAndGet()
            return pin
        }
        override fun pinnedFieldId(): String? = "field-1"
        override fun releasePinnedTarget() { releases.incrementAndGet() }
        /**
         * When set, a scheduled handoff writes its PASTED outcome through the row handle on this queue, as the paste
         * service does (#277): at the handoff, or when [releaseOutcome] is called if [deferOutcome] is set.
         */
        @Volatile var outcomeQueue: com.envi.wispr.history.HistoryWriteQueue? = null
        @Volatile var deferOutcome = false
        private val deferred = CopyOnWriteArrayList<HistoryRow>()
        private fun enqueueOutcome(row: HistoryRow) {
            outcomeQueue?.enqueue("fake insertion outcome") { repository ->
                val id = row.resolveOnQueue()
                if (id > 0L) repository.finalizeInsertionOutcome(id, TranscriptEntity.STATUS_COMPLETED, com.envi.wispr.insertion.InsertionResults.PASTED)
            }
        }
        fun releaseOutcome() = deferred.forEach(::enqueueOutcome).also { deferred.clear() }
        override fun pasteWhenTargetReturns(row: HistoryRow, text: String, policy: ClipboardInsertionPolicy, takeId: String): InsertionHandoff {
            requests += row to text
            savedAtHandoff += row.savedNow
            if (handoff == InsertionHandoff.SCHEDULED && outcomeQueue != null) {
                if (deferOutcome) deferred += row else enqueueOutcome(row)
            }
            return handoff
        }
        override fun isBound(): Boolean = bound
    }

    /**
     * The capture process as the owner sees it (#115): three commands in, the take's events out. Like the
     * audio process it PUSHES: a successful start publishes live (unless [liveStateAfterStart] holds it
     * WAITING), a stop or a cancel publishes the ending with the closed file, and a test can end the take on
     * its own or go silent. Every push runs on ONE thread in the order pushed, as the audio process's one
     * publisher worker delivers them (review round 1, F7). `startCaptureForTake` writes a small PCM file
     * for the stop path to measure. Every event carries the id the start was given; [endOnItsOwn] can
     * push another take's ending, which the owner must discard.
     */
    inner class FakeCapture : CaptureLink {
        private val binderThread = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "fake-audio-binder") }
        /** The id the owner passed to the last start; the fake's events carry it. */
        @Volatile var currentTakeId = ""
        fun close() = binderThread.shutdownNow()

        /**
         * Wait until every event pushed so far has reached the fake main thread AND run there (#210). An
         * event travels this fake's binder thread, then the owner's `postToMain`; draining main alone can
         * finish before the binder thread has handed the event over, which failed `aHeartbeatRearmsTheBound`
         * on the hosted runner and lets a "nothing happened" row pass before the event exists. It is the
         * ONE drain the session rows use: no row drains main alone, and every signal of this fake fires
         * after the push it causes, so a wait followed by this cannot read early.
         */
        fun settle() {
            binderThread.submit {}.get(5, TimeUnit.SECONDS)
            onMain {}
        }
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
        /** The thread each command arrived on; the owner's contract is one lane, never main (#115). */
        val commandThreads = CopyOnWriteArrayList<String>()

        /** Live, pushed by hand for a take that was held WAITING. */
        fun pushLive(takeId: String) {
            push { it.onLive(takeId, false, liveRouteKind, 0, 120L); it.onTick(takeId, 0L) }
        }

        /** The route kind every live event carries (`InputRouteKind.code`); 0 unless a row needs the Bluetooth tip (#256). */
        @Volatile var liveRouteKind = 0

        override fun startCaptureForTake(autoStopOnSilence: Boolean, pauseSeconds: Float, inputDevicePick: String, keepEarbudsReady: Boolean, takeId: String): Boolean {
            events += "start"
            commandThreads += Thread.currentThread().name
            currentTakeId = takeId
            startArguments += "start(autoStop=$autoStopOnSilence, pause=$pauseSeconds)"
            startThrows?.let { thrown ->
                started.countDown()
                // Held, when asked, so a test can put the process's death notice ahead of this throw (#213).
                startThrowGate?.let { check(it.await(10, TimeUnit.SECONDS)) { "the start throw was never released" } }
                throw thrown
            }
            if (!startResult) {
                // A refused start publishes its own ending with the failure code and no file (#115), queued
                // BEFORE the start is reported, so nothing a test waits on can run ahead of it (#210).
                push { it.onEnded(TakeEnding(takeId, AudioCaptureService.TERMINAL_REASON_NONE, startFailure, null, AudioCaptureService.SILENCE_STATUS_DISABLED, 0f, "")) }
                started.countDown()
                return false
            }
            audioFile = File.createTempFile("take", ".pcm").apply { writeBytes(ByteArray(32_000)) }
            capturing = true
            when (liveStateAfterStart) {
                AudioCaptureService.LIVE_READY -> push { it.onLive(takeId, false, liveRouteKind, 0, 120L); it.onTick(takeId, 0L) }
                AudioCaptureService.LIVE_FORCED -> push { it.onLive(takeId, true, liveRouteKind, 0, 120L); it.onTick(takeId, 0L) }
                else -> Unit
            }
            // Reported AFTER the push, like every signal of this fake: an event is queued before anything a
            // test waits on says it happened (#210).
            started.countDown()
            // A start whose RETURN is held: the live event is already on its way to main, as on a device
            // where the route goes live within a millisecond of the call (the hosted-runner race).
            startReturnGate?.await(10, TimeUnit.SECONDS)
            return true
        }

        /** When set, `startCaptureForTake` returns only once the test opens it, AFTER it pushed live. */
        @Volatile var startReturnGate: CountDownLatch? = null

        /** When set, `startCaptureForTake` throws it (a binder call into a process that died, #213). */
        @Volatile var startThrows: Throwable? = null

        /** When set with [startThrows], the throw waits until the test opens it. */
        @Volatile var startThrowGate: CountDownLatch? = null

        override fun stopCapture() {
            events += "stop"
            commandThreads += Thread.currentThread().name
            timeline += "capture-stop"
            capturing = false
            // The ending is queued BEFORE the stop is reported (#210), so settle() after awaitStopRequested()
            // always finds it on the binder thread.
            publishEnding(ending)
            stopRequested.countDown()
        }

        override fun finishTake(): Boolean {
            events += "finishTake"
            commandThreads += Thread.currentThread().name
            return false
        }

        /**
         * The capture loop ended the take itself: silence, the cap, an error, or one before live. With
         * [takeId] given, the ending belongs to ANOTHER take (the previous one, still queued in the
         * service's publisher when this owner registered) and consumes none of this take's one ending.
         */
        fun endOnItsOwn(reason: Int, takeId: String = currentTakeId) {
            if (takeId != currentTakeId) {
                push { it.onEnded(TakeEnding(takeId, reason, startFailure, "/tmp/previous-take.pcm", AudioCaptureService.SILENCE_STATUS_READY, 0.9f, "Earbuds")) }
                return
            }
            capturing = false
            publishEnding(reason)
        }

        /** A heartbeat from the capture loop, as the audio process sends one each second. */
        fun tick(elapsedMs: Long) {
            val takeId = currentTakeId
            push { it.onTick(takeId, elapsedMs) }
        }

        /** The silence detector's status changed. */
        fun silenceStatus(status: Int) {
            val takeId = currentTakeId
            push { it.onSilenceStatus(takeId, status) }
        }

        private fun publishEnding(reason: Int) {
            if (!ended.compareAndSet(false, true)) return
            val takeId = currentTakeId
            push {
                endingGate?.await(10, TimeUnit.SECONDS)
                it.onEnded(TakeEnding(takeId, reason, startFailure, audioFile?.path, AudioCaptureService.SILENCE_STATUS_DISABLED, peak, "Phone microphone"))
            }
        }

        private fun push(event: (TakeListener) -> Unit) {
            if (silent) return
            val target = takeListener ?: return
            binderThread.execute { event(target) }
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
            commandThreads += Thread.currentThread().name
            events += "listen"
            timeline += "listen"
            listening.countDown()
        }
        /** When set, the registration is held until the test opens it: a wedged process that returns late. */
        @Volatile var registrationGate: CountDownLatch? = null
        private val registering = CountDownLatch(1)

        /** The owner reached the registration (it may still be held by [registrationGate]). */
        fun awaitRegistering() {
            check(registering.await(10, TimeUnit.SECONDS)) { "the owner never registered for the take's events; events: $events" }
        }

        private val registered = CountDownLatch(1)

        /** The registration returned (after any gate). */
        fun awaitRegistered() {
            check(registered.await(10, TimeUnit.SECONDS)) { "the registration never returned; events: $events" }
        }

        override fun listenForTake(listener: TakeListener) {
            registering.countDown()
            registrationGate?.await(10, TimeUnit.SECONDS)
            takeListener = listener
            commandThreads += Thread.currentThread().name
            events += "listenForTake"
            registered.countDown()
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
        /** The thread each cancel arrived on, in order with [cancelled]. */
        val cancelThreads = CopyOnWriteArrayList<String>()
        /** When set, each cancel records whether its thread held this lock (#237: never). */
        @Volatile var lockToWatch: Any? = null
        /** When set, a cancel throws after it is recorded, as a dead engine's transaction would. */
        @Volatile var throwOnCancel = false
        val cancelHeldLock = CopyOnWriteArrayList<Boolean>()
        val warmed = CopyOnWriteArrayList<PolishPolicy>()
        private val requested = CountDownLatch(1)
        /** The raw text the owner handed the engine, after vocabulary restoration (#193). */
        @Volatile var lastRawText: String? = null
        /** The policy the owner sent with the last request (#278). */
        @Volatile var lastPolicy: PolishPolicy? = null
        /** When set, a warm-up blocks until released, as a stalled `:polish` binder call would (#236). */
        @Volatile var holdWarmUp: CountDownLatch? = null
        /** Counted down when a warm-up call has been entered. */
        val warmUpEntered = CountDownLatch(1)
        /** Counted down when a warm-up call has returned. */
        val warmUpCompleted = CountDownLatch(1)
        /** The thread each warm-up ran on (#236: never the rig's main thread). */
        val warmUpThreads = CopyOnWriteArrayList<String>()
        override fun warmUpWithPolicy(policy: PolishPolicy) {
            warmUpThreads += Thread.currentThread().name
            warmUpEntered.countDown()
            holdWarmUp?.await(10, TimeUnit.SECONDS)
            warmed += policy
            warmUpCompleted.countDown()
        }
        override fun polishRequestForTake(requestId: Long, rawText: String, removeFillers: Boolean, spokenEmoji: Boolean, spokenPunctuation: Boolean, policy: PolishPolicy, takeId: String, listener: PolishListener) {
            if (throwOnRequest) throw IllegalStateException("engine gone")
            lastRawText = rawText
            lastPolicy = policy
            this.requestId = requestId
            this.listener = listener
            requested.countDown()
        }
        override fun cancel(requestId: Long) {
            cancelThreads += Thread.currentThread().name
            lockToWatch?.let { cancelHeldLock += Thread.holdsLock(it) }
            if (throwOnCancel) throw IllegalStateException("engine gone")
            cancelled += requestId
        }
        fun awaitRequest(diagnostics: () -> String = { "" }): PolishListener {
            check(requested.await(10, TimeUnit.SECONDS)) { "the owner never asked the polish process. ${diagnostics()}" }
            return checkNotNull(listener)
        }
        fun outcome(text: String, reason: com.envi.wispr.polish.PolishReason = com.envi.wispr.polish.PolishReason.POLISHED) =
            PolishOutcome(requestId = requestId, text = text, engine = "Fake engine", reason = reason, statusCode = 0, latencyMs = 12L)
    }

    /**
     * The three connections: `bind` connects all three on the fake main thread, as the platform would. The
     * polish link is null until its connect callback, and null again after its disconnect, as
     * `PipelineBindings` sets it (#234).
     */
    inner class FakePipeline(
        override val capture: CaptureLink?,
        @Volatile override var speech: SpeechLink?,
        private val polishLink: PolishLink?,
    ) : PipelineController {
        @Volatile var bindResult = PipelineController.BindResult.BOUND
        @Volatile var connectSpeech = true
        /** False: the polish bind is refused while audio and speech bind (#234). */
        @Volatile var bindPolish = true
        /** False: polish binds but never connects, so its link stays null (#234). */
        @Volatile var connectPolish = true
        @Volatile override var polish: PolishLink? = null
        @Volatile var listener: PipelineController.Listener? = null
        val events = CopyOnWriteArrayList<String>()

        override fun bind(listener: PipelineController.Listener): PipelineController.BindOutcome {
            this.listener = listener
            events += "bind"
            if (bindResult != PipelineController.BindResult.BOUND) return PipelineController.BindOutcome(bindResult, polishBound = false)
            mainExecutor.execute {
                run("post") {
                    listener.onCaptureConnected()
                    if (connectSpeech) listener.onSpeechConnected()
                    if (bindPolish && connectPolish) connectPolishNow()
                }
            }
            return PipelineController.BindOutcome(bindResult, polishBound = bindPolish)
        }

        /** Main thread: the link first, then the callback, as `PipelineBindings` does. */
        private fun connectPolishNow() {
            polish = polishLink
            listener?.onPolishConnected()
        }

        /** The platform reconnecting polish after its process came back, on main. */
        fun reconnectPolish() = onMain { connectPolishNow() }
        override fun unbind() {
            events += "unbind"
            timeline += "unbind"
        }
        override fun stopAudioService() { events += "stopAudioService" }

        /** The platform reporting a helper's death, on main. */
        fun disconnect(which: String) = onMain {
            when (which) {
                "capture" -> listener?.onCaptureDisconnected()
                "speech" -> listener?.onSpeechDisconnected()
                "polish" -> {
                    polish = null
                    listener?.onPolishDisconnected()
                }
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
        /** When set, the FIRST `updateStatus` is held this long: on two threads the second lands first (the #115 P4 race). */
        @Volatile var delayFirstStatusMs = 0L
        private val statusWrites = AtomicLong(0L)
        /** When set, every status write is held until the test completes it: a stalled disk (the #115 destroy row). */
        @Volatile var holdStatusWrites: kotlinx.coroutines.CompletableDeferred<Unit>? = null

        override fun observeAll(): Flow<List<TranscriptEntity>> = flowOf(rows.values.toList())
        /** When set, only the take's draft insert fails, so the publication inserts its own row (#235 row 9c). */
        @Volatile var failDraftInsert = false
        /** When set, the publication's own saved-row insert (the no-draft path) is held until completed (#277 row 3). */
        @Volatile var holdSavedInsert: kotlinx.coroutines.CompletableDeferred<Unit>? = null
        /** The order the promotion and the insertion outcome landed in, by write (#277 row 5). */
        val routeWrites = java.util.concurrent.CopyOnWriteArrayList<String>()
        override suspend fun insert(transcript: TranscriptEntity): Long {
            if (failInserts) throw IllegalStateException("disk full")
            if (failDraftInsert && transcript.status == TranscriptEntity.STATUS_DRAFT) throw IllegalStateException("draft insert failed")
            if (transcript.status == TranscriptEntity.STATUS_SAVED_UNROUTED) holdSavedInsert?.await()
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
            if (statusWrites.getAndIncrement() == 0L && delayFirstStatusMs > 0L) kotlinx.coroutines.delay(delayFirstStatusMs)
            holdStatusWrites?.await()
            return if (rows.computeIfPresent(id) { _, row -> row.copy(status = status, stateChangedAtMs = stateChangedAtMs, interrupted = interrupted, insertionResult = insertionResult ?: row.insertionResult) } != null) 1 else 0
        }
        /** Runs after the publication's row is written, on the History worker: a recovery staged there (#235). */
        @Volatile var afterFinalize: (suspend () -> Unit)? = null
        /** When set, only the publication's finalize throws, after any hold: a late failure (#235 row 3). */
        @Volatile var failFinalize = false
        /** When set, the publication's History write is held until the test completes it (#234 row 6f). */
        @Volatile var holdFinalize: kotlinx.coroutines.CompletableDeferred<Unit>? = null
        /** Counted down when a held publication write has been entered. */
        val finalizeEntered = CountDownLatch(1)
        override suspend fun finalize(id: Long, originalText: String, finalText: String, speechEngine: String, polishEngine: String, polishLatencyMs: Long, insertionResult: String, durationMs: Long, stateChangedAtMs: Long, polishReason: String, polishStatus: Int, polishContext: String, captureDevice: String, status: String, interrupted: Boolean): Int {
            holdFinalize?.let { held -> finalizeEntered.countDown(); held.await() }
            if (failInserts || failFinalize) throw IllegalStateException("disk full")
            val written = if (rows.computeIfPresent(id) { _, row -> row.copy(originalText = originalText, finalText = finalText, speechEngine = speechEngine, polishEngine = polishEngine, polishLatencyMs = polishLatencyMs, insertionResult = insertionResult, durationMs = durationMs, stateChangedAtMs = stateChangedAtMs, polishReason = polishReason, polishStatus = polishStatus, polishContext = polishContext, captureDevice = captureDevice, status = status, interrupted = interrupted) } != null) 1 else 0
            afterFinalize?.invoke()
            return written
        }
        /** When set, an insertion-outcome write throws, as a failed Room update would (#235 row 13). */
        @Volatile var failOutcome = false
        /** Mirrors `TranscriptDao.finalizeInsertionOutcome`: ready or neutral, and still pending (#235). */
        override suspend fun finalizeInsertionOutcome(id: Long, status: String, result: String, stateChangedAtMs: Long, interrupted: Boolean): Int {
            if (failOutcome) throw IllegalStateException("outcome write failed")
            routeWrites += "outcome"
            var updated = 0
            rows.computeIfPresent(id) { _, row ->
                if (row.status !in OPEN_ROUTE || row.insertionResult != "pending") {
                    row
                } else {
                    updated = 1
                    row.copy(status = status, insertionResult = result, stateChangedAtMs = stateChangedAtMs, interrupted = interrupted)
                }
            }
            return updated
        }
        /** When set, the start-up recovery is held until the test completes it (the #115 review's F1 row). */
        @Volatile var holdRecovery: kotlinx.coroutines.CompletableDeferred<Unit>? = null
        /** Every recovery body below mirrors its `TranscriptDao` query's predicate and writes (#235). */
        private val recoveryLock = Any()
        private fun recover(cutoffMs: Long, matches: (TranscriptEntity) -> Boolean, into: (TranscriptEntity) -> TranscriptEntity): Int = synchronized(recoveryLock) {
            var updated = 0
            rows.keys.forEach { id ->
                rows.computeIfPresent(id) { _, row ->
                    if (row.stateChangedAtMs <= cutoffMs && matches(row)) { updated++; into(row) } else row
                }
            }
            updated
        }
        override suspend fun recoverStaleDrafts(cutoffMs: Long, nowMs: Long): Int {
            holdRecovery?.await()
            return recover(cutoffMs, { it.status == TranscriptEntity.STATUS_DRAFT }) {
                it.copy(status = TranscriptEntity.STATUS_INTERRUPTED, insertionResult = "not_attempted", interrupted = true)
            }
        }
        /** Mirrors `TranscriptDao.recoverStaleProcessingRows` (#277): delivery unknown, never "not attempted". */
        override suspend fun recoverStaleProcessingRows(cutoffMs: Long, nowMs: Long): Int =
            recover(cutoffMs, { it.status == TranscriptEntity.STATUS_PROCESSING }) {
                it.copy(status = TranscriptEntity.STATUS_INTERRUPTED, insertionResult = com.envi.wispr.insertion.InsertionResults.DELIVERY_UNKNOWN, interrupted = true)
            }
        override suspend fun recoverStaleReadyRows(cutoffMs: Long, nowMs: Long): Int =
            recover(cutoffMs, { it.status == TranscriptEntity.STATUS_READY_FOR_INSERTION && it.insertionResult == "pending" }) {
                it.copy(status = TranscriptEntity.STATUS_INSERTION_INTERRUPTED, insertionResult = com.envi.wispr.insertion.InsertionResults.INSERTION_INTERRUPTED, stateChangedAtMs = nowMs, interrupted = true)
            }
        override suspend fun staleReadyRowIds(cutoffMs: Long): List<Long> = synchronized(recoveryLock) {
            rows.values.filter { it.stateChangedAtMs <= cutoffMs && it.status == TranscriptEntity.STATUS_READY_FOR_INSERTION && it.insertionResult == "pending" }.map { it.id }
        }
        /** Room runs the snapshot and the update in one transaction; the fake serializes them under one lock. */
        override suspend fun recoverStaleReadyRowsReturningIds(cutoffMs: Long, nowMs: Long): List<Long> = synchronized(recoveryLock) {
            val ids = rows.values.filter { it.stateChangedAtMs <= cutoffMs && it.status == TranscriptEntity.STATUS_READY_FOR_INSERTION && it.insertionResult == "pending" }.map { it.id }
            ids.forEach { id ->
                rows.computeIfPresent(id) { _, row -> row.copy(status = TranscriptEntity.STATUS_INSERTION_INTERRUPTED, insertionResult = com.envi.wispr.insertion.InsertionResults.INSERTION_INTERRUPTED, stateChangedAtMs = nowMs, interrupted = true) }
            }
            ids
        }
        override suspend fun recoverStaleUnroutedRows(cutoffMs: Long, nowMs: Long): Int =
            recover(cutoffMs, { it.status == TranscriptEntity.STATUS_SAVED_UNROUTED && it.insertionResult == "pending" }) {
                it.copy(status = TranscriptEntity.STATUS_COMPLETED, insertionResult = com.envi.wispr.insertion.InsertionResults.DELIVERY_UNKNOWN, stateChangedAtMs = nowMs, interrupted = true)
            }
        /** When set, the promotion to ready is held until the test completes it (#235 row 11). */
        @Volatile var holdPromotion: kotlinx.coroutines.CompletableDeferred<Unit>? = null
        override suspend fun promoteUnroutedToReady(id: Long, nowMs: Long): Int {
            holdPromotion?.await()
            routeWrites += "promotion"
            var updated = 0
            rows.computeIfPresent(id) { _, row ->
                if (row.status == TranscriptEntity.STATUS_SAVED_UNROUTED && row.insertionResult == "pending") {
                    updated = 1
                    row.copy(status = TranscriptEntity.STATUS_READY_FOR_INSERTION, stateChangedAtMs = nowMs)
                } else row
            }
            return updated
        }

        private companion object {
            val OPEN_ROUTE = setOf(TranscriptEntity.STATUS_READY_FOR_INSERTION, TranscriptEntity.STATUS_SAVED_UNROUTED)
        }
    }
}
