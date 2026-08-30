package com.envi.wispr.paste

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import android.text.InputType
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast
import com.envi.wispr.history.EnviousWisprDatabase
import com.envi.wispr.history.TranscriptRepository
import com.envi.wispr.history.TranscriptEntity
import com.envi.wispr.insertion.ClipboardInsertionPolicy
import com.envi.wispr.insertion.ClipboardOutcome
import com.envi.wispr.insertion.FallbackAnnouncement
import com.envi.wispr.insertion.InsertionResults
import com.envi.wispr.insertion.InsertionText
import com.envi.wispr.insertion.ServiceFallbackReason
import com.envi.wispr.shortcuts.DictationNotificationController
import com.envi.wispr.shortcuts.RecordingOverlayState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.util.UUID

class PasteAccessibilityService : AccessibilityService() {

    enum class InsertResult {
        PASTED,
        SET_TEXT,
        RETRY,
        REJECTED_SENSITIVE,
        COPIED_ONLY,
    }

    private data class TargetSnapshot(
        val node: AccessibilityNodeInfo,
        val packageName: String,
        val windowId: Int,
        val className: String,
        val viewId: String?,
        val capturedAtMs: Long,
    )

    private data class TargetToken(
        val node: AccessibilityNodeInfo,
        val packageName: String,
        val windowId: Int,
        val className: String,
        val viewId: String?,
    )

    private data class PendingInsertion(
        val transcriptId: Long,
        val text: String,
        val previousClipboard: ClipData?,
        val policy: ClipboardInsertionPolicy,
        val deadlineMs: Long,
        val clipboardOwnershipToken: String = UUID.randomUUID().toString(),
        var attempts: Int = 0,
        var clipboardOverwritten: Boolean = false,
        var ownedClipboardFingerprint: ClipboardFingerprint? = null,
        var clipboardPayload: String? = null,
        var verification: AccessibilityInsertionRules.Verification? = null,
    )

    private data class SmartInsertionContext(
        val text: String,
        val selectionStart: Int,
        val selectionEnd: Int,
    )

