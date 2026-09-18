package com.envi.wispr.audio

import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioRouting
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.SystemClock
import com.envi.wispr.debug.DebugLogger
import com.envi.wispr.vad.ISilenceVadService
import com.envi.wispr.vad.SilenceStopDetector
import com.envi.wispr.vad.SilenceVadService
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.LockSupport
import kotlin.math.abs

/** Audio capture service running in a separate process (:audio). */
class AudioCaptureService : Service() {

    companion object {
        private const val TAG = "AudioCapture"
        private const val SAMPLE_RATE = PcmAudio.SAMPLE_RATE
        private const val CHANNEL = AudioFormat.CHANNEL_IN_MONO
        private const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
        private const val AUDIO_FILENAME = "recording.pcm"

        /**
         * How much audio one `AudioRecord.read` asks for: 512 samples, 32 ms at 16 kHz.
         *
         * **This is a different quantity from the buffer the AudioRecord is constructed with**, and the
         * two want opposite things. The native buffer is the margin that stops an overrun when the
         * capture thread is descheduled, so it wants to be large. This is the loop's decision
         * granularity, so it wants to be small: the duration ceiling can only fire on a read boundary,
         * and so can a silence stop. Reading the whole native buffer made both coarse to about a second.
         *
         * Android's own guidance is to read in short frequent chunks rather than waiting for the buffer
         * to fill. 32 ms is what the recorder's live picture needs: a syllable is about 100 ms, and the
         * 256 ms read this replaced handed the meter one averaged number per quarter second, which
         * cannot show a voice (#151).
         */
        private const val READ_CHUNK_BYTES = 1_024

        /**
         * The silence detector's block: 4096 samples, 256 ms at 16 kHz, macOS's detector chunk and what
         * the silence state machine ticks on. Reads are staged into whole blocks; the detector never
         * sees a partial one.
         */
        private const val READ_BLOCK_BYTES = 8_192

        /** Eight chunks of picture backlog, 256 ms: the analyser drains it every wake, so it never fills in practice. */
        private const val SPECTRUM_RING_CHUNKS = 8

        /** The analyser's longest sleep: it re-checks whether its take is over at least this often, unpark or not. */
        private const val ANALYSER_PARK_NS = 50_000_000L

        /**
         * Eight blocks, 2.048 seconds of audio, and the detector's own call deadline is set against it.
         * It is the client-owned deadline for the one binding failure Android gives no signal for: a
         * bind that succeeds and then never connects.
         */
        private const val RING_BLOCKS = 8

        /** How long the feeder waits when there is nothing to do. It is not the capture thread. */
        private const val FEEDER_IDLE_MS = 20L

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
        val startedAtMs: Long,
        val readBuffer: ByteArray,
        val token: Long,
        /** Null when the user has auto-stop off: no ring, no feeder, no detector process. */
        val ring: BlockRing?,
        /** Staging for a read that did not land on a block boundary. Preallocated, like everything else. */
        val pendingBlock: ByteArray?,
        /** What actually captured this take, in order. Read over the binder; outlives the session. */
        val effective: EffectiveDevice,
        /** This take's route ownership. Released in [closeResources], before the session slot frees. */
        val routeHold: RouteHold,
        /** Capture thread offers reads; the route thread reads the state and runs the deadline. */
        val gate: LiveGate,
        /** The take asked for earbuds (a Bluetooth target); the phone may then only record if picked. */
        val targetBluetooth: Boolean,
        val phonePicked: Boolean,
        /** The communication sink the take selected, for the one reset and for the hold. Null off Bluetooth. */
        val sink: AudioDeviceInfo?,
        /** The setting, frozen per take like the pick. */
        val keepEarbudsReady: Boolean,
    ) {
        /** Set on the capture thread when the gate opens; the timer and the duration cap count from here. */
        @Volatile var liveAtMs: Long = 0L

        /** True once the first admitted block is on disk: only then does the binder report READY or FORCED. */
        @Volatile var liveVisible: Boolean = false

        /** The route thread's deadline message for this take, removed at every end. */
        @Volatile var deadline: Runnable? = null

        /**
         * The earbuds this take asked for have been removed (their sink left the device list). Set on
         * the route thread by [sinkWatch]; read on the capture thread. Once true the phone may record:
         * the earbuds are disconnected, which is the one case the founder's rule allows.
         */
        @Volatile var sinkGone: Boolean = false
        @Volatile var sinkWatch: AudioDeviceCallback? = null

        /** Capture thread only. */
        var pendingBytes: Int = 0

        /** Capture thread only. The take position of the first byte staged in [pendingBlock]. */
        var pendingPosition: Long = 0L

        /**
         * The picture path. The capture thread offers every read here with its position; the analyser
         * thread drains it and publishes into [publishedBands] under [bandsLock], which the binder getter
         * shares and the capture thread never touches. All of it belongs to THIS take: a thread that
         * outlives its take writes into a dead session's array, and the getter reads the live one.
         */
        val spectrumRing = BlockRing(SPECTRUM_RING_CHUNKS, READ_CHUNK_BYTES)
        val publishedBands = FloatArray(SpectrumAnalyzer.BAND_COUNT)
        val bandsLock = Any()
        @Volatile var analyserThread: Thread? = null

        /**
         * Everything about the detector belongs to the take that started it.
         *
         * Held here rather than on the service so that a feeder or a connection callback belonging to a
         * finished take cannot set the status of, or unbind the detector of, the take running now.
         */
        val detectorAbandoned = AtomicBoolean(false)
        val silenceStatus = AtomicInteger(
            if (ring == null) SILENCE_STATUS_DISABLED else SILENCE_STATUS_PREPARING,
        )
        @Volatile var vadService: ISilenceVadService? = null
        @Volatile var vadBound: Boolean = false
        @Volatile var feederThread: Thread? = null
        @Volatile var vadConnection: ServiceConnection? = null
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

    /**
     * The warm hold between takes, or null. Written under [sessionLock]. The sink it holds is identified
     * by type and product name (never by id, which changes between reads on this phone).
     */
    @Volatile private var warmHold: WarmHold? = null
    @Volatile private var heldSinkType: Int = -1
    @Volatile private var heldSinkName: String = ""
    @Volatile private var holdExpiry: Runnable? = null

    /** Set first thing in `onDestroy`: no take that ends after this may start a hold (Codex review 1). */
    @Volatile private var destroyed = false

    /** A warm hold's route on its way to the next take, with the identity the hold was keeping. */
    private class HandedRoute(val route: RouteHold, val sinkType: Int, val sinkName: String)
    private var holdCommListener: AudioManager.OnCommunicationDeviceChangedListener? = null
    private var holdDeviceCallback: AudioDeviceCallback? = null

    /**
     * Owns every routing callback and nothing else. A callback carries the session it was registered
     * for and writes nothing once that session's hold is released.
     */
    private lateinit var routeThread: HandlerThread
    private lateinit var routeHandler: Handler
    private val isRecording = AtomicBoolean(false)
    @Volatile private var captureThread: Thread? = null
    @Volatile private var lastAudioFile: File? = null
    @Volatile private var currentAmplitude = 0f
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

    /**
     * Every way this binding can fail, and they all mean the same thing to a take: auto-stop is off for
     * it, and recording continues.
     *
     * The connection is built PER TAKE and captures the session it belongs to. A callback that arrives
     * after its take ended can then do nothing at all, rather than clearing the status or unbinding the
     * detector of whatever is recording now. `onNullBinding` and `onBindingDied` unbind explicitly,
     * because Android reconnects a disconnected binding on its own.
     */
    private fun vadConnectionFor(active: CaptureSession) = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            if (session !== active) {
                unbindVad(active)
                return
            }
            active.vadService = ISilenceVadService.Stub.asInterface(binder)
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            active.vadService = null
            abandonDetector(active)
        }

