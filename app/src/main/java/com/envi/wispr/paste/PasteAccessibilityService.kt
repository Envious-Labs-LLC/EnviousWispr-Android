package com.envi.wispr.paste

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.InputMethod
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.os.Build
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
import android.view.accessibility.AccessibilityWindowInfo
import android.view.WindowManager
import android.widget.Toast
import com.envi.wispr.history.EnviousWisprDatabase
import com.envi.wispr.history.TranscriptRepository
import com.envi.wispr.history.TranscriptEntity
import com.envi.wispr.insertion.ClipboardInsertionPolicy
import com.envi.wispr.insertion.ClipboardOutcome
import com.envi.wispr.insertion.FallbackAnnouncement
import com.envi.wispr.insertion.InsertionResults
import com.envi.wispr.insertion.ServiceFallbackReason
import com.envi.wispr.shortcuts.DictationNotificationController
import com.envi.wispr.shortcuts.RecordingOverlayState
import com.envi.wispr.ui.DictationSessionService
import com.envi.wispr.settings.AppPreferences
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

    /**
     * One dictation's insertion, from request to terminal outcome. The write itself, the judging and
     * the deadline live in [attempt]; this holds what the service owns around it: the History row, the
     * clipboard staging state and the restore snapshot.
     *
     * `previousClipboard` is taken immediately before the FIRST clipboard staging, never at request
     * time, so a clip the user copied during the wait is never overwritten by an older one on restore.
     * A null snapshot means "unreadable or empty" and either way the restore leaves the clipboard
     * alone: it never clears.
     */
    private class PendingInsertion(
        val transcriptId: Long,
        val text: String,
        val policy: ClipboardInsertionPolicy,
        val startedAtMs: Long,
        val deadlineMs: Long,
        val clipboardOwnershipToken: String = UUID.randomUUID().toString(),
    ) {
        lateinit var attempt: InsertionAttempt

        /** The input session captured at the eligibility check; the commit and its judge use only this. */
        var commitSession: EditorInputSession.Captured? = null
        var previousClipboard: ClipData? = null
        var previousClipboardCaptured: Boolean = false
        var clipboardOverwritten: Boolean = false
        var ownedClipboardFingerprint: ClipboardFingerprint? = null
        var clipboardPayload: String? = null
    }

    companion object {
        private const val TAG = "PasteService"
        private const val INSERTION_TIMEOUT_MS = 2_500L
        private const val MAIN_CALL_TIMEOUT_MS = 1_000L
        private const val RETRY_INTERVAL_MS = 125L
        // Offsets from the first discovery attempt for the bubble-discovery retries. Reaches past the ~2 s
        // a real Samsung takes to expose a returning editor without churning a no-field screen (#141).
        private val DISCOVERY_RETRY_DELAYS_MS = longArrayOf(250L, 750L, 2_250L)
        private const val LIFECYCLE_PREFERENCES = "paste_service_lifecycle"
        private const val KEY_STOP_WAS_CLEAN = "stop_was_clean"
        private val STOP_MARKER_LOCK = Any()
        // TYPE_WINDOWS_CHANGED is how the floating bubble learns a docked keyboard came or went; it
        // needs FLAG_RETRIEVE_INTERACTIVE_WINDOWS and canRetrieveWindowContent, both already set.
        private const val BASE_EVENT_TYPES = AccessibilityEvent.TYPE_VIEW_FOCUSED or
            AccessibilityEvent.TYPE_VIEW_CLICKED or
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
            AccessibilityEvent.TYPE_WINDOWS_CHANGED

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
                service.pinTarget()
            }
        }

        fun releasePinnedTarget() {
            val service = instance ?: return
            service.callOnMain(Unit) {
                if (service.pendingInsertion == null) service.clearPinnedTarget()
            }
        }

        /** The accessibility view id of the pinned editor, or null when nothing is pinned or it has none. */
        fun pinnedFieldId(): String? {
            val service = instance ?: return null
            return service.callOnMain(null) { service.pinnedTarget?.viewId }
        }

        /**
         * An admitted field of our own was withdrawn ([OwnFieldAdmission.withdraw]) without any
         * accessibility event to say so (setup left its practice screen): re-check the remembered
         * editor, so the bubble leaves with the field instead of lingering on the next screen.
         */
        fun refreshBubble() {
            val service = instance ?: return
            service.mainHandler.post { service.recordingOverlay?.let { overlay -> service.revalidateBubbleField(overlay) } }
        }
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val mainCall = MainThreadHandoff(
        onLooperThread = { Looper.myLooper() == Looper.getMainLooper() },
        post = { runnable -> mainHandler.post(runnable) },
        startTimeoutMs = MAIN_CALL_TIMEOUT_MS,
    )
    private val historyScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    /** Holds the one never-ending preference collect for the bubble's look; cancelled first in onDestroy. */
    private val lookScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val transcriptRepository by lazy {
        TranscriptRepository(EnviousWisprDatabase.get(applicationContext).transcriptDao())
    }
    private var lastTarget: TargetSnapshot? = null
    private var pendingInsertion: PendingInsertion? = null
    private var retryScheduled = false
    private var recordingOverlay: RecordingAccessibilityOverlay? = null
    private val bubblePositionStore by lazy { BubblePositionStore(applicationContext) }
    private var pinnedTarget: TargetToken? = null

    // Bubble-discovery retry: when a return to a focused field warrants discovery but the editor is not
    // accessible yet (the window is still coming up: ~2 s on a real Samsung, #141 phone pass 2026-09-13),
    // the first look finds nothing. Re-look a few times so the user never has to re-tap the field. One
    // sequence at a time, guarded by a generation so a superseding transition or teardown cancels it, and
    // coalesced per focused window so a stream of windows-changed events cannot postpone the retries
    // indefinitely (Codex review, 2026-09-13). Separate from retryRunnable, which serves insertion.
    private var discoveryGeneration = 0
    private var discoveryAttempt = 0
    private var discoveryWindowId = -1
    private var discoveryStartUptime = 0L
    private var discoveryRetryRunnable: Runnable? = null

    private val retryRunnable = Runnable {
        retryScheduled = false
        tryPendingInsertion()
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        publishBinding(this)
        configureEventMode(includeContentChanges = false)
        // onServiceConnected fires again on the same instance whenever the system recomputes the
        // accessibility state, for example when ANY package is installed (measured 2026-09-12 on the
        // emulator: another package's install reconnected this service mid-take). The overlay keeps
        // its field, keyboard and position state across those, so it is created once per instance.
        if (recordingOverlay == null) {
            recordingOverlay = RecordingAccessibilityOverlay(this).also { overlay ->
                overlay.onPositionChanged = { position -> historyScope.launch { bubblePositionStore.save(position) } }
                overlay.start()
            }
            // The chosen look, and every later change to it, applied on the main thread. Its own scope:
            // this collect never completes, and onDestroy joins historyScope's children before
            // cancelling them, so it must not be one of those.
            lookScope.launch {
                AppPreferences(applicationContext).state.collect { preferences ->
                    mainHandler.post { recordingOverlay?.setLook(preferences.bubbleLook) }
                }
            }
        }
        Log.i(TAG, "Accessibility insertion service connected")
        // A text box may already hold focus when this service (re)connects: discover it rather than
        // waiting for the user to tap it again. The window list is not populated at the instant of connect
        // (measured 2026-09-12: an immediate discovery found nothing while the editor was focused), so this
        // uses the same discover-with-retry sequence as an app-switch return, under the one cancellation
        // mechanism. It starts fresh: a prior sequence is invalidated first.
        cancelDiscoveryRetries()
        val connectGeneration = discoveryGeneration
        // Generation-guard the post itself: an unbind/destroy between here and the looper turn bumps the
        // generation, so a torn-down service never starts a fresh sequence (Codex review, 2026-09-13).
        mainHandler.post { if (connectGeneration == discoveryGeneration) discoverFieldWithRetry() }
        // Disk, and this is the connect path of the heart. Liveness is already published above, so
        // a dictation arriving in this window would otherwise pin a target while a synchronous
        // SharedPreferences load held the main thread and before the event mask was installed.
        historyScope.launch {
            reportPreviousStop()
            // The bubble's remembered dock, applied on the main thread once the disk has answered.
            bubblePositionStore.load()?.let { position ->
                mainHandler.post { recordingOverlay?.setPosition(position) }
            }
        }
    }

    private fun configureEventMode(includeContentChanges: Boolean) {
        serviceInfo = AccessibilityServiceInfo().apply {
            eventTypes = BASE_EVENT_TYPES or if (includeContentChanges) {
                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
            } else {
                0
            }
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            // This is the only writer of serviceInfo, and it replaces the whole object on every
            // call, so the input method flag lives here or it is wiped at the first request (#141).
            flags = AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS or
                AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                AccessibilityServiceInfo.FLAG_INPUT_METHOD_EDITOR
            notificationTimeout = 50
        }
    }

    /** The keyboard pipe: the framework asks for it once the input method flag is set. */
    override fun onCreateInputMethod(): InputMethod = EditorInputSession(this)

    private val editorInputSession: EditorInputSession?
        get() = inputMethod as? EditorInputSession

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val remembered = rememberEditableTarget(event)
        updateBubbleFromEvent(event, remembered)

        // An event from the target's own package can be the editor coming back. That is any other
        // package, or ours when the pinned target is the admitted practice field.
        val eventPackage = event.packageName?.toString()
        if (pendingInsertion != null && (eventPackage != packageName || pinnedTarget?.packageName == packageName)) {
            scheduleRetry(delayMs = 25L)
        }
    }

    /**
     * Tell the floating bubble whether another app's editable field is active, and where a docked
     * keyboard ends. Event-driven only: nothing here runs at idle, and a windows-changed event caused
     * by our own overlay moving is dropped before any node is touched
     * (`architecture-rules.md` RULE: no-idle-cost, as read by the #135 adjudication).
     */
    private fun updateBubbleFromEvent(event: AccessibilityEvent, remembered: Boolean) {
        val overlay = recordingOverlay ?: return
        when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_FOCUSED, AccessibilityEvent.TYPE_VIEW_CLICKED -> {
                if (remembered) {
                    // The field is found the direct way; stop any discovery-retry sequence still chasing it.
                    cancelDiscoveryRetries()
                    lastTarget?.let { overlay.fieldActivated(fieldKey(it)) }
                } else if (event.eventType == AccessibilityEvent.TYPE_VIEW_FOCUSED) {
                    // A focus that lands unremembered can be the return to an app whose editor is already
                    // focused (no fresh focus event for the editor itself): discover, and retry until it is
                    // accessible. A click stays cheap (Codex review, BUG 1, 2026-09-13).
                    discoverFieldWithRetry()
                } else {
                    // A click on a non-editor: whether the remembered editor still holds focus is a question,
                    // not a given (a hardware-keyboard tab to a button; Codex round 2). Re-check without a
                    // traversal; if the field is still here, a pending retry sequence has done its job.
                    if (revalidateBubbleField(overlay)) cancelDiscoveryRetries()
                }
            }
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, AccessibilityEvent.TYPE_WINDOWS_CHANGED -> {
                if (event.eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED && isOwnOverlayWindow(event.windowId)) return
                // A cosmetic windows-changed that finds the field present cancels any pending retry.
                if (discoveryWarranted(event)) discoverFieldWithRetry() else if (revalidateBubbleField(overlay)) cancelDiscoveryRetries()
            }
            else -> Unit
        }
    }

    /**
     * Whether this window event should run field discovery (one traversal). Always on a window state
     * change; on a windows-changed only when it carries a focus, active, added or removed change, i.e.
     * a genuine app switch or window replacement, never a cosmetic reshuffle. Returning to an app whose
     * editor was already focused emits a windows-changed with the FOCUSED/ACTIVE bit but no state change,
     * which the old state-change-only gate missed and left the bubble hidden (BUG 1, Codex 2026-09-13).
     * ADDED/REMOVED are included because AOSP can expose a returning window with ADDED alone.
     */
    private fun discoveryWarranted(event: AccessibilityEvent): Boolean {
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return true
        if (event.eventType != AccessibilityEvent.TYPE_WINDOWS_CHANGED) return false
        val relevant = AccessibilityEvent.WINDOWS_CHANGE_FOCUSED or
            AccessibilityEvent.WINDOWS_CHANGE_ACTIVE or
            AccessibilityEvent.WINDOWS_CHANGE_ADDED or
            AccessibilityEvent.WINDOWS_CHANGE_REMOVED
        return (event.windowChanges and relevant) != 0
    }

    /**
     * The bubble's direct route into the session owner: pin the focused editor here, in the process
     * that already knows it, and start the owner as a foreground service. Returns false when this
     * process may not do that (no microphone permission, or Android refusing a foreground start from
     * a bound accessibility service), and the caller falls back to the transparent launcher.
     *
     * The direct route exists because launching an activity, even a 1x1 non-focusable one, pauses the
     * user's app and Chrome then hides its keyboard (measured on the Android 16 emulator, 2026-09-12).
     */
    fun startDictationFromBubble(request: String): Boolean {
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            return false
        }
        pinTarget()
        return runCatching { DictationSessionService.sendCommand(this, DictationSessionService.ACTION_START, request) }
            .onFailure { error -> Log.w(TAG, "Direct start from the bubble refused: ${error.javaClass.simpleName}") }
            .isSuccess
    }

    /**
     * Is the remembered editor still focused? When it is not, and [discover] is set, look for the
     * editor that IS focused right now and adopt it. Discovery is one window traversal, so it runs only
     * on the rare events: a window state change and a service connect, never on the frequent
     * windows-changed stream. Without it a service recreated while a text box already had focus (a Play
     * update, an accessibility toggle) kept the bubble hidden until the user tapped the box again
     * (Codex review of the Play branch, 2026-09-12).
     */
    private fun revalidateBubbleField(overlay: RecordingAccessibilityOverlay, discover: Boolean = false): Boolean {
        var target = lastTarget
        var stillFocused = target != null &&
            runCatching { target.node.refresh() && isSafeFocusedEditor(target.node) && isInFocusedWindow(target.windowId) }
                .getOrDefault(false)
        if (!stillFocused && discover) {
            val found = runCatching { findFocusedEditableTarget()?.takeIf { isInFocusedWindow(it.windowId) } }.getOrNull()
            // Content-free: counts and booleans only (`kotlin-patterns.md` RULE: no-content-in-diagnostics).
            Log.d(
                TAG,
                "Bubble discovery found=${found != null} windows=${runCatching { windows.size }.getOrDefault(-1)}",
            )
            if (found != null) {
                clearTarget()
                lastTarget = found
                target = found
                stillFocused = true
            }
        }
        if (stillFocused) overlay.fieldActivated(fieldKey(target!!)) else overlay.fieldLost()
        overlay.keyboardBounds(dockedKeyboardTop())
        return stillFocused
    }

    /**
     * Discover the focused editor now, and if none is accessible yet, re-look at [DISCOVERY_RETRY_DELAYS_MS]
     * so a returning field that the framework exposes a second or two late still lights the bubble without a
     * re-tap (#141 phone pass). Coalesced per focused window: a stream of windows-changed events for the same
     * return does not restart the schedule (which would postpone the retries forever); a genuinely different
     * focused window starts one fresh sequence. Stops the moment a field is found.
     */
    private fun discoverFieldWithRetry() {
        val overlay = recordingOverlay ?: return
        val focusedId = focusedWindowId()
        // A sequence already chasing this same focused window keeps running; do not restart it, or a burst
        // of events would postpone the retries forever. A still-unknown focused window (-1) coalesces the
        // same way, so a burst during the exact window-unavailable state being repaired does not churn
        // (Codex review, 2026-09-13).
        if (discoveryRetryRunnable != null && focusedId == discoveryWindowId) return
        cancelDiscoveryRetries()
        discoveryWindowId = focusedId
        // Captured BEFORE the first look, so the retry deadlines measure from the transition, not from after
        // a slow initial traversal (Codex proviso, 2026-09-13).
        discoveryStartUptime = SystemClock.uptimeMillis()
        if (revalidateBubbleField(overlay, discover = true)) return
        scheduleDiscoveryRetry(discoveryGeneration)
    }

    private fun scheduleDiscoveryRetry(generation: Int) {
        if (discoveryAttempt >= DISCOVERY_RETRY_DELAYS_MS.size) return
        val runnable = Runnable {
            // A newer transition or a teardown bumped the generation: this attempt is stale.
            if (generation != discoveryGeneration) return@Runnable
            discoveryRetryRunnable = null
            val overlay = recordingOverlay ?: return@Runnable
            discoveryAttempt++
            if (revalidateBubbleField(overlay, discover = true)) {
                cancelDiscoveryRetries()
                return@Runnable
            }
            scheduleDiscoveryRetry(generation)
        }
        discoveryRetryRunnable = runnable
        // Absolute offsets from the first attempt (uptime timebase), so a slow traversal cannot stretch the
        // schedule past the ~2 s window it is meant to cover.
        mainHandler.postAtTime(runnable, discoveryStartUptime + DISCOVERY_RETRY_DELAYS_MS[discoveryAttempt])
    }

    /** Invalidate any pending discovery-retry sequence. Bumps the generation so an in-flight runnable that
     *  already left the handler queue sees itself superseded and does nothing. */
    private fun cancelDiscoveryRetries() {
        discoveryGeneration++
        discoveryRetryRunnable?.let { mainHandler.removeCallbacks(it) }
        discoveryRetryRunnable = null
        discoveryAttempt = 0
    }

    private fun focusedWindowId(): Int =
        runCatching { windows.firstOrNull { it.isFocused }?.id ?: -1 }.getOrDefault(-1)

    /**
     * The editor's identity for hide-until-the-next-field: window id plus the node's own hash, which
     * the framework derives from its source node id. Never the window or the view id alone, so two
     * editors in one window are two keys.
     */
    private fun fieldKey(target: TargetSnapshot): Any = FieldKey(target.windowId, target.node.hashCode())

    private data class FieldKey(val windowId: Int, val node: Int)

    /**
     * Does the window holding the editor have input focus right now? In split screen an editor in
     * the other pane keeps reporting itself focused after the user moves to this pane, so the node's
     * own focus flag alone would keep the bubble offering a field the user has left (Codex review of
     * the Play branch, round 5). The bubble asks the window, and hides until focus returns to it.
     */
    private fun isInFocusedWindow(windowId: Int): Boolean = runCatching {
        windows.any { it.id == windowId && it.isFocused }
    }.getOrDefault(false)

    private fun isOwnOverlayWindow(windowId: Int): Boolean = runCatching {
        windows.any { it.id == windowId && it.type == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY }
    }.getOrDefault(false)

    /**
     * The top of a keyboard docked at the bottom edge, in screen pixels, or null. A floating or split
     * keyboard does not touch the bottom edge and is reported as null on purpose: clamping above it
     * would push the bubble into the middle of the screen.
     */
    private fun dockedKeyboardTop(): Int? = runCatching {
        val ime = windows.firstOrNull { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD } ?: return null
        val bounds = Rect()
        ime.getBoundsInScreen(bounds)
        val screenBottom = getSystemService(WindowManager::class.java).currentWindowMetrics.bounds.bottom
        if (bounds.bottom >= screenBottom - 1 && bounds.height() > 0) bounds.top else null
    }.getOrNull()

    override fun onInterrupt() {
        Log.w(TAG, "Accessibility insertion service interrupted")
        // The surface goes; the session owner's phase does NOT. It alone publishes to the bus, so a
        // reconnect renders whatever it retained and a hidden pill never reads as IDLE (#135, R1).
        // The words were accepted against a pinned field and are not going to reach it. This used
        // to copy them and say nothing, which is issue #16's silence reached from the one direction
        // where the service dies holding the text.
        pendingInsertion?.let { pending ->
            logOutcome(pending, InsertionOutcomeLine.Outcome.INTERRUPTED, pinnedTarget?.packageName)
            recordAndAnnounce(ServiceFallbackReason.SERVICE_INTERRUPTED, pending)
        }
        pendingInsertion = null
        clearPinnedTarget()
        mainHandler.removeCallbacks(retryRunnable)
        retryScheduled = false
        cancelDiscoveryRetries()
    }

    /**
     * Fires before `onDestroy` when the user turns the service off, so the signal is never late.
     * Only this instance may retract its own publication: nulling unconditionally would let an
     * outgoing instance kill a replacement Android had already connected.
     */
    override fun onUnbind(intent: Intent?): Boolean {
        if (instance === this) publishBinding(null)
        markStopWasClean()
        cancelDiscoveryRetries()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        // Retract the publication FIRST. Teardown below blocks this thread draining Room, and a
        // reader during that window would otherwise see a healthy binding on a dying service.
        if (instance === this) publishBinding(null)
        lookScope.cancel()
        // Detach only; the bus belongs to the session owner (see onInterrupt).
        recordingOverlay?.stop()
        recordingOverlay = null
        mainHandler.removeCallbacks(retryRunnable)
        retryScheduled = false
        cancelDiscoveryRetries()
        // Announced BEFORE the blocking Room drain below, for the reason spelled out on
        // recordAndAnnounce: what survives this teardown is the durable notification, and it only
        // survives if it is handed to the system while this process is still alive.
        pendingInsertion?.let { pending ->
            logOutcome(pending, InsertionOutcomeLine.Outcome.DESTROYED, pinnedTarget?.packageName)
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

    /** True when the event named a new editable target and it was remembered. */
    private fun rememberEditableTarget(event: AccessibilityEvent): Boolean {
        val eventPackage = event.packageName?.toString().orEmpty()
        if (!OwnFieldAdmission.searches(packageName, eventPackage)) return false

        val source = event.source ?: return false
        try {
            val shouldTrack = source.isEditable &&
                OwnFieldAdmission.accepts(packageName, eventPackage, source.viewIdResourceName) &&
                (source.isFocused ||
                    event.eventType == AccessibilityEvent.TYPE_VIEW_FOCUSED ||
                    event.eventType == AccessibilityEvent.TYPE_VIEW_CLICKED)
            if (!shouldTrack) return false

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
            return true
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
        mainHandler.removeCallbacks(retryRunnable)
        retryScheduled = false
        val now = SystemClock.elapsedRealtime()
        val pending = PendingInsertion(
            transcriptId = transcriptId,
            text = text,
            policy = policy,
            startedAtMs = now,
            deadlineMs = now + INSERTION_TIMEOUT_MS,
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
        configureEventMode(includeContentChanges = true)
        Log.i(TAG, "Insertion requested; waiting for the original editor")
        tryPendingInsertion()
        return InsertionHandoff.SCHEDULED
    }

    /**
     * Pins the editor this dictation should return to, and NAMES its own outcome.
     *
     * A Boolean here was read by the caller as "no editor was focused", which is only one of the two
     * ways this declines. Refusing while an insertion is still pending is a dictation started on top
     * of another one, and the words of the second are the ones at risk; classifying it as the
     * ordinary no-editor case suppressed the only sentence that user would have seen. The type is
     * the fix rather than a second matcher at the call site: a new exit here has to say which it is
     * (`workflow-process.md` RULE: enumerate-from-the-producer-not-from-the-findings).
     */
    private fun pinTarget(): DictationTargetPin {
        if (pendingInsertion != null) return DictationTargetPin.INSERTION_BUSY
        pinnedTarget?.let { existing ->
            if (existing.node.refresh() && isSafeFocusedEditor(existing.node) && isInFocusedWindow(existing.windowId)) {
                return DictationTargetPin.PINNED
            }
            clearPinnedTarget()
        }

        // The window-focus check matches revalidateBubbleField: a remembered editor in the app the user
        // just left keeps its own focus flag, so node focus alone would pin the departed field and the
        // words would land there. Only reuse a target whose window still owns input focus; otherwise
        // rediscover the one that does (Codex review, BUG 1, 2026-09-13).
        var target = lastTarget
        if (target == null || !target.node.refresh() || !isSafeFocusedEditor(target.node) || !isInFocusedWindow(target.windowId)) {
            clearTarget()
            target = findFocusedEditableTarget()
            lastTarget = target
        }
        target ?: return DictationTargetPin.NO_TARGET
        pinnedTarget = TargetToken(
            node = AccessibilityNodeInfo.obtain(target.node),
            packageName = target.packageName,
            windowId = target.windowId,
            className = target.className,
            viewId = target.viewId,
        )
        Log.i(TAG, "Pinned original editor package=${target.packageName} window=${target.windowId}")
        return DictationTargetPin.PINNED
    }

    private fun findFocusedEditableTarget(): TargetSnapshot? {
        // Only the window that currently holds input focus may supply the pin. An editor in a background
        // window keeps its own focus flag, so an unfiltered search could rediscover a stale editor in an
        // unfocused window and pin it, sending the words to the app the user just left (Codex review,
        // BUG 1 fast-follow, 2026-09-13). The filter is applied DURING the search, not after, so a match
        // in the focused window is never overlooked because an unfocused one answered first.
        val activeRoot = rootInActiveWindow
        activeRoot?.takeIf { isInFocusedWindow(it.windowId) }?.let { root ->
            findFocusedEditableTarget(root)?.let { target ->
                activeRoot.recycle()
                return target
            }
        }
        val activeWindowId = activeRoot?.windowId
        activeRoot?.recycle()

        for (window in windows) {
            if (!window.isFocused) continue
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
        if (!OwnFieldAdmission.searches(packageName, rootPackage)) return null
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
            OwnFieldAdmission.accepts(packageName, node.packageName?.toString(), node.viewIdResourceName)

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
        val attempt = pending.attempt
        when (val tick = attempt.tick()) {
            InsertionAttempt.Tick.Waiting -> scheduleRetry(RETRY_INTERVAL_MS)
            is InsertionAttempt.Tick.Verified -> {
                Log.i(TAG, "Insertion completed via ${tick.route} after ${attempt.attempts} attempt(s)")
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
                Log.w(TAG, "Insertion refused for a password or sensitive field; clipboard only")
                finish(pending, InsertionOutcomeLine.Outcome.SENSITIVE)
                recordAndAnnounce(ServiceFallbackReason.SENSITIVE_FIELD, pending)
            }
            InsertionAttempt.Tick.Rejected -> {
                Log.w(TAG, "The editor refused the paste; clipboard only")
                finish(pending, InsertionOutcomeLine.Outcome.REJECTED)
                recordAndAnnounce(ServiceFallbackReason.NO_INSERTION_ACTION, pending)
            }
            InsertionAttempt.Tick.StagingFailed -> {
                Log.w(TAG, "The clipboard could not be staged; nothing written")
                finish(pending, InsertionOutcomeLine.Outcome.STAGING_FAILED)
                recordAndAnnounce(ServiceFallbackReason.NO_INSERTION_ACTION, pending)
            }
            is InsertionAttempt.Tick.Expired -> {
                Log.w(
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
        val target = pinnedTarget?.packageName
        clearPinnedTarget()
        configureEventMode(includeContentChanges = false)
        logOutcome(pending, outcome, target)
    }

    private fun logOutcome(pending: PendingInsertion, outcome: InsertionOutcomeLine.Outcome, target: String?) {
        val attempt = pending.attempt
        Log.i(
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
        override fun locateTarget(): TargetState? {
            val expected = pinnedTarget ?: return null
            return withPinnedNode(expected) { node ->
                val hint = node.isShowingHintText
                val snapshot = snapshotOf(node)
                TargetState(
                    read = AccessibilityInsertionRules.EditorRead(node.text?.toString(), hint),
                    selection = snapshot.selection,
                    sensitive = isSensitive(node),
                )
            }
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
                Log.d(TAG, "Commit route not eligible: $reason")
                return false
            }
            return true
        }

        /** Null when eligible (and the session is captured); otherwise the SHAPE of the refusal. */
        private fun commitIneligibleReason(): String? {
            val expected = pinnedTarget ?: return "no pin"
            val session = editorInputSession ?: return "no input method"
            val captured = session.capture() ?: return "input not started"
            if (captured.packageName != expected.packageName) return "session package differs"
            val focusedWindow = windows.firstOrNull { it.isFocused } ?: return "no focused window"
            if (focusedWindow.id != expected.windowId) return "focused window differs"
            // The same proof the write itself relies on: the pinned node is present in its window,
            // focused, editable and framework-equal to the pin. A window holds one focused view, so
            // with input focus in the pinned window the session belongs to that view. Chrome's
            // FOCUS_INPUT search from the window root answers a different node than the pinned
            // editor (measured 2026-09-13 on the Android 16 AVD), so it is not the test.
            if (withPinnedNode(expected) { node -> node.isFocused } != true) return "pinned editor not focused"
            pending.commitSession = captured
            return null
        }

        override fun readSurrounding(beforeChars: Int, afterChars: Int): AccessibilityInsertionRules.SurroundingWindow? {
            val captured = liveCommitSession() ?: return null
            val surrounding = captured.connection.getSurroundingText(beforeChars, afterChars, 0)
            if (surrounding == null) {
                Log.d(TAG, "The pipe read no surrounding text")
                return null
            }
            val window = AccessibilityInsertionRules.window(
                surrounding.text,
                surrounding.selectionStart,
                surrounding.selectionEnd,
                surrounding.offset,
            )
            Log.d(
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
            val session = editorInputSession ?: return null
            return captured.takeIf { session.isLive(it) }
        }

        override fun commitSessionLive(): Boolean = liveCommitSession() != null

        override fun stageClipboard(payload: String): Boolean {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            if (pending.clipboardOverwritten) {
                // A staging on a later tick needs CONFIRMED ownership. A refused read is neither a
                // licence to write over a clip the user may have copied since nor a licence to paste
                // whatever is there now, so both the write and the paste that would follow are refused.
                return when (clipboardOwner(clipboard.primaryClip, pending)) {
                    ClipboardOwner.OTHER -> {
                        Log.w(TAG, "Clipboard changed during retry; refusing to overwrite newer content")
                        false
                    }
                    ClipboardOwner.UNREADABLE -> {
                        Log.w(TAG, "Clipboard unreadable on retry; refusing to paste an unconfirmed clip")
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
            val expected = pinnedTarget ?: return PasteOutcome.TARGET_GONE
            // Two throw sites with different meanings: a throw while FINDING or READING the node
            // happened before any call the editor could act on, and is TARGET_GONE; a throw from
            // performAction itself may have mutated the editor and is rethrown for the attempt to
            // treat as written.
            var asked = false
            return try {
                withPinnedNode(expected) { node ->
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

        override fun readTarget(): AccessibilityInsertionRules.EditorRead? {
            val expected = pinnedTarget ?: return null
            return withPinnedNode(expected) { node ->
                AccessibilityInsertionRules.EditorRead(node.text?.toString(), node.isShowingHintText)
            }
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

    /**
     * Runs [block] against the pinned editor if it is present right now, else returns null.
     *
     * Uses the exact copied node captured before dictation. Metadata alone is ambiguous for Compose
     * editors where several fields can have no view ID. Samsung can invalidate that copied object
     * while a temporary Activity is above the editor; in that case reacquire only a focused node whose
     * framework identity is equal to the originally pinned node.
     */
    private fun <T> withPinnedNode(expected: TargetToken, block: (AccessibilityNodeInfo) -> T): T? {
        val pinnedWindowRoot = findPinnedWindowRoot(expected) ?: return null
        var reacquiredNode: AccessibilityNodeInfo? = null
        try {
            val originalRefreshed = expected.node.refresh()
            val originalReady = originalRefreshed &&
                expected.node.isVisibleToUser && expected.node.isEditable &&
                expected.node.isFocused && matchesPinnedTarget(expected.node, expected)
            val node = if (originalReady) {
                expected.node
            } else {
                pinnedWindowRoot.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.also {
                    reacquiredNode = it
                } ?: return null
            }
            if (node.isVisibleToUser && node.isEditable && node.isFocused &&
                matchesPinnedTarget(node, expected) &&
                (node === expected.node || node == expected.node)
            ) {
                if (node !== expected.node) {
                    Log.d(TAG, "Reacquired the pinned editor after its node became stale")
                }
                return block(node)
            }
            return null
        } finally {
            reacquiredNode?.recycle()
            pinnedWindowRoot.recycle()
        }
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
        // Restoring is the one direction where a guess can destroy something: an older clip written
        // over one we cannot see. OURS is the only answer that permits it.
        when (clipboardOwner(clipboard.primaryClip, pending)) {
            ClipboardOwner.OURS -> Unit
            ClipboardOwner.OTHER -> {
                Log.i(TAG, "Clipboard changed during insertion; preserving the newer clipboard")
                return
            }
            ClipboardOwner.UNREADABLE -> {
                Log.i(TAG, "Clipboard unreadable after insertion; leaving the words on it")
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
            Log.i(TAG, "No readable clipboard snapshot; leaving the clipboard as it is")
            return
        }
        runCatching {
            clipboard.setPrimaryClip(previous)
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
            when (clipboardOwner(clipboard.primaryClip, pending)) {
                ClipboardOwner.OTHER -> {
                    Log.w(TAG, "Newer clipboard content detected; leaving it unchanged")
                    return false
                }
                // Our staging is the last write this service knows of, so the words ARE on the
                // clipboard as far as anything can tell, and that is what the user is told. It is
                // not permission to write again: a refused read never authorises a mutation.
                ClipboardOwner.UNREADABLE -> {
                    Log.i(TAG, "Clipboard unreadable; the staged words are counted as still there")
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
        // This runs on the accessibility service's main thread, where an uncaught throw kills the
        // shared default process and takes auto-paste down with it, so a missing vibrator is caught.
        runCatching {
            val vibrator = getSystemService(VibratorManager::class.java)?.defaultVibrator ?: return
            val effect = if (success) {
                VibrationEffect.EFFECT_TICK
            } else {
                VibrationEffect.EFFECT_DOUBLE_CLICK
            }
            vibrator.vibrate(VibrationEffect.createPredefined(effect))
        }.onFailure { error -> Log.w(TAG, "Result haptic unavailable: ${error.message}") }
    }
}
