package com.envi.wispr.ui

import com.envi.wispr.audio.RecordingLimits
import com.envi.wispr.polish.PolishFailureNotice
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** When a session notice is said: while the recorder may still be up, or after it has gone (#256). */
internal enum class NoticeTiming { WHILE_RECORDING, AFTER_RECORDER }

/**
 * The session's recorder notices, one member per sentence (#256). The session owner picks which one to say;
 * [SessionNoticePresenter] picks where. The two earbud sentences stay owned by [CaptureNotices].
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
 */
internal class SessionNoticePresenter(
    private val surface: RecorderSurface,
    private val insertion: InsertionGateway,
    private val host: SessionHost,
    private val scope: CoroutineScope,
    private val mainDispatcher: CoroutineDispatcher,
) {
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
