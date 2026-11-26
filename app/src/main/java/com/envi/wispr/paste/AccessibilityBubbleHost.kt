package com.envi.wispr.paste

import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Rect
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Handler
import android.os.SystemClock
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo
import com.envi.wispr.audio.InputDeviceCandidate
import com.envi.wispr.audio.InputDevicePick
import com.envi.wispr.audio.InputDeviceResolver
import com.envi.wispr.debug.DebugLogger
import com.envi.wispr.settings.AppPreferences
import com.envi.wispr.settings.BubbleVisibilityStored
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * The floating lips bubble as the paste service hosts it (#217): the overlay, its look and colour, its
 * remembered dock, and the field discovery that decides when it shows. It asks [tracker] which editor is
 * focused and never pins one. Event and overlay operations run on main; the look collection and the
 * position reads and writes run on IO, with UI updates posted to main. The position work stays on the
 * service's [historyScope], so it is cancelled with it, after the insertion's teardown. Every accessibility
 * call of a field check (the window list, the active root, `findFocus`, `refresh`) runs on [probeExecutor],
 * never on main (#561), and its answer is rendered back on main by [finishFieldCheck].
 */
internal class AccessibilityBubbleHost(
    private val service: PasteAccessibilityService,
    private val mainHandler: Handler,
    private val tracker: EditorTargetTracker,
    private val historyScope: CoroutineScope,
) {
    private companion object {
        const val TAG = "PasteService"
        // Offsets from the first discovery attempt for the bubble-discovery retries. Reaches past the ~2 s
        // a real Samsung takes to expose a returning editor without churning a no-field screen (#141).
        val DISCOVERY_RETRY_DELAYS_MS = longArrayOf(250L, 750L, 2_250L)
    }

    /** Holds the one never-ending preference collect for the bubble's look; cancelled first at close. */
    private val lookScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var recordingOverlay: RecordingAccessibilityOverlay? = null
    private val bubblePositionStore by lazy { BubblePositionStore(service.applicationContext) }
    private var closed = false

    // Where the idle bubble may appear (#523), written on main from the preference collect. Null until the
    // first emission: no idle bubble is drawn before the user's hidden apps are known.
    private var visibilityStored: BubbleVisibilityStored? = null
    private var lastDecision: BubbleVisibility.Decision? = null
    /**
     * Delivered on main (registered with [mainHandler]): the lock state changed, so look for the field again
     * with the same discover-and-retry sequence an app switch uses. A single re-check is not enough: on a
     * Galaxy S24 Ultra the unlock broadcast arrives while the keyguard is still animating away, before the
     * app's window has focus back (Test Lab matrix-32skk5u4yv4i0, 2026-11-08: lips still hidden after unlock).
     */
    private val lockReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (recordingOverlay == null) return
            // A fresh sequence per broadcast: coalescing into a screen-on sequence near its end would leave
            // the unlock one short attempt (Codex code round 6). Three bounded system events, not a loop.
            cancelDiscoveryRetries()
            discoverFieldWithRetry()
        }
    }

    // Which microphone a take would use right now, for the bubble's colour (#171). Two inputs, both
    // written on the main thread: the phone's input list, read once at registration and again on every
    // add or remove the system pushes (no poll, no timer), and the Input device pick from preferences.
    // The pick is null until the first preference emission, and nothing is computed before both are
    // known, so the first paint is never a guess about the saved pick.
    private var connectedInputs: List<InputDeviceCandidate> = emptyList()
    private var inputDevicePick: InputDevicePick? = null
    private val audioDeviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) = readInputsAndApply()
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) = readInputsAndApply()
    }

    // Bubble-discovery retry: when a return to a focused field warrants discovery but the editor is not
    // accessible yet (the window is still coming up: ~2 s on a real Samsung, #141 phone pass 2026-09-13),
    // the first look finds nothing. Re-look a few times so the user never has to re-tap the field. One
    // sequence at a time, guarded by a generation so a superseding transition or teardown cancels it, and
    // coalesced per focused window so a stream of windows-changed events cannot postpone the retries
    // indefinitely (Codex review, 2026-09-13). Separate from the insertion's retry.
    private var discoveryGeneration = 0
    private var discoveryAttempt = 0
    /** The focused window a running sequence chases (-1 when it was unknown), or null when none runs. */
    private var discoveryWindowId: Int? = null
    private var discoveryStartUptime = 0L
    private var discoveryRetryRunnable: Runnable? = null

    /**
     * Bumped on main by every event that supersedes what a running check read: a focus or click, a window
     * state change, a focus-changing windows-changed (`WINDOWS_CHANGE_FOCUSED`), a lock broadcast, a
     * connect, an explicit [refresh], or a service interrupt/unbind. An answer that began before the newest
     * bump is not drawn; what it asked is asked again behind the newer check (#561 review). Cosmetic
     * windows-changed events do not bump it, so a running check is never starved by them.
     */
    private var checkRevision = 0

    /** Set by [onServiceUnbound] until the next [discoverOnConnect]: an unbound service starts no check. */
    private var unbound = false

    /** A check that resolved to something to do: what it asked and the tracker's answer, adopted on main. */
    private class Probed(val resolved: BubbleFieldCheck.Resolved, val adoption: FieldAdoption)

    /** One field check's answer, carried from [probeExecutor] back to main. Window ids and bounds are plain values. */
    private class FieldCheckAnswer(
        val check: BubbleFieldCheck,
        /** Null when every event in the check was an accessibility overlay's own (our bubble moving). */
        val probed: Probed?,
        val focusedWindowId: Int,
        val imeBounds: Rect?,
        /** The sequence generation when the check began; a cancel since then decides the sequence instead. */
        val startedIn: Int,
        /** [checkRevision] when the check began; a newer one means the screen changed while it ran. */
        val startedRevision: Int,
    )

    /**
     * The one thread a field check blocks on (#561: `findFocus` on main was a fatal ANR). One worker at
     * most, so checks never overlap; it exits after a few idle seconds, so nothing is resident at idle
     * (`architecture-rules.md` RULE: no-idle-cost). Shut down at [close].
     */
    private val probeExecutor = ThreadPoolExecutor(0, 1, 5L, TimeUnit.SECONDS, LinkedBlockingQueue()) { runnable ->
        Thread(runnable, "BubbleFieldCheck").apply { isDaemon = true }
    }
    private val fieldChecks = BubbleFieldChecks(
        background = { probeExecutor.execute(it) },
        main = { mainHandler.post(it) },
        begin = ::beginFieldCheck,
        finish = ::finishFieldCheck,
        failed = ::fieldCheckFailed,
    )

    /**
     * Main thread, from `onServiceConnected`. `onServiceConnected` fires again on the same instance
     * whenever the system recomputes the accessibility state, for example when ANY package is installed
     * (measured 2026-09-12 on the emulator: another package's install reconnected this service mid-take).
     * The overlay keeps its field, keyboard and position state across those, so it is created once.
     */
    fun attach() {
        if (recordingOverlay == null) {
            recordingOverlay = RecordingAccessibilityOverlay(service).also { overlay ->
                overlay.onPositionChanged = { position -> historyScope.launch { bubblePositionStore.save(position) } }
                overlay.start()
            }
            // Where the idle bubble may appear (#523), on the same scope for the same reason as the look below.
            // Until the first emission the bubble is not drawn, so a hidden app never flashes it at service
            // start; every change re-checks the focused field at once.
            lookScope.launch {
                AppPreferences(service.applicationContext).bubbleVisibilityStored.collect { stored ->
                    mainHandler.post {
                        visibilityStored = stored
                        refresh()
                    }
                }
            }
            // Lock and unlock over a field that stays focused (#523): a keyguard dismissed above an app shown
            // over the lock emits no window event the service hears (measured 2026-11-08, Galaxy S24 Ultra on
            // Firebase Test Lab: the lips stayed hidden after unlock until a re-tap). The system's screen and
            // unlock broadcasts re-check the field instead. Pushed by the system, so no idle cost.
            // Guarded: a throw here would take down the accessibility service, and auto-paste with it.
            // EXPORTED on purpose: USER_PRESENT is sent by SystemUI's keyguard, not the system uid, and a
            // NOT_EXPORTED receiver never heard it on a Galaxy S24 Ultra (Test Lab matrix-17ihfy4fh57pt: no
            // bubble log at all after "keyguardGone"). All three are protected broadcasts, so no app can fake them.
            runCatching {
                service.registerReceiver(lockReceiver, IntentFilter().apply {
                    addAction(Intent.ACTION_SCREEN_ON)
                    addAction(Intent.ACTION_SCREEN_OFF)
                    addAction(Intent.ACTION_USER_PRESENT)
                }, null, mainHandler, Context.RECEIVER_EXPORTED)
            }.onFailure { error -> DebugLogger.error(TAG, "Unable to watch lock changes for the bubble", error) }
            // The chosen look, and every later change to it, applied on the main thread. Its own scope:
            // this collect never completes, and it must not be one of the history scope's children.
            lookScope.launch {
                AppPreferences(service.applicationContext).state.collect { preferences ->
                    mainHandler.post {
                        recordingOverlay?.setLook(preferences.bubbleLook)
                        inputDevicePick = InputDevicePick.parse(preferences.inputDevicePick)
                        applyEarbuds()
                    }
                }
            }
            // Delivered on the main thread, and read once now as well: the initial add callback is
            // the platform's promise, the read is ours.
            service.getSystemService(AudioManager::class.java)?.registerAudioDeviceCallback(audioDeviceCallback, mainHandler)
            readInputsAndApply()
        }
    }

    /**
     * Main thread, from `onServiceConnected`. A text box may already hold focus when the service
     * (re)connects: discover it rather than waiting for the user to tap it again. The window list is not
     * populated at the instant of connect (measured 2026-09-12: an immediate discovery found nothing while
     * the editor was focused), so this uses the same discover-with-retry sequence as an app-switch return,
     * under the one cancellation mechanism. It starts fresh: a prior sequence is invalidated first.
     */
    fun discoverOnConnect() {
        unbound = false
        // Bump the revision and abandon pre-reconnect checks synchronously BEFORE posting, so a queued
        // pre-reconnect completion cannot adopt an old field, hit DiscoveryStep.Stop -> resetDiscoverySequence(),
        // and bump discoveryGeneration past connectGeneration (causing the posted discovery to be skipped).
        checkRevision++
        cancelDiscoveryRetries()
        fieldChecks.cancel()
        val connectGeneration = discoveryGeneration
        // Generation-guard the post itself: an unbind/destroy between here and the looper turn bumps the
        // generation, so a torn-down service never starts a fresh sequence (Codex review, 2026-09-13).
        mainHandler.post { if (connectGeneration == discoveryGeneration) discoverFieldWithRetry() }
    }

    /** On the service's history scope, after the previous stop was read: the bubble's remembered dock. */
    suspend fun restorePosition() {
        bubblePositionStore.load()?.let { position ->
            mainHandler.post { recordingOverlay?.setPosition(position) }
        }
    }

    /** Main thread. An admitted field of our own was withdrawn with no event to say so: re-check it. */
    fun refresh() {
        if (recordingOverlay != null) requestCheck(BubbleFieldCheck(revalidate = true), supersedes = true)
    }

    /**
     * Tell the floating bubble whether another app's editable field is active, and where a docked
     * keyboard ends. Event-driven only: nothing here runs at idle, and a windows-changed event caused
     * by our own overlay moving is dropped (off main, by the check's window read) before any node is touched
     * (`architecture-rules.md` RULE: no-idle-cost, as read by the #135 adjudication). No accessibility call
     * runs here: every re-check is a [BubbleFieldCheck] run off main (#561).
     */
    fun updateBubbleFromEvent(event: AccessibilityEvent, remembered: Boolean) {
        val overlay = recordingOverlay ?: return
        when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_FOCUSED, AccessibilityEvent.TYPE_VIEW_CLICKED -> {
                if (remembered) {
                    // The field is found the direct way; stop any discovery-retry sequence still chasing it, and
                    // draw no answer of a check that read the screen before this focus.
                    checkRevision++
                    cancelDiscoveryRetries()
                    tracker.rememberedFieldKey()?.let { activateField(overlay, it) }
                } else if (event.eventType == AccessibilityEvent.TYPE_VIEW_FOCUSED) {
                    // A focus that lands unremembered can be the return to an app whose editor is already
                    // focused (no fresh focus event for the editor itself): discover, and retry until it is
                    // accessible. A click stays cheap (Codex review, BUG 1, 2026-09-13).
                    discoverFieldWithRetry()
                } else {
                    // A click on a non-editor: whether the remembered editor still holds focus is a question,
                    // not a given (a hardware-keyboard tab to a button; Codex round 2). Re-check without a
                    // traversal; if the field is still here, a pending retry sequence has done its job.
                    requestCheck(BubbleFieldCheck(revalidate = true), supersedes = true)
                }
            }
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> discoverFieldWithRetry()
            AccessibilityEvent.TYPE_WINDOWS_CHANGED -> {
                // Whether this window is our own overlay moving is a window-list read, so the check answers it
                // off main and drops the event if so. A warranted change discovers with retry; a cosmetic one
                // re-checks, and finding the field present cancels any pending retry. A window-focus change
                // (e.g. switching split-screen panes) supersedes a running check's reads; cosmetic ones do not.
                val warrantedAt = if (discoveryWarranted(event)) SystemClock.uptimeMillis() else null
                val focusChanged = (event.windowChanges and AccessibilityEvent.WINDOWS_CHANGE_FOCUSED) != 0
                requestCheck(BubbleFieldCheck(windowEvents = mapOf(event.windowId to warrantedAt)), supersedes = focusChanged)
            }
            else -> Unit
        }
    }

    /** Invalidate any pending discovery-retry sequence. Bumps the generation so an in-flight runnable that
     *  already left the handler queue, or a check already running, sees itself superseded and starts or
     *  continues nothing; the check waiting to run keeps only its re-check. */
    fun cancelDiscoveryRetries() {
        resetDiscoverySequence()
        fieldChecks.forgetSequence()
    }

    /**
     * Main thread, from `onInterrupt`: no answer of a check already running is drawn, the waiting check is
     * dropped, and the retry sequence ends. The service stays bound, so later events check as usual.
     */
    fun onServiceInterrupted() {
        checkRevision++
        cancelDiscoveryRetries()
        fieldChecks.cancel()
    }

    /**
     * Main thread, from `onUnbind`: as [onServiceInterrupted], and no check starts until the next connect
     * ([discoverOnConnect]), so a preference emission after unbind cannot start one on a dead connection.
     */
    fun onServiceUnbound() {
        unbound = true
        onServiceInterrupted()
    }

    /** Main thread. Hands [check] to the queue; one that [supersedes] what a running check read bumps [checkRevision]. */
    private fun requestCheck(check: BubbleFieldCheck, supersedes: Boolean) {
        if (unbound) return
        if (supersedes) checkRevision++
        fieldChecks.submit(check)
    }

    /** The sequence's own state only: a check waiting to run was submitted after the answer that ends it. */
    private fun resetDiscoverySequence() {
        discoveryGeneration++
        discoveryRetryRunnable?.let { mainHandler.removeCallbacks(it) }
        discoveryRetryRunnable = null
        discoveryAttempt = 0
        discoveryWindowId = null
    }

    /**
     * Main thread, from `onDestroy`: the look collect, the audio-device callback, the overlay (detach only;
     * the bus belongs to the session owner), then any discovery retry. Never the history scope. Safe to
     * call twice.
     */
    fun close() {
        if (closed) return
        closed = true
        lookScope.cancel()
        runCatching { service.unregisterReceiver(lockReceiver) }
        service.getSystemService(AudioManager::class.java)?.unregisterAudioDeviceCallback(audioDeviceCallback)
        recordingOverlay?.stop()
        recordingOverlay = null
        cancelDiscoveryRetries()
        // No check answer is rendered after this; a check blocked in a call ends with the framework's timeout.
        fieldChecks.close()
        probeExecutor.shutdownNow()
    }

    /** Main thread. The current input list, then the colour. A failed read is an empty list: brand rainbow. */
    private fun readInputsAndApply() {
        connectedInputs = runCatching {
            service.getSystemService(AudioManager::class.java)
                ?.getDevices(AudioManager.GET_DEVICES_INPUTS)
                ?.map(InputDeviceCandidate::from)
        }.getOrNull().orEmpty()
        applyEarbuds()
    }

    /** Main thread. Colours the bubble once both the pick and the inputs are known; brand until then. */
    private fun applyEarbuds() {
        val pick = inputDevicePick ?: return
        val earbuds = InputDeviceResolver.earbudsAreTheMicrophone(pick, connectedInputs)
        // Shape only: a count and a boolean, so the phone pass can read the colour's input off logcat.
        DebugLogger.log(TAG, "Bubble colour: inputs=${connectedInputs.size} earbuds=$earbuds")
        recordingOverlay?.setEarbuds(earbuds)
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
     * Main thread. Draws a check's answer: the field it found through the one visibility decision, or no
     * field, and where a docked keyboard ends. A check asks [EditorTargetTracker.fieldProbe] whether the
     * remembered editor is still focused and, on the rare events, discovers the editor that IS focused; the
     * calls themselves ran off main ([beginFieldCheck]). Without discovery a service recreated while a text
     * box already had focus (a Play update, an accessibility toggle) kept the bubble hidden until the user
     * tapped the box again (Codex review of the Play branch, 2026-09-12).
     */
    private fun renderField(overlay: RecordingAccessibilityOverlay, key: Any?, imeBounds: Rect?) {
        if (key != null) activateField(overlay, key) else overlay.fieldLost()
        overlay.keyboardBounds(dockedKeyboardTop(imeBounds))
    }

    /**
     * The ONE place the host tells the overlay a field is active (#523). The tracker keeps the field either
     * way, so pinning, the side button and insertion never depend on what is drawn here.
     */
    private fun activateField(overlay: RecordingAccessibilityOverlay, key: Any) {
        val stored = visibilityStored ?: return overlay.fieldSuppressed(key)
        val traits = tracker.rememberedFieldTraits()
        val decision = BubbleVisibility.decide(
            keyguardLocked = keyguardLocked(),
            passwordField = traits?.passwordField ?: false,
            packageName = traits?.packageName,
            hiddenApps = stored.hiddenApps,
            showOnPasswords = stored.showOnPasswords,
        )
        if (decision != lastDecision) {
            // The reason only, never the package (`kotlin-patterns.md` RULE: no-content-in-diagnostics).
            DebugLogger.debug(TAG, "Bubble visibility reason=${BubbleVisibility.reason(decision)}")
            lastDecision = decision
        }
        when (decision) {
            BubbleVisibility.Decision.Show -> overlay.fieldActivated(key)
            BubbleVisibility.Decision.HiddenLocked,
            BubbleVisibility.Decision.HiddenApp,
            BubbleVisibility.Decision.HiddenPassword -> overlay.fieldSuppressed(key)
        }
    }

    /** A read that fails counts as unlocked: a wrong hide costs the primary trigger. */
    private fun keyguardLocked(): Boolean = runCatching {
        service.getSystemService(KeyguardManager::class.java)?.isKeyguardLocked ?: false
    }.getOrDefault(false)

    /**
     * Discover the focused editor now, and if none is accessible yet, re-look at [DISCOVERY_RETRY_DELAYS_MS]
     * so a returning field that the framework exposes a second or two late still lights the bubble without a
     * re-tap (#141 phone pass). Coalesced per focused window: a stream of events for the same return does not
     * restart the schedule (which would postpone the retries forever); a genuinely different focused window
     * starts one fresh sequence. Stops the moment a field is found. Since #561 the look is a check run off
     * main, and the coalescing and the schedule are decided when it answers ([discoveryStep]).
     */
    private fun discoverFieldWithRetry() {
        if (recordingOverlay == null) return
        // Captured at the transition, BEFORE the first look, so the retry deadlines measure from it, not from
        // after a slow initial traversal (Codex proviso, 2026-09-13).
        requestCheck(BubbleFieldCheck(transition = true, transitionUptime = SystemClock.uptimeMillis()), supersedes = true)
    }

    private fun scheduleDiscoveryRetry(generation: Int) {
        if (discoveryAttempt >= DISCOVERY_RETRY_DELAYS_MS.size) {
            // The last attempt has answered: the sequence is over, and the next transition starts a fresh one.
            discoveryWindowId = null
            return
        }
        val runnable = Runnable {
            // A newer transition or a teardown bumped the generation: this attempt is stale.
            if (generation != discoveryGeneration) return@Runnable
            discoveryRetryRunnable = null
            if (recordingOverlay == null) return@Runnable
            discoveryAttempt++
            requestCheck(BubbleFieldCheck(retryOf = generation), supersedes = false)
        }
        discoveryRetryRunnable = runnable
        // Absolute offsets from the first attempt (uptime timebase), so a slow traversal cannot stretch the
        // schedule past the ~2 s window it is meant to cover. A looper that refuses the post is quitting: end
        // the sequence rather than leave it chasing a window with nothing scheduled.
        if (!mainHandler.postAtTime(runnable, discoveryStartUptime + DISCOVERY_RETRY_DELAYS_MS[discoveryAttempt])) {
            resetDiscoverySequence()
        }
    }

    /** A fresh sequence chasing [windowId], measured from the transition that started it. */
    private fun restartDiscovery(windowId: Int, startUptime: Long) {
        resetDiscoverySequence()
        discoveryWindowId = windowId
        discoveryStartUptime = startUptime
        scheduleDiscoveryRetry(discoveryGeneration)
    }

    /**
     * Main thread, when [fieldChecks] starts a check: copy what it needs from main (the remembered editor,
     * the sequence's state) and return its blocking half, which [probeExecutor] runs. That half makes every
     * accessibility call of the check: one window-list read (our own overlay, the focused window, the
     * keyboard) and the tracker's probe.
     */
    private fun beginFieldCheck(submitted: BubbleFieldCheck): () -> FieldCheckAnswer {
        // An attempt of a sequence a restart or cancel superseded while it waited does not traverse.
        val check = submitted.withoutRetryOtherThan(discoveryGeneration)
        val startedIn = discoveryGeneration
        val startedRevision = checkRevision
        if (check.isEmpty) return { FieldCheckAnswer(check, null, -1, null, startedIn, startedRevision) }
        val probe = tracker.fieldProbe()
        val chasingWindowId = discoveryWindowId
        return {
            val windows = runCatching { service.windows }.getOrNull().orEmpty()
            val overlayWindowIds = windows.filter { it.type == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY }.mapTo(HashSet()) { it.id }
            val focusedWindowId = windows.firstOrNull { it.isFocused }?.id ?: -1
            val probed = check.resolve(overlayWindowIds)?.let { resolved ->
                Probed(resolved, probe.probe(discover = resolved.discovers(focusedWindowId, chasingWindowId), windows = windows))
            }
            FieldCheckAnswer(check, probed, focusedWindowId, imeBounds(windows), startedIn, startedRevision)
        }
    }

    /**
     * Main thread: a check answered. Adopt it through the tracker, draw it, and move the retry sequence on.
     * An answer about an editor that is no longer the remembered one (a newer focus or a pin changed it while
     * the check ran) is dropped and asked again, so an old answer never overwrites a newer field.
     */
    private fun finishFieldCheck(answer: FieldCheckAnswer) {
        val overlay = recordingOverlay ?: return
        val probed = answer.probed ?: return
        if (answer.startedRevision != checkRevision) {
            // The screen changed while this check ran (a focus, a transition, a refresh): its reads may predate
            // that, so nothing of it is adopted or drawn. What it asked is asked again, OLDER than the check the
            // change submitted, so a transition is never lost to a plain re-check that superseded it.
            fieldChecks.submitOlder(answer.check.askAgain(answer.startedIn, discoveryGeneration))
            return
        }
        val key = when (val outcome = probed.adoption.adopt()) {
            FieldProbeOutcome.Stale -> {
                // The remembered editor changed (a pin) while the check ran: ask again about the one remembered now.
                fieldChecks.submitOlder(answer.check.askAgain(answer.startedIn, discoveryGeneration))
                return
            }
            is FieldProbeOutcome.Current -> outcome.key
        }
        renderField(overlay, key, answer.imeBounds)
        val step = discoveryStep(
            fieldPresent = key != null,
            resolved = probed.resolved,
            focusedWindowId = answer.focusedWindowId,
            chasingWindowId = discoveryWindowId,
            startedIn = answer.startedIn,
            generation = discoveryGeneration,
        )
        when (step) {
            DiscoveryStep.Stop -> resetDiscoverySequence()
            is DiscoveryStep.Restart -> restartDiscovery(step.windowId, probed.resolved.transitionUptime)
            DiscoveryStep.Continue -> if (discoveryRetryRunnable == null) scheduleDiscoveryRetry(discoveryGeneration)
            DiscoveryStep.Keep -> Unit
        }
    }

    /**
     * Main thread: a check's blocking half threw, so it answered nothing and nothing is drawn. A running
     * sequence still moves on: its attempt counts as finding nothing, so it schedules the next one or ends,
     * never stalls chasing a window with nothing scheduled (which would make every later transition into that
     * window coalesce into a sequence that no longer runs).
     */
    private fun fieldCheckFailed(check: BubbleFieldCheck) {
        if (recordingOverlay == null) return
        val step = discoveryStep(
            fieldPresent = false,
            resolved = BubbleFieldCheck.Resolved(startsSequence = false, retryOf = check.retryOf),
            focusedWindowId = -1,
            chasingWindowId = discoveryWindowId,
            startedIn = discoveryGeneration,
            generation = discoveryGeneration,
        )
        if (step == DiscoveryStep.Continue && discoveryRetryRunnable == null) scheduleDiscoveryRetry(discoveryGeneration)
    }

    /** Off main, from the check's one window-list read: the input method window's bounds, or null. */
    private fun imeBounds(windows: List<AccessibilityWindowInfo>): Rect? = runCatching {
        windows.firstOrNull { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }?.let { ime -> Rect().also { ime.getBoundsInScreen(it) } }
    }.getOrNull()

    /**
     * Main thread. The top of a keyboard docked at the bottom edge, in screen pixels, or null. A floating or
     * split keyboard does not touch the bottom edge and is reported as null on purpose: clamping above it
     * would push the bubble into the middle of the screen. [imeBounds] were read off main by the check.
     */
    private fun dockedKeyboardTop(imeBounds: Rect?): Int? = runCatching {
        val bounds = imeBounds ?: return null
        val screenBottom = service.getSystemService(WindowManager::class.java).currentWindowMetrics.bounds.bottom
        if (bounds.bottom >= screenBottom - 1 && bounds.height() > 0) bounds.top else null
    }.getOrNull()
}
