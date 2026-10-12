package com.envi.wispr.audio

import android.content.Context
import com.envi.wispr.debug.DebugLogger
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Main-process, main-looper output ownership for recording cues and explicit previews. Holds application
 * context only. A take's pairing decisions live in RecordingSoundCue, never in a readiness callback.
 * Players exist only during a take or a preview and focus belongs to each individual playing clip.
 */
internal object RecordingSoundOutput {
    private val handler = Handler(Looper.getMainLooper())
    private val busyState = MutableStateFlow(false)
    val busy = busyState.asStateFlow()
    private val previewState = MutableStateFlow<RecordingSoundPairing?>(null)
    val previewing = previewState.asStateFlow()
    private val previewStartedState = MutableStateFlow<RecordingSoundPairing?>(null)
    val previewStarted = previewStartedState.asStateFlow()
    private var takeToken: String? = null
    private var takePlayers: PairPlayers? = null
    private var previewPlayers: PairPlayers? = null
    private var previewStop: Runnable? = null
    private val clips = mutableSetOf<Clip>()

    private fun main() = check(Looper.myLooper() == Looper.getMainLooper()) { "sound output belongs to main" }

    fun admit(token: String) {
        main()
        cancelPreview()
        takePlayers?.close()
        // A previous short stop sound must not continue into a new recording.
        clips.toList().forEach { it.close() }
        takePlayers = null
        takeToken = token
        busyState.value = true
    }

    fun prepare(context: Context, token: String, pairing: RecordingSoundPairing, onReady: () -> Unit) {
        main()
        if (takeToken != token) return
        takePlayers?.close()
        val pair = PairPlayers(context.applicationContext, pairing)
        takePlayers = pair
        pair.whenReady = { if (takeToken == token && takePlayers === pair) onReady() }
    }

    fun play(token: String, pairing: RecordingSoundPairing, moment: RecordingSoundMoment): Boolean {
        main()
        val pair = takePlayers
        if (pair == null) { DebugLogger.log("RecordingSounds", "$moment unavailable: no prepared pair"); return false }
        if (takeToken != token || pair.pairing != pairing) { DebugLogger.log("RecordingSounds", "$moment rejected: obsolete take or pair"); return false }
        return when (moment) {
            RecordingSoundMoment.START -> {
                if (!pair.ready) {
                    DebugLogger.log("RecordingSounds", "Start awaiting preparation")
                    return false
                }
                val played = pair.start.play()
                DebugLogger.log("RecordingSounds", "Start played=$played")
                if (!played) { pair.close(); takePlayers = null }
                played
            }
            RecordingSoundMoment.STOP -> {
                takePlayers = null
                val played = pair.stop.play()
                DebugLogger.log("RecordingSounds", "Stop played=$played ready=${pair.stop.ready}")
                pair.close(keepPlaying = true)
                played
            }
        }
    }

    fun finish(token: String) {
        main()
        if (takeToken != token) return
        takePlayers?.close(keepPlaying = true)
        takePlayers = null
        takeToken = null
        busyState.value = false
    }

    /** Explicit preview works with the recording-sounds switch off and never writes selection. */
    fun preview(context: Context, pairing: RecordingSoundPairing) {
        main()
        if (busyState.value) return
        cancelPreview()
        val pair = PairPlayers(context.applicationContext, pairing)
        previewPlayers = pair
        previewState.value = pairing
        pair.whenReady = {
            if (previewPlayers === pair && !busyState.value) {
                if (pair.start.play()) {
                    val stop = Runnable {
                        previewStop = null
                        if (previewPlayers === pair && !busyState.value) {
                            if (!pair.stop.play()) cancelPreview()
                        }
                    }
                    previewStop = stop
                    handler.postDelayed(stop, PREVIEW_GAP_MS)
                    previewStartedState.value = pairing
                } else cancelPreview()
            }
        }
    }

    fun cancelPreview() {
        main()
        previewStop?.let(handler::removeCallbacks)
        previewStop = null
        val previous = previewPlayers
        previewPlayers = null
        previewState.value = null
        previewStartedState.value = null
        previous?.close()
    }

    private const val PREVIEW_GAP_MS = 550L
    private const val PREPARATION_BOUND_MS = 2_000L
    private const val COMPLETION_MARGIN_MS = 500L

