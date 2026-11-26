package com.envi.wispr.paste

import com.envi.wispr.ui.SessionSources
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * DRIFT GUARD for the lips bubble's wiring (issue #135). None of these is product coverage; each pins
 * a decision the plan's reviews settled, so that a later edit cannot quietly undo it.
 */
class LipsBubbleWiringTest {

    /** Since #217 the service delegates: the bubble host, the tracker and the runner hold the moved code. */
    private val service = PasteSources.service
    private val bubbleHost = PasteSources.bubble
    private val tracker = PasteSources.tracker
    /** The owner since #186; the overlay state is reached through its `surface` seam and the notification through `host`. */
    private val session = File("src/main/java/com/envi/wispr/ui/DictationSessionCoordinator.kt").readText()
    private val recorder = File("src/main/java/com/envi/wispr/ui/RecorderSurface.kt").readText()
    /** The floating recorder since #360: the window, its views and its gesture rules, read as one text. */
    private val overlay = listOf("RecordingAccessibilityOverlay", "BubbleViews", "BubbleGestureController")
        .joinToString("\n") { File("src/main/java/com/envi/wispr/paste/$it.kt").readText() }
    private val launcher = File("src/main/java/com/envi/wispr/ui/VoiceInputActivity.kt").readText()
    private val manifest = File("src/main/AndroidManifest.xml").readText()
    private val config = File("src/main/res/xml/accessibility_service_config.xml").readText()

    @Test
    fun theAccessibilityServicePublishesNothingToTheOverlayBus() {
        // Only the session owner may say what phase a take is in. A hide() here made a mid-take
        // reconnect look like IDLE (review round 1).
        assertFalse(PasteSources.all.contains("RecordingOverlayState.hide()"))
        assertFalse(PasteSources.all.contains("RecordingOverlayState.show"))
    }

    @Test
    fun theSessionOwnerPublishesIdleOnlyWhereItCanNoLongerRefuseAStart() {
        // finishSession publishes IDLE right before stopSelf; onDestroy publishes it because the
        // owner is gone. Every other terminal path publishes PROCESSING.
        val finish = session.substringAfter("private fun finishSession()").substringBefore("private fun stopIfIdle()")
        assertTrue(finish.contains("surface.showProcessing()"))
        assertTrue(finish.contains("surface.hide()"))
        // Each phase the owner publishes must reach the real overlay state (Codex review C1, 2026-09-20).
        listOf("showProcessing", "hide", "nameTarget", "attachTranscript", "showNotice").forEach {
            assertTrue("RecorderSurface.$it is not wired", recorder.contains("RecordingOverlayState.$it("))
        }
        assertTrue(finish.indexOf("surface.hide()") > finish.indexOf("host.removeForegroundAndDismiss()"))
        // Both cancels share one body since the live gate (a cancelled start has capture running too).
        listOf("private fun cancelRecording()", "private fun cancelStarting()").forEach { head ->
            val body = session.substringAfter(head).substringBefore("\n    private fun ")
            assertTrue("$head delegates to the shared cancel", body.contains("cancelCaptureAndFinish("))
        }
        // The stop delegates to the one RECORDING to PROCESSING transition (#115); it publishes there.
        val stop = session.substringAfter("private fun stopAndTranscribe()").substringBefore("\n    private fun ")
        assertTrue("stopAndTranscribe enters PROCESSING through the shared transition", stop.contains("enterProcessing()"))
        assertFalse(stop.contains("surface.hide()"))
        listOf("private fun enterProcessing()", "private fun cancelCaptureAndFinish(", "private fun announceError(")
            .forEach { head ->
                val body = session.substringAfter(head).substringBefore("\n    private fun ")
                assertTrue("$head must publish PROCESSING", body.contains("surface.showProcessing()"))
                assertFalse("$head must not publish IDLE", body.contains("surface.hide()"))
            }
    }

