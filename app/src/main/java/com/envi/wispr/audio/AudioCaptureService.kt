package com.envi.wispr.audio

import android.app.Service
import android.content.Intent
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioRouting
import android.media.MediaRecorder
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.SystemClock
import com.envi.wispr.debug.DebugLogger
import com.envi.wispr.vad.SilenceStopDetector
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs

/** Audio capture service running in a separate process (:audio). */
class AudioCaptureService : Service() {

    companion object {
        private const val TAG = "AudioCapture"
        private const val SAMPLE_RATE = PcmAudio.SAMPLE_RATE
        private const val CHANNEL = AudioFormat.CHANNEL_IN_MONO
        private const val ENCODING = AudioFormat.ENCODING_PCM_16BIT

        const val SILENCE_STATUS_DISABLED = 0
        const val SILENCE_STATUS_PREPARING = 1
        const val SILENCE_STATUS_READY = 2
        const val SILENCE_STATUS_UNAVAILABLE = 3

        /**
         * The detector was working and then stopped being available.
         *
         * Diagnostic and SILENT. The recording is still correct, and a message several seconds into one
         * that is going fine is an interruption for nothing. Only [SILENCE_STATUS_UNAVAILABLE], which
         * means auto-stop never became available at all, is worth telling the user about.
         */
        const val SILENCE_STATUS_LOST_AFTER_READY = 4
        /**
         * The terminal reasons, re-exported under the names callers already use. `CaptureEnding` owns the
         * values, because they cross a process boundary and must have exactly one definition.
         *
         * [TERMINAL_REASON_SILENCE] is a NORMAL ending in the same class as [TERMINAL_REASON_MANUAL]: a
         * reader that treats it as a failure discards a good transcript. Nothing sets it yet.
         */
        const val TERMINAL_REASON_NONE = CaptureEnding.NONE
        const val TERMINAL_REASON_MAX_DURATION = CaptureEnding.MAX_DURATION
        const val TERMINAL_REASON_MANUAL = CaptureEnding.MANUAL
        const val TERMINAL_REASON_ERROR = CaptureEnding.ERROR
        const val TERMINAL_REASON_SILENCE = CaptureEnding.SILENCE

        /** Why the last start returned false. A start that never began is not a `CaptureEnding`. */
        const val START_FAILURE_NONE = 0
        const val START_FAILURE_NO_INPUT_DEVICE = 1
        const val START_FAILURE_OTHER = 2
        /** The earbuds are connected and Android would only record from the phone; the founder's rule refuses that. */
        const val START_FAILURE_EARBUDS = 3

        /** `getLiveState`: what the session owner waits on before it opens the pill. */
        const val LIVE_WAITING = 0
        const val LIVE_READY = 1
        const val LIVE_FORCED = 2
    }

    /** Every native and file resource for one take has one owner and one lifetime. */
    private class CaptureSession(
        val record: AudioRecord,
        val file: File,
        val output: FileOutputStream,
        val readBuffer: ByteArray,
        val token: Long,
        /** The silence detector's feed for this take; disabled when the user has auto-stop off (#188). */
        val detector: DetectorFeed,
        /** The recorder's live picture for this take (#188). */
        val picture: PicturePublisher,
        /** This take's route: the request, the gate, the listener, the sink watch and the deadline (#188). */
        val route: TakeRoute,
        /** The setting, frozen per take like the pick. */
        val keepEarbudsReady: Boolean,
        /** The owner's per-take UUID, request context only; empty for a legacy start. Forwarded to the detector. */
        val takeId: String,
    ) {
        /** True once the first admitted block is on disk: only then does the binder report READY or FORCED. */
        @Volatile var liveVisible: Boolean = false

        /** Capture thread writes; read over the binder and by the routing listener's log line. */
        @Volatile var bytesWritten: Long = 0L

        /** The one owner of how this take ended. First claim wins; see `CaptureEndingClaim`. */
        val endingClaim = CaptureEndingClaim()

        val stopRequested: Boolean get() = endingClaim.ended
    }

    private val sessionLock = Any()
    @Volatile private var session: CaptureSession? = null

    /**
     * The most recent take's device record. Kept after the take ends until the next start, so the
     * session owner can read a complete history once at stop, however short the take was.
     */
    @Volatile private var lastEffective: EffectiveDevice? = null
    @Volatile private var lastStartFailure = START_FAILURE_NONE

    /** The warm hold between takes, one owner for the service's lifetime (#188). Built in [onCreate]. */
    private lateinit var warmHoldOwner: WarmHoldOwner

    /** Set first thing in `onDestroy`: no take that ends after this may start a hold (Codex review 1). */
    @Volatile private var destroyed = false

