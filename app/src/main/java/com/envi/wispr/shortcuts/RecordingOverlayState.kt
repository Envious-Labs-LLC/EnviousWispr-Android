package com.envi.wispr.shortcuts

import android.os.Handler
import android.os.Looper
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Process-local state bridge between the dictation session and accessibility overlay. */
object RecordingOverlayState {
    /**
     * Where the session owner says the take is. Written by the owner ONLY: the accessibility service
     * detaches its surface without touching this, so a reconnect renders whatever the owner retained
     * and a hidden pill never means IDLE (issue #135, review round 1).
     */
    enum class Phase { IDLE, STARTING, RECORDING, PROCESSING }

    data class Snapshot(
        /** True exactly while the pill is drawn: the RECORDING phase. */
        val visible: Boolean = false,
        val phase: Phase = Phase.IDLE,
        /** The floating bubble's request this take answers, or null for a take started elsewhere. */
        val requestToken: BubbleRequestToken? = null,
        /**
         * The accessibility view id of the editor the take was pinned to, or null when the pin named no
         * field or the field carries no id. Published by the owner once it has pinned, so a reader can
         * tell a take aimed at ITS field from one aimed anywhere else (the onboarding practice box).
         */
        val targetFieldId: String? = null,
        /** The History row this take writes, once the owner has created it; 0 before that. */
        val transcriptId: Long = 0L,
        val elapsedSeconds: Int = 0,
        /**
         * A short line to show under the timer, or null.
         *
         * null is a SENTINEL meaning "nothing to say", never "not loaded yet". The recorder hides the
         * line entirely when it is null.
         */
        val notice: String? = null,
        /**
         * How much of the microphone meter is lit, 0 for nothing and 1 for full.
         *
         * Already scaled for display by `AudioLevelScale`, never a raw amplitude. 0 is a real reading
         * meaning silence, not a "not measured yet" sentinel; the recorder draws its resting bars for it.
         */
        val level: Float = 0f,
        /**
         * Counts every level poll while the pill is visible, whether or not [level] changed.
         *
         * The rail is a HISTORY: each poll pushes one bar. Silence is the one passage where consecutive
         * levels are identical, so a rail woken only by a change in [level] stops scrolling exactly when
         * the user stops talking, and the shape of their last words sits frozen until they speak again.
         * The recorder reads this counter, never the level alone, to decide that a poll happened.
         */
        val levelTick: Int = 0,
    )

    fun interface Listener {
        fun onChanged(snapshot: Snapshot)
    }

    /** How many distinct meter positions exist. The recorder cannot show more than this many. */
    private const val LEVEL_STEPS = 32f

    private val mainHandler = Handler(Looper.getMainLooper())
    private val lock = Any()
    private var snapshot = Snapshot()
    private var listener: Listener? = null
    private val snapshotFlow = MutableStateFlow(Snapshot())

    /**
     * The same state the overlay is handed, as a flow for a second reader that only wants the phase
     * (the onboarding practice screen). Written under the lock in commit order; a collector sees every
     * committed value or a later one, never an earlier one.
     */
    val snapshots: StateFlow<Snapshot> = snapshotFlow.asStateFlow()

    fun attach(listener: Listener) {
        synchronized(lock) { this.listener = listener }
        notifyListener()
    }

    fun detach(listener: Listener) {
        synchronized(lock) {
            if (this.listener === listener) this.listener = null
        }
    }

    /** The take was admitted and is binding its services. Not yet interactive. */
    fun showStarting(token: BubbleRequestToken?) = change {
        Snapshot(phase = Phase.STARTING, requestToken = token)
    }

    /** The take was pinned to an editor: name it. A no-op at IDLE, where there is no take to name. */
    fun nameTarget(fieldId: String?) = change {
        if (it.phase == Phase.IDLE || it.targetFieldId == fieldId) it else it.copy(targetFieldId = fieldId)
    }