    @Test
    fun theOwnerPublishesTheTakesTargetAndRowSoNoReaderGuessesThem() {
        // The onboarding practice judges ONLY the row the owner names, for a take aimed at ITS box.
        val begin = session.substringAfter("private fun beginSession()").substringBefore("\n    private fun ")
        assertTrue(begin.contains("surface.nameTarget("))
        assertTrue(begin.indexOf("nameTarget(") > begin.indexOf("pinTargetForDictation()"))
        assertTrue(session.contains("surface.attachTranscript(id)"))
        val viewModel = File("src/main/java/com/envi/wispr/ui/OnboardingViewModel.kt").readText()
        assertTrue(viewModel.contains("snapshot.targetFieldId == PRACTICE_FIELD_ID"))
        assertTrue(viewModel.contains("snapshot.transcriptId"))
        assertFalse(viewModel.contains("System.currentTimeMillis"))
    }

    @Test
    fun theBubbleMintsATokenAndSendsReleaseAndCancelWithIt() {
        // Since #360 the rules run in BubbleGestureController, whose own rows drive them; this pins the wiring.
        // The gesture is on the token, so the owner's snapshot can say which one a take answers.
        assertTrue(overlay.contains("mint = BubbleRequests::mint,"))
        assertTrue(overlay.contains("is BubbleCommand.Stop -> DictationSessionService.sendCommand(service, DictationSessionService.ACTION_STOP, command.request.encode())"))
        assertTrue(overlay.contains("is BubbleCommand.Cancel -> DictationSessionService.sendCommand(service, DictationSessionService.ACTION_CANCEL, command.request.encode())"))
        // A tap starts a new request only at IDLE.
        assertTrue(overlay.contains("override fun idle(): Boolean = snapshot.phase == RecordingOverlayState.Phase.IDLE"))
        // A hold's release and cancel go to the request THAT hold created, never to an earlier take.
        assertTrue(overlay.contains("holdRequest = request"))
        assertTrue(overlay.contains("BubbleGesture.HoldRelease -> listOfNotNull(holdRequest?.let { BubbleCommand.Stop(it) })"))
        assertFalse(overlay.contains("currentRequest"))
    }

    @Test
    fun focusLeavingTheEditorForANonEditableControlIsRevalidated() {
        val branch = PasteSources.slice(bubbleHost, "AccessibilityEvent.TYPE_VIEW_FOCUSED, AccessibilityEvent.TYPE_VIEW_CLICKED ->", "AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED")
        // Remembered = field found the direct way: activate it and stop any discovery retries chasing it.
        assertTrue(branch.contains("if (remembered) {"))
        assertTrue(branch.contains("cancelDiscoveryRetries()"))
        // An unremembered focus discovers WITH retry (the return to an app whose editor is already focused
        // emits no fresh focus event for the editor, and the editor can be exposed ~2 s late); a click just
        // re-checks without a traversal, to stay cheap (BUG 1 + phone-pass lag, 2026-09-13).
        assertTrue(branch.contains("event.eventType == AccessibilityEvent.TYPE_VIEW_FOCUSED) {"))
        assertTrue(branch.contains("discoverFieldWithRetry()"))
    }

    @Test
    fun theOwnerResolvesEveryMarkedCommandThroughTheLedgerBeforeDispatch() {
        val handler = session.substringAfter("fun handleCommand(").substringBefore("private fun admitBubbleCommand(")
        assertTrue(handler.indexOf("admitBubbleCommand(") < handler.indexOf("when (action)"))
        assertTrue(session.contains("BubbleRequests.resolveStart("))
        assertTrue(session.contains("BubbleRequests.resolveCommand("))
        // The early release is consumed at the RECORDING transition, nowhere else.
        assertTrue(session.contains("private fun publishLive(") && session.contains("private fun publishSilenceNoticeIfNeeded("))
        val live = session.substringAfter("private fun publishLive(").substringBefore("private fun publishSilenceNoticeIfNeeded(")
        assertTrue(live.contains("if (stopAfterRecording)"))
        assertEquals("and only there", 1, Regex("if \\(stopAfterRecording\\)").findAll(SessionSources.all).count())
    }