        override fun onNullBinding(name: ComponentName?) {
            active.vadService = null
            abandonDetector(active)
            unbindVad(active)
        }

        override fun onBindingDied(name: ComponentName?) {
            active.vadService = null
            abandonDetector(active)
            unbindVad(active)
        }
    }

    override fun onCreate() {
        super.onCreate()
        routeThread = HandlerThread("AudioRouteThread").also { it.start() }
        routeHandler = Handler(routeThread.looper)
    }

    private val binder = object : IAudioCaptureService.Stub() {
        override fun startCapture(): Boolean =
            this@AudioCaptureService.startRecording(autoStopOnSilence = false, pauseSeconds = 0f, pick = InputDevicePick.Auto)

        override fun startCaptureWithSilenceStop(autoStopOnSilence: Boolean, pauseSeconds: Float): Boolean =
            this@AudioCaptureService.startRecording(autoStopOnSilence, pauseSeconds, InputDevicePick.Auto)

        override fun startCaptureWithInputDevice(autoStopOnSilence: Boolean, pauseSeconds: Float, inputDevicePick: String?): Boolean =
            this@AudioCaptureService.startRecording(autoStopOnSilence, pauseSeconds, InputDevicePick.parse(inputDevicePick))

        override fun startCaptureWithInputDeviceHeld(autoStopOnSilence: Boolean, pauseSeconds: Float, inputDevicePick: String?, keepEarbudsReady: Boolean): Boolean =
            this@AudioCaptureService.startRecording(autoStopOnSilence, pauseSeconds, InputDevicePick.parse(inputDevicePick), keepEarbudsReady)

        override fun getLiveState(): Int {
            val active = this@AudioCaptureService.session ?: return LIVE_WAITING
            if (!active.liveVisible) return LIVE_WAITING
            return when (active.gate.state) {
                LiveGate.State.WAITING -> LIVE_WAITING
                LiveGate.State.READY -> LIVE_READY
                LiveGate.State.FORCED -> LIVE_FORCED
            }
        }

        override fun getLiveAfterMs(): Long {
            val active = this@AudioCaptureService.session ?: return 0L
            val live = active.liveAtMs
            return if (live > 0L) live - active.startedAtMs else 0L
        }

        override fun finishTake(): Boolean = this@AudioCaptureService.finishTake()

        override fun getEffectiveInputDevice(): String = this@AudioCaptureService.lastEffective?.label().orEmpty()
        override fun getInputRouteKind(): Int = this@AudioCaptureService.lastEffective?.kind?.code ?: InputRouteKind.NONE.code
        override fun getInputRouteReason(): Int = this@AudioCaptureService.lastEffective?.reasonCode() ?: InputRouteReason.AUTO.code
        override fun getLastStartFailure(): Int = this@AudioCaptureService.lastStartFailure

        override fun getSilenceStopStatus(): Int =
            this@AudioCaptureService.session?.silenceStatus?.get() ?: SILENCE_STATUS_DISABLED
        override fun stopCapture() = this@AudioCaptureService.stopRecording()
        override fun isCapturing(): Boolean = this@AudioCaptureService.isRecording.get()
        override fun getTerminalReason(): Int = this@AudioCaptureService.terminalReason
        override fun getCurrentAmplitude(): Float = this@AudioCaptureService.currentAmplitude

        override fun getSpectrumBands(): FloatArray {
            // Always BAND_COUNT long, never empty: the length is the contract. Zeros when no take is open.
            val active = this@AudioCaptureService.session ?: return FloatArray(SpectrumAnalyzer.BAND_COUNT)
            return synchronized(active.bandsLock) { active.publishedBands.copyOf() }
        }
        override fun getAudioFilePath(): String? = this@AudioCaptureService.lastAudioFile?.absolutePath

        override fun getElapsedMs(): Long {
            val active = this@AudioCaptureService.session
            // From LIVE, not from the recorder's start: the wait for the earbuds is not the user's time.
            val live = active?.liveAtMs ?: 0L
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
     * Started only by [finishTake], to outlive the owner's unbind while a hold runs. Never sticky: a
     * restart after a kill would have nothing to hold, so it ends at once.
     */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        synchronized(sessionLock) { if (warmHold?.isActive != true && session == null) stopSelf() }
        return START_NOT_STICKY
    }

    private fun startRecording(
        autoStopOnSilence: Boolean,
        pauseSeconds: Float,
        pick: InputDevicePick,
        keepEarbudsReady: Boolean = false,
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
            if (session != null) return false
            lastStartFailure = START_FAILURE_NONE

            // Route ownership exists BEFORE the session, so every failure path below can release it.
            val audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
            // Filled in once the listener exists; the hold removes exactly this one and no other.
            var routingListener: Pair<AudioRecord, AudioRouting.OnRoutingChangedListener>? = null
            val routeHold = RouteHold(
                clearCommunicationDevice = { audioManager.clearCommunicationDevice() },
                removeListener = { routingListener?.let { (r, l) -> r.removeOnRoutingChangedListener(l) } },
            )
            // A warm hold hands its route to this take (the link stays up; V13: live at ~120 ms). Any hold
            // that does not match the resolved target is ended by resolveRoute before it sets anything.
            // The identity is read BEFORE the handover: handOver ends the hold, and the hold's end clears
            // its bookkeeping synchronously (Codex review 1).
            val handedOver = warmHold?.let { hold ->
                val type = heldSinkType
                val name = heldSinkName
                hold.handOver()?.let { HandedRoute(it, type, name) }
            }
            val route = resolveRoute(audioManager, pick, routeHold, handedOver) ?: run {
                lastStartFailure = START_FAILURE_NO_INPUT_DEVICE
                routeHold.release()
                DebugLogger.error(TAG, "No input device at all; refusing to start")
                stopSelf()
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
                    "Buffer sizes: minimum=$minimum coerced=$coerced read=$READ_CHUNK_BYTES block=$READ_BLOCK_BYTES",
                )
                coerced
            } catch (e: Exception) {
                DebugLogger.error(TAG, "Failed to determine audio buffer size", e)
                lastStartFailure = START_FAILURE_OTHER
                routeHold.release()
                stopSelf()
                return false
            }

            var record: AudioRecord? = null
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
                applyPreferredDevice(record, route, effective, routeHold)

                // cacheDir is app-internal and shared by this package's processes. It is
                // excluded from user backups and is less exposed than shared storage.
                val file = File(cacheDir, AUDIO_FILENAME)
                // There is no active session under sessionLock, so no writer can own this
                // path. Remove a partial take left by process death before opening a new one.
                if (file.exists() && !file.delete()) {
                    throw IOException("Unable to remove stale audio recording")
                }
                output = FileOutputStream(file)
                record.startRecording()
                // The route is the target from the first read when the preferred device was set first
                // (V2, 2026-09-16: routedDevice already the SCO source at startRecording).
                record.routedDevice?.let { effective.observe(it.type, it.productName?.toString().orEmpty()) }

                val newSession = CaptureSession(
                    record = record,
                    file = file,
                    output = output,
                    startedAtMs = SystemClock.elapsedRealtime(),
                    // Allocated HERE, before the thread starts, and never inside the capture loop. The
                    // capture thread may not allocate: it must do nothing that can make it late.
                    readBuffer = ByteArray(READ_CHUNK_BYTES),
                    token = nextCaptureToken(),
                    ring = if (detectorEnabled) BlockRing(RING_BLOCKS, READ_BLOCK_BYTES) else null,
                    pendingBlock = if (detectorEnabled) ByteArray(READ_BLOCK_BYTES) else null,
                    effective = effective,
                    routeHold = routeHold,
                    gate = LiveGate(gated = route.needsBluetooth),
                    targetBluetooth = route.needsBluetooth,
                    phonePicked = pick is InputDevicePick.Device && InputRouteKind.of(pick.type) == InputRouteKind.PHONE,
                    sink = route.sink,
                    keepEarbudsReady = keepEarbudsReady,
                )
                registerRoutingListener(record, newSession)?.let { routingListener = record to it }
                session = newSession
                lastEffective = effective
                lastAudioFile = file
                isRecording.set(true)
                terminalReason = TERMINAL_REASON_NONE
                currentAmplitude = 0f
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
                    newSession.silenceStatus.set(SILENCE_STATUS_UNAVAILABLE)
                    DebugLogger.warn(TAG, "Auto-stop refused: pause $pauseSeconds is out of range")
                }
                if (detectorEnabled) startSilenceDetection(newSession, validPause!!)

                val thread = Thread({ captureLoop(newSession) }, "AudioCaptureThread")
                captureThread = thread
                try {
                    thread.start()
                } catch (e: Exception) {
                    isRecording.set(false)
                    session = null
                    closeResources(newSession, keepRoute = false)
                    captureThread = null
                    stopSelf()
                    DebugLogger.error(TAG, "Failed to start capture thread", e)
                    return false
                }
                startSpectrumAnalysis(newSession)
                if (route.needsBluetooth) {
                    watchSink(newSession)
                    armDeadline(newSession)
                } else {
                    markLive(newSession)
                }
                return thread.isAlive && session === newSession && isRecording.get()
            } catch (e: SecurityException) {
                DebugLogger.error(TAG, "RECORD_AUDIO permission not granted", e)
                lastStartFailure = START_FAILURE_OTHER
                isRecording.set(false)
                routeHold.release()
                closeResources(record, output)
                stopSelf()
                return false
            } catch (e: Exception) {
                DebugLogger.error(TAG, "Failed to start recording", e)
                lastStartFailure = START_FAILURE_OTHER
                isRecording.set(false)
                routeHold.release()
                closeResources(record, output)
                stopSelf()
                return false
            }
        }
    }

    /** What `startRecording` needs to know about the chosen route, resolved under `sessionLock`. */
    private class ResolvedRoute(
        val target: InputDeviceCandidate,
        val info: AudioDeviceInfo,
        val reason: InputRouteReason,
        val needsBluetooth: Boolean,
        /** The communication sink selected for a Bluetooth target; null otherwise or when none was found. */
        val sink: AudioDeviceInfo?,
    )

    /**
     * Pick the device and, for a Bluetooth target, select the headset as the communication device: the
     * one call that makes Android open the link for `VOICE_RECOGNITION` (measured 2026-09-16: the
     * preferred device alone records silence; the communication device alone starts on the phone).
     *
     * A refusal on the Bluetooth path (no sink, `false`, a throw) does NOT re-resolve onto the phone: with
     * earbuds connected the phone may only record when picked (founder rule 2026-09-18). The take keeps
     * the earbud source with `LINK_REFUSED`, and the live gate's reset and notice speak for it. Returns
     * null only when nothing at all can record.
     *
     * [handedOver] is a warm hold's route: when its sink is the one this take wants, the platform request
     * is adopted untouched and no call is made; otherwise it is released here before anything is set.
     */
    private fun resolveRoute(
        audioManager: AudioManager,
        pick: InputDevicePick,
        hold: RouteHold,
        handedOver: HandedRoute?,
    ): ResolvedRoute? {
        val infos = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS).toList()
        val candidates = infos.map(InputDeviceCandidate::from)
        val resolution = InputDeviceResolver.resolve(pick, candidates)
        val target = resolution.target ?: run { handedOver?.route?.release(); return null }
        var reason = resolution.reason
        var sinkInfo: AudioDeviceInfo? = null

        if (InputDeviceResolver.needsBluetoothRoute(target)) {
            val opened = runCatching {
                val available = audioManager.availableCommunicationDevices
                val sink = InputDeviceResolver.communicationSinkFor(target, available.map(InputDeviceCandidate::from))
                    ?: return@runCatching false
                sinkInfo = available.first { it.id == sink.id }
                val held = handedOver != null && handedOver.sinkType == sink.type && handedOver.sinkName == sink.name
                if (held) {
                    hold.adoptCommunicationFrom(handedOver!!.route)
                    DebugLogger.log(TAG, "route adopt=${target.label} from the warm hold")
                    true
                } else {
                    handedOver?.route?.release()
                    audioManager.setCommunicationDevice(sinkInfo!!).also { if (it) hold.markCommunicationSet() }
                }
            }.getOrElse { e ->
                DebugLogger.warn(TAG, "setCommunicationDevice threw: ${e.message}")
                false
            }
            if (!opened) {
                DebugLogger.warn(TAG, "Bluetooth link refused for ${target.label}; staying on the earbuds")
                handedOver?.route?.release()
                hold.releaseCommunicationDevice()
                reason = InputRouteReason.LINK_REFUSED
            }
        } else {
            handedOver?.route?.release()
        }
        val info = infos.firstOrNull { it.id == target.id } ?: return null
        return ResolvedRoute(
            target = target,
            info = info,
            reason = reason,
            needsBluetooth = InputDeviceResolver.needsBluetoothRoute(target),
            sink = sinkInfo,
        )
    }

    /**
     * Only a Bluetooth target, or any explicit pick, names a preferred device; Auto on wired, USB or the
     * phone leaves today's behaviour untouched. A refusal is recorded and the take proceeds on whatever
     * Android routes, reported truthfully by `routedDevice`, never by the target.
     */
    private fun applyPreferredDevice(record: AudioRecord, route: ResolvedRoute, effective: EffectiveDevice, hold: RouteHold) {
        if (!route.needsBluetooth && route.reason != InputRouteReason.PICKED) return
        val accepted = runCatching { record.setPreferredDevice(route.info) }.getOrDefault(false)
        if (!accepted) {
            DebugLogger.warn(TAG, "setPreferredDevice refused for ${route.target.label}")
            effective.markReason(InputRouteReason.PREFERRED_REFUSED)
            // The link is given back; listener ownership stays with the take so its route changes are recorded.
            hold.releaseCommunicationDevice()
        }
    }

    /**
     * Registered on the route thread, removed by the hold. A callback checks the hold before writing so
     * one already running when cleanup starts writes nothing; a callback for a dead session finds its
     * own session object, never the live one.
     */
    private fun registerRoutingListener(record: AudioRecord, active: CaptureSession): AudioRouting.OnRoutingChangedListener? {
        val listener = AudioRouting.OnRoutingChangedListener { router ->
            if (active.routeHold.isReleased) return@OnRoutingChangedListener
            val device = runCatching { router.routedDevice }.getOrNull() ?: return@OnRoutingChangedListener
            active.effective.observe(device.type, device.productName?.toString().orEmpty())
            DebugLogger.log(TAG, "route change=${active.effective.label()} at ${active.bytesWritten} bytes")
        }
        return runCatching {
            record.addOnRoutingChangedListener(listener, routeHandler)
        }.map {
            active.routeHold.markListenerSet()
            listener
        }.onFailure { DebugLogger.warn(TAG, "Routing listener not registered: ${it.message}") }
            .getOrNull()
    }

    /** Read the final route while the recorder is still active. Null preserves the history as it stands. */
    private fun observeFinalRoute(active: CaptureSession) {
        val device = runCatching { active.record.routedDevice }.getOrNull() ?: return
        active.effective.observe(device.type, device.productName?.toString().orEmpty())
    }

    /**
     * May a read on the OBSERVED route open the gate? An earbud target that Android is routing to the
     * phone may not, unless the phone was picked or the earbuds have left: the founder's rule, applied
     * to the observation and never to the request. A route not yet observed is not refused.
     *
     * The rule binds what this app SELECTS, and is enforced at the gate. A route Android moves by itself
     * once the take is live (V7, a call taking the link) is recorded on the History card ("AirPods Pro 3,
     * then Phone") and not fought: ending a take mid-sentence would lose the words, and capture must never
     * fail (architecture: heart and limbs). Decided at Codex code review 5, 2026-09-18.
     */
    private fun routeAdmissible(active: CaptureSession): Boolean =
        !active.targetBluetooth || active.phonePicked || active.sinkGone ||
            active.effective.currentKind != InputRouteKind.PHONE

    /**
     * Watch the take's earbuds leave, on the route thread, so the gate can admit the phone once they
     * are gone (V7: Android moves the route itself within 120 ms). Registered after the session exists,
     * removed in [releaseSession].
     */
    private fun watchSink(active: CaptureSession) {
        val sink = active.sink ?: return
        val type = sink.type
        val name = sink.productName?.toString().orEmpty()
        val audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
        val callback = object : AudioDeviceCallback() {
            override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>) {
                if (removed.any { it.type == type && it.productName?.toString().orEmpty() == name }) {
                    active.sinkGone = true
                    DebugLogger.log(TAG, "route earbuds removed while ${active.gate.state}; the phone may record")
                }
            }
        }
        active.sinkWatch = callback
        runCatching { audioManager.registerAudioDeviceCallback(callback, routeHandler) }
            .onFailure { DebugLogger.warn(TAG, "sink watch not registered: ${it.message}") }
        // Reconcile once: a removal between route resolution and this registration is not replayed by
        // the callback (Codex review 5). The list is read AFTER registering, so nothing can fall between.
        val stillOffered = runCatching {
            audioManager.availableCommunicationDevices.any { it.type == type && it.productName?.toString().orEmpty() == name }
        }.getOrDefault(true)
        if (!stillOffered) {
            active.sinkGone = true
            DebugLogger.log(TAG, "route earbuds already gone at start; the phone may record")
        }
    }

    /** Capture thread, on the read that opened the gate. */
    private fun markLive(active: CaptureSession) {
        active.liveAtMs = SystemClock.elapsedRealtime()
        routeHandler.post {
            active.deadline?.let { routeHandler.removeCallbacks(it) }
            active.deadline = null
            DebugLogger.log(
                TAG,
                "route live=${active.effective.label()} after ${active.liveAtMs - active.startedAtMs} ms " +
                    "resets=${active.gate.resetsUsed} state=${active.gate.state}",
            )
        }
    }

    /**
     * The live deadline runs on the route thread as a clock, so a blocked read cannot starve it. First
     * miss: reset the communication device once, if the sink is still there. Second miss: proceed without
     * sound on the earbuds (FORCED), or, when the observed route is the phone with earbuds connected,
     * fail the take rather than record from the phone.
     */
    private fun armDeadline(active: CaptureSession) {
        val runnable = object : Runnable {
            override fun run() {
                synchronized(sessionLock) {
                    if (session !== active || !isRecording.get() || active.gate.state != LiveGate.State.WAITING) return
                    when (active.gate.deadlinePassed()) {
                        LiveGate.DeadlineAction.NONE -> return
                        LiveGate.DeadlineAction.RESET -> {
                            val reset = resetCommunicationDevice(active)
                            DebugLogger.warn(TAG, "route reset=${active.effective.label()} performed=$reset after ${LiveGate.DEADLINE_MS} ms")
                            active.deadline = this
                            routeHandler.postDelayed(this, LiveGate.DEADLINE_MS)
                        }
                        LiveGate.DeadlineAction.FORCE -> {
                            active.deadline = null
                            if (routeAdmissible(active)) {
                                active.gate.force()
                                active.liveAtMs = SystemClock.elapsedRealtime()
                                DebugLogger.warn(TAG, "route forced=${active.effective.label()} after ${active.liveAtMs - active.startedAtMs} ms")
                            } else {
                                lastStartFailure = START_FAILURE_EARBUDS
                                DebugLogger.warn(TAG, "route refused=${active.effective.label()}: earbuds connected, phone would record; failing the take")
                                endTakeLocked(active, TERMINAL_REASON_ERROR)
                            }
                        }
                    }
                }
            }
        }
        active.deadline = runnable
        routeHandler.postDelayed(runnable, LiveGate.DEADLINE_MS)
    }

    /** Route thread, under `sessionLock`. Clear and re-select the sink, only while it is still offered. */
    private fun resetCommunicationDevice(active: CaptureSession): Boolean {
        val sink = active.sink ?: return false
        val audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
        val stillThere = runCatching {
            audioManager.availableCommunicationDevices.any { it.type == sink.type && it.productName?.toString() == sink.productName?.toString() }
        }.getOrDefault(false)
        if (!stillThere) return false
        return runCatching {
            audioManager.clearCommunicationDevice()
            audioManager.setCommunicationDevice(sink).also { if (it) active.routeHold.markCommunicationSet() }
        }.getOrElse { e ->
            DebugLogger.warn(TAG, "communication device reset threw: ${e.message}")
            false
        }
    }

    private fun captureLoop(active: CaptureSession) {
        val buffer = active.readBuffer
        try {
            while (isRecording.get() && session === active) {
                // The cap counts from LIVE; the wait for the earbuds has its own bound in the session owner.
                val live = active.liveAtMs
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

                // Until the gate opens, a read feeds the gate and nothing else: not the file, not the
                // detector, not the picture, not the level. The take's clock starts when the gate opens.
                if (active.gate.state == LiveGate.State.WAITING) {
                    val admissible = routeAdmissible(active)
                    if (active.gate.offer(buffer, bytesRead, admissible)) markLive(active) else continue
                }

                val position = active.bytesWritten
                active.output.write(buffer, 0, bytesRead)
                active.bytesWritten += bytesRead
                active.liveVisible = true
                offerToDetector(active, buffer, bytesRead, position)
                // The picture is a limb: a refused offer drops this chunk and nothing else. The analyser
                // sees the drop as a jump in position and starts its window afresh (SpectrumAnalyzer).
                active.spectrumRing.offer(buffer, bytesRead, position)
                LockSupport.unpark(active.analyserThread)

                var sum = 0L
                for (i in 0 until bytesRead step PcmAudio.BYTES_PER_SAMPLE) {
                    if (i + 1 < bytesRead) {
                        val sample = (buffer[i].toInt() and 0xFF) or
                            (buffer[i + 1].toInt() shl 8)
                        sum += abs(sample)
                    }
                }
                val numSamples = bytesRead / PcmAudio.BYTES_PER_SAMPLE
                currentAmplitude = if (numSamples > 0) {
                    (sum.toFloat() / numSamples) / Short.MAX_VALUE
                } else 0f
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
     * Capture thread only. Copies audio toward the detector and never waits for it.
     *
     * A read that does not land on a block boundary is staged, so the detector always sees whole 256 ms
     * blocks in order. **Nothing here logs, allocates, locks or calls across a process.**
     *
     * A full ring means the detector has fallen further behind than it can recover from. Auto-stop is
     * abandoned for the rest of the take and never resumed: resuming across dropped audio breaks the
     * model's recurrent continuity, and speech that resumed inside the gap could then read as silence.
     */
    private fun offerToDetector(active: CaptureSession, buffer: ByteArray, bytesRead: Int, position: Long) {
        val ring = active.ring ?: return
        val pending = active.pendingBlock ?: return
        if (active.detectorAbandoned.get()) return

        var consumed = 0
        while (consumed < bytesRead) {
            if (active.pendingBytes == 0) active.pendingPosition = position + consumed
            val room = READ_BLOCK_BYTES - active.pendingBytes
            val take = minOf(room, bytesRead - consumed)
            System.arraycopy(buffer, consumed, pending, active.pendingBytes, take)
            active.pendingBytes += take
            consumed += take
            if (active.pendingBytes == READ_BLOCK_BYTES) {
                active.pendingBytes = 0
                if (!ring.offer(pending, READ_BLOCK_BYTES, active.pendingPosition)) {
                    // Flag only. The feeder notices and does the logging, off this thread.
                    abandonDetector(active)
                    return
                }
            }
        }
    }

    /**
     * Start the one thread that turns this take's audio into the recorder's picture.
     *
     * Runs AFTER the capture thread is up, and its own failure is its own: a thread that cannot be
     * constructed or started leaves the take with no picture (the published bands stay zero, the pill
     * shows its resting rail) and touches none of the capture resources. The picture is a limb.
     */
    private fun startSpectrumAnalysis(active: CaptureSession) {
        runCatching {
            val thread = Thread({ analyserLoop(active) }, "SpectrumAnalyserThread")
            active.analyserThread = thread
            thread.start()
        }.onFailure {
            active.analyserThread = null
            DebugLogger.warn(TAG, "Live picture unavailable for this take: ${it.message}")
        }
    }

    /**
     * Analyser thread only. Drains the picture ring, analyses every queued chunk in order and publishes
     * once per wake, so what the recorder reads is always the newest audio the ring held.
     *
     * Exits when its take is no longer the live one, when its ending has been claimed, or when it is
     * interrupted, and checks all three at least every [ANALYSER_PARK_NS] whether or not the capture
     * thread unparks it. Never joined: it holds nothing a stop waits for.
     *
     * A failure publishes the zero picture before leaving, so the rail rests rather than holding the
     * last shape it was given, and it costs the picture only: the take does not know this thread exists.
     */
    private fun analyserLoop(active: CaptureSession) {
        val chunk = ByteArray(READ_CHUNK_BYTES)
        val bands = FloatArray(SpectrumAnalyzer.BAND_COUNT)
        try {
            val analyzer = SpectrumAnalyzer()
            while (session === active && !active.stopRequested && !Thread.currentThread().isInterrupted) {
                var analysed = false
                while (true) {
                    val length = active.spectrumRing.poll(chunk)
                    if (length < 0) break
                    analyzer.analyze(chunk, length, active.spectrumRing.lastPolledTag, bands)
                    analysed = true
                }
                if (analysed) {
                    synchronized(active.bandsLock) {
                        System.arraycopy(bands, 0, active.publishedBands, 0, SpectrumAnalyzer.BAND_COUNT)
                    }
                }
                LockSupport.parkNanos(ANALYSER_PARK_NS)
            }
        } catch (e: Exception) {
            synchronized(active.bandsLock) { active.publishedBands.fill(0f) }
            DebugLogger.warn(TAG, "Live picture stopped for this take: ${e.message}")
        } finally {
            if (active.analyserThread === Thread.currentThread()) active.analyserThread = null
        }
    }

    /**
     * Bind the detector process for one take and start the one thread allowed to talk to it.
     *
     * Capture has already started by the time this runs, so a slow or failed detector delays nothing. A
     * take whose detector never becomes ready is simply a take the user stops by hand.
     */
    private fun startSilenceDetection(active: CaptureSession, pauseSeconds: Float) {
        val connection = vadConnectionFor(active)
        active.vadConnection = connection

        active.vadBound = runCatching {
            bindService(
                Intent(this, SilenceVadService::class.java),
                connection,
                Context.BIND_AUTO_CREATE,
            )
        }.getOrDefault(false)

        if (!active.vadBound) {
            active.vadConnection = null
            abandonDetector(active)
            DebugLogger.warn(TAG, "Auto-stop unavailable: detector service could not be bound")
            return
        }

        val thread = Thread({ feederLoop(active, pauseSeconds) }, "SilenceFeederThread")
        active.feederThread = thread
        runCatching { thread.start() }
            .onFailure {
                active.feederThread = null
                abandonDetector(active)
                unbindVad(active)
                DebugLogger.warn(TAG, "Auto-stop unavailable: detector feeder could not start")
            }
    }

    /**
     * The only thread that calls the detector. It is allowed to block; the capture thread is not.
     *
     * The abandonment flag is checked at the top of every pass AND immediately after every remote call,
     * because a gap can open while a call is in flight and a verdict computed from the blocks before a
     * gap must never be applied to the audio after it.
     */
    private fun feederLoop(active: CaptureSession, pauseSeconds: Float) {
        val ring = active.ring ?: return
        val block = ByteArray(READ_BLOCK_BYTES)
        var started = false
        var reportedAbandon = false

        fun shouldStop(): Boolean {
            if (active.detectorAbandoned.get()) {
                if (!reportedAbandon) {
                    reportedAbandon = true
                    DebugLogger.warn(TAG, "Auto-stop abandoned for this take")
                }
                return true
            }
            return session !== active || active.stopRequested
        }

        try {
            while (!shouldStop()) {
                val remote = active.vadService
                if (remote == null) {
                    Thread.sleep(FEEDER_IDLE_MS)
                    continue
                }

                if (!started) {
                    val status = runCatching { remote.start(active.token, pauseSeconds) }
                        .getOrElse {
                            abandonDetector(active)
                            DebugLogger.warn(TAG, "Auto-stop unavailable: start failed, ${it.message}")
                            return
                        }
                    if (shouldStop()) return
                    if (status != SilenceVadService.STATUS_READY) {
                        abandonDetector(active)
                        DebugLogger.warn(TAG, "Auto-stop unavailable: the detector reported so")
                        return
                    }
                    started = true
                    active.silenceStatus.compareAndSet(
                        SILENCE_STATUS_PREPARING,
                        SILENCE_STATUS_READY,
                    )
                }

                val length = ring.poll(block)
                if (length <= 0) {
                    Thread.sleep(FEEDER_IDLE_MS)
                    continue
                }

                val result = runCatching { remote.processBlock(active.token, block) }
                    .getOrElse {
                        abandonDetector(active)
                        DebugLogger.warn(TAG, "Auto-stop unavailable: the detector call failed")
                        return
                    }

                if (shouldStop()) return

                when (result) {
                    SilenceVadService.RESULT_SILENCE -> {
                        // endTake re-checks that this session is still the live one, under the lock, so
                        // a verdict from a finished take cannot end the take running now.
                        endTake(active, TERMINAL_REASON_SILENCE)
                        return
                    }

                    SilenceVadService.RESULT_UNAVAILABLE -> {
                        abandonDetector(active)
                        DebugLogger.warn(TAG, "Auto-stop unavailable: the detector gave up mid-take")
                        return
                    }
                }
            }
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (e: Exception) {
            abandonDetector(active)
            DebugLogger.warn(TAG, "Auto-stop unavailable: the feeder failed, ${e.message}")
        } finally {
            if (started) runCatching { active.vadService?.finish(active.token) }
            unbindVad(active)
            if (active.feederThread === Thread.currentThread()) active.feederThread = null
        }
    }

    /**
     * Auto-stop is off for the rest of THIS take, and is never resumed within it.
     *
     * The status it lands on records whether the detector ever worked. A take that never got one tells
     * the user; a take that had one and lost it does not, because that recording is still correct and a
     * message part way through is an interruption for nothing.
     */
    private fun abandonDetector(active: CaptureSession) {
        active.detectorAbandoned.set(true)
        while (true) {
            val previous = active.silenceStatus.get()
            val next = when (previous) {
                SILENCE_STATUS_DISABLED,
                SILENCE_STATUS_UNAVAILABLE,
                SILENCE_STATUS_LOST_AFTER_READY -> return

                SILENCE_STATUS_READY -> SILENCE_STATUS_LOST_AFTER_READY
                else -> SILENCE_STATUS_UNAVAILABLE
            }
            if (active.silenceStatus.compareAndSet(previous, next)) return
        }
    }

    /** Unbinds only [active]'s own connection, so a finished take cannot unbind a running one's. */
    private fun unbindVad(active: CaptureSession) {
        if (!active.vadBound) return
        active.vadBound = false
        val connection = active.vadConnection ?: return
        active.vadConnection = null
        active.vadService = null
        runCatching { unbindService(connection) }
            .onFailure { DebugLogger.warn(TAG, "Detector unbind failed: ${it.message}") }
    }

    /**
     * Publish the first ending claimed for [active] and stop the loop.
     *
     * The published reason must outlive [releaseSession], because the client polls `getTerminalReason`
     * only AFTER `isCapturing` has gone false, by which point the session is gone. So the value lives on
     * the service while the CLAIM lives on the session, and the claim is what makes it first-wins.
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
        observeFinalRoute(active)
        try {
            // stop() unblocks a pending read. Do not release here while the reader may
            // still be using the same AudioRecord instance.
            active.record.stop()
        } catch (e: Exception) {
            // The reader's finally block still owns and releases the resources if stop
            // itself fails, so a vendor-specific AudioRecord error cannot leak a session.
            DebugLogger.warn(TAG, "AudioRecord stop failed: ${e.message}")
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
            active.deadline?.let { routeHandler.removeCallbacks(it) }
            active.deadline = null
            active.sinkWatch?.let { w ->
                runCatching { (getSystemService(AUDIO_SERVICE) as AudioManager).unregisterAudioDeviceCallback(w) }
            }
            active.sinkWatch = null
            // Capture-loop endings (cap, byte ceiling, error) reach here with the recorder still active.
            observeFinalRoute(active)
            // The one handoff point every ending reaches: a manual stop, the silence stop and both caps
            // may keep the earbuds warm; an error ending and teardown release everything.
            holding = holdEligible(active) && startWarmHold(active)
            closeResources(active, keepRoute = holding)
            session = null
            if (captureThread === Thread.currentThread()) captureThread = null
            currentAmplitude = 0f
        }

        // Audio and the PCM file are already closed above. Detector cleanup therefore cannot delay the
        // file becoming ready, which is what the user is waiting for. Nothing here blocks: the feeder is
        // told to stop and abandoned, and it holds no recorder, no stream, no ring slot and no reference
        // to a later take.
        active.detectorAbandoned.set(true)
        active.feederThread?.interrupt()
        active.analyserThread?.interrupt()
        // A hold keeps the service alive; its end calls stopSelf (RULE: the service owns its own end).
        if (!holding) stopSelf()
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
        if (keepRoute) active.routeHold.releaseListener() else active.routeHold.release()
        closeResources(active.record, active.output)
    }

    // ---- The warm hold ----

    /** Under `sessionLock`. */
    private fun holdEligible(active: CaptureSession): Boolean {
        if (destroyed) return false
        if (!active.keepEarbudsReady || !active.targetBluetooth || active.sink == null) return false
        if (active.routeHold.isReleased) return false
        if (active.effective.currentKind != InputRouteKind.BLUETOOTH) return false
        // Exhaustive, no else: a new ending decides here whether it keeps the earbuds warm.
        return when (active.endingClaim.ending) {
            CaptureEnding.Manual, CaptureEnding.Silence, CaptureEnding.MaxDuration -> true
            CaptureEnding.StillRunning, CaptureEnding.Failure -> false
        }
    }

    /** Under `sessionLock`. True when the hold is playing and now owns the route. */
    private fun startWarmHold(active: CaptureSession): Boolean {
        val sink = active.sink ?: return false
        val audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
        val label = active.effective.label()
        val hold = WarmHold(
            route = active.routeHold,
            track = AudioTrackSilence(),
            onEnded = { reason -> onHoldEnded(reason, label) },
        )
        heldSinkType = sink.type
        heldSinkName = sink.productName?.toString().orEmpty()
        warmHold = hold
        if (!hold.start()) {
            clearHoldBookkeeping()
            return false
        }
        val expiry = Runnable { synchronized(sessionLock) { if (warmHold === hold) hold.end(WarmHold.END_EXPIRED) } }
        holdExpiry = expiry
        routeHandler.postDelayed(expiry, WarmHold.HOLD_MS)
        val commListener = AudioManager.OnCommunicationDeviceChangedListener { device ->
            val ours = device != null && device.type == heldSinkType && device.productName?.toString().orEmpty() == heldSinkName
            if (!ours) synchronized(sessionLock) { if (warmHold === hold) hold.end(WarmHold.END_DEVICE_CHANGED) }
        }
        val deviceCallback = object : AudioDeviceCallback() {
            override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>) {
                val gone = removed.any { it.type == heldSinkType && it.productName?.toString().orEmpty() == heldSinkName }
                if (gone) synchronized(sessionLock) { if (warmHold === hold) hold.end(WarmHold.END_DEVICE_REMOVED) }
            }
        }
        holdCommListener = commListener
        holdDeviceCallback = deviceCallback
        runCatching { audioManager.addOnCommunicationDeviceChangedListener({ routeHandler.post(it) }, commListener) }
            .onFailure { DebugLogger.warn(TAG, "hold listener not registered: ${it.message}") }
        runCatching { audioManager.registerAudioDeviceCallback(deviceCallback, routeHandler) }
            .onFailure { DebugLogger.warn(TAG, "hold device callback not registered: ${it.message}") }
        DebugLogger.log(TAG, "route hold start=$label ms=${WarmHold.HOLD_MS}")
        return true
    }

    /** Runs inside `WarmHold.end` or `handOver`, under `sessionLock`. */
    private fun onHoldEnded(reason: String, label: String) {
        DebugLogger.log(TAG, "route hold end=$reason device=$label")
        clearHoldBookkeeping()
        // A new take keeps the service; every other end lets it go once no session is open.
        if (reason != WarmHold.END_NEW_TAKE && session == null) stopSelf()
    }

    /** Under `sessionLock`. Forgets the hold's listeners and identity; the hold object itself is done. */
    private fun clearHoldBookkeeping() {
        val audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
        holdExpiry?.let { routeHandler.removeCallbacks(it) }
        holdExpiry = null
        holdCommListener?.let { l -> runCatching { audioManager.removeOnCommunicationDeviceChangedListener(l) } }
        holdCommListener = null
        holdDeviceCallback?.let { c -> runCatching { audioManager.unregisterAudioDeviceCallback(c) } }
        holdDeviceCallback = null
        warmHold = null
        heldSinkType = -1
        heldSinkName = ""
    }

    /**
     * The session owner is done with this take. When a hold is running the service gives itself a
     * started lifetime, so the owner's unbind does not destroy it; the hold's end stops it. False means
     * "nothing to keep", and the owner stops the service as it always did.
     */
    private fun finishTake(): Boolean {
        synchronized(sessionLock) {
            val hold = warmHold ?: return false
            if (!hold.isActive) return false
            return runCatching {
                startService(Intent(this, AudioCaptureService::class.java))
                true
            }.getOrElse { e ->
                DebugLogger.warn(TAG, "hold could not keep the service: ${e.message}")
                hold.end(WarmHold.END_TRACK_FAILED)
                false
            }
        }
    }

    /**
     * The platform half of the hold: a silent `VOICE_COMMUNICATION` stream, which is what Android keys
     * the communication route on (any active playback for the uid). Its own thread paces on the blocking
     * write; `stop()` unblocks it.
     */
    private class AudioTrackSilence : WarmHold.SilentTrack {
        private var track: AudioTrack? = null
        private var thread: Thread? = null
        @Volatile private var stopped = false

        override fun play() {
            val rate = PcmAudio.SAMPLE_RATE
            val minimum = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val built = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build(),
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(rate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .build(),
                )
                .setBufferSizeInBytes(maxOf(minimum, rate * PcmAudio.BYTES_PER_SAMPLE))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
            if (built.state != AudioTrack.STATE_INITIALIZED) {
                built.release()
                throw IllegalStateException("silent track not initialized")
            }
            track = built
            built.play()
            val zeros = ByteArray(rate / 10 * PcmAudio.BYTES_PER_SAMPLE)
            thread = Thread({
                while (!stopped) {
                    val n = built.write(zeros, 0, zeros.size)
                    if (n < 0) break
                }
            }, "WarmHoldSilence").apply { start() }
        }

        override fun stop() {
            stopped = true
            track?.let { t ->
                runCatching { t.stop() }
                runCatching { t.release() }
            }
            track = null
        }
    }

    private fun closeResources(record: AudioRecord?, output: FileOutputStream?) {
        runCatching { output?.flush() }
            .onFailure { DebugLogger.warn(TAG, "Failed to flush audio file: ${it.message}") }
        runCatching { output?.close() }
            .onFailure { DebugLogger.warn(TAG, "Failed to close audio file: ${it.message}") }
        runCatching { record?.stop() }
            .onFailure {
                if (it !is IllegalStateException) DebugLogger.warn(TAG, "Failed to stop AudioRecord: ${it.message}")
            }
        runCatching { record?.release() }
            .onFailure { DebugLogger.warn(TAG, "Failed to release AudioRecord: ${it.message}") }
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
        synchronized(sessionLock) { warmHold?.end(WarmHold.END_DESTROYED) }
        stopRecording()
        session?.let { active ->
            active.detectorAbandoned.set(true)
            active.feederThread?.interrupt()
            active.analyserThread?.interrupt()
            unbindVad(active)
        }
        val thread = captureThread
        if (thread != null && thread !== Thread.currentThread()) {
            thread.join(2_000L)
            if (thread.isAlive) {
                // The capture thread is the sole owner of AudioRecord and the file. Do not
                // close either resource here after the bounded wait. Process termination of
                // the isolated :audio service will reclaim them without an ANR-length wait.
                DebugLogger.warn(TAG, "Capture thread did not finish during service teardown")
            }
        }
        if (captureThread?.isAlive != true) {
            captureThread = null
            synchronized(sessionLock) {
                isRecording.set(false)
            }
        }
        // A hold that slipped in between the flag and the join is ended here, before its expiry dies.
        synchronized(sessionLock) { warmHold?.end(WarmHold.END_DESTROYED) }
        // After the join: the capture thread's cleanup removed its listener; nothing else posts here.
        routeThread.quitSafely()
        super.onDestroy()
    }
}