    /** The take's History row exists: carry its id. A no-op at IDLE. */
    fun attachTranscript(id: Long) = change {
        if (it.phase == Phase.IDLE || it.transcriptId == id) it else it.copy(transcriptId = id)
    }

    /** Capture is running: draw the pill. Keeps the token and identity the take was admitted with. */
    fun show() = change {
        Snapshot(visible = true, phase = Phase.RECORDING, requestToken = it.requestToken, targetFieldId = it.targetFieldId, transcriptId = it.transcriptId, elapsedSeconds = 0)
    }

    /** Transcribing, polishing, cancelling, finishing or failing: not accepting a start, pill hidden. */
    fun showProcessing() = change {
        if (it.phase == Phase.PROCESSING) it else Snapshot(phase = Phase.PROCESSING, requestToken = it.requestToken, targetFieldId = it.targetFieldId, transcriptId = it.transcriptId)
    }

    /** Show a line under the timer. It survives every later tick until the recorder is hidden. */
    fun showNotice(text: String) = change {
        if (!it.visible || it.notice == text) it else it.copy(notice = text)
    }

    /**
     * Publish one poll of the microphone level, already scaled for display.
     *
     * Every poll wakes the recorder while the pill is visible, EQUAL levels included, because the rail
     * records one bar per poll (see [Snapshot.levelTick]). That is about ten small redraws a second for
     * exactly as long as a take is open and nothing at idle (`architecture-rules.md`
     * RULE: no-idle-cost). Quantised to [LEVEL_STEPS] so the bar heights are a small fixed set rather
     * than a fresh float every tick.
     */
    fun updateLevel(level: Float) {
        val safe = if (level.isFinite()) level.coerceIn(0f, 1f) else 0f
        val quantised = (safe * LEVEL_STEPS).roundToInt().toFloat() / LEVEL_STEPS
        change { if (!it.visible) it else it.copy(level = quantised, levelTick = it.levelTick + 1) }
    }

    fun updateElapsed(seconds: Int) {
        val safe = seconds.coerceAtLeast(0)
        change { if (!it.visible || it.elapsedSeconds == safe) it else it.copy(elapsedSeconds = safe) }
    }

    /** The owner can accept a new start: IDLE, no pill, no token. */
    fun hide() = change { Snapshot() }

    /**
     * Read the current state, decide the next one, and commit it WITHOUT letting go of the lock
     * in between.
     *
     * Splitting that into two lock acquisitions leaves the recorder able to come back from the dead.
     * The session owner's polling thread reads a visible snapshot, the user presses Stop, `hide` writes
     * the invisible one, and then the polling thread commits the visible copy it prepared before the
     * stop. Nothing hides it again, because the take is already over. The level moves about ten times a
     * second against a stop that can land on any of them, which is the pairing that makes the window
     * worth closing rather than documenting.
     *
     * The transform must be pure: it runs under the lock, so it may not call back into a listener,
     * block, or touch anything that takes another lock.
     */
    private inline fun change(transform: (Snapshot) -> Snapshot) {
        synchronized(lock) {
            val next = transform(snapshot)
            if (next == snapshot) return
            snapshot = next
            snapshotFlow.value = next
        }
        notifyListener()
    }

    /**
     * Deliver the state as it stands WHEN THE MAIN THREAD GETS THERE, never the state that was current
     * when the change was made.
     *
     * Posting a captured snapshot reopens the same defect from the delivery side: two changes can commit
     * in the right order and still be posted in the wrong one, because a thread can be descheduled
     * between releasing the lock and posting. Reading at delivery time has no such ordering to get
     * wrong, and it coalesces a burst of level changes into one redraw for free.
     */
    private fun notifyListener() {
        val target = synchronized(lock) { listener } ?: return
        mainHandler.post {
            val current = synchronized(lock) {
                // The listener that was attached when this was posted may be gone, and a NEW listener
                // must not be handed a delivery it did not ask for; `attach` posts its own.
                if (listener !== target) return@post
                snapshot
            }
            target.onChanged(current)
        }
    }
}
