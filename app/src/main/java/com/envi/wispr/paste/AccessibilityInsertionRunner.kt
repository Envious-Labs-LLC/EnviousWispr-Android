package com.envi.wispr.paste

import com.envi.wispr.history.HistoryRow
import android.accessibilityservice.AccessibilityService
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.VibratorManager
import android.provider.Settings
import android.text.InputType
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast
import com.envi.wispr.debug.DebugLogger
import com.envi.wispr.history.TranscriptEntity
import com.envi.wispr.insertion.ClipboardInsertionPolicy
import com.envi.wispr.insertion.ClipboardOutcome
import com.envi.wispr.insertion.FallbackAnnouncement
import com.envi.wispr.insertion.InsertionResults
import com.envi.wispr.insertion.ServiceFallbackReason
import java.util.UUID

/**
 * One dictation's insertion, from request to terminal outcome (#217): the pending attempt and its retry,
 * the clipboard it stages and restores, and the History and telemetry completion. It writes into the
 * editor the [tracker] pinned and never pins or forgets an editor itself, except releasing the pin as its
 * attempt ends. Every call runs on the service's main thread.
 */
internal class AccessibilityInsertionRunner(
    private val service: AccessibilityService,
    private val mainHandler: Handler,
    private val tracker: EditorTargetTracker,
    /** The service's `configureEventMode`: the one writer of `serviceInfo`. */
    private val setContentChanges: (Boolean) -> Unit,
    /** The service's input-method pipe, when the framework created it. */
    private val inputSession: () -> EditorInputSession?,
    /**
     * Where an accepted insertion's ending is recorded (#359): its History outcome and its terminal event. Built
     * from the service itself, never its application context: the runner is built in a field initializer, before
     * Android attaches the context, and the recorder reads it only when an ending is recorded (review round 1).
     */
    private val outcomes: InsertionOutcomeRecorder = InsertionOutcomeRecorder.forProcess(service),
) {
    private companion object {
        const val TAG = "PasteService"
        const val INSERTION_TIMEOUT_MS = 2_500L
        const val RETRY_INTERVAL_MS = 125L
    }

    /**
     * One dictation's insertion, from request to terminal outcome. The write itself, the judging and
     * the deadline live in [attempt]; this holds what the runner owns around it: the History row, the
     * clipboard staging state and the restore snapshot.
     *
     * `previousClipboard` is taken immediately before the FIRST clipboard staging, never at request
     * time, so a clip the user copied during the wait is never overwritten by an older one on restore.
     * A null snapshot means "unreadable or empty" and either way the restore leaves the clipboard
     * alone: it never clears.
     */
    private class PendingInsertion(
        /** The take's History row, resolved only inside a queued write (#277); [HistoryRow.None] from a debug probe. */
        val row: HistoryRow,
        val text: String,
        val policy: ClipboardInsertionPolicy,
        val startedAtMs: Long,
        val deadlineMs: Long,
        /** The dictation this text belongs to, for its `insertion.terminal` row; null from a debug probe. */
        val takeId: String?,
        val clipboardOwnershipToken: String = UUID.randomUUID().toString(),
    ) {
        lateinit var attempt: InsertionAttempt

        /** The pinned editor's package at the moment the attempt ended; read by the outcome row only. */
        var targetPackage: String? = null

        /** The input session captured at the eligibility check; the commit and its judge use only this. */
        var commitSession: EditorInputSession.Captured? = null
        var previousClipboard: ClipData? = null
        var previousClipboardCaptured: Boolean = false
        var clipboardOverwritten: Boolean = false
        var ownedClipboardFingerprint: ClipboardFingerprint? = null
        var clipboardPayload: String? = null
    }

    private var pendingInsertion: PendingInsertion? = null
    private var retryScheduled = false

    private val retryRunnable = Runnable {
        retryScheduled = false
        tryPendingInsertion()
    }

    /** Whether an insertion is pending; the pin and the pin's release read it on main. */
    val isPending: Boolean get() = pendingInsertion != null

    /**
     * An accessibility event arrived. One from the target's own package can be the editor coming back:
     * that is any other package, or ours when the pinned target is the admitted practice field.
     */
    fun onAccessibilityEvent(eventPackage: String?) {
        if (pendingInsertion != null && (eventPackage != service.packageName || tracker.pinnedPackage == service.packageName)) {
            scheduleRetry(delayMs = 25L)
        }
    }

    fun requestInsertion(
        row: HistoryRow,
        text: String,
        previousClipboard: ClipData?,
        policy: ClipboardInsertionPolicy,
        takeId: String?,
    ): InsertionHandoff {
        // Three separate refusals. Merging them into one answer is what made a crashed service and
        // a back-to-back dictation indistinguishable from the log and from the History row.
        if (text.isBlank()) {
            DebugLogger.warn(TAG, "Nothing to insert; clipboard only")
            return InsertionHandoff.EMPTY_TEXT
        }
        if (pendingInsertion != null) {
            DebugLogger.warn(TAG, "An insertion is already pending; refusing replacement")
            return InsertionHandoff.INSERTION_ALREADY_PENDING
        }
        if (tracker.pinnedPackage == null) {
            DebugLogger.warn(TAG, "No editor was pinned for this dictation; clipboard only")
            return InsertionHandoff.NO_PINNED_TARGET
        }
        mainHandler.removeCallbacks(retryRunnable)
        retryScheduled = false
        val now = SystemClock.elapsedRealtime()
        val pending = PendingInsertion(
            row = row,
            text = text,
            policy = policy,
            startedAtMs = now,
            deadlineMs = now + INSERTION_TIMEOUT_MS,
            takeId = takeId,
        )
        // A caller-supplied snapshot is honoured; otherwise the paste route takes its own immediately
        // before the first staging (#141, review round 3).
        if (previousClipboard != null) {
            pending.previousClipboard = previousClipboard
            pending.previousClipboardCaptured = true
        }
        pending.attempt = InsertionAttempt(
            editor = ServiceEditor(pending),
            text = text,
            smartInsertion = policy.smartInsertion,
            deadlineMs = pending.deadlineMs,
        )
        pendingInsertion = pending
        setContentChanges(true)
        DebugLogger.log(TAG, "Insertion requested; waiting for the original editor")
        tryPendingInsertion()
        return InsertionHandoff.SCHEDULED
    }

    /**
     * The service was interrupted: the words were accepted against a pinned field and are not going to
     * reach it. The outcome is written and announced, then the retry goes; the service clears the pin
     * afterwards, so the outcome names the pinned package.
     */
    fun abandon(reason: ServiceFallbackReason, outcome: InsertionOutcomeLine.Outcome) {
        finalizePending(reason, outcome)
        mainHandler.removeCallbacks(retryRunnable)
        retryScheduled = false
    }

    /**
     * The service is being destroyed: the retry goes, then a pending insertion is written and announced
     * as destroyed. Safe to call twice: the second call finds nothing pending.
     */
    fun close() {
        mainHandler.removeCallbacks(retryRunnable)
        retryScheduled = false
        finalizePending(ServiceFallbackReason.SERVICE_DESTROYED, InsertionOutcomeLine.Outcome.DESTROYED)
    }

    private fun finalizePending(reason: ServiceFallbackReason, outcome: InsertionOutcomeLine.Outcome) {
        pendingInsertion?.let { pending ->
            pending.targetPackage = tracker.pinnedPackage
            logOutcome(pending, outcome, tracker.pinnedPackage)
            recordAndAnnounce(reason, pending)
        }
        pendingInsertion = null
    }

    private fun tryPendingInsertion() {
        val pending = pendingInsertion ?: return
        val attempt = pending.attempt
        when (val tick = attempt.tick()) {
            InsertionAttempt.Tick.Waiting -> scheduleRetry(RETRY_INTERVAL_MS)
            is InsertionAttempt.Tick.Verified -> {
                DebugLogger.log(TAG, "Insertion completed via ${tick.route} after ${attempt.attempts} attempt(s)")
                finish(pending, InsertionOutcomeLine.Outcome.VERIFIED)
                if (pending.policy.restoreClipboardAfterPaste) {
                    restorePreviousClipboardIfSafe(pending)
                }
                finalizeInsertion(
                    pending,
                    TranscriptEntity.STATUS_COMPLETED,
                    when (tick.route) {
                        InsertionRoute.COMMIT -> InsertionResults.COMMITTED
                        InsertionRoute.PASTE -> InsertionResults.PASTED
                    },
                )
                performResultHaptic(success = true)
            }
            InsertionAttempt.Tick.Sensitive -> {
                DebugLogger.warn(TAG, "Insertion refused for a password or sensitive field; clipboard only")
                finish(pending, InsertionOutcomeLine.Outcome.SENSITIVE)
                recordAndAnnounce(ServiceFallbackReason.SENSITIVE_FIELD, pending)
            }
            InsertionAttempt.Tick.Rejected -> {
                DebugLogger.warn(TAG, "The editor refused the paste; clipboard only")
                finish(pending, InsertionOutcomeLine.Outcome.REJECTED)
                recordAndAnnounce(ServiceFallbackReason.NO_INSERTION_ACTION, pending)
            }
            InsertionAttempt.Tick.StagingFailed -> {
                DebugLogger.warn(TAG, "The clipboard could not be staged; nothing written")
                finish(pending, InsertionOutcomeLine.Outcome.STAGING_FAILED)
                recordAndAnnounce(ServiceFallbackReason.NO_INSERTION_ACTION, pending)
            }
            is InsertionAttempt.Tick.Expired -> {
                DebugLogger.warn(
                    TAG,
                    if (tick.written) {
                        "Editor action could not be verified after ${attempt.attempts} attempts; " +
                            "clipboard only (${attempt.lastMissShape ?: "no judgement recorded"})"
                    } else {
                        "Original editor did not return after ${attempt.attempts} attempts; clipboard only"
                    },
                )
                finish(
                    pending,
                    if (tick.written) {
                        InsertionOutcomeLine.Outcome.UNVERIFIED
                    } else {
                        InsertionOutcomeLine.Outcome.NEVER_RETURNED
                    },
                )
                recordAndAnnounce(
                    reason = if (tick.written) {
                        ServiceFallbackReason.UNVERIFIED
                    } else {
                        ServiceFallbackReason.TARGET_NEVER_RETURNED
                    },
                    pending = pending,
                )
            }
        }
    }

    /** Ends the attempt, releases the pin, and writes the ONE content-free outcome line. */
    private fun finish(pending: PendingInsertion, outcome: InsertionOutcomeLine.Outcome) {
        pendingInsertion = null
        val target = tracker.pinnedPackage
        pending.targetPackage = target
        tracker.clearPinnedTarget()
        setContentChanges(false)
        logOutcome(pending, outcome, target)
    }

    private fun logOutcome(pending: PendingInsertion, outcome: InsertionOutcomeLine.Outcome, target: String?) {
        val attempt = pending.attempt
        DebugLogger.log(
            TAG,
            InsertionOutcomeLine.format(
                api = Build.VERSION.SDK_INT,
                route = attempt.route,
                written = attempt.written,
                returned = attempt.returned,
                evidence = attempt.evidence,
                outcome = outcome,
                attempts = attempt.attempts,
                elapsedMs = SystemClock.elapsedRealtime() - pending.startedAtMs,
                overrun = attempt.overrun,
                targetPackage = target,
            ),
        )
    }

    private fun scheduleRetry(delayMs: Long) {
        if (retryScheduled || pendingInsertion == null) return
        retryScheduled = true
        mainHandler.postDelayed(retryRunnable, delayMs)
    }

    /**
     * The accessibility calls behind [InsertionAttempt], with every framework read and write in one
     * place. Throws are allowed to escape: the attempt classifies them by when they happened.
     */
    private inner class ServiceEditor(private val pending: PendingInsertion) : EditorWrites {
        override fun locateTarget(): TargetState? =
            tracker.withPinnedNode { node ->
                val hint = node.isShowingHintText
                val snapshot = snapshotOf(node)
                TargetState(
                    read = AccessibilityInsertionRules.EditorRead(node.text?.toString(), hint),
                    selection = snapshot.selection,
                    sensitive = isSensitive(node),
                )
            }

        /**
         * The input session and the pinned node are two identities. The pipe is used only when they
         * provably coincide right now: input is started on the pipe for the pinned package, the
         * window holding input focus is the pinned window, and that window's input-focused node is
         * FRAMEWORK-EQUAL to the pinned node (never metadata alone: two same-class fields with no
         * view ids in one window are indistinguishable by metadata). The session captured here,
         * generation included, is the one the commit and its judge use.
         */
        override fun commitEligible(): Boolean {
            pending.commitSession = null
            val reason = commitIneligibleReason()
            if (reason != null) {
                DebugLogger.debug(TAG, "Commit route not eligible: $reason")
                return false
            }
            return true
        }

        /** Null when eligible (and the session is captured); otherwise the SHAPE of the refusal. */
        private fun commitIneligibleReason(): String? {
            val expectedPackage = tracker.pinnedPackage ?: return "no pin"
            val expectedWindowId = tracker.pinnedWindowId ?: return "no pin"
            val session = inputSession() ?: return "no input method"
            val captured = session.capture() ?: return "input not started"
            if (captured.packageName != expectedPackage) return "session package differs"
            val focusedWindow = service.windows.firstOrNull { it.isFocused } ?: return "no focused window"
            if (focusedWindow.id != expectedWindowId) return "focused window differs"
            // The same proof the write itself relies on: the pinned node is present in its window,
            // focused, editable and framework-equal to the pin. A window holds one focused view, so
            // with input focus in the pinned window the session belongs to that view. Chrome's
            // FOCUS_INPUT search from the window root answers a different node than the pinned
            // editor (measured 2026-09-13 on the Android 16 AVD), so it is not the test.
            if (tracker.withPinnedNode { node -> node.isFocused } != true) return "pinned editor not focused"
            pending.commitSession = captured
            return null
        }

        override fun readSurrounding(beforeChars: Int, afterChars: Int): AccessibilityInsertionRules.SurroundingWindow? {
            val captured = liveCommitSession() ?: return null
            val surrounding = captured.connection.getSurroundingText(beforeChars, afterChars, 0)
            if (surrounding == null) {
                DebugLogger.debug(TAG, "The pipe read no surrounding text")
                return null
            }
            val window = AccessibilityInsertionRules.window(
                surrounding.text,
                surrounding.selectionStart,
                surrounding.selectionEnd,
                surrounding.offset,
            )
            DebugLogger.debug(
                TAG,
                "The pipe read surrounding text: before=${window?.before?.length ?: -1} " +
                    "after=${window?.after?.length ?: -1} offset=${surrounding.offset}",
            )
            return window
        }

        override fun commit(payload: String): CommitOutcome {
            val captured = liveCommitSession() ?: return CommitOutcome.SESSION_CHANGED
            captured.connection.commitText(payload, 1, null)
            return CommitOutcome.SENT
        }

        /** The captured session, only while it is still the pipe's current one. */
        private fun liveCommitSession(): EditorInputSession.Captured? {
            val captured = pending.commitSession ?: return null
            val session = inputSession() ?: return null
            return captured.takeIf { session.isLive(it) }
        }

        override fun commitSessionLive(): Boolean = liveCommitSession() != null

        override fun stageClipboard(payload: String): Boolean {
            val clipboard = service.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            if (pending.clipboardOverwritten) {
                // A staging on a later tick needs CONFIRMED ownership. A refused read is neither a
                // licence to write over a clip the user may have copied since nor a licence to paste
                // whatever is there now, so both the write and the paste that would follow are refused.
                return when (clipboardOwner(clipboard.primaryClip, pending)) {
                    ClipboardOwner.OTHER -> {
                        DebugLogger.warn(TAG, "Clipboard changed during retry; refusing to overwrite newer content")
                        false
                    }
                    ClipboardOwner.UNREADABLE -> {
                        DebugLogger.warn(TAG, "Clipboard unreadable on retry; refusing to paste an unconfirmed clip")
                        false
                    }
                    ClipboardOwner.OURS -> {
                        if (pending.clipboardPayload != payload) {
                            writeTranscriptClipboard(clipboard, pending, payload)
                        }
                        true
                    }
                }
            }
            if (pending.policy.restoreClipboardAfterPaste && !pending.previousClipboardCaptured) {
                // Immediately before the first write, never earlier. A null read is "unreadable or
                // empty", and the restore treats both as "leave the clipboard alone".
                pending.previousClipboard = clipboard.primaryClip
                pending.previousClipboardCaptured = pending.previousClipboard != null
            }
            writeTranscriptClipboard(clipboard, pending, payload)
            pending.clipboardOverwritten = true
            return true
        }

        override fun paste(
            expectedBaseline: String?,
            expectedSelection: AccessibilityInsertionRules.EditorSelection?,
        ): PasteOutcome {
            if (tracker.pinnedPackage == null) return PasteOutcome.TARGET_GONE
            // Two throw sites with different meanings: a throw while FINDING or READING the node
            // happened before any call the editor could act on, and is TARGET_GONE; a throw from
            // performAction itself may have mutated the editor and is rethrown for the attempt to
            // treat as written.
            var asked = false
            return try {
                tracker.withPinnedNode { node ->
                    // The SAME derivation as locateTarget, so a null text compares equal to itself.
                    val now = snapshotOf(node)
                    when {
                        // The payload was composed against a snapshot; a moved caret or a changed
                        // draft since then means a smart seam repair may now be wrong, so nothing
                        // is written and the attempt prepares again.
                        now.baseline != expectedBaseline || now.selection != expectedSelection ->
                            PasteOutcome.CONTEXT_CHANGED
                        // Read AFTER staging: a standard EditText advertises ACTION_PASTE only
                        // while the clipboard holds something.
                        node.actionList.none { it.id == AccessibilityNodeInfo.ACTION_PASTE } ->
                            PasteOutcome.REFUSED
                        else -> {
                            asked = true
                            if (node.performAction(AccessibilityNodeInfo.ACTION_PASTE)) {
                                PasteOutcome.ACCEPTED
                            } else {
                                PasteOutcome.REFUSED
                            }
                        }
                    }
                } ?: PasteOutcome.TARGET_GONE
            } catch (error: Exception) {
                if (asked) throw error
                PasteOutcome.TARGET_GONE
            }
        }

        override fun readTarget(): AccessibilityInsertionRules.EditorRead? =
            tracker.withPinnedNode { node ->
                AccessibilityInsertionRules.EditorRead(node.text?.toString(), node.isShowingHintText)
            }

        override fun now(): Long = SystemClock.elapsedRealtime()
    }

    /** One derivation for both the composing read and the write-boundary read. */
    private fun snapshotOf(node: AccessibilityNodeInfo): AccessibilityInsertionRules.Snapshot =
        AccessibilityInsertionRules.snapshot(
            node.text,
            node.isShowingHintText,
            node.textSelectionStart,
            node.textSelectionEnd,
        )

    private fun isSensitive(node: AccessibilityNodeInfo): Boolean {
        if (node.isPassword) return true
        val inputType = node.inputType
        val inputClass = inputType and InputType.TYPE_MASK_CLASS
        val variation = inputType and InputType.TYPE_MASK_VARIATION
        return when (inputClass) {
            InputType.TYPE_CLASS_TEXT -> variation == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
                variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD ||
                variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD
            InputType.TYPE_CLASS_NUMBER -> variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD
            else -> false
        }
    }

    private fun restorePreviousClipboardIfSafe(pending: PendingInsertion) {
        if (!pending.clipboardOverwritten) return
        val clipboard = service.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        // Restoring is the one direction where a guess can destroy something: an older clip written
        // over one we cannot see. OURS is the only answer that permits it.
        when (clipboardOwner(clipboard.primaryClip, pending)) {
            ClipboardOwner.OURS -> Unit
            ClipboardOwner.OTHER -> {
                DebugLogger.log(TAG, "Clipboard changed during insertion; preserving the newer clipboard")
                return
            }
            ClipboardOwner.UNREADABLE -> {
                DebugLogger.log(TAG, "Clipboard unreadable after insertion; leaving the words on it")
                return
            }
        }

        // The clip being restored came from another app and can carry a URI this process has no
        // grant for. Every other clipboard write in this file is already guarded; this one runs on
        // the SUCCESS path, where a throw would kill the shared process right after a dictation the
        // user believes worked.
        // A snapshot that could not be read (or was empty) is not restored and NEVER cleared: the
        // words stay on the clipboard, which is the same place the keep path leaves them.
        val previous = pending.previousClipboard ?: run {
            DebugLogger.log(TAG, "No readable clipboard snapshot; leaving the clipboard as it is")
            return
        }
        runCatching {
            clipboard.setPrimaryClip(previous)
        }.fold(
            onSuccess = { DebugLogger.log(TAG, "Previous clipboard restored after successful insertion") },
            onFailure = { error ->
                DebugLogger.warn(TAG, "Previous clipboard could not be restored: ${error.javaClass.simpleName}")
            },
        )
    }

    private fun keepTranscriptOnClipboard(pending: PendingInsertion): Boolean {
        val clipboard = service.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        if (pending.clipboardOverwritten) {
            when (clipboardOwner(clipboard.primaryClip, pending)) {
                ClipboardOwner.OTHER -> {
                    DebugLogger.warn(TAG, "Newer clipboard content detected; leaving it unchanged")
                    return false
                }
                // Our staging is the last write this runner knows of, so the words ARE on the
                // clipboard as far as anything can tell, and that is what the user is told. It is
                // not permission to write again: a refused read never authorises a mutation.
                ClipboardOwner.UNREADABLE -> {
                    DebugLogger.log(TAG, "Clipboard unreadable; the staged words are counted as still there")
                    return true
                }
                ClipboardOwner.OURS -> if (pending.clipboardPayload != pending.text) {
                    return runCatching { writeTranscriptClipboard(clipboard, pending, pending.text) }.isSuccess
                }
            }
        } else {
            if (!runCatching {
                    writeTranscriptClipboard(clipboard, pending, pending.text)
                }.isSuccess
            ) {
                return false
            }
            pending.clipboardOverwritten = true
        }
        return true
    }

    private fun writeTranscriptClipboard(
        clipboard: ClipboardManager,
        pending: PendingInsertion,
        text: String,
    ) {
        val clip = enviousWisprTextClip(text, pending.clipboardOwnershipToken)
        clipboard.setPrimaryClip(clip)
        pending.ownedClipboardFingerprint = ClipboardFingerprint.from(clip)
        pending.clipboardPayload = text
    }

    /** What a clipboard read said about who wrote it last. Three answers, because the read can be refused. */
    private enum class ClipboardOwner { OURS, OTHER, UNREADABLE }

    /**
     * Android 10+ refuses `primaryClip` to any app that is not in focus or the default keyboard, and
     * this service is neither while the editor has focus (`ClipboardService: Denying clipboard access
     * to com.envi.wispr`, measured on the S26 and the emulator 2026-09-13). A null read is therefore
     * "cannot see", not "somebody else's clip", and each caller says what it does with that.
     */
    private fun clipboardOwner(clip: ClipData?, pending: PendingInsertion): ClipboardOwner = when {
        clip == null -> ClipboardOwner.UNREADABLE
        clip.isOwnedBy(
            token = pending.clipboardOwnershipToken,
            fingerprint = pending.ownedClipboardFingerprint,
        ) -> ClipboardOwner.OURS
        else -> ClipboardOwner.OTHER
    }

    /**
     * Every outcome of a text this runner ACCEPTED ends here (issue #176, G1 D3): the ending is frozen from the
     * pending attempt and handed to [outcomes], which records the History outcome and the terminal event (#359).
     */
    private fun finalizeInsertion(
        pending: PendingInsertion,
        status: String,
        result: String,
        interrupted: Boolean = false,
        clipboard: ClipboardOutcome? = null,
    ) {
        outcomes.record(
            InsertionEnding(
                row = pending.row,
                takeId = pending.takeId,
                targetPackage = pending.targetPackage,
                status = status,
                result = result,
                interrupted = interrupted,
                clipboard = clipboard,
                latencyMs = SystemClock.elapsedRealtime() - pending.startedAtMs,
            ),
        )
    }

    /**
     * Keeps the transcript, records the outcome, and says where the words went. One function,
     * because all three used to be composed separately for one event, and now the ONLY way this
     * runner reaches the clipboard with words it failed to insert.
     *
     * There is deliberately no way to pass a sentence or a History value in. The call sites each
     * handed in a toast literal saying "Transcript copied" beside a notification computed from the
     * clipboard write's real result, so a failed copy had the two surfaces stating opposite facts
     * in the same second, and the History row lost the unverified hedge on that same branch. All
     * three surfaces now come from one `(reason, clipboard)` pair, and
     * `insertion/FallbackAnnouncement` is the only type either user-facing surface can be built
     * from.
     *
     * **What the two teardown reasons can honestly deliver, and the limit that comes with one calm
     * line.** The runner copies the words, enqueues the History outcome and requests a Toast while the
     * service is alive. An interrupt runs on a live service and the Toast is delivered normally; an
     * immediate process kill after a destroy may lose it. No durable notification is posted, as macOS
     * posts none for a clipboard fallback: the Toast may be lost; the clipboard result records whether
     * copying succeeded, and History writes for a saved transcript use the application queue. A
     * low-memory kill that never calls `onDestroy` at all delivers nothing, and nothing here can change
     * that: the row is recovered as
     * `INSERTION_INTERRUPTED` by `TranscriptDao.recoverStaleReadyRows` on the next start, which is the
     * sentence that claims no destination it cannot know.
     */
    private fun recordAndAnnounce(reason: ServiceFallbackReason, pending: PendingInsertion) {
        // Every route here attempts the copy, so there is no NOT_ATTEMPTED case: the transcript is
        // put on the clipboard as part of the insertion itself.
        val clipboard = if (keepTranscriptOnClipboard(pending)) {
            ClipboardOutcome.COPIED
        } else {
            ClipboardOutcome.WRITE_FAILED
        }
        finalizeInsertion(
            pending,
            TranscriptEntity.STATUS_INSERTION_INTERRUPTED,
            InsertionResults.forServiceFallback(reason, clipboard),
            true,
            clipboard,
        )
        val announcement = FallbackAnnouncement.serviceFallbackAnnouncement(
            reason = reason,
            clipboard = clipboard,
            savedInHistory = pending.row.savedNow,
            kept = pending.row.wordsKept(),
        )
        Toast.makeText(service, announcement.line, Toast.LENGTH_LONG).show()
    }

    private fun performResultHaptic(success: Boolean) {
        if (Settings.System.getInt(
                service.contentResolver,
                Settings.System.HAPTIC_FEEDBACK_ENABLED,
                1,
            ) != 1
        ) {
            return
        }
        // This runs on the accessibility service's main thread, where an uncaught throw kills the
        // shared default process and takes auto-paste down with it, so a missing vibrator is caught.
        runCatching {
            val vibrator = service.getSystemService(VibratorManager::class.java)?.defaultVibrator ?: return
            val effect = if (success) {
                VibrationEffect.EFFECT_TICK
            } else {
                VibrationEffect.EFFECT_DOUBLE_CLICK
            }
            vibrator.vibrate(VibrationEffect.createPredefined(effect))
        }.onFailure { error -> DebugLogger.warn(TAG, "Result haptic unavailable: ${error.javaClass.simpleName}") }
    }
}