    /**
     * Owns every routing callback and nothing else. A callback carries the session it was registered
     * for and writes nothing once that session's hold is released.
     */
    private lateinit var routeThread: HandlerThread
    private lateinit var routeHandler: Handler
    private val routeScheduler = object : RouteScheduler {
        override fun post(runnable: Runnable) { routeHandler.post(runnable) }
        override fun postDelayed(runnable: Runnable, delayMs: Long) { routeHandler.postDelayed(runnable, delayMs) }
        override fun removeCallbacks(runnable: Runnable) { routeHandler.removeCallbacks(runnable) }
    }
    private val isRecording = AtomicBoolean(false)
    @Volatile private var captureThread: Thread? = null
    @Volatile private var lastAudioFile: File? = null
    @Volatile private var currentAmplitude = 0f
    /**
     * The one listener the picture is pushed to (#187). Owned by the BINDING, not the take: a
     * registration survives a take's release and goes with the client (unregister, unbind, or a push
     * that finds it dead). Registration `set`s; unregister and failed-push cleanup
     * `compareAndSet(observed, null)`, so a late clear can never erase a newer registration. Read by
     * the analyser thread, written by binder threads and the service's main thread; never touched by
     * the capture thread.
     */
    private val spectrumListener = AtomicReference<IAudioSpectrumListener?>(null)
    /**
     * The one registered take-event listener (#115): the binding's slot, like the spectrum listener's, so it
     * is dropped with the binding and never has to be unregistered by an owner tearing down.
     */
    private val takeListener = AtomicReference<ITakeListener?>(null)
    private lateinit var takeEvents: TakeEventPublisher
    /**
     * The loudest sample of the current or most recent take, 0..1 of full scale. Written on the capture
     * thread, reset at start, kept after the take ends until the next start (like `lastEffective`), so a
     * reader that asks once at stop gets the whole take. It is what lets an empty transcript be told apart
     * from a quiet room (issue #176). Absent (0 before any take) is "not measured", never "silence".
     */
    @Volatile private var takePeakAmplitude = 0f
    /**
     * The silence detector's status as the MOST RECENT take ended, kept like the peak until the next
     * start, so the owner's one read at stop sees `lost after ready` and not the session-gone default
     * (issue #176; a `null` session read as DISABLED, a plausible value that hid the detector's death).
     */
    @Volatile private var lastSilenceStatus = SILENCE_STATUS_DISABLED
    @Volatile private var terminalReason = TERMINAL_REASON_NONE
    private val tokens = AtomicLong(0L)

    /**
     * A capture token that only ever increases, INCLUDING across a restart of this process.
     *
     * The detector process refuses anything not newer than the newest it has seen, which is what stops a
     * call from a finished take reaching a live one. A plain counter would break that: if `:audio` dies
     * and comes back it would restart at 1, and every take would then be refused forever. The boot clock
     * is shared between processes, so it keeps the order; the compare-and-set keeps consecutive tokens
     * distinct if the clock has not ticked between two takes.
     */
    private fun nextCaptureToken(): Long {
        while (true) {
            val previous = tokens.get()
            val clock = SystemClock.elapsedRealtimeNanos()
            val next = if (clock > previous) clock else previous + 1L
            check(next > 0L) { "capture token clock overflow" }
            if (tokens.compareAndSet(previous, next)) return next
        }
    }

    override fun onCreate() {
        super.onCreate()
        routeThread = HandlerThread("AudioRouteThread").also { it.start() }
        routeHandler = Handler(routeThread.looper)
        val audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
        warmHoldOwner = WarmHoldOwner(
            tag = TAG,
            scheduler = routeScheduler,
            locked = { block -> synchronized(sessionLock) { block() } },
            addCommListener = { audioManager.addOnCommunicationDeviceChangedListener({ routeHandler.post(it) }, it) },
            removeCommListener = { audioManager.removeOnCommunicationDeviceChangedListener(it) },
            registerDeviceCallback = { audioManager.registerAudioDeviceCallback(it, routeHandler) },
            unregisterDeviceCallback = { audioManager.unregisterAudioDeviceCallback(it) },
            keepAlive = { startService(Intent(this, AudioCaptureService::class.java)) },
            // A new take keeps the service; every other end lets it go once no session is open.
            onIdle = { if (session == null) stopSelf() },
        )
        takeEvents = TakeEventPublisher(takeListener, TAG).also { it.start() }
    }

