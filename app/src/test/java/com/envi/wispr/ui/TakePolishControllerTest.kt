package com.envi.wispr.ui

import com.envi.wispr.polish.PolishOutcome
import com.envi.wispr.polish.PolishPolicy
import com.envi.wispr.polish.PolishReason
import com.envi.wispr.polish.S1ControlSettings
import com.envi.wispr.telemetry.AppDefect
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The take's polish controller alone (#237): one answer wins, the fallback keeps the words, a cancel that
 * lands before the engine registered is sent again, and nothing is sent or handed back under the owner's
 * lock. Every wait is bounded and woken by the subject's own signal; a held request is released in
 * `finally`. The owner-level rows (`PolishFailsOpenTest`, `WarmUpOffMainTest`, the polish rows of
 * `DictationSessionCoordinatorTest`) prove the move changed nothing a person sees.
 */
class TakePolishControllerTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    /** The owner's main thread (#253): one named thread, so a row can say what ran on it. */
    private val mainExecutor = java.util.concurrent.Executors.newSingleThreadExecutor { Thread(it, "test-main") }
    /** Every task queued on main, counted and signalled, so an ordering row waits for a contender's queueing, never a clock. */
    private val mainQueued = java.util.concurrent.atomic.AtomicInteger()
    private val mainQueue = Object()
    private val countingMain = java.util.concurrent.Executor { task ->
        synchronized(mainQueue) { mainQueued.incrementAndGet(); mainQueue.notifyAll() }
        mainExecutor.execute(task)
    }
    private val mainDispatcher = countingMain.asCoroutineDispatcher()

    /** The running thread's name without the coroutine debug agent's ` @coroutine#N` suffix. */
    private fun threadName() = Thread.currentThread().name.substringBefore(" @")

    /** Waits, bounded, until [n] tasks have been queued on main since the test began. */
    private fun awaitMainQueued(n: Int) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        synchronized(mainQueue) {
            while (mainQueued.get() < n) {
                val left = deadline - System.nanoTime()
                check(left > 0) { "main never had $n tasks queued (had ${mainQueued.get()})" }
                TimeUnit.NANOSECONDS.timedWait(mainQueue, left)
            }
        }
    }

    /** Holds main until the returned latch is released; the hold itself is task 1 on main's count. */
    private fun holdMain(): CountDownLatch {
        val release = CountDownLatch(1)
        countingMain.execute { release.await(10, TimeUnit.SECONDS) }
        return release
    }
    private val publishable = AtomicBoolean(true)
    private val preparedOn = CopyOnWriteArrayList<String>()
    private val lock = Any()
    private val log = DictationSessionRig.FakeLog()
    private val timeout = DictationSessionRig.FakePolishTimeout()
    private val link = Link()
    private val processing = AtomicBoolean(true)
    private val defects = CopyOnWriteArrayList<Pair<AppDefect, Map<String, Any?>>>()
    private val prepared = CopyOnWriteArrayList<PreparedText>()
    private val preparedUnderLock = CopyOnWriteArrayList<Boolean>()
    private val handedBack = CountDownLatch(1)
    private val preferences = SessionPreferences(policy = PolishPolicy.LocalS1(S1ControlSettings.DEFAULT))

    private val controller = newController()

    /** The controller under test; #252's rows pass a restorer, a cleanup or a defect sink that throws. */
    private fun newController(
        restore: (String, com.envi.wispr.vocabulary.StructuredTermRestorer.Matcher) -> String = { text, matcher -> matcher.restore(text) },
        cleanup: (String, com.envi.wispr.cleanup.CleanupOptions, com.envi.wispr.cleanup.LanguageDetector) -> String = com.envi.wispr.polish.PolishFallback::deterministic,
        defectSink: (AppDefect, Map<String, Any?>) -> Unit = { defect, facts -> defects += defect to facts },
    ) = TakePolishController(
        lock = lock,
        ledger = PolishRequestLedger(),
        timeout = timeout,
        scope = scope,
        link = { link },
        languageDetector = { null },
        log = log,
        takeId = "take-1",
        defectSink = defectSink,
        preferences = { preferences },
        transcript = { "hello world" },
        isProcessing = { processing.get() },
        isLive = { true },
        onPrepared = { text ->
            preparedOn += threadName()
            preparedUnderLock += Thread.holdsLock(lock)
            prepared += text
            handedBack.countDown()
        },
        restore = restore,
        cleanup = cleanup,
        mainDispatcher = mainDispatcher,
        stillPublishable = { publishable.get() },
    )

    @After fun tearDown() {
        link.hold?.countDown()
        scope.cancel()
        mainExecutor.shutdownNow()
    }

    /** The polish process as the controller sees it; a request can be held inside the call. */
    private inner class Link : PolishLink {
        @Volatile var listener: PolishListener? = null
        @Volatile var requestId = 0L
        @Volatile var hold: CountDownLatch? = null
        /** The raw text the controller sent (#252). */
        @Volatile var lastRaw: String? = null
        val requested = CountDownLatch(1)
        val cancels = CopyOnWriteArrayList<Long>()
        val cancelUnderLock = CopyOnWriteArrayList<Boolean>()
        @Volatile var cancelled = CountDownLatch(1)
        @Volatile var throwOnCancel = false
        override fun warmUpWithPolicy(policy: PolishPolicy) = Unit
        override fun polishRequestForTake(requestId: Long, rawText: String, removeFillers: Boolean, spokenEmoji: Boolean, spokenPunctuation: Boolean, policy: PolishPolicy, takeId: String, listener: PolishListener) {
            this.requestId = requestId
            this.listener = listener
            lastRaw = rawText
            requested.countDown()
            hold?.await(10, TimeUnit.SECONDS)
        }
        override fun cancel(requestId: Long) {
            cancelUnderLock += Thread.holdsLock(lock)
            cancels += requestId
            cancelled.countDown()
            if (throwOnCancel) throw IllegalStateException("engine gone")
        }
        fun awaitRequest(): PolishListener {
            assertTrue("the controller never sent the request", requested.await(10, TimeUnit.SECONDS))
            return checkNotNull(listener)
        }
        fun outcome(text: String) = PolishOutcome(requestId = requestId, text = text, engine = "Fake engine", reason = PolishReason.POLISHED, statusCode = 0, latencyMs = 12L)
    }

    private fun awaitHandedBack(): PreparedText {
        assertTrue("nothing was handed back to the owner", handedBack.await(10, TimeUnit.SECONDS))
        return prepared.first()
    }

    /** Waits, bounded, for every coroutine the controller launched on its scope to finish. */
    private fun awaitControllerIdle() = runBlocking {
        withTimeout(10_000) { checkNotNull(scope.coroutineContext[Job]).children.forEach { it.join() } }
    }

    private fun protocolShapes() = defects.filter { it.first == AppDefect.PolishProtocolViolation }.map { it.second["shape"] }

    /** Row a: the watchdog wins and a late answer is ignored. */
    @Test fun theWatchdogWinsAndALateAnswerIsIgnored() {
        controller.prepare("hello world", preferences)
        val listener = link.awaitRequest()
        timeout.fire()
        val first = awaitHandedBack()
        assertEquals(PolishReason.WATCHDOG_TIMEOUT, (first as PreparedText.Fallback).reason)
        listener.onOutcome(link.outcome("Hello world."))
        assertEquals("one text for the take", 1, prepared.size)
        assertTrue(log.lines.any { it.contains("Ignoring polish outcome") })
    }

    /**
     * Row a2. The answer wins and the watchdog that fires after it adds nothing; the controller's own coroutines
     * finishing is the signal. MUTATION: drop the claim in the watchdog (it then hands back a second text).
     */
    @Test fun anAnswerThatWonIsNeverFollowedByTheWatchdog() {
        controller.prepare("hello world", preferences)
        link.awaitRequest().onOutcome(link.outcome("Hello world."))
        assertEquals("Hello world.", awaitHandedBack().text)
        timeout.fire()
        awaitControllerIdle()
        assertEquals("one text for the take", 1, prepared.size)
    }

    /**
     * Row b2. The owner cancelled the open request (a cancel while processing closes the ledger), then polish
     * died: the disconnect finds nothing to claim and hands back nothing. MUTATION: skip the ledger claim in
     * `disconnected` (it then hands back a fallback for a cancelled take).
     */
    @Test fun aDisconnectAfterTheOwnerCancelledHandsBackNothing() {
        controller.prepare("hello world", preferences)
        link.awaitRequest()
        controller.cancelOpen()
        controller.disconnected()
        assertTrue("nothing handed back for a cancelled take: $prepared", prepared.isEmpty())
        assertEquals("the loss is still reported once", listOf(AppDefect.PolishServiceDied), defects.map { it.first })
    }

    /** Row b: a disconnect with the request open falls back once, and the late answer is ignored. */
    @Test fun aDisconnectWithTheRequestOpenFallsBackOnceAndTheLateAnswerIsIgnored() {
        controller.prepare("hello world", preferences)
        val listener = link.awaitRequest()
        controller.disconnected()
        assertEquals(PolishReason.SERVICE_DIED, (awaitHandedBack() as PreparedText.Fallback).reason)
        listener.onOutcome(link.outcome("Hello world."))
        assertEquals(1, prepared.size)
        assertEquals(listOf(AppDefect.PolishServiceDied), defects.map { it.first })
    }

    /** Row c. MUTATION: pass a blank answer through as the polished text. */
    @Test fun aBlankAnswerGivesTheDeterministicTextAndOneViolation() {
        controller.prepare("hello world", preferences)
        link.awaitRequest().onOutcome(link.outcome(""))
        val text = awaitHandedBack() as PreparedText.Fallback
        assertEquals(PolishReason.CALL_FAILED, text.reason)
        assertTrue("the words are kept: '${text.text}'", text.text.contains("hello world"))
        assertEquals(listOf("blank"), protocolShapes())
    }

    /** Row d. MUTATION: drop the re-send after the request call returns. */
    @Test fun aCancelBeforeTheEngineRegisteredIsSentAgainOnTheSameLink() {
        val held = CountDownLatch(1)
        link.hold = held
        link.cancelled = CountDownLatch(2)
        try {
            controller.prepare("hello world", preferences)
            val id = link.awaitRequest().let { link.requestId }
            controller.cancelOpen()
            held.countDown()
            assertTrue("the cancel was sent again once the engine registered: ${link.cancels}", link.cancelled.await(10, TimeUnit.SECONDS))
            assertEquals(listOf(id, id), link.cancels.toList())
            assertTrue("nothing handed back for a cancelled request", prepared.isEmpty())
        } finally {
            held.countDown()
        }
    }

    /** Row d2: an answer that lands before the request call returns stands, and the no-op re-send still goes. */
    @Test fun anAnswerBeforeTheCallReturnsStandsAndTheReSendStillGoes() {
        val held = CountDownLatch(1)
        link.hold = held
        try {
            controller.prepare("hello world", preferences)
            val listener = link.awaitRequest()
            listener.onOutcome(link.outcome("Hello world."))
            assertEquals("Hello world.", awaitHandedBack().text)
            held.countDown()
            assertTrue("the re-send went", link.cancelled.await(10, TimeUnit.SECONDS))
            assertEquals(1, prepared.size)
        } finally {
            held.countDown()
        }
    }

    /** Row e. MUTATION: ignore the loss latch when speech answers (a request is sent that nobody answers). */
    @Test fun aLossBeforeSpeechFallsBackWithThatReasonAndSendsNothing() {
        controller.bindRefused()
        controller.prepare("hello world", preferences)
        assertEquals(PolishReason.SERVICE_UNAVAILABLE, (awaitHandedBack() as PreparedText.Fallback).reason)
        assertEquals("no request was sent", 1L, link.requested.count)
        assertEquals(listOf(AppDefect.PolishServiceUnavailable), defects.map { it.first })
    }

    /** Row f. MUTATION: name the null outcome as mismatched. */
    @Test fun aNullOutcomeFallsBackAndIsNamed() {
        controller.prepare("hello world", preferences)
        link.awaitRequest().onOutcome(null)
        assertEquals(PolishReason.CALL_FAILED, (awaitHandedBack() as PreparedText.Fallback).reason)
        assertEquals(listOf("null"), protocolShapes())
    }

    /** Row f2. MUTATION: accept an outcome for another request id. */
    @Test fun aMismatchedOutcomeFallsBackAndIsNamed() {
        controller.prepare("hello world", preferences)
        val listener = link.awaitRequest()
        listener.onOutcome(link.outcome("Hello world.").copy(requestId = link.requestId + 1))
        assertEquals(PolishReason.CALL_FAILED, (awaitHandedBack() as PreparedText.Fallback).reason)
        assertEquals(listOf("mismatched"), protocolShapes())
    }

    /** Row f3. MUTATION: drop the defect on a legacy `onResult`. */
    @Test fun aLegacyResultFallsBackAndIsNamed() {
        controller.prepare("hello world", preferences)
        link.awaitRequest().onResult("Hello world.", "engine", 5)
        assertEquals(PolishReason.CALL_FAILED, (awaitHandedBack() as PreparedText.Fallback).reason)
        assertEquals(listOf("v1_result"), protocolShapes())
    }

    /** Row f4. MUTATION: drop the defect on a legacy `onError`. */
    @Test fun aLegacyErrorFallsBackAndIsNamed() {
        controller.prepare("hello world", preferences)
        link.awaitRequest().onError("gone")
        assertEquals(PolishReason.CALL_FAILED, (awaitHandedBack() as PreparedText.Fallback).reason)
        assertEquals(listOf("v1_error"), protocolShapes())
    }

    /**
     * Row h. The watchdog's cancel and a binder-thread disconnect's fallback are sent and handed back with the
     * lock released. MUTATION: hand the disconnect's fallback back inside the lock.
     */
    @Test fun nothingIsSentOrHandedBackUnderTheLock() {
        controller.prepare("hello world", preferences)
        link.awaitRequest()
        val binderThread = Thread { controller.disconnected() }
        binderThread.start()
        binderThread.join(10_000)
        awaitHandedBack()
        assertEquals("handed back with the lock released", listOf(false), preparedUnderLock.toList())
        assertFalse("sent with the lock released", link.cancelUnderLock.any { it })
    }

    /** Row h2: the watchdog sends its cancel with the lock released. MUTATION: send the watchdog's cancel inside the lock. */
    @Test fun theWatchdogCancelsWithTheLockReleased() {
        controller.prepare("hello world", preferences)
        link.awaitRequest()
        timeout.fire()
        awaitHandedBack()
        assertTrue("the watchdog cancelled", link.cancelled.await(10, TimeUnit.SECONDS))
        assertEquals(listOf(false), link.cancelUnderLock.toList())
    }

    /**
     * Row i. A claimed request's cancel that fails is quiet, as before the move: the watchdog's own line says
     * why it cancelled. MUTATION: cancel through `sendCancel` in the watchdog (a failure warning appears).
     */
    @Test fun theWatchdogsFailedCancelIsQuiet() {
        link.throwOnCancel = true
        controller.prepare("hello world", preferences)
        link.awaitRequest()
        timeout.fire()
        awaitHandedBack()
        assertTrue("the watchdog cancelled", link.cancelled.await(10, TimeUnit.SECONDS))
        awaitControllerIdle()
        assertTrue(log.lines.any { it.contains("Polish watchdog fired") })
        assertTrue("no failure warning: ${log.lines}", log.lines.none { it.contains("Unable to cancel polish request") })
    }

    /**
     * Row i2 (the owner). A speech drop's failed cancel of the claimed request is quiet too. MUTATION: cancel
     * through `sendCancel` in `claimSpeechLossFallback`.
     */
    @Test fun theSpeechDropsFailedCancelIsQuiet() {
        val rig = DictationSessionRig()
        try {
            rig.polish.throwOnCancel = true
            val coordinator = rig.coordinator()
            coordinator.onCreated()
            rig.command(coordinator, DictationSessionService.ACTION_START)
            rig.surface.awaitShown()
            rig.command(coordinator, DictationSessionService.ACTION_STOP)
            rig.speech.awaitRequest().onResult("hello there")
            rig.polish.awaitRequest()
            rig.pipeline.disconnect("speech")
            assertEquals(TerminalReason.COMPLETED, rig.endings.awaitOne())
            rig.host.awaitStopped()
            assertTrue("the claimed request was cancelled", rig.polish.cancelThreads.contains(rig.mainThread.name))
            assertTrue("no failure warning: ${rig.log.lines}", rig.log.lines.none { it.contains("Unable to cancel polish request") })
        } finally {
            rig.close()
        }
    }

    /**
     * Row g3 (the owner). An owner admits exactly one take, by behaviour: after its take ended, a new start is
     * not admitted (no second pin). MUTATION: return to IDLE in `finishSession`.
     */
    @Test fun anOwnerNeverAdmitsASecondTake() {
        val rig = DictationSessionRig()
        try {
            val coordinator = rig.coordinator()
            coordinator.onCreated()
            rig.command(coordinator, DictationSessionService.ACTION_START)
            rig.surface.awaitShown()
            rig.command(coordinator, DictationSessionService.ACTION_STOP)
            rig.speech.awaitRequest().onResult("hello there")
            rig.polish.awaitRequest().onOutcome(rig.polish.outcome("Hello there."))
            assertEquals(TerminalReason.COMPLETED, rig.endings.awaitOne())
            rig.host.awaitStopped()
            val pins = rig.insertion.pins.get()
            rig.command(coordinator, DictationSessionService.ACTION_START)
            assertEquals("no second take on this owner", pins, rig.insertion.pins.get())
        } finally {
            rig.close()
        }
    }

    /**
     * Row h3 (the owner): a cancel while processing sends the engine's cancel after the owner's submission lock
     * is released. MUTATION: send the cancel inside `cancelProcessing`'s lock.
     */
    @Test fun theOwnersCancelWhileProcessingIsSentWithTheLockReleased() {
        val rig = DictationSessionRig()
        try {
            val coordinator = rig.coordinator()
            val ownerLock = DictationSessionCoordinator::class.java.getDeclaredField("polishSubmissionLock")
                .apply { isAccessible = true }.get(coordinator)
            rig.polish.lockToWatch = ownerLock
            coordinator.onCreated()
            rig.command(coordinator, DictationSessionService.ACTION_START)
            rig.surface.awaitShown()
            rig.command(coordinator, DictationSessionService.ACTION_STOP)
            rig.speech.awaitRequest().onResult("some words")
            rig.polish.awaitRequest()
            rig.command(coordinator, DictationSessionService.ACTION_CANCEL)
            assertEquals(TerminalReason.CANCELLED_PROCESSING, rig.endings.awaitOne())
            rig.host.awaitStopped()
            assertTrue("the open request was cancelled", rig.polish.cancelHeldLock.isNotEmpty())
            assertTrue("never under the owner's lock: ${rig.polish.cancelHeldLock}", rig.polish.cancelHeldLock.none { it })
        } finally {
            rig.close()
        }
    }

    // ---- #252: the fallback and the vocabulary restore never throw ----------------------------------------------

    /** A restorer that throws on its [n]th call only (one seam serves four restores), else leaves the text alone. */
    private fun restoreThrowingOn(n: Int): (String, com.envi.wispr.vocabulary.StructuredTermRestorer.Matcher) -> String {
        var calls = 0
        return { text, _ -> if (++calls == n) throw IllegalStateException("restorer broke") else text }
    }

    private val markedCleanup: (String, com.envi.wispr.cleanup.CleanupOptions, com.envi.wispr.cleanup.LanguageDetector) -> String =
        { text, _, _ -> "cleaned $text" }

    private fun preparationSteps() = defects.filter { it.first == AppDefect.PolishPreparationFailed }.map { it.second["step"] }

    /** Row 1a: the fallback's first restore throws; the raw words land. MUTATION: remove the restore step's catch. */
    @Test fun aFailedFirstRestoreHandsBackTheRawWords() {
        val c = newController(restore = restoreThrowingOn(1), cleanup = markedCleanup)
        c.claimSpeechLossFallback("hello world")
        val text = awaitHandedBack() as PreparedText.Fallback
        assertEquals("hello world", text.text)
        assertEquals(PolishReason.SERVICE_DIED, text.reason)
        assertEquals(listOf(TakePolishController.STEP_RESTORE_RAW), preparationSteps())
    }

    /** Row 1b: the cleanup throws; the restored words land. MUTATION: remove the cleanup step's catch. */
    @Test fun aFailedCleanupHandsBackTheRestoredWords() {
        val c = newController(restore = { text, _ -> "restored $text" }, cleanup = { _, _, _ -> throw IllegalStateException("detector broke") })
        c.claimSpeechLossFallback("hello world")
        assertEquals("the restored words, not the raw ones", "restored hello world", awaitHandedBack().text)
        assertEquals(listOf(TakePolishController.STEP_CLEANUP), preparationSteps())
    }

    /** Row 1c: the final restore throws; the cleaned words land. MUTATION: remove the final restore's catch. */
    @Test fun aFailedFinalRestoreHandsBackTheCleanedWords() {
        val c = newController(restore = restoreThrowingOn(2), cleanup = markedCleanup)
        c.claimSpeechLossFallback("hello world")
        assertEquals("cleaned hello world", awaitHandedBack().text)
        assertEquals(listOf(TakePolishController.STEP_RESTORE_CLEANED), preparationSteps())
    }

    /** Row 1d: the answer's restore throws; the engine's answer lands as polished. MUTATION: remove the answer restore's catch. */
    @Test fun aFailedAnswerRestoreHandsBackTheEnginesAnswer() {
        val c = newController(restore = restoreThrowingOn(2))
        c.prepare("hello world", preferences)
        link.awaitRequest().onOutcome(link.outcome("Hello world."))
        val text = awaitHandedBack() as PreparedText.Polished
        assertEquals("Hello world.", text.text)
        assertEquals(PolishReason.POLISHED, text.reason)
        assertEquals(listOf(TakePolishController.STEP_RESTORE_ANSWER), preparationSteps())
    }

    /** Row 1e: the pre-request restore throws; the request carries the raw words. MUTATION: remove the pre-request catch. */
    @Test fun aFailedPreRequestRestoreSendsTheRawWords() {
        val c = newController(restore = restoreThrowingOn(1))
        c.prepare("hello world", preferences)
        link.awaitRequest()
        assertEquals("hello world", link.lastRaw)
        assertEquals(listOf(TakePolishController.STEP_RESTORE_RAW), preparationSteps())
    }

    /**
     * Row 4: a defect sink that throws never stops the words, on the preparation defect and on a polish death's
     * defect. MUTATION: call the sink directly in `report`.
     */
    @Test fun aThrowingDefectSinkNeverStopsTheWords() {
        val c = newController(restore = restoreThrowingOn(1), defectSink = { _, _ -> throw IllegalStateException("sink broke") })
        c.claimSpeechLossFallback("hello world")
        assertEquals("hello world", awaitHandedBack().text)
        assertEquals(1, prepared.size)
    }

    /** Row 4b: a polish death whose defect report throws still hands the fallback back. MUTATION: as row 4. */
    @Test fun aThrowingSinkOnAPolishDeathNeverStopsTheWords() {
        val c = newController(defectSink = { _, _ -> throw IllegalStateException("sink broke") })
        c.prepare("hello world", preferences)
        link.awaitRequest()
        c.disconnected()
        assertEquals(PolishReason.SERVICE_DIED, (awaitHandedBack() as PreparedText.Fallback).reason)
    }

    // ---- #253: claims and hand-backs on main, preparation off it ---------------------------------------------------

    /**
     * Row 2: a fallback is prepared off main and handed back on main, and a held preparation leaves main free.
     * MUTATIONS: prepare inside the main hop; hand back without the main hop.
     */
    @Test fun aFallbackIsPreparedOffMainAndHandedBackOnMain() {
        val preparing = CountDownLatch(1)
        val release = CountDownLatch(1)
        val preparedIn = CopyOnWriteArrayList<String>()
        val c = newController(cleanup = { text, _, _ ->
            preparedIn += threadName()
            preparing.countDown()
            release.await(10, TimeUnit.SECONDS)
            text
        })
        try {
            c.claimSpeechLossFallback("hello world")
            assertTrue("the preparation started", preparing.await(10, TimeUnit.SECONDS))
            val mainFree = CountDownLatch(1)
            countingMain.execute { mainFree.countDown() }
            assertTrue("main stays free while the fallback is prepared", mainFree.await(10, TimeUnit.SECONDS))
        } finally {
            release.countDown()
        }
        awaitHandedBack()
        assertTrue("prepared off main: $preparedIn", preparedIn.none { it == "test-main" })
        assertEquals("handed back on main", listOf("test-main"), preparedOn.toList())
    }

    /**
     * Row 3: an answer already queued on main before the watchdog fires wins; the watchdog's claim, queued after
     * it, finds nothing. MUTATION: claim the watchdog off main.
     */
    @Test fun anAnswerQueuedOnMainBeforeTheWatchdogWins() {
        controller.prepare("hello world", preferences)
        val listener = link.awaitRequest()
        val release = holdMain()
        countingMain.execute { listener.onOutcome(link.outcome("Hello world.")) }
        timeout.fire()
        // Task 3 is the watchdog's claim (or, if it claimed off main, its hand-back): either way it has acted.
        awaitMainQueued(3)
        release.countDown()
        assertEquals("Hello world.", awaitHandedBack().text)
        awaitControllerIdle()
        assertEquals("one text for the take", 1, prepared.size)
        assertTrue(prepared.single() is PreparedText.Polished)
    }

    /**
     * Row 4b: the engine posts an answer and then its request call throws; the answer, queued on main first,
     * wins. MUTATION: claim the thrown request off main.
     */
    @Test fun anAnswerSentBeforeTheRequestThrewWins() {
        val release = holdMain()
        val throwing = object : PolishLink by link {
            override fun polishRequestForTake(requestId: Long, rawText: String, removeFillers: Boolean, spokenEmoji: Boolean, spokenPunctuation: Boolean, policy: PolishPolicy, takeId: String, listener: PolishListener) {
                val answer = PolishOutcome(requestId = requestId, text = "Hello world.", engine = "Fake engine", reason = PolishReason.POLISHED, statusCode = 0, latencyMs = 1L)
                countingMain.execute { listener.onOutcome(answer) }
                throw IllegalStateException("engine threw after answering")
            }
        }
        val c = TakePolishController(
            lock = lock, ledger = PolishRequestLedger(), timeout = timeout, scope = scope, link = { throwing },
            languageDetector = { null }, log = log, takeId = "take-1", defectSink = { defect, facts -> defects += defect to facts },
            preferences = { preferences }, transcript = { "hello world" }, isProcessing = { processing.get() }, isLive = { true },
            onPrepared = { text -> prepared += text; handedBack.countDown() },
            mainDispatcher = mainDispatcher, stillPublishable = { publishable.get() },
        )
        c.prepare("hello world", preferences)
        // Task 3: the thrown request's claim on main, or (off main) its fallback's hand-back.
        awaitMainQueued(3)
        release.countDown()
        awaitHandedBack()
        // The watchdog, still armed, now finds the request answered and stands down.
        timeout.fire()
        awaitControllerIdle()
        assertTrue("the answer won: $prepared", prepared.single() is PreparedText.Polished)
        assertTrue("no call failure was reported", defects.none { it.first == AppDefect.PolishCallFailed })
    }

    /**
     * Row 4: a text prepared after the take can no longer publish is dropped, fallback and answer alike.
     * MUTATION: drop the `stillPublishable` check.
     */
    @Test fun aTakeThatCanNoLongerPublishGetsNoText() {
        publishable.set(false)
        controller.claimSpeechLossFallback("hello world")
        log.awaitLine("Prepared polish text dropped")
        val c2 = newController()
        c2.prepare("hello world", preferences)
        link.awaitRequest().onOutcome(link.outcome("Hello world."))
        log.awaitLine("Prepared polish text dropped", 2)
        assertTrue("nothing handed back: $prepared", prepared.isEmpty())
    }
}