    @Test
    fun theLauncherKnowsStartAndForwardsTheRequest() {
        assertTrue(launcher.contains("const val EXTRA_START = \"start\""))
        assertTrue(launcher.contains("const val EXTRA_REQUEST = \"request\""))
        assertTrue(launcher.contains("intent.getBooleanExtra(EXTRA_START, false) -> DictationSessionService.ACTION_START"))
        assertTrue(launcher.contains("intent.getStringExtra(EXTRA_REQUEST)"))
        // The default stays TOGGLE for the side button.
        assertTrue(launcher.contains("else -> DictationSessionService.ACTION_TOGGLE"))
    }

    @Test
    fun noAppearOnTopPermissionWasAdded() {
        // The adjudicated decision: the bubble lives in the accessibility window.
        assertFalse(manifest.contains("SYSTEM_ALERT_WINDOW"))
    }

    @Test
    fun theServiceRequestsTheKeyboardPipeInBothPlacesTheFlagsLive() {
        // configureEventMode replaces serviceInfo whole on every call, so the input method flag must
        // be in that call or the first dictation wipes it; the XML mirrors it for the bind (#141).
        assertTrue(config.contains("flagInputMethodEditor"))
        val eventMode = service.substringAfter("private fun configureEventMode(").substringBefore("\n    }")
        assertTrue(eventMode.contains("AccessibilityServiceInfo.FLAG_INPUT_METHOD_EDITOR"))
        assertTrue(service.contains("override fun onCreateInputMethod(): InputMethod = EditorInputSession(this)"))
    }

    @Test
    fun theServiceSubscribesToWindowsChangedForTheKeyboard() {
        assertTrue(config.contains("typeWindowsChanged"))
        assertTrue(config.contains("flagRetrieveInteractiveWindows"))
        assertTrue(config.contains("android:canRetrieveWindowContent=\"true\""))
        assertTrue(service.contains("AccessibilityEvent.TYPE_WINDOWS_CHANGED"))
        // And the bubble host acts on it (#217): the keyboard's own windows-changed reaches the bubble.
        assertTrue(bubbleHost.contains("AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> discoverFieldWithRetry()"))
        assertTrue(bubbleHost.contains("AccessibilityEvent.TYPE_WINDOWS_CHANGED -> {"))
        assertTrue(PasteSources.slice(service, "override fun onAccessibilityEvent(", "\n    }").contains("bubble.updateBubbleFromEvent(event, remembered)"))
    }

