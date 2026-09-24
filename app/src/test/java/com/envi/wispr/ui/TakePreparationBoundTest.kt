package com.envi.wispr.ui

import com.envi.wispr.polish.PolishPolicy
import com.envi.wispr.providers.PolicyRead
import com.envi.wispr.providers.Provider
import com.envi.wispr.providers.SelfHostedProtocol
import com.envi.wispr.settings.AppPreferencesState
import com.envi.wispr.vocabulary.CustomTerm
import com.envi.wispr.vocabulary.StructuredTermRestorer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.flow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Product Outcome (#290, #294): every take goes on to record within a fixed time. The vocabulary matcher and the
 * policy read are limbs with one deadline; a throw or a hang in either starts the take on its fallback (no custom
 * words, or the last read policy) with one defect, and a take cancelled during the wait reports nothing.
 *
 * Each row waits on a subject-fired signal (the recorder going live, the polish request) and names the compiling
 * mutation that turns it red (recorded in the PR's receipts).
 */
class TakePreparationBoundTest {
    private val rig = DictationSessionRig()
    private val released = CountDownLatch(1)

    @After fun tearDown() {
        released.countDown()
        rig.policyHold?.complete(Unit)
        rig.close()
    }

    private val term = CustomTerm(spelling = "Envious", aliases = listOf("envious"))
    private val withTerm = SessionPreferencesSource(
        preferenceStates = flow { emit(AppPreferencesState()) },
        terms = flow { emit(listOf(term)) },
        migrateLegacyTerms = {},
        log = rig.log,
    )

    private fun preparationDefects() = rig.defects.filter { it.first == "take_preparation_failed" }

    private fun policyDefects() = rig.defects.map { it.first }.filter { it == "polish_policy_unreadable" }

    private fun goLive(coordinator: DictationSessionCoordinator) {
        coordinator.onCreated()
        rig.command(coordinator, DictationSessionService.ACTION_START)
        rig.surface.awaitShown()
    }

    /** The raw text the polish request carried: the vocabulary restoration shows whether the matcher was used. */
    private fun rawSentToPolish(coordinator: DictationSessionCoordinator): String? {
        rig.command(coordinator, DictationSessionService.ACTION_STOP)
        rig.speech.awaitRequest().onResult("hello envious")
        rig.polish.awaitRequest { "log: ${rig.log.lines}" }
        return rig.polish.lastRawText
    }

    /** Row 1. A matcher that throws: the take records, restores no custom words, one `error` defect. MUTATION m1: the job's catch removed. */
    @Test fun aMatcherThatThrowsStartsTheTakeWithNoCustomWords() {
        val coordinator = rig.coordinator(preferences = withTerm, compileMatcher = { error("broken term") })
        goLive(coordinator)
        assertEquals("no custom word was restored", "hello envious", rawSentToPolish(coordinator))
        assertEquals(listOf(mapOf("take_id" to preparationDefects().single().second["take_id"], "step" to "matcher", "kind" to "error")), preparationDefects().map { it.second })
    }

    /** Row 3. A matcher that never finishes: the take records by the bound, no custom words, one `timeout` defect. MUTATION m3: the matcher awaited without the deadline. */
    @Test fun aMatcherThatNeverFinishesStartsTheTakeByTheBound() {
        val coordinator = rig.coordinator(preferences = withTerm, preparationBoundMs = 100L, compileMatcher = { terms -> released.await(); StructuredTermRestorer.compile(terms) })
        goLive(coordinator)
        assertEquals("no custom word was restored", "hello envious", rawSentToPolish(coordinator))
        assertEquals(listOf("timeout"), preparationDefects().map { it.second["kind"] })
    }

    /** A healthy matcher still restores the custom words (the control for rows 1 and 3). */
    @Test fun aHealthyMatcherRestoresTheCustomWords() {
        val coordinator = rig.coordinator(preferences = withTerm, preparationBoundMs = 100L)
        goLive(coordinator)
        assertEquals("hello Envious", rawSentToPolish(coordinator))
        assertTrue(preparationDefects().isEmpty())
    }

    /**
     * Row 2 (#294). A policy read that never answers: the take records by the bound and polishes on the process's last
     * read policy, with exactly one `polish_policy_unreadable`. MUTATION m2: the policy awaited without the deadline.
     */
    @Test fun aPolicyReadThatNeverAnswersUsesTheLastReadPolicy() {
        val lastRead = PolishPolicy.Cloud(Provider.SELF_HOSTED_POLISH, "llama3.2", "http://localhost:8080/v1", SelfHostedProtocol.OLLAMA)
        rig.lastReadPolicy = lastRead
        rig.policyHold = CompletableDeferred()
        val coordinator = rig.coordinator(preparationBoundMs = 100L)
        goLive(coordinator)
        rawSentToPolish(coordinator)
        assertEquals(lastRead, rig.polish.lastPolicy)
        assertEquals(listOf("polish_policy_unreadable"), policyDefects())
        assertTrue(preparationDefects().isEmpty())
    }

    /**
     * Row 4. The two run side by side: with the matcher held, the policy read starts AND answers before the deadline,
     * so the take polishes on the FRESH policy even though the matcher falls back. MUTATION m4: the policy job started
     * only after the matcher returned (the read then never runs before the deadline and the take falls back).
     */
    @Test fun thePolicyAnswersWhileTheMatcherIsHeld() {
        val fresh = PolishPolicy.Cloud(Provider.SELF_HOSTED_POLISH, "fresh", "http://localhost:8080/v1", SelfHostedProtocol.OLLAMA)
        rig.policyRead = PolicyRead.Fresh(fresh)
        rig.lastReadPolicy = PolishPolicy.Off
        val order = CopyOnWriteArrayList<String>()
        val coordinator = rig.coordinator(preparationBoundMs = 300L, compileMatcher = { terms -> order += "matcher held"; released.await(); StructuredTermRestorer.compile(terms) })
        goLive(coordinator)
        rawSentToPolish(coordinator)
        assertEquals("the fresh policy answered in time", fresh, rig.polish.lastPolicy)
        assertTrue("no policy fallback", policyDefects().isEmpty())
        assertEquals(listOf("timeout"), preparationDefects().map { it.second["kind"] })
        assertEquals(listOf("matcher held"), order.toList())
    }

    /** Row 5. `admitTake` throws: the take still records. MUTATION m5: the wrapper removed. */
    @Test fun anAdmissionThatThrowsNeverPreventsTheTake() {
        val coordinator = rig.coordinator(admit = { _, _ -> error("journal broken") })
        goLive(coordinator)
        assertTrue(rig.log.lines.any { it.contains("Journal admission failed to queue") })
    }

    /** Row 6. An admission that completes exceptionally: the take still records. MUTATION m6: the admission wait's catch removed. */
    @Test fun anAdmissionThatFailsNeverPreventsTheTake() {
        val coordinator = rig.coordinator(admit = { _, _ -> CompletableDeferred<Boolean>().apply { completeExceptionally(IllegalStateException("journal broken")) } })
        goLive(coordinator)
        assertTrue(rig.log.lines.any { it.contains("Journal admission failed:") })
    }

    /**
     * Row 7. A take torn down during the preparation wait (the service destroyed while STARTING; a cancel command in
     * STARTING is held until live, so it cannot end the take here) reports nothing and binds nothing. MUTATION m7: the
     * fallbacks taken before the STARTING check (the matcher defect is then reported for a take that is gone).
     */
    @Test fun aTakeEndedDuringThePreparationWaitReportsNothing() {
        val entered = CountDownLatch(1)
        val coordinator = rig.coordinator(preferences = withTerm, preparationBoundMs = 200L, compileMatcher = { terms -> entered.countDown(); released.await(); StructuredTermRestorer.compile(terms) })
        coordinator.onCreated()
        rig.command(coordinator, DictationSessionService.ACTION_START)
        assertTrue(entered.await(10, TimeUnit.SECONDS))
        rig.onMain { coordinator.destroy() }
        Thread.sleep(500) // past the preparation bound: a start coroutine that survived would have decided by now
        assertTrue("no preparation defect for a take that is gone", preparationDefects().isEmpty())
        assertTrue("no bind", "bind" !in rig.pipeline.events)
    }

    /** `freeze` takes the effective terms it is given (#290): the fallback's empty list is what the take carries. */
    @Test fun theFreezeCarriesTheEffectiveTerms() {
        val start = kotlinx.coroutines.runBlocking { withTerm.awaitAnswers(1_000L) }
        val matcher = StructuredTermRestorer.compile(emptyList())
        assertEquals(emptyList<CustomTerm>(), withTerm.freeze(start, matcher, emptyList(), PolishPolicy.Off).terms)
        assertEquals(start.terms.structuredTerms, withTerm.freeze(start, matcher, start.terms.structuredTerms, PolishPolicy.Off).terms)
        assertTrue(TimeUnit.SECONDS.toMillis(1) > 0)
    }
}