    private val binder = object : IAudioCaptureService.Stub() {
        override fun startCapture(): Boolean =
            this@AudioCaptureService.startRecording(autoStopOnSilence = false, pauseSeconds = 0f, pick = InputDevicePick.Auto, takeId = "")

        override fun startCaptureWithSilenceStop(autoStopOnSilence: Boolean, pauseSeconds: Float): Boolean =
            this@AudioCaptureService.startRecording(autoStopOnSilence, pauseSeconds, InputDevicePick.Auto, takeId = "")

        override fun startCaptureWithInputDevice(autoStopOnSilence: Boolean, pauseSeconds: Float, inputDevicePick: String?): Boolean =
            this@AudioCaptureService.startRecording(autoStopOnSilence, pauseSeconds, InputDevicePick.parse(inputDevicePick), takeId = "")

        override fun startCaptureWithInputDeviceHeld(autoStopOnSilence: Boolean, pauseSeconds: Float, inputDevicePick: String?, keepEarbudsReady: Boolean): Boolean =
            this@AudioCaptureService.startRecording(autoStopOnSilence, pauseSeconds, InputDevicePick.parse(inputDevicePick), keepEarbudsReady, takeId = "")

        override fun startCaptureForTake(autoStopOnSilence: Boolean, pauseSeconds: Float, inputDevicePick: String?, keepEarbudsReady: Boolean, takeId: String?): Boolean =
            this@AudioCaptureService.startRecording(autoStopOnSilence, pauseSeconds, InputDevicePick.parse(inputDevicePick), keepEarbudsReady, takeId.orEmpty())

        override fun getTakePeakAmplitude(): Float = this@AudioCaptureService.takePeakAmplitude

        override fun getLiveState(): Int {
            val active = this@AudioCaptureService.session ?: return LIVE_WAITING
            if (!active.liveVisible) return LIVE_WAITING
            return when (active.route.gate.state) {
                LiveGate.State.WAITING -> LIVE_WAITING
                LiveGate.State.READY -> LIVE_READY
                LiveGate.State.FORCED -> LIVE_FORCED
            }
        }

        override fun getLiveAfterMs(): Long {
            val active = this@AudioCaptureService.session ?: return 0L
            val live = active.route.liveAtMs
            return if (live > 0L) live - active.route.startedAtMs else 0L
        }

        override fun finishTake(): Boolean = synchronized(sessionLock) { this@AudioCaptureService.warmHoldOwner.finishTake() }

        override fun getEffectiveInputDevice(): String = this@AudioCaptureService.lastEffective?.label().orEmpty()
        override fun getInputRouteKind(): Int = this@AudioCaptureService.lastEffective?.kind?.code ?: InputRouteKind.NONE.code
        override fun getInputRouteReason(): Int = this@AudioCaptureService.lastEffective?.reasonCode() ?: InputRouteReason.AUTO.code
        override fun getLastStartFailure(): Int = this@AudioCaptureService.lastStartFailure

        override fun getSilenceStopStatus(): Int =
            this@AudioCaptureService.session?.detector?.status ?: this@AudioCaptureService.lastSilenceStatus
        override fun stopCapture() = this@AudioCaptureService.stopRecording()
        override fun isCapturing(): Boolean = this@AudioCaptureService.isRecording.get()
        override fun getTerminalReason(): Int = this@AudioCaptureService.terminalReason
        override fun getCurrentAmplitude(): Float = this@AudioCaptureService.currentAmplitude

        override fun getSpectrumBands(): FloatArray {
            // LEGACY since #187: no production caller; counted so the take-end line can prove it.
            // Always BAND_COUNT long, never empty: the length is the contract. Zeros when no take is open.
            val active = this@AudioCaptureService.session ?: return FloatArray(SpectrumAnalyzer.BAND_COUNT)
            return active.picture.snapshot()
        }

        override fun registerSpectrumListener(listener: IAudioSpectrumListener?) {
            this@AudioCaptureService.spectrumListener.set(listener)
        }

        override fun unregisterSpectrumListener(listener: IAudioSpectrumListener?) {
            val current = this@AudioCaptureService.spectrumListener.get() ?: return
            if (listener != null && current.asBinder() == listener.asBinder()) {
                this@AudioCaptureService.spectrumListener.compareAndSet(current, null)
            }
        }

        override fun registerTakeListener(listener: ITakeListener?) {
            this@AudioCaptureService.takeListener.set(listener)
        }

        override fun unregisterTakeListener(listener: ITakeListener?) {
            val current = this@AudioCaptureService.takeListener.get() ?: return
            if (listener != null && current.asBinder() == listener.asBinder()) {
                this@AudioCaptureService.takeListener.compareAndSet(current, null)
            }
        }
        override fun getAudioFilePath(): String? = this@AudioCaptureService.lastAudioFile?.absolutePath

        override fun getElapsedMs(): Long {
            val active = this@AudioCaptureService.session
            // From LIVE, not from the recorder's start: the wait for the earbuds is not the user's time.
            val live = active?.route?.liveAtMs ?: 0L
            return if (this@AudioCaptureService.isRecording.get() && active != null && live > 0L) {
                SystemClock.elapsedRealtime() - live
            } else 0L
        }

        override fun getMaxDurationMs(): Long = RecordingLimits.MAX_DURATION_MS
        override fun waitForFileReady(timeoutMs: Long): Boolean =
            this@AudioCaptureService.waitForFileReady(timeoutMs)

        // Legacy method retained for old clients. Audio is now file-backed.
        override fun getAudioData(): ByteArray {
            DebugLogger.warn(TAG, "getAudioData() called, use getAudioFilePath() instead")
            return ByteArray(0)
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    /**
     * The last binding is gone: clear both listener slots so no future push reaches an owner that left; a
     * reference already read may still deliver once (#115 review round 1, F8: a warm hold keeps this
     * service alive past the owner's unbind, so the slot is not dropped by the binding going). Runs on the
     * service main thread.
     */
    override fun onUnbind(intent: Intent?): Boolean {
        spectrumListener.set(null)
        takeListener.set(null)
        return super.onUnbind(intent)
    }

    /**
     * Started only by [finishTake], to outlive the owner's unbind while a hold runs. Never sticky: a
     * restart after a kill would have nothing to hold, so it ends at once.
     */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        synchronized(sessionLock) { if (!warmHoldOwner.isActive && session == null) stopSelf() }
        return START_NOT_STICKY
    }

    private fun startRecording(
        autoStopOnSilence: Boolean,
        pauseSeconds: Float,
        pick: InputDevicePick,
        keepEarbudsReady: Boolean = false,
        takeId: String,
    ): Boolean {
        // REQUESTED is not the same as VALID AND ENABLED. A pause outside the slider's range reaching
        // this binder means a caller we do not control, so the detector is not built at all rather than
        // built with a number nobody chose. Ordinary recording is untouched either way.
        val validPause = pauseSeconds.takeIf {
            it.isFinite() &&
                it >= SilenceStopDetector.MIN_PAUSE_SECONDS &&
                it <= SilenceStopDetector.MAX_PAUSE_SECONDS
        }
        val detectorEnabled = autoStopOnSilence && validPause != null
        val requestedButRefused = autoStopOnSilence && validPause == null

        synchronized(sessionLock) {
            // A stopped session remains here until its reader has closed both resources.
            // Starting another take before that point would make the old thread write into
            // the new take's file or release the new take's AudioRecord.
            if (session != null) {
                // The previous take publishes its own ending from releaseSession, under ITS id; this refusal
                // is the REQUESTED take's own event, so the owner never waits for an ending no new session
                // can produce (#115).
                publishStartRefused(takeId, START_FAILURE_OTHER)
                return false
            }
            lastStartFailure = START_FAILURE_NONE
            takeEvents.resetTicks()

            // Route ownership exists BEFORE the session, so every failure path below can release it.
            val audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
            // Filled in once the listener exists; the hold removes exactly this one and no other.
            val routingListener = AtomicReference<Pair<AudioRecord, AudioRouting.OnRoutingChangedListener>?>(null)
            val routeHold = TakeRoute.newHold(audioManager, routingListener)
            // A warm hold hands its route to this take (the link stays up; V13: live at ~120 ms). Any hold
            // that does not match the resolved target is ended by resolveRoute before it sets anything.
            val handedOver = warmHoldOwner.handOver()
            val route = TakeRoute.resolve(audioManager, pick, routeHold, handedOver, TAG) ?: run {
                lastStartFailure = START_FAILURE_NO_INPUT_DEVICE
                routeHold.release()
                DebugLogger.error(TAG, "No input device at all; refusing to start")
                stopSelf()
                publishStartRefused(takeId, lastStartFailure)
                return false
            }

            val nativeBufferBytes = try {
                val minimum = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL, ENCODING)
                    .takeIf { it > 0 }
                    ?: throw IllegalStateException("AudioRecord buffer size unavailable")
                val coerced = minimum.coerceAtLeast(SAMPLE_RATE * PcmAudio.BYTES_PER_SAMPLE)
                // Whether the floor binds cannot be settled from source: getMinBufferSize is computed by
                // the platform from the device's own frame count. Log both so the answer comes from the
                // phone rather than from an assumption. Android may also enlarge what it actually
                // allocates, which getBufferSizeInFrames reports once the recorder exists.
                DebugLogger.log(
                    TAG,
                    "Buffer sizes: minimum=$minimum coerced=$coerced read=${PcmAudio.READ_CHUNK_BYTES} block=${DetectorFeed.READ_BLOCK_BYTES}",
                )
                coerced
            } catch (e: Exception) {
                DebugLogger.error(TAG, "Failed to determine audio buffer size", e)
                lastStartFailure = START_FAILURE_OTHER
                routeHold.release()
                stopSelf()
                publishStartRefused(takeId, lastStartFailure)
                return false
            }

            // The process's one recorder (#115 review, F3): a previous Service instance's capture thread may
            // still hold it, parked in a read, and `session` cannot see across instances.
            if (!RecorderLease.PROCESS.acquire()) {
                DebugLogger.error(TAG, "A recorder is still held in this process; refusing to start")
                lastStartFailure = START_FAILURE_OTHER
                routeHold.release()
                stopSelf()
                publishStartRefused(takeId, lastStartFailure)
                return false
            }
            var record: AudioRecord? = null
            var threadStarted = false
            var output: FileOutputStream? = null
            try {
                record = AudioRecord(
                    MediaRecorder.AudioSource.VOICE_RECOGNITION,
                    SAMPLE_RATE,
                    CHANNEL,
                    ENCODING,
                    nativeBufferBytes,
                )
                if (record.state != AudioRecord.STATE_INITIALIZED) {
                    throw IllegalStateException("AudioRecord failed to initialize")
                }
                val effective = EffectiveDevice(route.reason)
                val takeRoute = TakeRoute(
                    hold = routeHold,
                    resolved = route,
                    effective = effective,
                    gate = LiveGate(gated = route.needsBluetooth),
                    phonePicked = pick is InputDevicePick.Device && InputRouteKind.of(pick.type) == InputRouteKind.PHONE,
                    listenerSlot = routingListener,
                    scheduler = routeScheduler,
                    unregisterDeviceCallback = { audioManager.unregisterAudioDeviceCallback(it) },
                    tag = TAG,
                )
                takeRoute.applyPreferred(record)

                // cacheDir is app-internal and shared by this package's processes. It is
                // excluded from user backups and is less exposed than shared storage.
                // One file per capture (#212, #221): a late answer for an ended take can only delete its
                // own recording. The token is minted once and names both the file and the session.
                val token = nextCaptureToken()
                val file = File(cacheDir, CaptureFiles.nameFor(takeId, token))
                // There is no active session under sessionLock, so no writer can own this
                // path. Remove a partial take left by process death before opening a new one.
                if (file.exists() && !file.delete()) {
                    throw IOException("Unable to remove stale audio recording")
                }
                if (CaptureFiles.isProductionTake(takeId)) sweepEarlierTakeFiles(keep = file.name)
                output = FileOutputStream(file)
                record.startRecording()
                // The route is the target from the first read when the preferred device was set first
                // (V2, 2026-09-16: routedDevice already the SCO source at startRecording).
                record.routedDevice?.let { effective.observe(it.type, it.productName?.toString().orEmpty()) }
                takeRoute.markRecorderStarted(SystemClock.elapsedRealtime())

                val newSession = CaptureSession(
                    record = record,
                    file = file,
                    output = output,
                    // Allocated HERE, before the thread starts, and never inside the capture loop. The
                    // capture thread may not allocate: it must do nothing that can make it late.
                    readBuffer = ByteArray(PcmAudio.READ_CHUNK_BYTES),
                    token = token,
                    detector = DetectorFeed(
                        tag = TAG,
                        autoStop = detectorEnabled,
                        bind = DetectorFeed.bindingThrough(this),
                        unbind = DetectorFeed.unbindingThrough(this),
                        // The session's own id, captured here: a callback outliving its take must not
                        // borrow the next take's (review round 2, F2).
                        onStatus = { status -> takeEvents.publishSilenceStatus(takeId, status) },
                    ),
                    picture = PicturePublisher(spectrumListener, TAG),
                    route = takeRoute,
                    keepEarbudsReady = keepEarbudsReady,
                    takeId = takeId,
                )
                takeRoute.registerListener(record, routeHandler) { newSession.bytesWritten }
                session = newSession
                lastEffective = effective
                lastAudioFile = file
                isRecording.set(true)
                terminalReason = TERMINAL_REASON_NONE
                currentAmplitude = 0f
                takePeakAmplitude = 0f
                DebugLogger.startPipeline()
                DebugLogger.mark(TAG, "recording_start")
                DebugLogger.log(
                    TAG,
                    "Recording started (PID: ${android.os.Process.myPid()}, " +
                        "max: ${RecordingLimits.MAX_DURATION_MS}ms, " +
                        "nativeFrames: ${runCatching { record.bufferSizeInFrames }.getOrDefault(-1)})",
                )
                DebugLogger.log(
                    TAG,
                    "route start=${effective.label()} kind=${effective.kind} reason=${route.reason} " +
                        "target=${route.target.label}",
                )

                if (requestedButRefused) {
                    // The caller asked for auto-stop and cannot have it, which is exactly the state the
                    // notice exists for. Recording itself is unaffected.
                    newSession.detector.markRequestedButRefused()
                    DebugLogger.warn(TAG, "Auto-stop refused: pause $pauseSeconds is out of range")
                }
                if (detectorEnabled) {
                    newSession.detector.start(
                        pauseSeconds = validPause!!,
                        token = newSession.token,
                        takeId = newSession.takeId,
                        isCurrent = { session === newSession },
                        stillLive = { session === newSession && !newSession.stopRequested },
                        endOnSilence = { endTake(newSession, TERMINAL_REASON_SILENCE) },
                    )
                }

                val thread = Thread({ captureLoop(newSession) }, "AudioCaptureThread")
                captureThread = thread
                try {
                    thread.start()
                    threadStarted = true
                } catch (e: Exception) {
                    isRecording.set(false)
                    session = null
                    closeResources(newSession, keepRoute = false)
                    // The detector was started above and would otherwise keep running for a take that
                    // never began (#115 review); the picture has not started yet, close is idempotent.
                    newSession.detector.close(unbindNow = true)
                    newSession.picture.close()
                    captureThread = null
                    stopSelf()
                    DebugLogger.error(TAG, "Failed to start capture thread", e)
                    lastStartFailure = START_FAILURE_OTHER
                    publishStartRefused(takeId, lastStartFailure)
                    return false
                }
                newSession.picture.start(stillLive = { session === newSession && !newSession.stopRequested })
                if (route.needsBluetooth) {
                    takeRoute.watchSink(audioManager, routeHandler)
                    takeRoute.armDeadline(
                        audioManager = audioManager,
                        locked = { block -> synchronized(sessionLock) { block() } },
                        stillWaiting = { session === newSession && isRecording.get() && takeRoute.gate.state == LiveGate.State.WAITING },
                        onRefused = {
                            lastStartFailure = START_FAILURE_EARBUDS
                            endTakeLocked(newSession, TERMINAL_REASON_ERROR)
                        },
                    )
                } else {
                    takeRoute.markLive()
                }
                return thread.isAlive && session === newSession && isRecording.get()
            } catch (e: SecurityException) {
                DebugLogger.error(TAG, "RECORD_AUDIO permission not granted", e)
                failSetup(threadStarted, record, output, routeHold, takeId)
                return false
            } catch (e: Exception) {
                DebugLogger.error(TAG, "Failed to start recording", e)
                failSetup(threadStarted, record, output, routeHold, takeId)
                return false
            }
        }
    }