    @Test
    fun aReconnectAndAWindowSwitchDiscoverAnAlreadyFocusedEditor() {
        // Discovery is a traversal, so it is allowed on connect, on a window state change, and on a
        // windows-changed that carries a focus/active/added/removed change (a real app switch), never on
        // the cosmetic windows-changed stream (BUG 1, 2026-09-13). Connect and app-switch both run the
        // discover-with-retry sequence so a late-exposed editor still lights the bubble (phone-pass lag).
        // Since #217 the service asks the bubble host to discover on connect; the host holds the guarded post.
        assertTrue(PasteSources.slice(service, "override fun onServiceConnected()", "\n    }").contains("bubble.discoverOnConnect()"))
        assertTrue(bubbleHost.contains("mainHandler.post { if (connectGeneration == discoveryGeneration) discoverFieldWithRetry() }"))
        // Since #561 a windows-changed is a check run off main: the window-list read that tells our own overlay
        // apart drops the event there, and a warranted change starts a sequence when the check answers.
        val windowsChanged = PasteSources.slice(bubbleHost, "AccessibilityEvent.TYPE_WINDOWS_CHANGED -> {", "else -> Unit")
        assertTrue(windowsChanged.contains("val warranted = discoveryWarranted(event)"))
        assertTrue(windowsChanged.contains("windowEvents = mapOf(event.windowId to warranted)"))
        val gate = PasteSources.slice(bubbleHost, "private fun discoveryWarranted(", "\n    /**")
        assertTrue(gate.contains("event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return true"))
        assertTrue(gate.contains("AccessibilityEvent.WINDOWS_CHANGE_FOCUSED"))
        assertTrue(gate.contains("AccessibilityEvent.WINDOWS_CHANGE_ACTIVE"))
        assertTrue(gate.contains("AccessibilityEvent.WINDOWS_CHANGE_ADDED"))
        assertTrue(gate.contains("AccessibilityEvent.WINDOWS_CHANGE_REMOVED"))
        assertTrue(gate.contains("event.windowChanges and relevant) != 0"))
        // Since #217 the bubble asks the tracker, which checks and adopts; since #561 it asks through a probe
        // whose calls run off main, and the bubble renders the adopted answer.
        val begin = PasteSources.slice(bubbleHost, "private fun beginFieldCheck(", "\n    /**")
        assertTrue(begin.contains("val probe = tracker.fieldProbe()"))
        assertTrue(begin.contains("probe.probe(discover = resolved.discovers(focusedWindowId, chasingWindowId))"))
        val finish = PasteSources.slice(bubbleHost, "private fun finishFieldCheck(", "\n    /**")
        assertTrue(finish.contains("probed.adoption.adopt()"))
        assertTrue(finish.indexOf("FieldProbeOutcome.Stale ->") in 0 until finish.indexOf("renderField(overlay, key, answer.imeBounds)"))
        // Since #523 an active field goes through the one visibility decision (`activateField`).
        val render = PasteSources.slice(bubbleHost, "private fun renderField(", "\n    /**")
        assertTrue(render.indexOf("activateField(overlay, key) else overlay.fieldLost()") in 0 until render.indexOf("overlay.keyboardBounds(dockedKeyboardTop(imeBounds))"))
        val body = PasteSources.slice(tracker, "fun fieldProbe(): FieldProbe {", "\n    /**")
        assertTrue(body.contains("if (!stillFocused && discover)"))
        assertTrue(body.contains("findFocusedEditableTarget()"))
        // Split screen: an editor in the other pane keeps its focus flag. Both the remembered editor
        // and a discovered one must sit in the window that has input focus, or the bubble hides.
        assertTrue(body.contains("isSafeFocusedEditor(remembered.node) && isInFocusedWindow(remembered.windowId)"))
        assertTrue(body.contains("findFocusedEditableTarget()?.takeIf { isInFocusedWindow(it.windowId) }"))
        // A failed discovery keeps the old target: it is recycled only once a new one was adopted, on main,
        // and only while the remembered editor is still the one the probe copied.
        val adopt = PasteSources.slice(tracker, "private fun adoptFieldProbe(", "\n    }\n")
        assertTrue(adopt.indexOf("if (epoch != targetEpoch) {") in 0 until adopt.indexOf("if (found != null) {"))
        assertTrue(adopt.indexOf("if (found != null) {") in 0 until adopt.indexOf("clearTarget()"))
        assertTrue(tracker.contains("service.windows.any { it.id == windowId && it.isFocused }"))
    }