    companion object {
        private const val TAG = "PasteService"
        private const val INSERTION_TIMEOUT_MS = 2_500L
        private const val MAIN_CALL_TIMEOUT_MS = 1_000L
        private const val RETRY_INTERVAL_MS = 125L
        private const val LIFECYCLE_PREFERENCES = "paste_service_lifecycle"
        private const val KEY_STOP_WAS_CLEAN = "stop_was_clean"
        private val STOP_MARKER_LOCK = Any()
        private const val BASE_EVENT_TYPES = AccessibilityEvent.TYPE_VIEW_FOCUSED or
            AccessibilityEvent.TYPE_VIEW_CLICKED or
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED

        // The one liveness answer. The insertion path reads the field and the UI collects the flow,
        // both written by publishBinding alone so they cannot report different health.
        @Volatile
        private var instance: PasteAccessibilityService? = null

        private val boundState = MutableStateFlow(false)

        // onServiceConnected can fire more than once for one process. Report the previous stop only
        // on the first, so a reconnect cannot invent an unclean stop that did not happen.
        @Volatile
        private var previousStopReported = false

        /**
         * Whether a service instance is bound right now. Pushed from the lifecycle callbacks, so
         * readers pay nothing at idle (`architecture-rules.md` RULE: no-idle-cost). The Android
         * setting string cannot answer this: it still names a service that has crashed.
         */
        val isBound: StateFlow<Boolean> = boundState.asStateFlow()

        private fun publishBinding(service: PasteAccessibilityService?) {
            instance = service
            boundState.value = service != null
        }

        /**
         * Starts an event-assisted insertion attempt. The dictated text remains on the
         * clipboard even if Android never restores a safe editable target.
         */
        fun pasteWhenTargetReturns(
            transcriptId: Long,
            text: String,
            previousClipboard: ClipData? = null,
            policy: ClipboardInsertionPolicy = ClipboardInsertionPolicy(),
        ): InsertionHandoff {
            val service = instance ?: run {
                Log.w(TAG, "Accessibility service is not running; clipboard only")
                return InsertionHandoff.SERVICE_NOT_RUNNING
            }
            return service.callOnMain(InsertionHandoff.SERVICE_DID_NOT_ANSWER) {
                service.requestInsertion(transcriptId, text, previousClipboard, policy)
            }
        }

        /**
         * Pins the editor active before the windowless dictation launcher exits.
         *
         * The answer is [DictationTargetPin] rather than a Boolean because the session has to
         * carry WHY nothing was pinned all the way to the announcement. See
         * [InsertionJudgement.handoffToJudge].
         */
        fun pinTargetForDictation(): DictationTargetPin {
            val service = instance ?: return DictationTargetPin.SERVICE_NOT_RUNNING
            return service.callOnMain(DictationTargetPin.SERVICE_DID_NOT_ANSWER) {
                if (service.pinTarget()) DictationTargetPin.PINNED else DictationTargetPin.NO_TARGET
            }
        }

        fun releasePinnedTarget() {
            val service = instance ?: return
            service.callOnMain(Unit) {
                if (service.pendingInsertion == null) service.clearPinnedTarget()
            }
        }
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val mainCall = MainThreadHandoff(
        onLooperThread = { Looper.myLooper() == Looper.getMainLooper() },
        post = { runnable -> mainHandler.post(runnable) },
        startTimeoutMs = MAIN_CALL_TIMEOUT_MS,
    )
    private val historyScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val transcriptRepository by lazy {
        TranscriptRepository(EnviousWisprDatabase.get(applicationContext).transcriptDao())
    }
    private var lastTarget: TargetSnapshot? = null
    private var pendingInsertion: PendingInsertion? = null
    private var retryScheduled = false
    private var recordingOverlay: RecordingAccessibilityOverlay? = null
    private var pinnedTarget: TargetToken? = null

    private val retryRunnable = Runnable {
        retryScheduled = false
        tryPendingInsertion()
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        publishBinding(this)
        configureEventMode(includeContentChanges = false)
        recordingOverlay?.stop()
        recordingOverlay = RecordingAccessibilityOverlay(this).also { it.start() }
        Log.i(TAG, "Accessibility insertion service connected")
        // Disk, and this is the connect path of the heart. Liveness is already published above, so
        // a dictation arriving in this window would otherwise pin a target while a synchronous
        // SharedPreferences load held the main thread and before the event mask was installed.
        historyScope.launch { reportPreviousStop() }
    }

    private fun configureEventMode(includeContentChanges: Boolean) {
        serviceInfo = AccessibilityServiceInfo().apply {
            eventTypes = BASE_EVENT_TYPES or if (includeContentChanges) {
                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
            } else {
                0
            }
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS or
                AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
            notificationTimeout = 50
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        rememberEditableTarget(event)

        if (pendingInsertion != null && event.packageName?.toString() != packageName) {
            scheduleRetry(delayMs = 25L)
        }
    }

    override fun onInterrupt() {
        Log.w(TAG, "Accessibility insertion service interrupted")
        RecordingOverlayState.hide()
        // The words were accepted against a pinned field and are not going to reach it. This used
        // to copy them and say nothing, which is issue #16's silence reached from the one direction
        // where the service dies holding the text.
        pendingInsertion?.let { pending ->
            recordAndAnnounce(ServiceFallbackReason.SERVICE_INTERRUPTED, pending)
        }
        pendingInsertion = null
        clearPinnedTarget()
        mainHandler.removeCallbacks(retryRunnable)
        retryScheduled = false
    }

    /**
     * Fires before `onDestroy` when the user turns the service off, so the signal is never late.
     * Only this instance may retract its own publication: nulling unconditionally would let an
     * outgoing instance kill a replacement Android had already connected.
     */
    override fun onUnbind(intent: Intent?): Boolean {
        if (instance === this) publishBinding(null)
        markStopWasClean()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        // Retract the publication FIRST. Teardown below blocks this thread draining Room, and a
        // reader during that window would otherwise see a healthy binding on a dying service.
        if (instance === this) publishBinding(null)
        RecordingOverlayState.hide()
        recordingOverlay?.stop()
        recordingOverlay = null
        mainHandler.removeCallbacks(retryRunnable)
        retryScheduled = false
        // Announced BEFORE the blocking Room drain below, for the reason spelled out on
        // recordAndAnnounce: what survives this teardown is the durable notification, and it only
        // survives if it is handed to the system while this process is still alive.
        pendingInsertion?.let { pending ->
            recordAndAnnounce(ServiceFallbackReason.SERVICE_DESTROYED, pending)
        }
        pendingInsertion = null
        runBlocking(Dispatchers.IO) {
            historyScope.coroutineContext[Job]?.children?.toList()?.joinAll()
        }
        historyScope.cancel()
        clearPinnedTarget()
        clearTarget()
        // LAST, after the blocking Room drain above. Marking clean before it would record a system
        // kill that lands during the drain as an orderly stop, which is the case the marker exists
        // to catch.
        markStopWasClean()
        super.onDestroy()
    }

    private fun lifecyclePreferences() =
        getSharedPreferences(LIFECYCLE_PREFERENCES, Context.MODE_PRIVATE)

    /**
     * Names an unclean stop once per process, then re-arms the marker for the next stop.
     *
     * Nothing else in the app records why this service went away, and it shares the default
     * process, so its death is the app's death. Content-free by construction: one Boolean, no text,
     * no package names.
     */
    private fun reportPreviousStop() {
        runCatching {
            // READ once per process: the marker answers how the PREVIOUS process died, so a second
            // connect inside this one must not re-read our own armed value and invent a crash.
            if (!previousStopReported) {
                previousStopReported = true
                if (!lifecyclePreferences().getBoolean(KEY_STOP_WAS_CLEAN, true)) {
                    Log.i(TAG, "Reconnected after an unclean stop")
                }
            }
            // ARM on EVERY connect. Turning the service off and on again is the recovery the Home
            // card asks for, and it writes a clean stop; leaving the marker disarmed after that
            // would silence the crash most likely to follow, which is the one this exists to name.
            writeStopMarker(clean = false)
        }.onFailure { error -> Log.w(TAG, "Unable to read the previous stop marker: ${error.message}") }
    }

    private fun markStopWasClean() = writeStopMarker(clean = true)

    /**
     * The only writer, because the two writes race: arming runs off the connect path so a service
     * that unbinds seconds later would otherwise have its clean stop overwritten by a stale arm and
     * be reported as a crash. `onUnbind` retracts the publication BEFORE marking clean, so an arm
     * that loses the lock sees a stale `instance` and declines, and one that wins is overwritten by
     * the clean write that follows it.
     */
    private fun writeStopMarker(clean: Boolean) {
        synchronized(STOP_MARKER_LOCK) {
            if (!clean && instance !== this) return
            // A replacement has already published, so the marker is ITS arm now. An outgoing
            // instance finishing its teardown must not report the live service's stop as clean:
            // that is the same stuck-at-true marker MIN-1 named, reached from the other side.
            if (clean && instance != null) return
            runCatching {
                lifecyclePreferences().edit().putBoolean(KEY_STOP_WAS_CLEAN, clean).apply()
            }.onFailure { error -> Log.w(TAG, "Unable to record the stop marker: ${error.message}") }
        }
    }

    private fun rememberEditableTarget(event: AccessibilityEvent) {
        val eventPackage = event.packageName?.toString().orEmpty()
        if (eventPackage.isBlank() || eventPackage == packageName) return

        val source = event.source ?: return
        try {
            val shouldTrack = source.isEditable &&
                (source.isFocused ||
                    event.eventType == AccessibilityEvent.TYPE_VIEW_FOCUSED ||
                    event.eventType == AccessibilityEvent.TYPE_VIEW_CLICKED)
            if (!shouldTrack) return

            val snapshot = TargetSnapshot(
                node = AccessibilityNodeInfo.obtain(source),
                packageName = eventPackage,
                windowId = source.windowId,
                className = source.className?.toString().orEmpty(),
                viewId = source.viewIdResourceName,
                capturedAtMs = SystemClock.elapsedRealtime(),
            )
            clearTarget()
            lastTarget = snapshot
            Log.d(
                TAG,
                "Remembered editable target package=${snapshot.packageName} " +
                    "window=${snapshot.windowId} class=${source.className}",
            )
        } finally {
            source.recycle()
        }
    }

    private fun requestInsertion(
        transcriptId: Long,
        text: String,
        previousClipboard: ClipData?,
        policy: ClipboardInsertionPolicy,
    ): InsertionHandoff {
        // Three separate refusals. Merging them into one answer is what made a crashed service and
        // a back-to-back dictation indistinguishable from the log and from the History row.
        if (text.isBlank()) {
            Log.w(TAG, "Nothing to insert; clipboard only")
            return InsertionHandoff.EMPTY_TEXT
        }
        if (pendingInsertion != null) {
            Log.w(TAG, "An insertion is already pending; refusing replacement")
            return InsertionHandoff.INSERTION_ALREADY_PENDING
        }
        if (pinnedTarget == null) {
            Log.w(TAG, "No editor was pinned for this dictation; clipboard only")
            return InsertionHandoff.NO_PINNED_TARGET
        }
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        mainHandler.removeCallbacks(retryRunnable)
        retryScheduled = false
        pendingInsertion = PendingInsertion(
            transcriptId = transcriptId,
            text = text,
            // Read private clipboard contents only when the frozen setting requires a restore.
            // A background session service cannot reliably perform this read.
            previousClipboard = if (policy.restoreClipboardAfterPaste) {
                previousClipboard ?: clipboard.primaryClip
            } else {
                null
            },
            policy = policy,
            deadlineMs = SystemClock.elapsedRealtime() + INSERTION_TIMEOUT_MS,
        )
        configureEventMode(includeContentChanges = true)
        Log.i(TAG, "Insertion requested; waiting for the original editor")
        tryPendingInsertion()
        return InsertionHandoff.SCHEDULED
    }

    private fun pinTarget(): Boolean {
        if (pendingInsertion != null) return false
        pinnedTarget?.let { existing ->
            if (existing.node.refresh() && isSafeFocusedEditor(existing.node)) return true
            clearPinnedTarget()
        }

        var target = lastTarget
        if (target == null || !target.node.refresh() || !isSafeFocusedEditor(target.node)) {
            clearTarget()
            target = findFocusedEditableTarget()
            lastTarget = target
        }
        target ?: return false
        pinnedTarget = TargetToken(
            node = AccessibilityNodeInfo.obtain(target.node),
            packageName = target.packageName,
            windowId = target.windowId,
            className = target.className,
            viewId = target.viewId,
        )
        Log.i(TAG, "Pinned original editor package=${target.packageName} window=${target.windowId}")
        return true
    }

    private fun findFocusedEditableTarget(): TargetSnapshot? {
        val activeRoot = rootInActiveWindow
        findFocusedEditableTarget(activeRoot)?.let { target ->
            activeRoot?.recycle()
            return target
        }
        val activeWindowId = activeRoot?.windowId
        activeRoot?.recycle()

        for (window in windows) {
            if (window.id == activeWindowId) continue
            val root = window.root ?: continue
            val target = findFocusedEditableTarget(root)
            root.recycle()
            if (target != null) return target
        }
        return null
    }

    private fun findFocusedEditableTarget(root: AccessibilityNodeInfo?): TargetSnapshot? {
        root ?: return null
        val rootPackage = root.packageName?.toString().orEmpty()
        if (rootPackage.isBlank() || rootPackage == packageName) return null
        val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return null
        return try {
            val focusedPackage = focused.packageName?.toString().orEmpty()
            if (!isSafeFocusedEditor(focused) ||
                focusedPackage != rootPackage ||
                focused.windowId != root.windowId
            ) {
                null
            } else {
                TargetSnapshot(
                    node = AccessibilityNodeInfo.obtain(focused),
                    packageName = focusedPackage,
                    windowId = focused.windowId,
                    className = focused.className?.toString().orEmpty(),
                    viewId = focused.viewIdResourceName,
                    capturedAtMs = SystemClock.elapsedRealtime(),
                )
            }
        } finally {
            focused.recycle()
        }
    }

    private fun isSafeFocusedEditor(node: AccessibilityNodeInfo): Boolean =
        node.isEditable && node.isFocused && node.isVisibleToUser &&
            node.packageName?.toString().orEmpty().let { it.isNotBlank() && it != packageName }

    /**
     * Hands work to this service's main thread. The claim, the single deadline and the reason
     * there is no second one all live in [MainThreadHandoff], where they can be raced by a test.
     *
     * The three actions are [pinTarget], [clearPinnedTarget] and [requestInsertion], and only the
     * first two are in-memory work. [requestInsertion] attempts the insertion inline through
     * [tryPendingInsertion], so it makes accessibility calls into another process and can reach
     * [recordAndAnnounce], which writes the clipboard, shows a Toast and posts a notification.
     * That is why the wait after the body has claimed the work is bounded BY THE BODY rather than
     * by a clock: against a frozen target app it lasts as long as the framework's own node
     * timeouts. Read [MainThreadHandoff] for why waiting through that is the right trade.
     *
     * The bound on a new action is therefore "cannot block indefinitely", not "returns instantly":
     * no file read, no database call, no network call, and no lock a caller of this may hold.
     */
    private fun <T> callOnMain(fallback: T, action: () -> T): T = mainCall.call(fallback, action)

    private fun tryPendingInsertion() {
        val pending = pendingInsertion ?: return
        pending.attempts += 1

        when (val result = performInsertion(pending)) {
            InsertResult.PASTED, InsertResult.SET_TEXT -> {
                Log.i(TAG, "Insertion completed via $result after ${pending.attempts} attempt(s)")
                pendingInsertion = null
                clearPinnedTarget()
                configureEventMode(includeContentChanges = false)
                if (pending.policy.restoreClipboardAfterPaste) {
                    restorePreviousClipboardIfSafe(pending)
                }
                finalizeInsertion(pending, TranscriptEntity.STATUS_COMPLETED, if (result == InsertResult.SET_TEXT) "set_text" else "pasted")
                performResultHaptic(success = true)
            }
            InsertResult.REJECTED_SENSITIVE -> {
                Log.w(TAG, "Insertion refused for a password or sensitive field; clipboard only")
                pendingInsertion = null
                clearPinnedTarget()
                configureEventMode(includeContentChanges = false)
                recordAndAnnounce(ServiceFallbackReason.SENSITIVE_FIELD, pending)
            }
            InsertResult.RETRY -> {
                if (SystemClock.elapsedRealtime() < pending.deadlineMs) {
                    scheduleRetry(RETRY_INTERVAL_MS)
                } else {
                    val unverified = pending.verification != null
                    Log.w(
                        TAG,
                        if (unverified) {
                            "Editor action could not be verified after ${pending.attempts} attempts; clipboard only"
                        } else {
                            "Original editor did not return after ${pending.attempts} attempts; clipboard only"
                        },
                    )
                    pendingInsertion = null
                    clearPinnedTarget()
                    configureEventMode(includeContentChanges = false)
                    recordAndAnnounce(
                        reason = if (unverified) {
                            ServiceFallbackReason.UNVERIFIED
                        } else {
                            ServiceFallbackReason.TARGET_NEVER_RETURNED
                        },
                        pending = pending,
                    )
                }
            }
            InsertResult.COPIED_ONLY -> {
                Log.w(TAG, "No safe insertion action was available; clipboard only")
                pendingInsertion = null
                clearPinnedTarget()
                configureEventMode(includeContentChanges = false)
                recordAndAnnounce(ServiceFallbackReason.NO_INSERTION_ACTION, pending)
            }
        }
    }

    private fun scheduleRetry(delayMs: Long) {
        if (retryScheduled || pendingInsertion == null) return
        retryScheduled = true
        mainHandler.postDelayed(retryRunnable, delayMs)
    }

    private fun performInsertion(pending: PendingInsertion): InsertResult {
        val expected = pinnedTarget ?: return InsertResult.RETRY
        val pinnedWindowRoot = findPinnedWindowRoot(expected) ?: return InsertResult.RETRY
        var reacquiredNode: AccessibilityNodeInfo? = null

        try {
            // Use the exact copied node captured before dictation. Metadata alone is
            // ambiguous for Compose editors where several fields can have no view ID.
            // Samsung can invalidate that copied object while a temporary Activity is
            // above the editor. In that case, reacquire only a focused node whose
            // framework identity is equal to the originally pinned node.
            val originalRefreshed = expected.node.refresh()
            val originalReady = originalRefreshed &&
                expected.node.isVisibleToUser && expected.node.isEditable &&
                expected.node.isFocused && matchesPinnedTarget(expected.node, expected)
            val node = if (originalReady) {
                expected.node
            } else {
                pinnedWindowRoot.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.also {
                    reacquiredNode = it
                } ?: return InsertResult.RETRY
            }
            if (node.isVisibleToUser && node.isEditable && node.isFocused &&
                matchesPinnedTarget(node, expected) &&
                (node === expected.node || node == expected.node)
            ) {
                if (node !== expected.node) {
                    Log.d(TAG, "Reacquired the pinned editor after its node became stale")
                }
                pending.verification?.let { verification ->
                    val verified = AccessibilityInsertionRules.isVerified(
                            verification,
                            AccessibilityInsertionRules.observableEditorText(
                                node.text,
                                node.isShowingHintText,
                            ),
                            node.textSelectionStart,
                            node.textSelectionEnd,
                        )
                    return if (verified) {
                        if (verification.action == AccessibilityInsertionRules.Action.SET_TEXT) {
                            InsertResult.SET_TEXT
                        } else {
                            InsertResult.PASTED
                        }
                    } else {
                        InsertResult.RETRY
                    }
                }
                return insertIntoNode(node, pending)
            }
        } catch (exception: Exception) {
            Log.w(TAG, "Insertion attempt failed safely", exception)
        } finally {
            reacquiredNode?.recycle()
            pinnedWindowRoot.recycle()
        }

        return InsertResult.RETRY
    }

    private fun findPinnedWindowRoot(token: TargetToken): AccessibilityNodeInfo? {
        val activeRoot = rootInActiveWindow
        if (matchesPinnedWindow(activeRoot, token)) return activeRoot
        val activeWindowId = activeRoot?.windowId
        activeRoot?.recycle()

        for (window in windows) {
            if (window.id == activeWindowId) continue
            if (window.id != token.windowId) continue
            val root = window.root ?: continue
            if (matchesPinnedWindow(root, token)) return root
            root.recycle()
        }
        return null
    }

    private fun matchesPinnedWindow(root: AccessibilityNodeInfo?, token: TargetToken): Boolean =
        root != null && AccessibilityInsertionRules.isExpectedWindow(
            packageName = root.packageName?.toString(),
            windowId = root.windowId,
            expectedPackageName = token.packageName,
            expectedWindowId = token.windowId,
        )

    private fun matchesPinnedTarget(node: AccessibilityNodeInfo, token: TargetToken): Boolean {
        if (node.packageName?.toString() != token.packageName) return false
        if (node.windowId != token.windowId) return false
        if (node.className?.toString().orEmpty() != token.className) return false
        return token.viewId == null || node.viewIdResourceName == token.viewId
    }

    private fun insertIntoNode(
        node: AccessibilityNodeInfo,
        pending: PendingInsertion,
    ): InsertResult {
        if (!node.isEditable || !node.isVisibleToUser || !node.isFocused) {
            return InsertResult.RETRY
        }
        if (isSensitive(node)) return InsertResult.REJECTED_SENSITIVE

        val existing = AccessibilityInsertionRules.observableEditorText(
            node.text,
            node.isShowingHintText,
        )
        val selection = AccessibilityInsertionRules.normalizedSelection(
            existing,
            node.textSelectionStart,
            node.textSelectionEnd,
        ) ?: return pasteIntoNode(node, pending)
        val start = selection.start
        val end = selection.end
        val plan = if (pending.policy.smartInsertion) {
            InsertionText.smartPayloadPlan(existing, pending.text, start, end)
        } else {
            InsertionText.SmartPayloadPlan(pending.text, false)
        }
        val insertionText = plan.text
        val updated = InsertionText.mergeAtSelection(
            existing,
            insertionText,
            start,
            end,
        )

        val arguments = Bundle().apply {
            putCharSequence(
                AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                updated,
            )
        }
        // ACTION_SET_TEXT replaces the whole exposed value. Never let a stale snapshot erase
        // an edit made after we read the node.
        if (!node.refresh()) return InsertResult.RETRY
        val refreshedText = AccessibilityInsertionRules.observableEditorText(
            node.text,
            node.isShowingHintText,
        )
        val refreshedSelection = AccessibilityInsertionRules.normalizedSelection(
            refreshedText,
            node.textSelectionStart,
            node.textSelectionEnd,
        )
        if (refreshedText != existing || refreshedSelection != selection) {
            return InsertResult.RETRY
        }
        if (!node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)) {
            return pasteIntoNode(
                node = node,
                pending = pending,
                payload = insertionText,
                smartContext = if (plan.changesDictatedText) {
                    SmartInsertionContext(existing, start, end)
                } else {
                    null
                },
            )
        }

        val caret = start + insertionText.length
        node.performAction(
            AccessibilityNodeInfo.ACTION_SET_SELECTION,
            Bundle().apply {
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, caret)
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, caret)
            },
        )
        pending.verification = AccessibilityInsertionRules.Verification(
            action = AccessibilityInsertionRules.Action.SET_TEXT,
            beforeText = existing,
            insertedText = insertionText,
            expectedText = updated,
            expectedCaret = caret,
        )
        return verifyAcceptedAction(node, pending)
    }

    private fun pasteIntoNode(
        node: AccessibilityNodeInfo,
        pending: PendingInsertion,
        payload: String = pending.text,
        smartContext: SmartInsertionContext? = null,
    ): InsertResult {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        var committedPayload = payload

        if (pending.clipboardOverwritten) {
            if (!ownsClipboard(clipboard.primaryClip, pending)) {
                Log.w(TAG, "Clipboard changed during retry; refusing to overwrite newer content")
                return InsertResult.COPIED_ONLY
            }
            if (pending.clipboardPayload != committedPayload) {
                writeTranscriptClipboard(clipboard, pending, committedPayload)
            }
        } else {
            writeTranscriptClipboard(clipboard, pending, committedPayload)
            pending.clipboardOverwritten = true
        }

        // Re-read after the clipboard write, immediately before ACTION_PASTE. If the user moved
        // the caret while we were preparing the smart payload, fall back to their literal words.
        if (!node.refresh()) return InsertResult.RETRY
        var existing = AccessibilityInsertionRules.observableEditorText(
            node.text,
            node.isShowingHintText,
        )
        var selection = AccessibilityInsertionRules.normalizedSelection(
            existing,
            node.textSelectionStart,
            node.textSelectionEnd,
        )

        if (smartContext != null &&
            (existing != smartContext.text ||
                selection != AccessibilityInsertionRules.EditorSelection(
                    smartContext.selectionStart,
                    smartContext.selectionEnd,
                ))
        ) {
            if (!ownsClipboard(clipboard.primaryClip, pending)) {
                return InsertResult.COPIED_ONLY
            }
            committedPayload = pending.text
            writeTranscriptClipboard(clipboard, pending, committedPayload)

            // Capture the exact selection that immediately precedes ACTION_PASTE so verification
            // cannot approve a paste using an older caret snapshot.
            if (!node.refresh()) return InsertResult.RETRY
            existing = AccessibilityInsertionRules.observableEditorText(
                node.text,
                node.isShowingHintText,
            )
            selection = AccessibilityInsertionRules.normalizedSelection(
                existing,
                node.textSelectionStart,
                node.textSelectionEnd,
            )
        }

        if (!node.performAction(AccessibilityNodeInfo.ACTION_PASTE)) return InsertResult.RETRY

        val expectedText = selection?.let {
            InsertionText.mergeAtSelection(existing, committedPayload, it.start, it.end)
        }
        val expectedCaret = selection?.let { it.start + committedPayload.length }
        pending.verification = AccessibilityInsertionRules.Verification(
            action = AccessibilityInsertionRules.Action.PASTE,
            beforeText = existing,
            insertedText = committedPayload,
            expectedText = expectedText,
            expectedCaret = expectedCaret,
        )
        return verifyAcceptedAction(node, pending)
    }

    private fun verifyAcceptedAction(
        node: AccessibilityNodeInfo,
        pending: PendingInsertion,
    ): InsertResult {
        val verification = pending.verification ?: return InsertResult.RETRY
        if (!node.refresh()) return InsertResult.RETRY
        return if (AccessibilityInsertionRules.isVerified(
                verification,
                AccessibilityInsertionRules.observableEditorText(
                    node.text,
                    node.isShowingHintText,
                ),
                node.textSelectionStart,
                node.textSelectionEnd,
            )
        ) {
            if (verification.action == AccessibilityInsertionRules.Action.SET_TEXT) {
                InsertResult.SET_TEXT
            } else {
                InsertResult.PASTED
            }
        } else {
            InsertResult.RETRY
        }
    }

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

    private fun clearTarget() {
        lastTarget?.node?.recycle()
        lastTarget = null
    }

    private fun clearPinnedTarget() {
        pinnedTarget?.node?.recycle()
        pinnedTarget = null
    }

    private fun restorePreviousClipboardIfSafe(pending: PendingInsertion) {
        if (!pending.clipboardOverwritten) return
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        if (!ownsClipboard(clipboard.primaryClip, pending)) {
            Log.i(TAG, "Clipboard changed during insertion; preserving the newer clipboard")
            return
        }

        // The clip being restored came from another app and can carry a URI this process has no
        // grant for. Every other clipboard write in this file is already guarded; this one runs on
        // the SUCCESS path, where a throw would kill the shared process right after a dictation the
        // user believes worked.
        runCatching {
            val previous = pending.previousClipboard
            if (previous == null) {
                clipboard.clearPrimaryClip()
            } else {
                clipboard.setPrimaryClip(previous)
            }
        }.fold(
            onSuccess = { Log.i(TAG, "Previous clipboard restored after successful insertion") },
            onFailure = { error ->
                Log.w(TAG, "Previous clipboard could not be restored: ${error.message}")
            },
        )
    }

    private fun keepTranscriptOnClipboard(pending: PendingInsertion): Boolean {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        if (pending.clipboardOverwritten) {
            if (!ownsClipboard(clipboard.primaryClip, pending)) {
                Log.w(TAG, "Newer clipboard content detected; leaving it unchanged")
                return false
            }
            if (pending.clipboardPayload != pending.text) {
                return runCatching { writeTranscriptClipboard(clipboard, pending, pending.text) }.isSuccess
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

    private fun ownsClipboard(clip: ClipData?, pending: PendingInsertion): Boolean {
        return clip.isOwnedBy(
            token = pending.clipboardOwnershipToken,
            fingerprint = pending.ownedClipboardFingerprint,
        )
    }

    private fun finalizeInsertion(pending: PendingInsertion, status: String, result: String, interrupted: Boolean = false) {
        if (pending.transcriptId <= 0L) return
        historyScope.launch {
            runCatching { transcriptRepository.finalizeInsertionOutcome(pending.transcriptId, status, result, interrupted) }
                .onFailure { error -> Log.w(TAG, "Unable to update transcript insertion result: ${error.message}") }
        }
    }

    /**
     * Keeps the transcript, records the outcome, and says where the words went. One function,
     * because all three used to be composed separately for one event, and now the ONLY way this
     * service reaches the clipboard with words it failed to insert.
     *
     * There is deliberately no way to pass a sentence or a History value in. The call sites each
     * handed in a toast literal saying "Transcript copied" beside a notification computed from the
     * clipboard write's real result, so a failed copy had the two surfaces stating opposite facts
     * in the same second, and the History row lost the unverified hedge on that same branch. All
     * three surfaces now come from one `(reason, clipboard)` pair, and
     * `insertion/FallbackAnnouncement` is the only type either user-facing surface can be built
     * from.
     *
     * A toast is gone in seconds and this route is taken when the user has switched apps or put
     * the phone down, so the durable notification is not optional here either.
     *
     * **What the two teardown reasons can honestly deliver, and the limit that comes with one calm
     * line.** `onInterrupt` runs on a live service and the toast is delivered normally. `onDestroy`
     * runs while this instance is being torn down and the toast is queued into a window this process
     * still has to draw, so a destroy followed immediately by a process kill can drop it. The durable
     * notification that used to cover that case is gone deliberately: macOS posts nothing durable for
     * a clipboard fallback, and the words are on the clipboard and in History either way, so what is
     * lost is the sentence rather than the transcript. This still runs before `onDestroy`'s blocking
     * Room drain rather than after it, which is what gives the toast its best chance.
     * A low-memory kill that never calls `onDestroy` at all delivers nothing, and nothing here can
     * change that: the row is recovered as `INSERTION_INTERRUPTED` by
     * `TranscriptDao.recoverStaleReadyRows` on the next start, which is the sentence that claims no
     * destination it cannot know.
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
        )
        val announcement = FallbackAnnouncement.serviceFallbackAnnouncement(
            reason = reason,
            clipboard = clipboard,
            savedInHistory = pending.transcriptId > 0L,
        )
        Toast.makeText(this, announcement.line, Toast.LENGTH_LONG).show()
    }

    private fun performResultHaptic(success: Boolean) {
        if (Settings.System.getInt(
                contentResolver,
                Settings.System.HAPTIC_FEEDBACK_ENABLED,
                1,
            ) != 1
        ) {
            return
        }
        // VibratorManager is API 31 and minSdk is 30, so the unguarded call throws on the oldest
        // supported phone. This runs on the accessibility service's main thread, where an uncaught
        // throw kills the shared default process and takes auto-paste down with it.
        runCatching {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                getSystemService(VibratorManager::class.java)?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                getSystemService(Vibrator::class.java)
            } ?: return
            val effect = if (success) {
                VibrationEffect.EFFECT_TICK
            } else {
                VibrationEffect.EFFECT_DOUBLE_CLICK
            }
            vibrator.vibrate(VibrationEffect.createPredefined(effect))
        }.onFailure { error -> Log.w(TAG, "Result haptic unavailable: ${error.message}") }
    }
}