    private fun captureLoop(active: CaptureSession) {
        val buffer = active.readBuffer
        try {
            while (isRecording.get() && session === active) {
                // The cap counts from LIVE; the wait for the earbuds has its own bound in the session owner.
                val live = active.route.liveAtMs
                val elapsed = if (live > 0L) SystemClock.elapsedRealtime() - live else 0L
                if (elapsed >= RecordingLimits.MAX_DURATION_MS) {
                    // The reason is the whole signal. The session owner reads it back through
                    // getTerminalReason and is what tells the user why their take ended.
                    claimEnding(active, TERMINAL_REASON_MAX_DURATION)
                    DebugLogger.log(TAG, "Max duration reached (${elapsed}ms), auto-stopping")
                    break
                }

                // THE CLOCK CANNOT BOUND THE FILE, because the two measure different things.
                // `record.startRecording()` runs before `startedAtMs` is taken, so the hardware is
                // already buffering when the clock starts, and the check above can pass on its last
                // pass with the file already at the ceiling. One more whole block then goes in.
                //
                // That is not a rounding error. `AsrService` REFUSES a file over the ceiling rather
                // than truncating it, so a few bytes past it discards the entire take: exactly the
                // long dictation this limit exists to keep. So the read is bounded by what is left.
                val remaining = RecordingLimits.MAX_AUDIO_BYTES - active.bytesWritten
                if (remaining <= 0L) {
                    claimEnding(active, TERMINAL_REASON_MAX_DURATION)
                    DebugLogger.log(TAG, "Byte ceiling reached (${active.bytesWritten} bytes), auto-stopping")
                    break
                }

                // Explicit, because the three-argument overload's blocking behaviour is a default
                // rather than a statement, and this loop's timing depends on it.
                val requested = minOf(buffer.size.toLong(), remaining).toInt()
                val bytesRead = active.record.read(buffer, 0, requested, AudioRecord.READ_BLOCKING)
                if (bytesRead < 0) throw IOException("AudioRecord.read failed: $bytesRead")
                if (bytesRead == 0) continue
                // The heartbeat, live or not, BEFORE the gate branch: its arrival is liveness to the owner
                // (#115). One primitive comparison here; the publisher's worker makes the binder call.
                takeEvents.offerTick(active.takeId, if (active.liveVisible) SystemClock.elapsedRealtime() - active.route.liveAtMs else 0L)

                // Until the gate opens, a read feeds the gate and nothing else: not the file, not the
                // detector, not the picture, not the level. The take's clock starts when the gate opens.
                if (active.route.gate.state == LiveGate.State.WAITING) {
                    val admissible = active.route.admissible()
                    if (active.route.gate.offer(buffer, bytesRead, admissible)) active.route.markLive() else continue
                }

                val position = active.bytesWritten
                active.output.write(buffer, 0, bytesRead)
                active.bytesWritten += bytesRead
                if (!active.liveVisible) {
                    // Live is published only once the first admitted block is on disk (#115 review).
                    active.liveVisible = true
                    val effective = lastEffective
                    takeEvents.publishLive(
                        active.takeId,
                        active.route.gate.state == LiveGate.State.FORCED,
                        effective?.kind?.code ?: InputRouteKind.NONE.code,
                        effective?.reasonCode() ?: InputRouteReason.AUTO.code,
                        (active.route.liveAtMs - active.route.startedAtMs).coerceAtLeast(0L),
                    )
                }
                active.detector.offer(buffer, bytesRead, position)
                active.picture.offer(buffer, bytesRead, position)

                var sum = 0L
                var peak = 0
                for (i in 0 until bytesRead step PcmAudio.BYTES_PER_SAMPLE) {
                    if (i + 1 < bytesRead) {
                        val sample = (buffer[i].toInt() and 0xFF) or
                            (buffer[i + 1].toInt() shl 8)
                        val magnitude = abs(sample)
                        sum += magnitude
                        if (magnitude > peak) peak = magnitude
                    }
                }
                val numSamples = bytesRead / PcmAudio.BYTES_PER_SAMPLE
                currentAmplitude = if (numSamples > 0) {
                    (sum.toFloat() / numSamples) / Short.MAX_VALUE
                } else 0f
                // No allocation, one compare per read: the take's peak, kept for the reader at stop.
                val peakLevel = peak.toFloat() / Short.MAX_VALUE
                if (peakLevel > takePeakAmplitude) takePeakAmplitude = peakLevel
            }
        } catch (e: Exception) {
            synchronized(sessionLock) {
                if (session === active) claimEnding(active, TERMINAL_REASON_ERROR)
            }
            DebugLogger.error(TAG, "Capture thread error", e)
        } finally {
            releaseSession(active)
        }
    }