    @Test
    fun discoveryRetriesUntilTheReturningEditorIsAccessible() {
        // A real Samsung exposes a returning editor ~2 s late, so the first look finds nothing. Re-look at
        // fixed offsets so the bubble lights without a re-tap; one guarded sequence, coalesced per window,
        // cancelled on success and teardown (phone pass + Codex, 2026-09-13).
        assertTrue(bubbleHost.contains("DISCOVERY_RETRY_DELAYS_MS = longArrayOf(250L, 750L, 2_250L)"))
        // Since #561 the first look and each attempt are checks run off main; the coalescing and the schedule
        // are decided when a check answers (`discoveryStep`, its branches in BubbleFieldCheckTest).
        val start = PasteSources.slice(bubbleHost, "private fun discoverFieldWithRetry()", "\n    }\n")
        // The deadlines measure from the transition, captured before the first look.
        assertTrue(start.contains("BubbleFieldCheck(transition = true, transitionUptime = SystemClock.uptimeMillis())"))
        val finish = PasteSources.slice(bubbleHost, "private fun finishFieldCheck(", "\n    /**")
        // Coalesce: a sequence already chasing the same focused window (unknown -1 included) is not restarted;
        // the window it chases is the sequence's state on main, read when the check answers.
        assertTrue(finish.contains("chasingWindowId = discoveryWindowId"))
        assertTrue(finish.contains("is DiscoveryStep.Restart -> restartDiscovery(step.windowId, answer.check.transitionUptime)"))
        // Stops the moment a field is found.
        assertTrue(finish.contains("DiscoveryStep.Stop -> resetDiscoverySequence()"))
        assertTrue(finish.contains("DiscoveryStep.Continue -> if (discoveryRetryRunnable == null) scheduleDiscoveryRetry(discoveryGeneration)"))
        val restart = PasteSources.slice(bubbleHost, "private fun restartDiscovery(", "\n    }\n")
        assertTrue(restart.contains("discoveryStartUptime = startUptime"))
        assertTrue(restart.contains("scheduleDiscoveryRetry(discoveryGeneration)"))
        val sched = PasteSources.slice(bubbleHost, "private fun scheduleDiscoveryRetry(", "\n    }\n")
        // Absolute offsets from the first attempt, so a slow traversal cannot stretch the schedule.
        assertTrue(sched.contains("postAtTime(runnable, discoveryStartUptime + DISCOVERY_RETRY_DELAYS_MS[discoveryAttempt])"))
        // Generation guard: a superseding transition or teardown makes an in-flight attempt do nothing.
        assertTrue(sched.contains("if (generation != discoveryGeneration) return@Runnable"))
        assertTrue(sched.contains("fieldChecks.submit(BubbleFieldCheck(retryOf = generation))"))
        val cancel = PasteSources.slice(bubbleHost, "fun cancelDiscoveryRetries()", "\n    }\n")
        assertTrue(cancel.contains("resetDiscoverySequence()"))
        assertTrue(cancel.contains("fieldChecks.forgetSequence()"))
        val reset = PasteSources.slice(bubbleHost, "private fun resetDiscoverySequence()", "\n    }\n")
        assertTrue(reset.contains("discoveryGeneration++"))
        assertTrue(reset.contains("mainHandler.removeCallbacks(it)"))
        // Cancelled on every teardown so retries never leak past the service.
        // Since #217 the service asks the bubble host; its close cancels them on destroy.
        assertTrue(PasteSources.slice(service, "override fun onInterrupt()", "\n    }\n").contains("bubble.cancelDiscoveryRetries()"))
        assertTrue(PasteSources.slice(service, "override fun onUnbind(", "\n    }\n").contains("bubble.cancelDiscoveryRetries()"))
        assertTrue(PasteSources.slice(service, "override fun onDestroy()", "\n    }\n").contains("bubble.close()"))
        val close = PasteSources.slice(bubbleHost, "fun close() {", "\n    }\n")
        assertTrue(close.contains("cancelDiscoveryRetries()"))
        assertTrue(close.contains("fieldChecks.close()"))
        assertTrue(close.contains("probeExecutor.shutdownNow()"))
    }

