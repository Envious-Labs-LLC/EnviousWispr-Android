package com.envi.wispr

import android.app.ActivityManager
import android.app.NotificationManager
import android.app.UiAutomation
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.content.SharedPreferences
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import androidx.core.app.NotificationManagerCompat
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.envi.wispr.asr.AsrService
import com.envi.wispr.asr.IAsrCallback
import com.envi.wispr.asr.IAsrService
import com.envi.wispr.debug.DebugLogger
import com.envi.wispr.history.EnviousWisprDatabase
import com.envi.wispr.history.TranscriptEntity
import com.envi.wispr.insertion.InsertionResults
import com.envi.wispr.paste.PasteAccessibilityService
import com.envi.wispr.polish.IPolishCallback
import com.envi.wispr.polish.IPolishService
import com.envi.wispr.polish.PolishOutcome
import com.envi.wispr.polish.PolishPolicy
import com.envi.wispr.polish.S1ControlSettings
import com.envi.wispr.polish.PolishService
import com.envi.wispr.settings.AppPreferences
import com.envi.wispr.shortcuts.DictationNotificationController
import com.envi.wispr.ui.DictationSessionService
import com.envi.wispr.vocabulary.BuiltinVocabulary
import com.envi.wispr.vocabulary.CustomTermRecord
import com.envi.wispr.vocabulary.CustomTermRepository
import com.envi.wispr.vocabulary.StructuredTermRestorer
import java.io.File
import java.io.FileInputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The heart on a real device, driven the way the side button drives it (#161, REF-09).
 *
 * PRODUCT OUTCOME rows: [aSideButtonTakeLandsInTheFocusedEditorExactlyOnce] (when it fails, the user's
 * words did not reach the field they were typing in, or reached it twice), [aTakeStartedInAAndFocusMovedToBInsertsNowhereAndKeepsTheWordsOnTheClipboard]
 * (the words reached a field the user had left, or the wrong field), [aDictationWithNoFieldToInsertIntoIsNotReportedToTheUserAsAFailure]
 * (an ordinary tile dictation is reported as broken), and [transcribesThenPolishesWithSavedCustomWords]
 * (the polish limb drops the user's own spellings), and [aSilenceStoppedTakeLandsInTheFocusedEditorExactlyOnce]
 * (with stop-on-silence on, a take the user simply stops talking in never ends, or its words do not land).
 *
 * Every wait here is on a signal the SUBJECT fires, never elapsed time: the session owner's phase writes
 * (`DictationSurfaceState`, read through an `OnSharedPreferenceChangeListener`), the History row the
 * owner finalises (`TranscriptDao.observeAll`), the driver's audio-done broadcast, the rig's ordered
 * focus answer, and `AudioTrack.onMarkerReached` for speaker playback. The one poll left is the polish
 * row's wait on `isLocalModelReady`, a binder property with no completion callback, stated here rather
 * than hidden.
 *
 * AUDIO ARRIVES FROM OUTSIDE. `-e audio external` (the default): the row publishes `driver_phase=READY`
 * on the instrumentation stream with the reserved code [DRIVER_READY_STATUS] once it has seen LISTENING
 * and finished staging; the driver (`scripts/uat/wispr_eyes.py` `run_device_test`) then feeds the audio
 * and sends [ACTION_EXTERNAL_AUDIO_DONE] carrying this run's `-e driver_token`; the row waits for it, then
 * stops the take. `-e audio speaker` plays the cached fixture through the phone's speaker instead, for a
 * phone with no driver present. The editor's text is the rig's own receipt file, read from the test
 * package's storage with `run-as`, an oracle this process did not write.
 */
@RunWith(AndroidJUnit4::class)
class VoicePipelineDeviceTest {
    companion object {
        const val SURFACE_STATE_PREFERENCES = "dictation_surface_state"
        const val SURFACE_STATE_PHASE = "phase"
        const val ARG_AUDIO = "audio"
        const val AUDIO_EXTERNAL = "external"
        const val AUDIO_SPEAKER = "speaker"
        const val ARG_DRIVER_TOKEN = "driver_token"
        const val ARG_EXPECTED_FINAL = "expected_final"
        const val DRIVER_PHASE_KEY = "driver_phase"

        /** A result code no runner uses; the driver treats a bundle with this code and `driver_phase=READY` as control, never a verdict. */
        const val DRIVER_READY_STATUS = 161
        const val ACTION_EXTERNAL_AUDIO_DONE = "com.envi.wispr.debug.EXTERNAL_AUDIO_DONE"

        /**
         * The WHOLE text the editor must hold after the driver's default sentence, a literal: the speech
         * engine's own terminal period and the insertion's trailing space at the end of an empty field
         * (`InsertionText`). Never derived from the sentence. `-e expected_final` overrides it, and the
         * speaker path REQUIRES it because the cached fixture's words are the caller's.
         */
        const val EXPECTED_TEXT = "The quarterly marker sentence lands tomorrow. "
        const val TEST_PACKAGE = "com.envi.wispr.test"

        /** The owner's terminal statuses for a take that produced words: inserted, or kept on the clipboard. */
        val FINAL_STATUSES = setOf(TranscriptEntity.STATUS_COMPLETED, TranscriptEntity.STATUS_INSERTION_INTERRUPTED)
    }

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val arguments: Bundle get() = InstrumentationRegistry.getArguments()
    private val fixturePath = File(context.cacheDir, "enviouswispr-uat.pcm").path

    // ---- the polish limb ---------------------------------------------------------------------------------

    @Test
    fun transcribesThenPolishesWithSavedCustomWords() {
        // ASSERTED, never assumed (#215): an assumption is reported as a pass by a bare run, so this row read
        // green wherever nobody had staged its fixture.
        assertTrue(
            "The real-model fixture is missing at $fixturePath: stage it with wispr_eyes.stage_uat_fixture(sentence); " +
                "a skipped row is not a pass",
            File(fixturePath).isFile,
        )
        // The saved USER term, read alone, before anything is merged with built-ins: a built-in of the same
        // spelling must never stand in for the founder's own data. The exact `Prerequisite:` prefix is what
        // the harness door reports as NOT RUN (#215).
        val userTerms = runBlocking { CustomTermRepository(context).list() }.map(CustomTermRecord::term)
        assertTrue(
            "Prerequisite: the saved custom name 'Saurabh' is not in this device's dictionary, so the row cannot judge " +
                "saved spellings here (it is the founder's phone's data)",
            userTerms.any { it.spelling == "Saurabh" },
        )
        val (asr, asrConnection) = bind<IAsrService>(AsrService::class.java) { IAsrService.Stub.asInterface(it) }
        val (polish, polishConnection) = bind<IPolishService>(PolishService::class.java) { IPolishService.Stub.asInterface(it) }
        try {
            polish.warmUpWithPolicy(PolishPolicy.LocalS1(S1ControlSettings.DEFAULT))
            // A binder property with no completion callback: the one poll in this file, bounded.
            val deadline = System.currentTimeMillis() + 30_000
            while (!polish.isLocalModelReady && System.currentTimeMillis() < deadline) Thread.sleep(250)
            assertTrue("S1-mini did not become ready: ${polish.localModelStatus()}", polish.isLocalModelReady)

            val terms = BuiltinVocabulary.withUserTerms(userTerms)
            val matcher = StructuredTermRestorer.compile(terms)

            val asrFinished = CountDownLatch(1)
            var rawText = ""
            var asrError = ""
            asr.transcribeFile(fixturePath, object : IAsrCallback.Stub() {
                override fun onResult(text: String?) {
                    rawText = text.orEmpty()
                    asrFinished.countDown()
                }

                override fun onError(message: String?) {
                    asrError = message.orEmpty()
                    asrFinished.countDown()
                }

                // Never answered on the legacy request (#176): the legacy transaction keeps onError.
                override fun onFailure(reason: Int, detail: String?) {
                    asrError = "typed failure $reason on a legacy request"
                    asrFinished.countDown()
                }
            })
            assertTrue("ASR callback timed out", asrFinished.await(30, TimeUnit.SECONDS))
            assertTrue("ASR returned no text: $asrError", rawText.isNotBlank())

            val polishFinished = CountDownLatch(1)
            var polishedText = ""
            var engine = ""
            var latencyMs = -1L
            polish.polishRequest(1L, matcher.restore(rawText), true, true, false, PolishPolicy.LocalS1(S1ControlSettings.DEFAULT), object : IPolishCallback.Stub() {
                override fun onOutcome(outcome: PolishOutcome?) {
                    polishedText = matcher.restore(outcome?.text.orEmpty())
                    engine = outcome?.engine.orEmpty()
                    latencyMs = outcome?.latencyMs ?: -1L
                    polishFinished.countDown()
                }

                override fun onResult(text: String?, usedEngine: String?, measuredLatencyMs: Long) = Unit

                override fun onError(message: String?) = Unit
            })
            assertTrue("Polish callback timed out", polishFinished.await(30, TimeUnit.SECONDS))
            assertTrue("Unexpected engine: $engine", engine.startsWith("S1-mini by Superwhisper"))
            assertTrue("Saved product spelling was not applied: $polishedText", polishedText.contains("EnviousWispr"))
            assertTrue("Saved name spelling was not applied: $polishedText", polishedText.contains("Saurabh"))
            DebugLogger.log(
                "VoicePipelineDeviceTest",
                "rawChars=${rawText.length} engine=$engine latencyMs=$latencyMs polishedChars=${polishedText.length}"
            )
        } finally {
            runCatching { context.unbindService(asrConnection) }
            runCatching { context.unbindService(polishConnection) }
        }
    }

    // ---- the heart, driven like the side button ----------------------------------------------------------

    /**
     * REVERT: write the text twice, or disable the editor write (the row fails even though the owner's
     * log lines remain), or judge success from the caret.
     */
    @Test
    fun aSideButtonTakeLandsInTheFocusedEditorExactlyOnce() {
        val expected = expectedFinal()
        val run = SideButtonRun()
        startRig(twoFields = false)
        run.recordOneTake(stage = {})
        val row = run.awaitFinalRow()
        val text = receipt(PasteTargetActivity.RECEIPT_NAME)
        assertEquals("the editor's whole text is the literal expectation", expected, text)
        assertEquals("the words appear exactly once", 1, occurrences(text, expected.trim()))
        assertEquals("the owner recorded the commit route", InsertionResults.COMMITTED, row.insertionResult)
        DebugLogger.log("VoicePipelineDeviceTest", "sideButtonTake chars=${text.length} result=${row.insertionResult}")
    }

    /**
     * Stop-on-silence, end to end (#239, REF-06): the setting on, the audio in, NO stop sent. The take must end
     * by itself, and the capture process's own ending line must name silence; the words must land in the
     * focused editor exactly once by the commit route. A take silence never ends fails "silence did not end
     * this take" and is ended by the row's cleanup. Replaces `SilenceStopEndToEndDeviceTest`, which slept and
     * asserted nothing.
     * REVERT: start capture with auto-stop off (`CaptureSessionController`, `preferences.autoStopOnSilence`
     * to `false`); the row fails by that name. Or send the manual stop: the cause assertion fails.
     */
    @Test
    fun aSilenceStoppedTakeLandsInTheFocusedEditorExactlyOnce() {
        val expected = expectedFinal()
        val preferences = AppPreferences(context)
        val saved = runBlocking { preferences.authoritativeState.first().autoStopOnSilenceEnabled }
        try {
            // The owner reads its settings when its Service is created, and a Service is built per take; so the
            // write below reaches the take only if no session Service is alive to have read them already.
            awaitNoSessionService()
            runBlocking { preferences.setAutoStopOnSilenceEnabled(true) }
            val run = SideButtonRun()
            startRig(twoFields = false)
            run.recordOneTake(stage = {}, stopByUser = false)
            run.ownTheVerdict {
                val row = run.awaitFinalRow("silence did not end this take")
                assertTrue("the take ended, but the capture process never logged a silence ending", run.stoppedBySilence())
                val text = receipt(PasteTargetActivity.RECEIPT_NAME)
                assertEquals("the editor's whole text is the literal expectation", expected, text)
                assertEquals("the words appear exactly once", 1, occurrences(text, expected.trim()))
                assertEquals("the owner recorded the commit route", InsertionResults.COMMITTED, row.insertionResult)
                DebugLogger.log("VoicePipelineDeviceTest", "silenceStoppedTake chars=${text.length} result=${row.insertionResult}")
            }
        } finally {
            runBlocking { preferences.setAutoStopOnSilenceEnabled(saved) }
        }
    }

    /**
     * No session Service is alive: the previous take's owner publishes IDLE before it stops its Service, so
     * IDLE alone is not enough. A bounded wait on harness scaffolding (the platform's own service list), named
     * as staging when it fails.
     */
    private fun awaitNoSessionService() {
        val manager = context.getSystemService(ActivityManager::class.java)
        val name = DictationSessionService::class.java.name
        val deadline = System.currentTimeMillis() + 15_000
        while (System.currentTimeMillis() < deadline) {
            @Suppress("DEPRECATION") // Still answers for the caller's own services.
            if (manager.getRunningServices(Int.MAX_VALUE).none { it.service.className == name }) return
            Thread.sleep(100)
        }
        throw AssertionError("a session Service from an earlier take is still running after 15 s (staging)")
    }

    /**
     * REF-01's two-editor case, asserting the product's FAIL-SAFE (`architecture-rules.md` RULE:
     * insertion-fails-safe-never-silently): the take is pinned to editor A at its start; when B holds
     * focus at insertion time the words go into NEITHER editor and stay on the clipboard, the paste
     * service having waited for the original editor to return and given up (the live run 2026-09-22 read
     * `outcome=NEVER_RETURNED attempts=20`, row `insertion_interrupted`/`copy_only`). A first draft of
     * this row expected the words in A; the rig said otherwise and the product's rule is the authority.
     * REVERT: insert into whatever is focused at insertion time (B gains the text), or write into an
     * unfocused A.
     */
    @Test
    fun aTakeStartedInAAndFocusMovedToBInsertsNowhereAndKeepsTheWordsOnTheClipboard() {
        val run = SideButtonRun()
        startRig(twoFields = true)
        run.recordOneTake(stage = { moveFocusToB() })
        val row = run.awaitFinalRow()
        val a = receipt(PasteTargetActivity.RECEIPT_NAME)
        val b = receipt(PasteTargetActivity.RECEIPT_B_NAME)
        assertEquals("editor A, focused at the start but not at insertion, received nothing", "", a)
        assertEquals("editor B, focused at insertion time, received nothing", "", b)
        assertEquals("the owner recorded the copy-only fallback", InsertionResults.COPY_ONLY, row.insertionResult)
        assertEquals("the row is closed as insertion interrupted", TranscriptEntity.STATUS_INSERTION_INTERRUPTED, row.status)
    }

    /**
     * PRODUCT OUTCOME. When this fails, every dictation from the Quick Settings tile, the app's
     * microphone button and the side button pressed outside a text field ends in a failure haptic, a
     * long toast and a shade notification saying auto-paste did not reach the field. Those entry
     * points cannot pin a target, so that is ordinary use of the product being reported as broken.
     *
     * Stages the alive-but-nothing-pinned case: only our own launcher is on screen, and the paste
     * service excludes our own package except the onboarding practice box, which is not admitted
     * here (`OwnFieldAdmission`), so nothing is pinned and the handoff is NO_PINNED_TARGET while the
     * service is perfectly alive. There was no field, so the clipboard is the designed destination and
     * the dictation SUCCEEDED.
     *
     * HONEST LIMIT: this proves the SILENCE on the no-field path, not the announcement on the
     * SERVICE_NOT_RUNNING path. That cause cannot be staged in-process without killing the
     * instrumentation or opening the private liveness field, which `validation-discipline.md`
     * RULE: a-test-seam-on-a-GUARD-is-a-bypass forbids. The announcement is the adb recipe in
     * `.claude/knowledge/device-testing.md`, whose step 6 dictates with the service unbound.
     *
     * The precondition is CONFIRMED after the run rather than assumed, per `validation-discipline.md`
     * RULE: verify-the-feature-not-the-crash. `pinTarget` falls back to scanning every window, so a
     * third-party editable field still focused behind our 1x1 launcher pins successfully and the
     * dictation reaches the editor. Reporting that as a red row would accuse working code, so it
     * reports SKIPPED with the observed handoff named. [observedHandoff] is a log read and stays a
     * PRECONDITION read (it classifies the staging); it never judges the outcome.
     */
    @Test
    fun aDictationWithNoFieldToInsertIntoIsNotReportedToTheUserAsAFailure() {
        assumeTrue(
            "Notifications are not permitted, so an announcement could not be posted either way " +
                "and this row could not tell silence from a blocked notification",
            NotificationManagerCompat.from(context).areNotificationsEnabled(),
        )
        assumeTrue(
            "The accessibility service is not enabled",
            shell("settings get secure enabled_accessibility_services")
                .contains("com.envi.wispr/com.envi.wispr.paste.PasteAccessibilityService"),
        )
        val notificationManager = context.getSystemService(NotificationManager::class.java)
        // The session's own notification is dismissed when the session ends, so an empty tray at
        // the end of this run is the state to assert against.
        notificationManager.cancel(DictationNotificationController.NOTIFICATION_ID)
        // The ring buffer wraps, so observedHandoff() must read THIS run
        // (`android-tooling.md` RULE: clear-logcat-before-you-measure).
        shell("logcat -c")

        val run = SideButtonRun()
        run.recordOneTake(stage = {})
        assertTrue("The session never returned to IDLE; phase was ${run.phaseNow()}", run.awaitIdle())

        // The session owner names its own handoff, so the staged precondition is READ rather than
        // assumed. Anything but NO_PINNED_TARGET means the phone was not in the state this row is
        // about.
        val handoff = observedHandoff()
        assumeTrue(
            "Nothing could be staged: the dictation reported handoff=$handoff, so an editable " +
                "field was focused behind the launcher and the words went where they should",
            handoff == "NO_PINNED_TARGET",
        )

        // NOT a check for one id. The durable fallback notification was DELETED in the messaging
        // rework, so asserting its absence would be a green row about nothing
        // (`testing-philosophy.md` RULE: the-rig-decides-where-a-test-lives-not-the-subject). What
        // is asserted instead is the property that outlives it: after an ordinary dictation with no
        // field, this app has left NOTHING in the user's shade. That goes red if any durable
        // announcement comes back, whatever id it chooses.
        // Two entries are NOT delivery announcements and are set aside BY EXACT NAME: the polish notice
        // (`POLISH_NOTIFICATION_ID`, "AI cleanup skipped" on a device whose local model is not ready,
        // which is about the polish limb and is exactly what the emulator shows), and the SYSTEM's own
        // silent-section group summary (id 1, tag `g:Aggregate_SilentSection`, `FLAG_GROUP_SUMMARY`),
        // which the OS creates around any silent notification of ours. Any OTHER group summary of ours
        // stays in the verdict (code review round 1: a future fallback shaped as a summary must fail this).
        val ourNotifications = notificationManager.activeNotifications
            .filter { it.packageName == context.packageName }
            .filter { it.id != DictationNotificationController.POLISH_NOTIFICATION_ID }
            .filterNot {
                // The OS's tag reads `0|com.envi.wispr|g:Aggregate_SilentSection` (dumpsys, 2026-09-22).
                it.id == 1 && (it.tag ?: "").endsWith("g:Aggregate_SilentSection") &&
                    (it.notification.flags and android.app.Notification.FLAG_GROUP_SUMMARY) != 0
            }
        val titles = ourNotifications.joinToString {
            it.notification.extras?.getCharSequence(android.app.Notification.EXTRA_TITLE)
                ?.toString()
                .orEmpty() + " (id=${it.id})"
        }
        assertEquals(
            "A dictation with no field to insert into left something in the shade: $titles. There " +
                "was no field, the clipboard is the designed destination, and this is what every " +
                "tile dictation and every in-app microphone-button dictation does.",
            emptyList<String>(),
            ourNotifications.map { "id=${'$'}{it.id}" },
        )
        DebugLogger.log("VoiceFallbackDeviceTest", "handoff=$handoff shadeAfterDictation=empty")
    }

    // ---- one side-button take, every wait on a signal -----------------------------------------------------

    /**
     * Owns one take: start the recorder like the side button, await the owner's LISTENING, run the row's
     * staging, get the audio in (driver or speaker), stop, and hand back the History row the owner
     * finalised. `startedAtMs` bounds the row wait so an earlier take's row cannot answer (the Stale edge).
     */
    private inner class SideButtonRun {
        val startedAtMs = System.currentTimeMillis()
        private val listening = CountDownLatch(1)
        private val idle = CountDownLatch(1)
        private val stopRequested = AtomicBoolean(false)
        /** A take this run started and has not handed to a stop or a verdict yet; ended by [endTheTake] on failure. */
        private var takeStarted = false
        private val surfaceState = context.getSharedPreferences(SURFACE_STATE_PREFERENCES, Context.MODE_PRIVATE)
        private val phaseListener = SharedPreferences.OnSharedPreferenceChangeListener { store, key ->
            if (key != SURFACE_STATE_PHASE) return@OnSharedPreferenceChangeListener
            when (store.getString(key, null)) {
                "LISTENING" -> listening.countDown()
                "IDLE" -> if (stopRequested.get()) idle.countDown()
            }
        }

        fun phaseNow(): String? = surfaceState.getString(SURFACE_STATE_PHASE, null)

        /**
         * [stopByUser] false (#239): no stop is sent; the take must end by itself, and the take stays this run's
         * to end through the verdict ([ownTheVerdict]).
         */
        fun recordOneTake(stage: () -> Unit, stopByUser: Boolean = true) {
            surfaceState.registerOnSharedPreferenceChangeListener(phaseListener)
            val audio = audioForThisRun()
            var handedOff = false
            try {
                audio.prepare()
                // Instrumentation restarts the app's process, which kills the accessibility service; the
                // system (or the driver) rebinds it, and the take must not start before it is BOUND, read
                // from the service's own liveness fact (`PasteAccessibilityService.isBound`, subject-fired).
                val bound = runBlocking { withTimeoutOrNull(30_000) { PasteAccessibilityService.isBound.first { it } } }
                assertTrue(
                    "the accessibility service is not bound, so no insertion can be judged (staging: " +
                        "enable_auto_paste() in the harness rebinds it)",
                    bound == true,
                )
                takeStarted = true
                context.startActivity(
                    Intent(context, com.envi.wispr.ui.VoiceInputActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
                assertTrue("The recorder never reported LISTENING; phase was ${phaseNow()}", listening.await(20, TimeUnit.SECONDS))
                stage()
                if (stopByUser) {
                    audio.deliver()
                    stopRequested.set(true)
                    context.startActivity(
                        Intent(context, com.envi.wispr.ui.VoiceInputActivity::class.java)
                            .putExtra(com.envi.wispr.ui.VoiceInputActivity.EXTRA_STOP, true)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                    )
                    takeStarted = false
                } else {
                    // IDLE must count BEFORE the audio ends: the take may end itself as soon as it goes quiet.
                    stopRequested.set(true)
                    audio.deliver()
                }
                handedOff = true
            } catch (failure: Throwable) {
                // A TAKE THIS TEST STARTED IS THIS TEST'S TO END. A failure between the start and the stop
                // (LISTENING never came, the staging refused, the audio never arrived) would otherwise leave
                // the microphone open on the device with nothing recording that it was: this take did not
                // go through the harness's journal (code review round 1). Cancel, then require BOTH the
                // owner's IDLE and the capture process's own close line, because the owner publishes IDLE
                // before it asks the capture process to stop (code review round 2): IDLE alone does not
                // prove the microphone closed. A cleanup that could not prove it is attached to the
                // original failure, never swallowed.
                endTheTake(failure)
                throw failure
            } finally {
                audio.close()
                if (!handedOff) surfaceState.unregisterOnSharedPreferenceChangeListener(phaseListener)
                // On success the listener stays registered until awaitIdle/awaitFinalRow release it.
            }
        }

        /**
         * Runs a no-stop take's verdict; a failure there (the take never ended, or ended wrong) ends the take
         * first, so the microphone is never left open behind a red row (#239).
         */
        fun ownTheVerdict(verdict: () -> Unit) {
            try {
                verdict()
                takeStarted = false
            } catch (failure: Throwable) {
                endTheTake(failure)
                throw failure
            } finally {
                surfaceState.unregisterOnSharedPreferenceChangeListener(phaseListener)
            }
        }

        /**
         * A TAKE THIS TEST STARTED IS THIS TEST'S TO END. A failure while it may still be live (LISTENING never
         * came, the staging refused, the audio never arrived, silence never ended it) would otherwise leave the
         * microphone open on the device with nothing recording that it was: this take did not go through the
         * harness's journal (code review round 1). Cancel, then require BOTH the owner's IDLE and the capture
         * process's own close line, because the owner publishes IDLE before it asks the capture process to stop
         * (code review round 2): IDLE alone does not prove the microphone closed. A cleanup that could not prove
         * it is attached to the original failure, never swallowed. The close line counts from this take's start:
         * a take that ended itself before the failure closed the microphone then.
         */
        private fun endTheTake(failure: Throwable) {
            if (!takeStarted) return
            takeStarted = false
            stopRequested.set(true)
            context.startActivity(
                Intent(context, com.envi.wispr.ui.VoiceInputActivity::class.java)
                    .putExtra(com.envi.wispr.ui.VoiceInputActivity.EXTRA_CANCEL, true)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            )
            val idleSeen = idle.await(30, TimeUnit.SECONDS)
            val closed = captureClosedAfter(startedAtMs, 15_000)
            if (!idleSeen || !closed) {
                failure.addSuppressed(
                    AssertionError(
                        "cleanup after the failure could not prove the take ended: idle=$idleSeen " +
                            "captureClosed=$closed; the microphone may still be open on the device",
                    ),
                )
            }
        }

        /**
         * The capture process's own ending line for this take names silence (`Stopped by silence.`, logged
         * before the ending is published). Read once, after the owner's final row, as the cause; never a
         * completion signal.
         */
        fun stoppedBySilence(): Boolean {
            val stamp = java.text.SimpleDateFormat("MM-dd HH:mm:ss.SSS", java.util.Locale.US).format(java.util.Date(startedAtMs))
            return shell("logcat -d -v time -t 2000 AudioCapture:I *:S").lineSequence()
                .any { it.length > 18 && it.contains("Stopped by silence.") && it.substring(0, 18) >= stamp }
        }

        /**
         * Whether the capture process wrote its own close line (`recording_stop`, logged when the
         * microphone closes) since [sinceMs]. A bounded read of the CAPTURE process's log on the failure
         * path only, never part of a verdict: the capture process is `:audio`, another process, and its
         * close is not observable from here through any binder this test holds.
         */
        private fun captureClosedAfter(sinceMs: Long, boundMs: Long): Boolean {
            // `executeShellCommand` splits on spaces with no shell, so a `-T "MM-dd HH:mm:ss.SSS"` bound cannot be
            // passed; the last lines are read and the `-v time` prefix compared to the stamp instead.
            val stamp = java.text.SimpleDateFormat("MM-dd HH:mm:ss.SSS", java.util.Locale.US).format(java.util.Date(sinceMs))
            val deadline = System.currentTimeMillis() + boundMs
            while (System.currentTimeMillis() < deadline) {
                val closed = shell("logcat -d -v time -t 300 AudioCapture:I *:S").lineSequence()
                    .any { it.length > 18 && it.contains("recording_stop") && it.substring(0, 18) >= stamp }
                if (closed) return true
                Thread.sleep(250)
            }
            return false
        }

        fun awaitIdle(): Boolean = try {
            idle.await(90, TimeUnit.SECONDS)
        } finally {
            surfaceState.unregisterOnSharedPreferenceChangeListener(phaseListener)
        }

        /** The row the owner created for THIS take and finalised; the failure names [whenMissing] and the rows seen. */
        fun awaitFinalRow(whenMissing: String = "No History row for this take reached a final status within 90 s"): TranscriptEntity {
            val dao = EnviousWisprDatabase.get(context).transcriptDao()
            var seen: List<TranscriptEntity> = emptyList()
            val row = try {
                runBlocking {
                    withTimeoutOrNull(90_000) {
                        dao.observeAll().first { rows ->
                            seen = rows.filter { it.createdAtMs >= startedAtMs }
                            seen.any { it.status in FINAL_STATUSES }
                        }
                    }
                }
            } finally {
                // A no-stop take keeps listening until its verdict: a failed verdict still needs IDLE to prove the end.
                if (!takeStarted) surfaceState.unregisterOnSharedPreferenceChangeListener(phaseListener)
            }
            assertTrue(
                "$whenMissing; rows since the start: " +
                    seen.joinToString { "id=${it.id} status=${it.status} insertion=${it.insertionResult}" },
                row != null,
            )
            return seen.first { it.status in FINAL_STATUSES }
        }
    }

    /** Where the audio comes from: the driver outside the process, or the phone's speaker. */
    private abstract inner class AudioSource {
        abstract fun prepare()
        abstract fun deliver()
        open fun close() = Unit
    }

    private fun audioForThisRun(): AudioSource = when (val mode = arguments.getString(ARG_AUDIO, AUDIO_EXTERNAL)) {
        AUDIO_EXTERNAL -> ExternalAudio()
        AUDIO_SPEAKER -> SpeakerAudio()
        else -> throw AssertionError("-e audio must be '$AUDIO_EXTERNAL' or '$AUDIO_SPEAKER', not '$mode'")
    }

    /**
     * The driver feeds the audio. The done-receiver is registered BEFORE READY is published, so the
     * driver's broadcast cannot arrive into nothing; the token keeps a stale broadcast from an earlier
     * run out.
     */
    private inner class ExternalAudio : AudioSource() {
        private val token = arguments.getString(ARG_DRIVER_TOKEN).orEmpty()
        private val done = CountDownLatch(1)
        private val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                if (intent.getStringExtra(ARG_DRIVER_TOKEN) == token) done.countDown()
            }
        }

        override fun prepare() {
            assertTrue("-e driver_token is required with -e audio external", token.matches(Regex("[0-9a-f]{8,64}")))
            if (Build.VERSION.SDK_INT >= 33) {
                context.registerReceiver(receiver, IntentFilter(ACTION_EXTERNAL_AUDIO_DONE), Context.RECEIVER_EXPORTED)
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                context.registerReceiver(receiver, IntentFilter(ACTION_EXTERNAL_AUDIO_DONE))
            }
        }

        override fun deliver() {
            InstrumentationRegistry.getInstrumentation().sendStatus(
                DRIVER_READY_STATUS,
                Bundle().apply { putString(DRIVER_PHASE_KEY, "READY"); putString(ARG_DRIVER_TOKEN, token) },
            )
            assertTrue("the driver never reported the audio done (staging, not the product)", done.await(60, TimeUnit.SECONDS))
        }

        override fun close() {
            runCatching { context.unregisterReceiver(receiver) }
        }
    }

    /** The phone's own speaker plays the cached fixture; completion is the track's marker, not the clock. */
    private inner class SpeakerAudio : AudioSource() {
        override fun prepare() {
            assertTrue(
                "The fixture is missing at $fixturePath: stage it with wispr_eyes.stage_uat_fixture(sentence); " +
                    "a skipped row is not a pass",
                File(fixturePath).isFile,
            )
            assertTrue("-e expected_final is required with -e audio speaker", arguments.containsKey(ARG_EXPECTED_FINAL))
        }

        override fun deliver() {
            val pcm = File(fixturePath).readBytes()
            val audioManager = context.getSystemService(AudioManager::class.java)
            val originalVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
            val testVolume = minOf(5, audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC))
            val audioTrack = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(16_000)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(pcm.size)
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build()
            val played = CountDownLatch(1)
            try {
                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, testVolume, 0)
                assertEquals("Phone playback was not queued in full", pcm.size, audioTrack.write(pcm, 0, pcm.size))
                audioTrack.setPlaybackPositionUpdateListener(object : AudioTrack.OnPlaybackPositionUpdateListener {
                    override fun onMarkerReached(track: AudioTrack) = played.countDown()
                    override fun onPeriodicNotification(track: AudioTrack) = Unit
                })
                audioTrack.notificationMarkerPosition = pcm.size / 2
                audioTrack.play()
                assertTrue(
                    "the speaker never reached the clip's last frame (staging, not the product)",
                    played.await(pcm.size / 32L + 5_000, TimeUnit.MILLISECONDS),
                )
            } finally {
                audioTrack.stop()
                audioTrack.release()
                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, originalVolume, 0)
            }
        }
    }

    // ---- the rig ------------------------------------------------------------------------------------------

    private fun startRig(twoFields: Boolean) {
        // Stale receipts from an earlier run are removed BEFORE the launch, so nothing already on disk can
        // answer for this run (code review round 1).
        for (name in listOf(PasteTargetActivity.RECEIPT_NAME, PasteTargetActivity.RECEIPT_B_NAME, PasteTargetActivity.READY_NAME)) {
            shell("run-as $TEST_PACKAGE rm -f files/$name")
        }
        val rigToken = java.util.UUID.randomUUID().toString().replace("-", "")
        context.startActivity(
            Intent()
                .setClassName(TEST_PACKAGE, "com.envi.wispr.PasteTargetActivity")
                .putExtra(PasteTargetActivity.EXTRA_TWO_FIELDS, twoFields)
                .putExtra(PasteTargetActivity.EXTRA_RIG_TOKEN, rigToken)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        )
        // THE RIG'S readiness, not the subject's: the rig lives in another UID and can signal this process
        // only through a file, read through the shell. The file it writes is this run's TOKEN, written only
        // once editor A holds focus, so existence alone answers nothing. A bounded existence check on
        // harness scaffolding, named in the plan's wait table as such.
        val deadline = System.currentTimeMillis() + 15_000
        while (System.currentTimeMillis() < deadline) {
            if (receiptExists(PasteTargetActivity.READY_NAME) &&
                shell("run-as $TEST_PACKAGE cat files/${PasteTargetActivity.READY_NAME}").trim() == rigToken
            ) {
                return
            }
            Thread.sleep(100)
        }
        throw AssertionError("the paste-target rig did not come up with editor A focused (no ready receipt carrying this run's token after 15 s)")
    }

    /** The rig answers 1 only once editor B holds focus; anything else is a staging failure. */
    private fun moveFocusToB() {
        val answered = CountDownLatch(1)
        var code = -1
        context.sendOrderedBroadcast(
            Intent(PasteTargetActivity.ACTION_FOCUS_B).setPackage(TEST_PACKAGE),
            null,
            object : BroadcastReceiver() {
                override fun onReceive(c: Context, intent: Intent) {
                    code = resultCode
                    answered.countDown()
                }
            },
            null,
            0,
            null,
            null,
        )
        assertTrue("the rig never answered the focus request (staging)", answered.await(10, TimeUnit.SECONDS))
        assertEquals("editor B did not take focus (staging)", 1, code)
    }

    // `executeShellCommand` runs the command directly, not through a shell, so no quoting and no `&&`:
    // `ls <path>` prints the path when it exists and an error otherwise.
    private fun receiptExists(name: String): Boolean =
        shell("run-as $TEST_PACKAGE ls files/$name").trim() == "files/$name"

    private fun receipt(name: String): String {
        assertTrue("the receipt $name does not exist; the rig never wrote it", receiptExists(name))
        return shell("run-as $TEST_PACKAGE cat files/$name")
    }

    private fun expectedFinal(): String = arguments.getString(ARG_EXPECTED_FINAL) ?: EXPECTED_TEXT

    private fun occurrences(text: String, needle: String): Int =
        if (needle.isEmpty()) 0 else Regex(Regex.escape(needle)).findAll(text).count()

    /** The handoff the session owner logged for the run that just finished, or `"none"`. */
    private fun observedHandoff(): String {
        val logs = shell("logcat -d -v brief -s DictationSession:I '*:S'")
        return Regex("\\(handoff=([A-Z_]+)\\)").findAll(logs).lastOrNull()?.groupValues?.get(1)
            ?: "none"
    }

    private fun <T> bind(service: Class<*>, wrap: (IBinder?) -> T): Pair<T, ServiceConnection> {
        val connected = CountDownLatch(1)
        var bound: T? = null
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                bound = wrap(binder)
                connected.countDown()
            }

            override fun onServiceDisconnected(name: ComponentName?) = Unit
        }
        context.bindService(Intent(context, service), connection, Context.BIND_AUTO_CREATE)
        assertTrue("${service.simpleName} did not connect", connected.await(15, TimeUnit.SECONDS))
        return bound!! to connection
    }

    /**
     * Shell reads through UiAutomation WITHOUT suppressing accessibility services. A plain `uiAutomation`
     * connection makes the system unbind every other accessibility service for as long as it is held
     * (the trap that removed Appium in #177), so a test using it kills the paste service it means to
     * observe and every take falls back to the clipboard (seen live 2026-09-22: handoff=SERVICE_NOT_RUNNING).
     */
    private fun shell(command: String): String {
        val descriptor = InstrumentationRegistry.getInstrumentation()
            .getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
            .executeShellCommand(command)
        return FileInputStream(descriptor.fileDescriptor).bufferedReader().use { it.readText() }
            .also { descriptor.close() }
    }
}
