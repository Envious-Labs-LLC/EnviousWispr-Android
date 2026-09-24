package com.envi.wispr.ui

import com.envi.wispr.polish.PolishFailure
import com.envi.wispr.polish.PolishFailureNotice
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * `SessionNoticePresenter` (#256): the one place that says a recorder notice where the user can see it. Driven
 * with the rig's fakes and its main thread; every "nothing happened" check follows a drain of main, and every
 * expected event is a bounded signal wait, never a clock.
 */
class SessionNoticePresenterTest {
    private val rig = DictationSessionRig()
    private val presenter = SessionNoticePresenter(rig.surface, rig.insertion, rig.host, rig.scope, rig.mainDispatcher)

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
        SessionNoticePresenter(rig.surface, rig.insertion, broken, rig.scope, rig.mainDispatcher)
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
        val notice = PolishFailureNotice.notice(PolishFailure.KEY_REJECTED, null)
        presenter.sayPolishFailure(notice)
        rig.onMain { }
        assertEquals(
            listOf("toast:${PolishFailureNotice.LOCKED_SENTENCE}", "polish-notice"),
            rig.host.events.filter { it.startsWith("toast:") || it == "polish-notice" },
        )
    }

    /** Row 8 (#293): a take's failure sentence is one toast from the service, on main. MUTATION m2: drop the toast. */
    @Test fun aFailureSentenceIsOneToast() {
        presenter.sayFailure("Microphone service stopped unexpectedly")
        rig.onMain { }
        assertEquals(listOf("toast:Microphone service stopped unexpectedly"), rig.host.events.filter { it.startsWith("toast:") })
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
        assertEquals(5, Regex("""\bnotices\.say\(SessionNotice\.""").findAll(owner).count())
        assertEquals(SessionNotice.entries.map { it.name }.toSet(), Regex("""\bnotices\.say\(SessionNotice\.([A-Z_]+)\)""").findAll(owner).map { it.groupValues[1] }.toSet())
    }
}