    @Test
    fun theBubbleMakesNoAccessibilityCallOnTheMainThread() {
        // #561 (Sentry ENVIOUSWISPR-ANDROID-V): `discoverOnConnect` reached `findFocus` on main and the phone
        // froze in a fatal ANR. Every accessibility call of a field check now runs in the blocking half that
        // `beginFieldCheck` returns, which `probeExecutor` runs; nothing else in the host makes one.
        // REVERT: call `tracker.fieldProbe().probe(...)` from `updateBubbleFromEvent`, or read `service.windows`
        // in `discoverFieldWithRetry`, and this row turns red.
        val begin = PasteSources.slice(bubbleHost, "private fun beginFieldCheck(", "\n    /**")
        val blockingHalf = begin.substringAfter("return {")
        assertEquals("one window-list read in the host", 1, Regex("""\bservice\.windows\b""").findAll(bubbleHost).count())
        assertTrue("and it is in the blocking half", blockingHalf.contains("service.windows"))
        assertEquals("one probe call in the host", 1, Regex("""\.probe\(""").findAll(bubbleHost).count())
        assertTrue("and it is in the blocking half", blockingHalf.contains("probe.probe(discover = "))
        listOf("""\bfindFocus\(""", """\brootInActiveWindow\b""", """\.refresh\(\)""", """\bgetWindows\(""").forEach {
            assertEquals("the host makes no $it call", 0, Regex(it).findAll(bubbleHost).count())
        }
        // The blocking half reaches main only through the queue, which runs it on the executor.
        assertTrue(bubbleHost.contains("background = { probeExecutor.execute(it) }"))
        assertTrue(bubbleHost.contains("main = { mainHandler.post(it) }"))
        assertTrue(bubbleHost.contains("ThreadPoolExecutor(0, 1, 5L, TimeUnit.SECONDS, LinkedBlockingQueue())"))
        // The tracker's half that runs off main touches none of its state: no write of the remembered or
        // pinned editor inside the probe lambda, and its answer is adopted back on main.
        val probe = PasteSources.slice(tracker, "return FieldProbe { discover ->", "FieldAdoption { adoptFieldProbe(epoch, stillFocused, found) }")
        listOf("lastTarget", "pinnedTarget", "targetEpoch", "clearTarget(", "notifyObservedField(").forEach {
            assertFalse("the probe lambda touches $it", probe.contains(it))
        }
    }

    @Test
    fun discoverySearchesOnlyTheInputFocusedWindow() {
        // findFocusedEditableTarget must not return an editor from an unfocused window, or pinTarget could
        // rediscover the stale editor it just rejected and pin the departed app (Codex fast-follow,
        // 2026-09-13). The filter is applied during the search: the active-root shortcut is gated on
        // window focus, and the windows loop skips unfocused windows.
        val body = PasteSources.slice(tracker, "private fun findFocusedEditableTarget(): TargetSnapshot?", "\n    }\n")
        assertTrue(body.contains("activeRoot?.takeIf { isInFocusedWindow(it.windowId) }"))
        assertTrue(body.contains("if (!window.isFocused) continue"))
    }

    @Test
    fun pinningReusesATargetOnlyWhileItsWindowStillHasFocus() {
        // A remembered editor in the app the user just left keeps its own focus flag; node focus alone
        // would pin the departed field and the words would land there. Both reuse checks in pinTarget
        // require the target's window to still own input focus (Codex review, BUG 1, 2026-09-13).
        val body = PasteSources.slice(tracker, "fun pinTarget(insertionPending: Boolean): DictationTargetPin {", "\n    }\n")
        assertTrue(body.contains("existing.node.refresh() && isSafeFocusedEditor(existing.node) && isInFocusedWindow(existing.windowId)"))
        assertTrue(body.contains("!isSafeFocusedEditor(target.node) || !isInFocusedWindow(target.windowId)"))
    }

    @Test
    fun aScreenReaderDoubleTapStartsDictationThroughTheClickAction() {
        val bubble = overlay.substringAfter("private fun buildBubble()").substringBefore("private fun buildPillColumn()")
        assertTrue(bubble.contains("setOnClickListener { onBubbleClick() }"))
        assertTrue(overlay.contains("onBubbleClick = { perform(gestures.accessibilityTap()) },"))
        assertTrue(bubble.contains("contentDescription = RecordingAccessibilityOverlay.BUBBLE_LABEL"))
        assertTrue(RecordingAccessibilityOverlay.BUBBLE_LABEL.contains("Double tap to dictate"))
    }

    // ---- #171: the earbud colour ----

