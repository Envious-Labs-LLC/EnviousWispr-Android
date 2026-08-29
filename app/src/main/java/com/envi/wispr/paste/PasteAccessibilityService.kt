package com.envi.wispr.paste

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationEffect
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
import com.envi.wispr.insertion.InsertionText
import com.envi.wispr.insertion.ClipboardInsertionPolicy
import com.envi.wispr.shortcuts.RecordingOverlayState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
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
        private const val RETRY_INTERVAL_MS = 125L
        private const val BASE_EVENT_TYPES = AccessibilityEvent.TYPE_VIEW_FOCUSED or
            AccessibilityEvent.TYPE_VIEW_CLICKED or
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED

        @Volatile
        var instance: PasteAccessibilityService? = null
            private set

        /**
         * Starts an event-assisted insertion attempt. The dictated text remains on the
         * clipboard even if Android never restores a safe editable target.
         */
        fun pasteWhenTargetReturns(
            transcriptId: Long,
            text: String,
            previousClipboard: ClipData? = null,
            policy: ClipboardInsertionPolicy = ClipboardInsertionPolicy(),
        ): Boolean {
            val service = instance ?: run {
                Log.w(TAG, "Accessibility service is not running; clipboard only")
                return false
            }
            return service.callOnMain(false) {
                service.requestInsertion(transcriptId, text, previousClipboard, policy)
            }
        }

        /** Pins the editor active before the windowless dictation launcher exits. */
        fun pinTargetForDictation(): Boolean {
            val service = instance ?: return false
            return service.callOnMain(false) { service.pinTarget() }
        }

        fun releasePinnedTarget() {
            val service = instance ?: return
            service.callOnMain(Unit) {
                if (service.pendingInsertion == null) service.clearPinnedTarget()
            }
        }

        @Deprecated("Pass the transcript ID so insertion outcome can be persisted")
        fun pasteWhenTargetReturns(
            text: String,
            previousClipboard: ClipData? = null,
        ): Boolean = pasteWhenTargetReturns(0L, text, previousClipboard)
    }

    private val mainHandler = Handler(Looper.getMainLooper())
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
        instance = this
        configureEventMode(includeContentChanges = false)
        recordingOverlay?.stop()
        recordingOverlay = RecordingAccessibilityOverlay(this).also { it.start() }
        Log.i(TAG, "Accessibility insertion service connected")
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
        pendingInsertion?.let { pending ->
            val copied = keepTranscriptOnClipboard(pending)
            finalizeInsertion(
                pending,
                TranscriptEntity.STATUS_INSERTION_INTERRUPTED,
                if (copied) "copy_only_interrupted" else "insertion_failed",
                interrupted = true,
            )
        }
        pendingInsertion = null
        clearPinnedTarget()
        mainHandler.removeCallbacks(retryRunnable)
        retryScheduled = false
    }

    override fun onDestroy() {
        RecordingOverlayState.hide()
        recordingOverlay?.stop()
        recordingOverlay = null
        mainHandler.removeCallbacks(retryRunnable)
        retryScheduled = false
        pendingInsertion?.let { pending ->
            val copied = keepTranscriptOnClipboard(pending)
            finalizeInsertion(
                pending,
                TranscriptEntity.STATUS_INSERTION_INTERRUPTED,
                if (copied) "copy_only_service_destroyed" else "insertion_failed",
                true,
            )
        }
        pendingInsertion = null
        runBlocking(Dispatchers.IO) {
            historyScope.coroutineContext[Job]?.children?.toList()?.joinAll()
        }
        historyScope.cancel()
        clearPinnedTarget()
        clearTarget()
        instance = null
        super.onDestroy()
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
    ): Boolean {
        if (text.isBlank() || pinnedTarget == null || pendingInsertion != null) {
            Log.w(TAG, "Insertion already pending or no pinned target; refusing replacement")
            return false
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
        return true
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

    private fun <T> callOnMain(fallback: T, action: () -> T): T {
        if (Looper.myLooper() == Looper.getMainLooper()) return action()
        val task = FutureTask(action)
        mainHandler.post(task)
        return runCatching { task.get(1, TimeUnit.SECONDS) }.getOrDefault(fallback)
    }

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
                val copied = keepTranscriptOnClipboard(pending)
                finalizeInsertion(
                    pending,
                    TranscriptEntity.STATUS_INSERTION_INTERRUPTED,
                    if (copied) "copy_only_sensitive" else "insertion_failed",
                    true,
                )
                showCopyOnlyMessage("Protected field. Transcript copied for manual paste.")
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
                    val copied = keepTranscriptOnClipboard(pending)
                    finalizeInsertion(
                        pending,
                        TranscriptEntity.STATUS_INSERTION_INTERRUPTED,
                        if (copied && unverified) "copy_only_unverified" else if (copied) "copy_only" else "insertion_failed",
                        true,
                    )
                    showCopyOnlyMessage(
                        if (unverified) {
                            "Automatic insertion could not be verified. Transcript copied."
                        } else {
                            "Original field unavailable. Transcript copied. Tap Paste."
                        },
                    )
                }
            }
            InsertResult.COPIED_ONLY -> {
                Log.w(TAG, "No safe insertion action was available; clipboard only")
                pendingInsertion = null
                clearPinnedTarget()
                configureEventMode(includeContentChanges = false)
                val copied = keepTranscriptOnClipboard(pending)
                finalizeInsertion(pending, TranscriptEntity.STATUS_INSERTION_INTERRUPTED, if (copied) "copy_only" else "insertion_failed", true)
                showCopyOnlyMessage("Automatic insertion unavailable. Transcript copied. Tap Paste.")
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

        val previous = pending.previousClipboard
        if (previous == null) {
            clipboard.clearPrimaryClip()
        } else {
            clipboard.setPrimaryClip(previous)
        }
        Log.i(TAG, "Previous clipboard restored after successful insertion")
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

    private fun showCopyOnlyMessage(message: String) {
        performResultHaptic(success = false)
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
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
        val vibrator = getSystemService(VibratorManager::class.java).defaultVibrator
        val effect = if (success) {
            VibrationEffect.EFFECT_TICK
        } else {
            VibrationEffect.EFFECT_DOUBLE_CLICK
        }
        vibrator.vibrate(VibrationEffect.createPredefined(effect))
    }
}
