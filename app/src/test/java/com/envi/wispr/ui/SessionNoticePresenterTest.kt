package com.envi.wispr.ui

import com.envi.wispr.audio.AudioCaptureService
import com.envi.wispr.audio.InputRouteKind
import com.envi.wispr.audio.RecordingLimits
import com.envi.wispr.polish.PolishFailure
import com.envi.wispr.polish.PolishFailureNotice
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * `SessionNoticePresenter` (#256): the one place that says a recorder notice where the user can see it. Driven
 * with the rig's fakes and its main thread; every "nothing happened" check follows a drain of main, and every
 * expected event is a bounded signal wait, never a clock.
 */
class SessionNoticePresenterTest {
    private val rig = DictationSessionRig()
    private val presenter = SessionNoticePresenter(rig.surface, rig.insertion, rig.host, rig.scope, rig.mainDispatcher, DictationSessionRig.FakeLog(), BluetoothTipGate())

    @After fun tearDown() = rig.close()

    private fun notices() = rig.surface.events.filter { it.startsWith("notice:") }
    private fun toasts() = rig.host.events.filter { it.startsWith("toast-app:") }

    /** Row 1: while recording with the recorder up, the pill says it and no toast does. MUTATION: always toast. */
    @Test fun aWhileRecordingNoticeWithTheRecorderUpIsThePill() {
        rig.insertion.bound = true
        presenter.say(SessionNotice.DURATION_WARNING)
        rig.onMain { }
        assertEquals(listOf("notice:${SessionNotice.DURATION_WARNING.line}"), notices())
        assertTrue("no toast: ${rig.host.events}", toasts().isEmpty())
    }

    /** Row 2: while recording with no recorder, one toast, on main; no pill. MUTATION: pill regardless of binding. */
    @Test fun aWhileRecordingNoticeWithNoRecorderIsAToastOnMain() {
        rig.insertion.bound = false
        presenter.say(SessionNotice.SILENCE_UNAVAILABLE)
        rig.host.awaitApplicationToast()
        rig.onMain { }
        assertEquals(listOf("toast-app:${SessionNotice.SILENCE_UNAVAILABLE.line}@dispatch"), toasts())
        assertTrue("no pill: ${rig.surface.events}", notices().isEmpty())
    }

    /** Row 3: after the recorder, a toast even when the recorder service is bound. MUTATION: ignore the timing. */
    @Test fun anAfterRecorderNoticeIsAToastEvenWhenBound() {
        rig.insertion.bound = true
        presenter.say(SessionNotice.DURATION_REACHED)
        rig.host.awaitApplicationToast()
        rig.onMain { }
        assertEquals(listOf("toast-app:${SessionNotice.DURATION_REACHED.line}@dispatch"), toasts())
        assertTrue("no pill: ${rig.surface.events}", notices().isEmpty())
    }

    /** Row 4: a toast that throws escapes neither `say` nor the scope. MUTATION: drop the `runCatching`. */
    @Test fun aThrowingToastEscapesNothing() {
        rig.insertion.bound = false
        val calls = AtomicInteger()
        val broken = object : SessionHost by rig.host {
            override fun toastFromApplication(line: String) {
                calls.incrementAndGet()
                throw IllegalStateException("toast broke")
            }
        }
        SessionNoticePresenter(rig.surface, rig.insertion, broken, rig.scope, rig.mainDispatcher, DictationSessionRig.FakeLog(), BluetoothTipGate())
            .say(SessionNotice.EARBUDS_SILENT)
        rig.onMain { }
        rig.onMain { }
        assertEquals("the toast was attempted", 1, calls.get())
        assertTrue("the scope's handler received nothing: ${rig.uncaught}", rig.uncaught.isEmpty())
    }

    /** Row 5: every notice's sentence and timing, literally. MUTATIONS: change one sentence; change one timing. */
    @Test fun everyNoticeSaysTodaysSentenceAtTodaysTime() {
        val expected = listOf(
            Triple(SessionNotice.DURATION_REACHED, "Reached the 10 minute limit. Working on what you said.", NoticeTiming.AFTER_RECORDER),
            Triple(SessionNotice.EARBUDS_SILENT, "Earbuds are not sending sound.", NoticeTiming.WHILE_RECORDING),
            Triple(SessionNotice.SILENCE_UNAVAILABLE, "Auto-stop on silence is unavailable right now", NoticeTiming.WHILE_RECORDING),
            Triple(SessionNotice.BLUETOOTH_TIP, "Recording through your earbuds", NoticeTiming.WHILE_RECORDING),
            Triple(SessionNotice.DURATION_WARNING, "Recording stops in under a minute (10 minute limit)", NoticeTiming.WHILE_RECORDING),
        )
        assertEquals(SessionNotice.entries.toList(), expected.map { it.first })
        expected.forEach { (notice, line, timing) ->
            assertEquals(notice.name, line, notice.line)
            assertEquals(notice.name, timing, notice.timing)
        }
    }

    /**
     * Row 7 (#293): a polish failure is the toast line then the notification, both from one post on main.
     * MUTATION m1: drop the notification.
     */
    @Test fun aPolishFailureIsTheToastThenTheNotificationOnMain() {
        val held = HeldPosts()
        SessionNoticePresenter(rig.surface, rig.insertion, held, rig.scope, rig.mainDispatcher, DictationSessionRig.FakeLog(), BluetoothTipGate())
            .sayPolishFailure(PolishFailureNotice.notice(PolishFailure.KEY_REJECTED, null))
        assertEquals("nothing is said before the one post runs", emptyList<String>(), held.said)
        assertEquals("one post", 1, held.posts.size)
        rig.onMain { held.posts.single().run() }
        assertEquals(listOf("toast:${PolishFailureNotice.LOCKED_SENTENCE}@main", "polish-notice@main"), held.said)
    }

    /** Row 8 (#293): a take's failure sentence is one toast from the service, on main. MUTATION m2: drop the toast. */
    @Test fun aFailureSentenceIsOneToast() {
        val held = HeldPosts()
        SessionNoticePresenter(rig.surface, rig.insertion, held, rig.scope, rig.mainDispatcher, DictationSessionRig.FakeLog(), BluetoothTipGate())
            .sayFailure("Microphone service stopped unexpectedly")
        assertEquals("nothing is said before the one post runs", emptyList<String>(), held.said)
        assertEquals("one post", 1, held.posts.size)
        rig.onMain { held.posts.single().run() }
        assertEquals(listOf("toast:Microphone service stopped unexpectedly@main"), held.said)
    }

    /** The rig's host with `postToMain` held rather than run, and each delivery stamped with whether it ran on main. */
    private inner class HeldPosts : SessionHost by rig.host {
        val posts = CopyOnWriteArrayList<Runnable>()
        val said = CopyOnWriteArrayList<String>()
        private fun where() = if (rig.host.onMainThread()) "main" else "off-main"
        override fun postToMain(runnable: Runnable) { posts += runnable }
        override fun toastFromService(line: String) { said += "toast:$line@${where()}" }
        override fun showPolishNotice(notice: PolishFailureNotice) { said += "polish-notice@${where()}" }
    }

    /**
     * Row 6: the owner picks which notice and never where. MUTATION: a direct `surface.showNotice(` back in the
     * coordinator.
     */
    @Test fun theOwnerPicksTheNoticeAndThePresenterPicksTheSurface() {
        val owner = SessionSources.coordinator
        assertFalse(owner.contains("showNotice("))
        assertFalse(owner.contains("toastFromApplication("))
        // Since #293 the polish failure and the take's failure sentence are the presenter's too. MUTATION m5.
        assertFalse(owner.contains("toastFromService("))
        assertFalse(owner.contains("showPolishNotice("))
        assertEquals(1, Regex("""\bnotices\.sayPolishFailure\(notice\)""").findAll(owner).count())
        assertEquals(1, Regex("""\bnotices\.sayFailure\(line\)""").findAll(owner).count())
        // Since #309 the owner says only the cap-reached line directly; the four once-per-take lines are said inside the
        // presenter's own methods. Together the two files say every notice.
        assertEquals(1, Regex("""\bnotices\.say\(SessionNotice\.""").findAll(owner).count())
        val presenterSource = java.io.File("src/main/java/com/envi/wispr/ui/SessionNotice.kt").readText()
        val said = Regex("""\bsay\(SessionNotice\.([A-Z_]+)\)""").findAll(owner + presenterSource).map { it.groupValues[1] }.toSet()
        assertEquals(SessionNotice.entries.map { it.name }.toSet(), said)
    }

    // ---- #309: the once-per-take lines, decided by the presenter on main

    private val bluetooth = InputRouteKind.BLUETOOTH.code

    private fun selecting(gate: BluetoothTipGate = BluetoothTipGate(), log: DictationSessionRig.FakeLog = DictationSessionRig.FakeLog()) =
        SessionNoticePresenter(rig.surface, rig.insertion, rig.host, rig.scope, rig.mainDispatcher, log, gate)

    /** Row 9: the forced line outranks the tip and leaves the gate unspent for a later take. MUTATION m1. */
    @Test fun aForcedTakeSaysNoTipAndLeavesTheGateForTheNextTake() {
        rig.insertion.bound = true
        val gate = BluetoothTipGate()
        val presenter = selecting(gate)
        rig.onMain {
            presenter.beginTake()
            presenter.sayEarbudsSilent()
            presenter.sayBluetoothTipIfDue(bluetooth, tipsEnabled = true)
        }
        assertEquals(listOf("notice:${SessionNotice.EARBUDS_SILENT.line}"), notices())
        assertTrue("the gate was not spent", gate.shouldShow(bluetooth, tipsEnabled = true))
    }

    /** Row 10: the auto-stop line is said once a take, only for the unavailable status, and outranks the tip. MUTATION m2. */
    @Test fun theSilenceLineIsOncePerTakeOnlyWhenUnavailableAndOutranksTheTip() {
        rig.insertion.bound = true
        val presenter = selecting()
        rig.onMain {
            presenter.beginTake()
            presenter.saySilenceUnavailableIfDue(AudioCaptureService.SILENCE_STATUS_READY)
            presenter.saySilenceUnavailableIfDue(AudioCaptureService.SILENCE_STATUS_LOST_AFTER_READY)
            presenter.saySilenceUnavailableIfDue(AudioCaptureService.SILENCE_STATUS_UNAVAILABLE)
            presenter.saySilenceUnavailableIfDue(AudioCaptureService.SILENCE_STATUS_UNAVAILABLE)
            presenter.sayBluetoothTipIfDue(bluetooth, tipsEnabled = true)
        }
        assertEquals(listOf("notice:${SessionNotice.SILENCE_UNAVAILABLE.line}"), notices())
        rig.onMain {
            presenter.beginTake()
            presenter.saySilenceUnavailableIfDue(AudioCaptureService.SILENCE_STATUS_UNAVAILABLE)
        }
        assertEquals("a new take says it again", 2, notices().size)
    }

    /** Row 11: the last-minute warning once a take, not before the moment. MUTATION m3. */
    @Test fun theDurationWarningIsSaidOnceAtTheMomentAndAgainNextTake() {
        rig.insertion.bound = true
        val log = DictationSessionRig.FakeLog()
        val presenter = selecting(log = log)
        rig.onMain {
            presenter.beginTake()
            presenter.sayDurationWarningIfDue(RecordingLimits.WARNING_AT_MS - 1)
            presenter.sayDurationWarningIfDue(RecordingLimits.WARNING_AT_MS)
            presenter.sayDurationWarningIfDue(RecordingLimits.WARNING_AT_MS + 1_000)
        }
        assertEquals(listOf("notice:${SessionNotice.DURATION_WARNING.line}"), notices())
        assertEquals(1, log.count("Duration warning shown at ${RecordingLimits.WARNING_AT_MS}ms"))
        rig.onMain {
            presenter.beginTake()
            presenter.sayDurationWarningIfDue(RecordingLimits.WARNING_AT_MS)
        }
        assertEquals(2, notices().size)
    }

    /** Row 12: the tip once per gate, only on Bluetooth with tips on. */
    @Test fun theTipIsSaidOnceOnBluetoothWithTipsOn() {
        rig.insertion.bound = true
        val presenter = selecting()
        rig.onMain {
            presenter.beginTake()
            presenter.sayBluetoothTipIfDue(bluetooth, tipsEnabled = false)
            presenter.sayBluetoothTipIfDue(InputRouteKind.PHONE.code, tipsEnabled = true)
            presenter.sayBluetoothTipIfDue(bluetooth, tipsEnabled = true)
            presenter.beginTake()
            presenter.sayBluetoothTipIfDue(bluetooth, tipsEnabled = true)
        }
        assertEquals(listOf("notice:${SessionNotice.BLUETOOTH_TIP.line}"), notices())
    }

    /** Row 13: the log line comes before the line is shown, so a failing display still leaves it. MUTATION m6. */
    @Test fun theTipIsLoggedEvenWhenTheRecorderThrows() {
        rig.insertion.bound = true
        val log = DictationSessionRig.FakeLog()
        val broken = object : RecorderSurface by rig.surface {
            override fun showNotice(text: String) = throw IllegalStateException("overlay gone")
        }
        val presenter = SessionNoticePresenter(broken, rig.insertion, rig.host, rig.scope, rig.mainDispatcher, log, BluetoothTipGate())
        rig.onMain {
            presenter.beginTake()
            runCatching { presenter.sayBluetoothTipIfDue(bluetooth, tipsEnabled = true) }
            runCatching { presenter.sayDurationWarningIfDue(RecordingLimits.WARNING_AT_MS) }
        }
        assertEquals(1, log.count("Bluetooth tip shown"))
        assertEquals(1, log.count("Duration warning shown"))
    }

    /** Row 14: the owner starts every take's latches before capture, and keeps the setting and state checks. MUTATION m4. */
    @Test fun theOwnerClearsTheLatchesBeforeCaptureAndKeepsItsOwnChecks() {
        val owner = SessionSources.coordinator
        val start = owner.substringAfter("private fun tryStartRecording(").substringBefore("\n    }\n")
        val begin = start.indexOf("notices.beginTake()")
        assertTrue(begin >= 0 && begin < start.indexOf("capture.start("))
        val silence = owner.substringAfter("private fun publishSilenceNoticeIfNeeded(").substringBefore("\n    }\n")
        assertTrue(silence.contains("if (!sessionPreferences.autoStopOnSilence) return"))
        assertTrue(silence.contains("if (state.get() != SessionState.RECORDING) return"))
        for (gone in listOf("silenceNoticeShown", "forcedNoticeShown", "durationWarningShown", "tipGate", "fun recordTakeEnding")) {
            assertFalse("the owner no longer holds $gone", owner.contains(gone))
        }
    }
}
