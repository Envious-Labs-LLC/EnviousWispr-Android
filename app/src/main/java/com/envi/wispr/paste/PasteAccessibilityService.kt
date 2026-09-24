package com.envi.wispr.paste

import com.envi.wispr.history.HistoryRow
import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.InputMethod
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import com.envi.wispr.debug.DebugLogger
import com.envi.wispr.insertion.ClipboardInsertionPolicy
import com.envi.wispr.insertion.ServiceFallbackReason
import com.envi.wispr.ui.DictationSessionService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * The accessibility service as Android sees it (#217): its lifecycle callbacks, its
 * `AccessibilityServiceInfo`, the input method it hands the framework, and the published binding every
 * other part of the app reads. The work is its three collaborators': [EditorTargetTracker] (which editor a
 * take aims at), [AccessibilityInsertionRunner] (one insertion, request to outcome) and
 * [AccessibilityBubbleHost] (the floating bubble). Each has one idempotent `close`, called from
 * [onDestroy].
 */
class PasteAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "PasteService"
        private const val MAIN_CALL_TIMEOUT_MS = 1_000L
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
        internal val isBound: StateFlow<Boolean> = boundState.asStateFlow()

        private fun publishBinding(service: PasteAccessibilityService?) {
            instance = service
            boundState.value = service != null
        }

        /**
         * Starts an event-assisted insertion attempt. The dictated text remains on the
         * clipboard even if Android never restores a safe editable target.
         */
        internal fun pasteWhenTargetReturns(
            row: HistoryRow,
            text: String,
            previousClipboard: ClipData? = null,
            policy: ClipboardInsertionPolicy = ClipboardInsertionPolicy(),
            takeId: String? = null,
        ): InsertionHandoff {
            val service = instance ?: run {
                DebugLogger.warn(TAG, "Accessibility service is not running; clipboard only")
                return InsertionHandoff.SERVICE_NOT_RUNNING
            }
            return service.callOnMain(InsertionHandoff.SERVICE_DID_NOT_ANSWER) {
                service.runner.requestInsertion(row, text, previousClipboard, policy, takeId)
            }
        }

        /**
         * The single pin attempt for the take the session owner is admitting; the owner is the only
         * caller, once per take, through `InsertionGateway`, and the answer may be no pin (#192).
         *
         * The answer is [DictationTargetPin] rather than a Boolean because the session has to
         * carry WHY nothing was pinned all the way to the announcement. See
         * [InsertionJudgement.handoffToJudge].
         */
        internal fun pinTargetForDictation(): DictationTargetPin {
            val service = instance ?: return DictationTargetPin.SERVICE_NOT_RUNNING
            return service.callOnMain(DictationTargetPin.SERVICE_DID_NOT_ANSWER) {
                service.tracker.pinTarget(insertionPending = service.runner.isPending)
            }
        }

        internal fun releasePinnedTarget() {
            val service = instance ?: return
            service.callOnMain(Unit) {
                if (!service.runner.isPending) service.tracker.clearPinnedTarget()
            }
        }

        /** The accessibility view id of the pinned editor, or null when nothing is pinned or it has none. */
        internal fun pinnedFieldId(): String? {
            val service = instance ?: return null
            return service.callOnMain(null) { service.tracker.pinnedViewId }
        }

        /**
         * Every window's accessibility tree as `uiautomator dump` XML, or null when no service is
         * bound (#181). Read on the main thread like every other read of `windows`. Only the
         * debug-build dump receiver calls this; it is here because [instance] stays private.
         */
        internal fun windowTreeXml(): String? {
            val service = instance ?: return null
            return service.callOnMain(null) {
                WindowTreeXml.render(WindowTreeXml.fromWindows(service.windows))
            }
        }

        /**
         * An admitted field of our own was withdrawn ([OwnFieldAdmission.withdraw]) without any
         * accessibility event to say so (setup left its practice screen): re-check the remembered
         * editor, so the bubble leaves with the field instead of lingering on the next screen.
         */
        internal fun refreshBubble() {
            val service = instance ?: return
            service.mainHandler.post { service.bubble.refresh() }
        }
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val mainCall = MainThreadHandoff(
        onLooperThread = { Looper.myLooper() == Looper.getMainLooper() },
        post = { runnable -> mainHandler.post(runnable) },
        startTimeoutMs = MAIN_CALL_TIMEOUT_MS,
    )
    /** The stop-marker read and the bubble's dock loads and saves; cancelled after the insertion's teardown. */
    private val historyScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val tracker = EditorTargetTracker(this)
    private val runner = AccessibilityInsertionRunner(
        this,
        mainHandler,
        tracker,
        setContentChanges = ::configureEventMode,
        inputSession = { editorInputSession },
    )
    private val bubble = AccessibilityBubbleHost(this, mainHandler, tracker, historyScope)

    override fun onServiceConnected() {
        super.onServiceConnected()
        publishBinding(this)
        configureEventMode(includeContentChanges = false)
        bubble.attach()
        DebugLogger.log(TAG, "Accessibility insertion service connected")
        bubble.discoverOnConnect()
        // Disk, and this is the connect path of the heart. Liveness is already published above, so
        // a dictation arriving in this window would otherwise pin a target while a synchronous
        // SharedPreferences load held the main thread and before the event mask was installed.
        historyScope.launch {
            reportPreviousStop()
            // The bubble's remembered dock, applied on the main thread once the disk has answered.
            bubble.restorePosition()
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
        val remembered = tracker.rememberEditableTarget(event)
        bubble.updateBubbleFromEvent(event, remembered)
        runner.onAccessibilityEvent(event.packageName?.toString())
    }

    /**
     * The bubble's direct route into the session owner: start the owner as a foreground service.
     * Returns false when this process may not do that (no microphone permission, or Android refusing
     * a foreground start from a bound accessibility service), and the caller falls back to the
     * transparent launcher. The focused editor is not pinned here: the owner makes the single pin
     * attempt in `beginSession`, and that result is the record every announcement is judged against (#192).
     *
     * The direct route exists because launching an activity, even a 1x1 non-focusable one, pauses the
     * user's app and Chrome then hides its keyboard (measured on the Android 16 emulator, 2026-09-12).
     */
    internal fun startDictationFromBubble(request: String): Boolean {
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            return false
        }
        return runCatching { DictationSessionService.sendCommand(this, DictationSessionService.ACTION_START, request) }
            .onFailure { error -> DebugLogger.warn(TAG, "Direct start from the bubble refused: ${error.javaClass.simpleName}") }
            .isSuccess
    }

    override fun onInterrupt() {
        DebugLogger.warn(TAG, "Accessibility insertion service interrupted")
        // The surface goes; the session owner's phase does NOT. It alone publishes to the bus, so a
        // reconnect renders whatever it retained and a hidden pill never reads as IDLE (#135, R1).
        // The words were accepted against a pinned field and are not going to reach it. This used
        // to copy them and say nothing, which is issue #16's silence reached from the one direction
        // where the service dies holding the text.
        runner.abandon(ServiceFallbackReason.SERVICE_INTERRUPTED, InsertionOutcomeLine.Outcome.INTERRUPTED)
        tracker.clearPinnedTarget()
        bubble.cancelDiscoveryRetries()
    }

    /**
     * Fires before `onDestroy` when the user turns the service off, so the signal is never late.
     * Only this instance may retract its own publication: nulling unconditionally would let an
     * outgoing instance kill a replacement Android had already connected.
     */
    override fun onUnbind(intent: Intent?): Boolean {
        if (instance === this) publishBinding(null)
        markStopWasClean()
        bubble.cancelDiscoveryRetries()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        // Retract the publication FIRST, so no reader sees a healthy binding on a dying service.
        if (instance === this) publishBinding(null)
        bubble.close()
        // The runner copies the words, enqueues the History outcome and requests a Toast while the service
        // is alive. An immediate process kill may lose the Toast. The History write goes on the
        // application's queue (#115).
        runner.close()
        // Nothing is drained here (#115): the take's History writes are the application queue's, in
        // order, and the bubble-position saves on this scope are a preference whose next save wins, so a
        // cancelled in-flight one costs nothing the next open does not restore.
        historyScope.cancel()
        tracker.close()
        // LAST, and written HERE rather than queued behind the outcome write (#115 review, F4): the marker
        // answers whether THIS service stopped in order, which it did once this method runs, and a
        // queued mark could land after a replacement connected and either be declined by the instance
        // guard (the orderly stop then reads as a crash) or overwrite the replacement's own arm. A kill
        // after this point loses only the queued outcome write, which the start-up recovery closes.
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
                    DebugLogger.log(TAG, "Reconnected after an unclean stop")
                }
            }
            // ARM on EVERY connect. Turning the service off and on again is the recovery the Home
            // card asks for, and it writes a clean stop; leaving the marker disarmed after that
            // would silence the crash most likely to follow, which is the one this exists to name.
            writeStopMarker(clean = false)
        }.onFailure { error -> DebugLogger.warn(TAG, "Unable to read the previous stop marker: ${error.javaClass.simpleName}") }
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
            }.onFailure { error -> DebugLogger.warn(TAG, "Unable to record the stop marker: ${error.javaClass.simpleName}") }
        }
    }

    /**
     * Hands work to this service's main thread. The claim, the single deadline and the reason
     * there is no second one all live in [MainThreadHandoff], where they can be raced by a test.
     *
     * The actions are the tracker's pin and pin release and the runner's `requestInsertion`. Since #362
     * `requestInsertion` admits the text and posts its first attempt, so none of them makes an
     * accessibility call into another process. Once the main-thread body claims the handoff, the caller
     * waits for its admission answer with no second deadline, so it cannot report a fallback while the
     * accepted work continues; read [MainThreadHandoff] for why.
     *
     * The bound on a new action is therefore "cannot block indefinitely", not "returns instantly":
     * no file read, no database call, no network call, and no lock a caller of this may hold.
     */
    private fun <T> callOnMain(fallback: T, action: () -> T): T = mainCall.call(fallback, action)
}