    /**
     * A start refused before any capture began: the owner registered for the take's events and would
     * otherwise wait for an ending no session can produce (#115). Carries the REQUESTED take's id, the
     * failure code, no path, and nothing of the previous take: a disabled silence status, a zero peak and
     * no device label (review round 1, F6).
     */
    /**
     * A production take is admitted only after the earlier take has ended, so no live outcome still
     * depends on an earlier production file; this removes the files of takes whose ending never reached
     * the owner (an answer discarded at close, a process death). Legacy captures are never swept: a
     * separately installed client may still hold that path across an unbind (#212).
     */
    private fun sweepEarlierTakeFiles(keep: String) {
        var removed = 0
        var failed = 0
        cacheDir.listFiles()?.forEach { entry ->
            if (entry.name == keep || !CaptureFiles.isSweptAtTakeStart(entry.name)) return@forEach
            if (entry.delete()) removed++ else failed++
        }
        if (removed + failed > 0) DebugLogger.log(TAG, "Removed $removed earlier capture files; $failed could not be removed")
    }

    private fun publishStartRefused(takeId: String, failure: Int) {
        takeEvents.publishEnded(takeId, TERMINAL_REASON_NONE, failure, null, SILENCE_STATUS_DISABLED, 0f, null)
    }

    /**
     * A setup exception in `startRecording`. BEFORE the capture thread started nothing else owns the
     * recorder or the file, so this clears and closes locally and publishes the refusal itself. AFTER it
     * started, the live thread owns both, and closing them from the binder thread would race it: the
     * error is claimed on the session and the recorder signalled, and `releaseSession` alone cleans up
     * and publishes (#115 review, round 4). Under `sessionLock` in both cases (the caller holds it).
     */
    private fun failSetup(threadStarted: Boolean, record: AudioRecord?, output: java.io.FileOutputStream?, routeHold: RouteHold, takeId: String) {
        lastStartFailure = START_FAILURE_OTHER
        val active = session
        if (threadStarted && active != null) {
            endTakeLocked(active, TERMINAL_REASON_ERROR)
            return
        }
        isRecording.set(false)
        if (active != null) {
            session = null
            captureThread = null
            active.detector.close(unbindNow = true)
            active.picture.close()
        }
        routeHold.release()
        closeResources(record, output)
        stopSelf()
        publishStartRefused(takeId, lastStartFailure)
    }