    private class PairPlayers(context: Context, val pairing: RecordingSoundPairing) {
        var whenReady: (() -> Unit)? = null
        private var closed = false
        val start = Clip(context, pairing.startResource, ::loaded, ::failed, ::interrupted) {}
        val stop = Clip(context, pairing.stopResource, ::loaded, ::failed, ::interrupted) {
            if (previewPlayers === this) cancelPreview()
        }
        val ready: Boolean get() = !closed && start.ready && stop.ready
        private val timeout = Runnable { failed() }

        init { handler.postDelayed(timeout, PREPARATION_BOUND_MS) }

        private fun loaded() {
            if (ready) {
                handler.removeCallbacks(timeout)
                val callback = whenReady
                whenReady = null
                callback?.invoke()
            }
        }

        private fun failed() {
            DebugLogger.warn("RecordingSounds", "Preparation or player failed")
            if (previewPlayers === this) cancelPreview() else close()
        }

        private fun interrupted() {
            // A preview is one cancellable sequence. A live take's future stop is a separate cue.
            if (previewPlayers === this) cancelPreview()
        }

        fun close(keepPlaying: Boolean = false) {
            if (closed) return
            closed = true
            whenReady = null
            handler.removeCallbacks(timeout)
            if (!keepPlaying || !start.playing) start.close()
            if (!keepPlaying || !stop.playing) stop.close()
        }
    }

    private class Clip(
        context: Context,
        resource: Int,
        private val onReady: () -> Unit,
        private val onFailure: () -> Unit,
        private val onInterruption: () -> Unit,
        private val onCompletion: () -> Unit,
    ) {
        private val manager = context.getSystemService(AudioManager::class.java)
        private var player: MediaPlayer? = null
        private var focus: AudioFocusRequest? = null
        private var focusListener: AudioManager.OnAudioFocusChangeListener? = null
        private var closed = false
        var ready = false
            private set
        var playing = false
            private set
        private val lifetime = Runnable { close(); onCompletion() }
        private val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()

        init {
            clips.add(this)
            try {
                val created = MediaPlayer()
                player = created
                created.setAudioAttributes(attributes)
                created.setOnPreparedListener {
                    if (!closed) { ready = true; onReady() }
                }
                created.setOnCompletionListener { close(); onCompletion() }
                created.setOnErrorListener { _, _, _ -> close(); onFailure(); true }
                context.resources.openRawResourceFd(resource).use { fd ->
                    created.setDataSource(fd.fileDescriptor, fd.startOffset, fd.length)
                }
                created.prepareAsync()
            } catch (_: Exception) {
                close()
                // Queue failure so the pair is fully constructed before it releases its handles.
                handler.post { onFailure() }
            }
        }

        fun play(): Boolean {
            if (closed || !ready || playing) return false
            val output = player ?: return false
            return try {
                val listener = AudioManager.OnAudioFocusChangeListener { change ->
                    if (change < 0 && focus != null) { DebugLogger.log("RecordingSounds", "Focus interrupted=$change"); close(); onInterruption() }
                }
                focusListener = listener
                val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                    .setAudioAttributes(attributes)
                    .setAcceptsDelayedFocusGain(false)
                    .setOnAudioFocusChangeListener(listener, handler)
                    .build()
                focus = request
                if (manager == null || manager.requestAudioFocus(request) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                    close()
                    false
                } else {
                    output.start()
                    playing = true
                    // Completion normally owns release. Bound a missing platform callback by the actual
                    // prepared clip duration, never by a delay on the recording or transcription path.
                    handler.postDelayed(lifetime, output.duration.toLong() + COMPLETION_MARGIN_MS)
                    true
                }
            } catch (_: Exception) { close(); false }
        }

        fun close() {
            if (closed) return
            closed = true
            ready = false
            playing = false
            handler.removeCallbacks(lifetime)
            val heldFocus = focus
            focus = null
            focusListener = null
            if (heldFocus != null) runCatching { manager?.abandonAudioFocusRequest(heldFocus) }
            val owned = player
            player = null
            runCatching { owned?.release() }
            clips.remove(this)
        }
    }
}