    @Test
    fun theServiceWatchesTheInputsOnTheMainThreadOncePerInstanceAndStopsWatchingOnDestroy() {
        // The callback is registered inside the once-per-instance block (a repeat onServiceConnected
        // must not register twice), delivered on the main handler (the overlay's thread), read once at
        // registration (the initial add callback is the platform's promise, the read is ours), and
        // unregistered in onDestroy before the overlay is stopped.
        // Since #217 the service attaches the bubble host once per connect; the host does the rest.
        assertEquals(1, Regex("bubble\\.attach\\(\\)").findAll(service).count())
        assertTrue(PasteSources.slice(service, "override fun onServiceConnected()", "\n    }\n").contains("bubble.attach()"))
        val creation = PasteSources.slice(bubbleHost, "if (recordingOverlay == null) {", "\n        }\n    }\n")
        assertTrue(creation.indexOf("registerAudioDeviceCallback(audioDeviceCallback, mainHandler)") in 0 until creation.lastIndexOf("readInputsAndApply()"))
        assertEquals(1, Regex("registerAudioDeviceCallback\\(").findAll(PasteSources.all.replace("unregisterAudioDeviceCallback(", "")).count())
        val close = PasteSources.slice(bubbleHost, "fun close() {", "recordingOverlay?.stop()")
        assertTrue(close.contains("unregisterAudioDeviceCallback(audioDeviceCallback)"))
        // Both inputs of the colour are written on main: the pick inside the posted preference block,
        // the list inside the callback and the registration read.
        val posted = PasteSources.slice(bubbleHost, "AppPreferences(service.applicationContext).state.collect", "readInputsAndApply()")
        assertTrue(posted.contains("mainHandler.post {") && posted.contains("inputDevicePick = InputDevicePick.parse(preferences.inputDevicePick)") && posted.contains("applyEarbuds()"))
        // Nothing is coloured before the saved pick has arrived, and a failed device read is brand.
        val apply = PasteSources.slice(bubbleHost, "private fun applyEarbuds()", "\n    }")
        assertTrue(apply.contains("val pick = inputDevicePick ?: return"))
        assertTrue(apply.contains("InputDeviceResolver.earbudsAreTheMicrophone(pick, connectedInputs)"))
        val read = PasteSources.slice(bubbleHost, "private fun readInputsAndApply()", "\n    }")
        assertTrue(read.contains("runCatching") && read.contains(".getOrNull().orEmpty()"))
    }

    @Test
    fun theBubbleTellsAScreenReaderAboutTheEarbudsAndTakesItBack() {
        val set = overlay.substringAfter("fun setEarbuds(earbuds: Boolean) {").substringBefore("\n    }")
        assertTrue(set.contains("bubble.contentDescription = if (earbuds) RecordingAccessibilityOverlay.BUBBLE_LABEL_EARBUDS else RecordingAccessibilityOverlay.BUBBLE_LABEL"))
        assertTrue(RecordingAccessibilityOverlay.BUBBLE_LABEL_EARBUDS.contains("using your earbuds"))
        assertFalse(RecordingAccessibilityOverlay.BUBBLE_LABEL.contains("earbuds"))
        // The instructions survive in both labels.
        for (label in listOf(RecordingAccessibilityOverlay.BUBBLE_LABEL, RecordingAccessibilityOverlay.BUBBLE_LABEL_EARBUDS)) {
            assertTrue(label.endsWith("Double tap to dictate. Touch and hold to talk. Drag to move."))
        }
    }

    @Test
    fun theOverlayIsCreatedOncePerServiceInstance() {
        // A repeat onServiceConnected (any package install triggers one) must not drop the bubble's state.
        assertTrue(bubbleHost.contains("if (recordingOverlay == null) {"))
        assertTrue(PasteSources.slice(bubbleHost, "fun attach() {", "\n    }\n").contains("if (recordingOverlay == null) {"))
    }

    @Test
    fun theOverlayWindowTitleIsUnchangedForTheDeviceHarness() {
        assertEquals(1, Regex("\"EnviousWispr recording controls\"").findAll(overlay).count())
    }
}