    /**
     * Publish the first ending claimed for [active] and stop the loop.
     *
     * The published reason outlives [releaseSession] on the service for the legacy `getTerminalReason`
     * getter (append-only interface; no production caller since #115), while the CLAIM lives on the
     * session and is what makes it first-wins. The owner learns the reason from the ending
     * [releaseSession] pushes, never from the getter.
     */
    private fun claimEnding(active: CaptureSession, reason: Int): Boolean {
        if (!active.endingClaim.claim(reason)) return false
        terminalReason = reason
        isRecording.set(false)
        return true
    }

    /** The user, or anything else with the authority to end whatever take is running. */
    private fun stopRecording() {
        synchronized(sessionLock) {
            val active = session ?: return
            if (!isRecording.get()) return
            endTakeLocked(active, TERMINAL_REASON_MANUAL)
        }
    }

    /**
     * End exactly [expected], and nothing else.
     *
     * The identity check and the claim happen under ONE hold of the lock. Checking outside it and then
     * ending "the current session" is the shape that lets a detector result from a take that has already
     * finished stop the recording that started after it.
     */
    private fun endTake(expected: CaptureSession, reason: Int) {
        synchronized(sessionLock) {
            if (session !== expected || !isRecording.get()) return
            endTakeLocked(expected, reason)
        }
    }

