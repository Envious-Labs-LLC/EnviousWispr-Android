package com.envi.wispr.ui

import com.envi.wispr.polish.PolishPolicy
import com.envi.wispr.providers.PolicyRead
import com.envi.wispr.telemetry.TakeFacts
import com.envi.wispr.vocabulary.StructuredTermRestorer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.cancellation.CancellationException

/**
 * Harness Contract (#281): the steps before the bind, driven alone. `TakePreparationBoundTest` drives the same
 * preparer through the owner; these rows pin what the owner cannot show: the publication-then-recheck of the jobs, the
 * last read policy taken before this take's read, the admission wait's own deadline and logs, and the stamps' order.
 * Each row names the mutation that turns it red.
 */
class TakeStartPreparerTest {
    private val rig = DictationSessionRig()
    private val held = CountDownLatch(1)
    private val ticks = AtomicLong()

    @After fun tearDown() {
        held.countDown()
        rig.close()
    }

    private fun preparer(
        compileMatcher: () -> StructuredTermRestorer.Matcher = { StructuredTermRestorer.compile(emptyList()) },
        loadPolicy: suspend () -> PolicyRead = { PolicyRead.Fresh(PolishPolicy.Off) },
        lastRead: () -> PolishPolicy? = { null },
        boundMs: Long = 2_000L,
    ) = TakeStartPreparer(
        preferences = rig.preferencesSource,
        answerBoundMs = 50L,
        preparationBoundMs = boundMs,
        compileMatcher = { compileMatcher() },
        loadPolicy = loadPolicy,
        lastReadPolicy = lastRead,
        clock = { 0L },
        scope = rig.scope,
        log = rig.log,
    )

    private fun facts() = TakeFacts("0a1b2c3d-4e5f-4a6b-8c7d-9e8f7a6b5c4d", TriggerSource.APP)

    /** Row 1: an unstarted reader is a timed-out answer; its token, its warn, and every stamp in order. MUTATION m5 is the source row. */
    @Test fun theSettingsFallbackAndTheStampsAreWrittenInOrder() = runBlocking {
        val facts = facts()
        val prepared = preparer().prepare(facts.takeId, facts, admission = null, sinceAccepted = { ticks.incrementAndGet() }, jobs = {}, stillStarting = { true })
        assertEquals("both:timed_out:timed_out", facts.settingsFallback)
        assertTrue(rig.log.lines.any { it.contains("Settings reader fell back; the take runs on the last values: both:timed_out:timed_out") })
        assertEquals(listOf(1L, 2L, 3L), listOf(facts.settingsAnswerMs, facts.matcherReadyMs, facts.policyLoadedMs))
        assertEquals("auto", facts.inputDevice)
        assertTrue(prepared.matcher is Prepared.Ready && prepared.policy == Prepared.Ready(PolicyRead.Fresh(PolishPolicy.Off)))
    }

    /** Row 2: a take that stopped starting while the jobs launched has both cancelled by the preparer itself. MUTATION m1. */
    @Test fun jobsPublishedAfterTheTakeStoppedStartingAreCancelled() = runBlocking {
        val facts = facts()
        var published: List<Job> = emptyList()
        // Still starting until the jobs are published: only a recheck AFTER publication can see the cancel.
        var starting = true
        val outcome = runCatching {
            preparer(
                compileMatcher = { held.await(10, TimeUnit.SECONDS); StructuredTermRestorer.compile(emptyList()) },
                loadPolicy = { CompletableDeferred<PolicyRead>().await() },
            )
                .prepare(facts.takeId, facts, null, { 0L }, jobs = { published = it; starting = false }, stillStarting = { starting })
        }
        assertEquals(2, published.size)
        assertTrue("both jobs were cancelled: $published", published.all { it.isCancelled })
        assertTrue("a cancelled job ends the preparation: $outcome", outcome.exceptionOrNull() is CancellationException)
    }

    /** Row 3: the last read policy is taken before this take's read, which moves it. MUTATION m2. */
    @Test fun thePriorPolicyIsTheOneBeforeThisTakesRead() = runBlocking {
        val facts = facts()
        val last = java.util.concurrent.atomic.AtomicReference<PolishPolicy?>(PolishPolicy.Off)
        val prepared = preparer(
            loadPolicy = { last.set(PolishPolicy.CloudUnconfigured); PolicyRead.Fresh(PolishPolicy.CloudUnconfigured) },
            lastRead = { last.get() },
        ).prepare(facts.takeId, facts, null, { 0L }, {}, { true })
        assertEquals(PolishPolicy.CloudUnconfigured, last.get())
        assertEquals(PolishPolicy.Off, prepared.priorPolicy)
        // The job's start is scheduled, so the order itself is pinned too: the snapshot precedes the launch.
        val source = SessionSources.preparer
        val prior = source.indexOf("val priorPolicy = lastReadPolicy()")
        val launch = source.indexOf("val policyJob = scope.async(")
        assertTrue("snapshot before policy launch", prior >= 0 && launch > prior)
    }

    /** Row 4: an admission that never lands is waited for only up to its own deadline, and says so. MUTATION m3. */
    @Test fun anAdmissionThatNeverLandsIsWaitedForOnlyToItsDeadline() = runBlocking {
        val facts = facts()
        withTimeout(5_000L) {
            preparer().prepare(facts.takeId, facts, CompletableDeferred(), { 0L }, {}, { true })
        }
        assertTrue(rig.log.lines.any { it.contains("Journal admission did not land within ${DictationSessionCoordinator.JOURNAL_ADMISSION_DEADLINE_MS} ms; starting anyway") })
    }

    /** Row 5: an admission that fails is logged by its type and never stops the preparation. */
    @Test fun anAdmissionThatFailsIsLoggedAndPassed() = runBlocking {
        val facts = facts()
        val failed = CompletableDeferred<Boolean>().apply { completeExceptionally(IllegalStateException("db")) }
        preparer().prepare(facts.takeId, facts, failed, { 0L }, {}, { true })
        assertTrue(rig.log.lines.any { it.contains("Journal admission failed: IllegalStateException; starting anyway") })
    }

    /** Row 6 (RED on main): the owner no longer holds the steps, and it calls the preparer. MUTATION m4 is the order row. */
    @Test fun theOwnerHoldsNoPreparationStepAndChecksBeforeAnyFallback() {
        val owner = SessionSources.coordinator
        for (gone in listOf("awaitAnswers(", "compileMatcher(", "loadPolicy()", "withTimeoutOrNull(JOURNAL_ADMISSION_DEADLINE_MS", "awaitBy(", "class Timed", "interface Prepared")) {
            assertTrue("the owner no longer holds $gone", !owner.contains(gone))
        }
        assertTrue(owner.contains("preparer.prepare("))
        val main = owner.substringAfter("withContext(mainDispatcher) {\n                // Checked BEFORE any fallback")
        val check = main.indexOf("if (state.get() != SessionState.STARTING) {")
        val matcher = main.indexOf("when (val step = prepared.matcher)")
        val policy = main.indexOf("when (val step = prepared.policy)")
        assertTrue("the state check precedes both fallbacks", check >= 0 && matcher > check && policy > check)
    }
}
