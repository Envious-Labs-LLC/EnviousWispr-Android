package com.envi.wispr.ui

import com.envi.wispr.audio.AudioCaptureService
import com.envi.wispr.audio.RecordingLimits
import com.envi.wispr.polish.PolishFailureNotice
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** When a session notice is said: while the recorder may still be up, or after it has gone (#256). */
internal enum class NoticeTiming { WHILE_RECORDING, AFTER_RECORDER }

/**
 * The session's recorder notices, one member per sentence (#256). [SessionNoticePresenter] picks where each is said,
 * and decides whether a once-per-take line is due (#309); the session owner decides the rest at its own transitions.
 * The two earbud sentences stay owned by [CaptureNotices].
 */
internal enum class SessionNotice(val line: String, val timing: NoticeTiming) {
    /** Shown after the cap has stopped a take. The recorder is already gone by then. */
    DURATION_REACHED(
        "Reached the ${RecordingLimits.MAX_DURATION_MINUTES} minute limit. " +
            "Working on what you said.",
        NoticeTiming.AFTER_RECORDER,
    ),
    EARBUDS_SILENT(CaptureNotices.EARBUDS_SILENT, NoticeTiming.WHILE_RECORDING),

    /**
     * macOS's own sentence for this state, reused rather than reinvented. Android writing its own
     * words for a state macOS has already worded is how the two products drift apart.
     */
    SILENCE_UNAVAILABLE("Auto-stop on silence is unavailable right now", NoticeTiming.WHILE_RECORDING),
    BLUETOOTH_TIP(CaptureNotices.BLUETOOTH_TIP, NoticeTiming.WHILE_RECORDING),

    /**
     * Shown on the recorder in the last minute of a take, so the user can finish the sentence
     * they are in rather than discover the cap by losing the end of it.
     */
    DURATION_WARNING(
        "Recording stops in under a minute " +
            "(${RecordingLimits.MAX_DURATION_MINUTES} minute limit)",
        NoticeTiming.WHILE_RECORDING,
    ),
}

/**
 * Says one session notice wherever the user can actually see it (#256), built by the Service and the rig from
 * the same collaborators they give the session owner.
 *
 * The floating recorder exists only while the accessibility service is bound. In clipboard-only mode there
 * is no recorder at all, so a while-recording sentence has to arrive as a toast instead; after the recorder
 * has gone, a toast is the only surface left. Making that decision in one place is what stops the next
 * message being announced on a surface that is not there.
 *
 * It also holds the take's once-per-take lines (#309): the owner asks on main at the moment a fact arrives and keeps
 * the state checks it owns; this class remembers what was already said. It is built per Service and one owner admits
 * one take; [beginTake] clears the latches all the same, where the owner starts capture.
 */
internal class SessionNoticePresenter(
    private val surface: RecorderSurface,
    private val insertion: InsertionGateway,
    private val host: SessionHost,
    private val scope: CoroutineScope,
    private val mainDispatcher: CoroutineDispatcher,
    private val log: SessionLog,
    /** Process-scoped in production: the Service stops itself after every take, so a per-Service gate would reset. */
    private val tipGate: BluetoothTipGate = BluetoothTipGate.PROCESS,
) {
    @Volatile private var silenceNoticeShown = false
    /** The take proceeded on earbuds that sent nothing; said once, before any other microphone line. */
    @Volatile private var forcedNoticeShown = false
    /** One warning per take, latched so the last minute is not announced ten times a second. */
    @Volatile private var durationWarningShown = false

    /** A new take: nothing has been said yet. */
    fun beginTake() {
        silenceNoticeShown = false
        durationWarningShown = false
        forcedNoticeShown = false
    }

    /** The take proceeds on earbuds that sent nothing. Said first, so neither the tip nor another line takes the slot. */
    fun sayEarbudsSilent() {
        forcedNoticeShown = true
        say(SessionNotice.EARBUDS_SILENT)
    }

    /**
     * Once, and only when auto-stop never became available for a take the user had it on for (the owner checks the
     * setting and that the take is recording). Losing the detector after it was already working leaves a correct
     * recording, and a message several seconds into one is an interruption for nothing.
     */
    fun saySilenceUnavailableIfDue(status: Int) {
        if (silenceNoticeShown) return
        if (status != AudioCaptureService.SILENCE_STATUS_UNAVAILABLE) return
        silenceNoticeShown = true
        say(SessionNotice.SILENCE_UNAVAILABLE)
    }

    /**
     * The one-time line about the microphone, decided from the kind code the capture process reports: the Bluetooth
     * tip (once per app process, tips on, take started on Bluetooth). It never reads the display label. A pick that
     * was not connected has no line of its own (#173, the Mac rule): the take records through Auto, the History card
     * names what recorded, and a Bluetooth take reached that way is an ordinary Bluetooth take for the tip.
     *
     * The recorder has ONE notice slot and the last write wins, so the tip is never said in a take that already
     * carries the auto-stop warning or the forced notice: a capture warning outranks a nudge. The tip's
     * once-per-process allowance is spent only when the tip is actually said, so a take that had to say something
     * else leaves it for the next Bluetooth take (Codex review 5, 2026-09-17).
     */
    fun sayBluetoothTipIfDue(routeKind: Int, tipsEnabled: Boolean) {
        if (silenceNoticeShown || forcedNoticeShown) return
        if (tipGate.shouldShow(routeKind, tipsEnabled)) {
            log.log("Bluetooth tip shown")
            say(SessionNotice.BLUETOOTH_TIP)
        }
    }

    /**
     * Once, in the last minute of a take, that the cap is about to stop it. The moment comes from `RecordingLimits`,
     * the same object the capture process stops the take with; both processes compile the same constant, so it is
     * never asked for over the binder (issue #115).
     */
    fun sayDurationWarningIfDue(elapsedMs: Long) {
        if (durationWarningShown || elapsedMs < RecordingLimits.WARNING_AT_MS) return
        durationWarningShown = true
        log.log("Duration warning shown at ${elapsedMs}ms")
        say(SessionNotice.DURATION_WARNING)
    }

    fun say(notice: SessionNotice) {
        if (notice.timing == NoticeTiming.WHILE_RECORDING && insertion.isBound()) {
            surface.showNotice(notice.line)
        } else {
            scope.launch(mainDispatcher) {
                runCatching {
                    host.toastFromApplication(notice.line)
                }
            }
        }
    }

    /**
     * A polish that did not do its job (#77, #293): the toast line, then the silent notification that carries the
     * full reason, both on main. The owner posts it before its continuation starts, so it precedes the delivery.
     */
    fun sayPolishFailure(notice: PolishFailureNotice) {
        host.postToMain {
            host.toastFromService(notice.toastLine)
            host.showPolishNotice(notice)
        }
    }

    /** A take's failure sentence (#293), from [TakeNotices]; a toast on main, since the recorder is going away. */
    fun sayFailure(line: String) {
        host.postToMain { host.toastFromService(line) }
    }
}