    /**
     * Signal the reader to finish. The reader performs the one final native release.
     *
     * Called only while [sessionLock] is held and [active] is still the current session.
     */
    private fun endTakeLocked(active: CaptureSession, reason: Int) {
        if (!claimEnding(active, reason)) return
        // routedDevice returns null once the recorder is inactive, so the final route is read HERE,
        // before stop(); a headset removed just before the stop is then in the record even when its
        // routing callback runs late. A null read preserves the history and proves nothing.
        active.route.observeFinal(active.record)
        try {
            // stop() unblocks a pending read. Do not release here while the reader may
            // still be using the same AudioRecord instance.
            active.record.stop()
        } catch (e: Exception) {
            // The reader's finally block still owns and releases the resources if stop
            // itself fails, so a vendor-specific AudioRecord error cannot leak a session.
            DebugLogger.warn(TAG, "AudioRecord stop failed: ${e.javaClass.simpleName}")
        }
        DebugLogger.mark(TAG, "recording_stop")
        // The ending is read back from the CLAIM rather than from this function's parameter, because the
        // claim is the owner of the answer and the parameter is only what this caller proposed.
        //
        // Naming it here is what makes a stop button and a silence stop tell apart in a log at all. The
        // other two endings already write their own distinct lines from the capture loop; these two wrote
        // the same one. Ordering against the foreground-service line is NOT a discriminator, because
        // `claimEnding` clears `isRecording` before this log runs, so the session's polling thread can log
        // its own promotion first. Measured on the S26 and recorded in issue #114: a silence-ended take
        // did exactly that, by 11 ms.
        DebugLogger.log(
            TAG,
            "Stopped by ${active.endingClaim.ending.label}. ${active.bytesWritten} bytes " +
                "(${String.format("%.1f", PcmAudio.durationSeconds(active.bytesWritten))}s) -> ${active.file.absolutePath}",
        )
    }

    private fun releaseSession(active: CaptureSession) {
        var holding = false
        synchronized(sessionLock) {
            if (session !== active) return
            active.route.stopWatching()
            // Capture-loop endings (cap, byte ceiling, error) reach here with the recorder still active.
            active.route.observeFinal(active.record)
            // The one handoff point every ending reaches: a manual stop, the silence stop and both caps
            // may keep the earbuds warm; an error ending and teardown release everything.
            holding = warmHoldOwner.eligible(active.route, active.endingClaim.ending, active.keepEarbudsReady, destroyed) &&
                warmHoldOwner.start(active.route)
            closeResources(active, keepRoute = holding)
            lastSilenceStatus = active.detector.status
            session = null
            if (captureThread === Thread.currentThread()) captureThread = null
            currentAmplitude = 0f
        }

        // Audio and the PCM file are already closed above. Detector cleanup therefore cannot delay the
        // file becoming ready, which is what the user is waiting for. Nothing here blocks: each owner's
        // close tells its thread to stop and abandons it (#188); the feeder unbinds as it exits.
        active.detector.close(unbindNow = false)
        active.picture.close()
        // Once per take, on EVERY ending (a stop, a silence stop, a cap, a capture error, teardown):
        // release is the one point they all reach. Shape only; `polled` is the proof that no production
        // code polls the picture any more (#187).
        DebugLogger.log(TAG, "Live picture: pushed=${active.picture.pushes.get()} polled=${active.picture.polls.get()}")
        // A hold keeps the service alive; its end calls stopSelf (RULE: the service owns its own end).
        if (!holding) stopSelf()
        // LAST, once per take, after every close and the service-lifetime work: the owner acts on this
        // event and nothing about the take changes after it (#115).
        takeEvents.publishEnded(
            active.takeId,
            terminalReason,
            lastStartFailure,
            active.file.absolutePath,
            lastSilenceStatus,
            takePeakAmplitude,
            lastEffective?.label(),
        )
    }

    /**
     * The route is released HERE, synchronously, before the session slot frees (the caller clears
     * `session` after this returns under `sessionLock`), so a later start can never install a request
     * that an earlier release still has to clear. The loop is over, so the binder call delays no read.
     *
     * With [keepRoute] only the recorder's listener goes (it dies with the `AudioRecord`); the
     * communication ownership stays in the `RouteHold` the warm hold now carries.
     */
    private fun closeResources(active: CaptureSession, keepRoute: Boolean) {
        active.route.close(keepRoute)
        closeResources(active.record, active.output)
    }

    private fun closeResources(record: AudioRecord?, output: FileOutputStream?) {
        runCatching { output?.flush() }
            .onFailure { DebugLogger.warn(TAG, "Failed to flush audio file: ${it.javaClass.simpleName}") }
        runCatching { output?.close() }
            .onFailure { DebugLogger.warn(TAG, "Failed to close audio file: ${it.javaClass.simpleName}") }
        runCatching { record?.stop() }
            .onFailure {
                if (it !is IllegalStateException) DebugLogger.warn(TAG, "Failed to stop AudioRecord: ${it.javaClass.simpleName}")
            }
        val released = record == null || runCatching { record.release() }
            .onFailure { DebugLogger.warn(TAG, "Failed to release AudioRecord: ${it.javaClass.simpleName}") }
            .isSuccess
        // Every caller acquired the lease before creating the recorder (or failing to); released only
        // AFTER the recorder was, so the next start in this process cannot open a second one first. A
        // recorder whose release failed is still held by the native layer for all this code knows, so the
        // lease stays held until the process dies (#115 review round 2).
        if (released) {
            RecorderLease.PROCESS.release()
        } else {
            DebugLogger.warn(TAG, "Recorder release failed; the process's recorder lease stays held")
        }
    }

    /** Wait for the capture thread to finish writing and close the file. */
    fun waitForFileReady(timeoutMs: Long = 2_000L): Boolean {
        val thread = captureThread
        if (thread != null && Thread.currentThread() !== thread) {
            thread.join(timeoutMs.coerceAtLeast(0L))
        }
        val ready = thread?.isAlive != true && session == null
        if (ready) captureThread = null
        return ready
    }

    override fun onDestroy() {
        // Ordered: no hold may start after this flag, so the take stopRecording ends below cannot open
        // one after the route thread is gone (Codex review 1).
        destroyed = true
        synchronized(sessionLock) { warmHoldOwner.close(WarmHold.END_DESTROYED) }
        stopRecording()
        session?.let { active ->
            active.detector.close(unbindNow = true)
            active.picture.close()
        }
        // NOT joined (#115): the capture thread is the sole owner of AudioRecord and the file, and it
        // releases both in its own releaseSession whenever its read returns; a thread parked inside a
        // read is abandoned to process termination rather than held on the main thread for two seconds.
        // A new take in this process cannot open a second recorder while the old one is held, in THIS
        // instance (startRecording refuses while `session` is set) or in a replacement instance in the
        // same process (`RecorderLease.PROCESS` is released only after the recorder is).
        if (captureThread?.isAlive != true) {
            captureThread = null
            synchronized(sessionLock) {
                isRecording.set(false)
            }
        }
        // A hold that slipped in between the flag and the stop is ended here, before its expiry dies.
        synchronized(sessionLock) { warmHoldOwner.close(WarmHold.END_DESTROYED) }
        // The route thread quits after what is already posted; the capture thread's cleanup removes its
        // own listener when it runs.
        routeThread.quitSafely()
        // A take that ended above published its ending through this worker, which delivers what is queued
        // and then leaves (#115). Never joined. Both slots cleared here as well: a destroyed service
        // pushes nothing.
        takeEvents.close()
        spectrumListener.set(null)
        takeListener.set(null)
        super.onDestroy()
    }
}
