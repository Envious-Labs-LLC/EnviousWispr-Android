package com.envi.wispr.ui

import com.envi.wispr.polish.PolishPolicy
import com.envi.wispr.providers.PolicyRead
import com.envi.wispr.telemetry.TakeFacts
import com.envi.wispr.telemetry.Telemetry
import com.envi.wispr.ui.DictationSessionCoordinator.Companion.JOURNAL_ADMISSION_DEADLINE_MS
import com.envi.wispr.ui.DictationSessionCoordinator.Companion.KIND_ERROR
import com.envi.wispr.ui.DictationSessionCoordinator.Companion.KIND_TIMEOUT
import com.envi.wispr.vocabulary.CustomTerm
import com.envi.wispr.vocabulary.StructuredTermRestorer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.cancellation.CancellationException

/** A start preparation's answer by its deadline (#290): the value, or why there is none (`timeout` or `error`). */
internal sealed interface Prepared<out T> {
    data class Ready<T>(val value: T) : Prepared<T>
    data class Failed(val kind: String) : Prepared<Nothing>
}

/**
 * What a take starts from (#281): the settings answer, the matcher and the policy by their deadline, and the last read
 * policy as it was before this take's read began. Immutable; the owner decides what to do with a failed step, after its
 * own state check on main (#290).
 */
internal class TakeStartPreparation(
    val start: PreferenceStart,
    val matcher: Prepared<StructuredTermRestorer.Matcher>,
    val policy: Prepared<PolicyRead>,
    val priorPolicy: PolishPolicy?,
    val jobs: List<Job>,
)

/**
 * The work a take does before the owner decides to bind (#281, moved from `beginSession`): the settings answer wait and
 * its fallback facts (#193), the vocabulary matcher compile and the policy read under ONE deadline (#290), and the
 * journal admission wait under a deadline that never gates the take (#176). Every step is a limb: a failed or slow step
 * is an answer, never a stalled start. The owner stays the only session owner: it enqueues the admission, holds the
 * jobs this publishes, checks its state on main, reports any fallback, freezes and binds.
 */
internal class TakeStartPreparer(
    private val preferences: SessionPreferencesSource,
    private val answerBoundMs: Long,
    private val preparationBoundMs: Long,
    private val compileMatcher: (List<CustomTerm>) -> StructuredTermRestorer.Matcher,
    private val loadPolicy: suspend () -> PolicyRead,
    private val lastReadPolicy: () -> PolishPolicy?,
    private val clock: () -> Long,
    private val scope: CoroutineScope,
    private val log: SessionLog,
) {
    /** A start preparation's answer and the host time its job finished (#290 review): late means stamped past the deadline. */
    private data class Timed<T>(val result: Prepared<T>, val finishedAtMs: Long)

    /**
     * Runs the four steps in order and stamps each on [facts] as it completes. [jobs] receives the matcher and policy
     * jobs as soon as they are launched; [stillStarting] is asked right after, so a cancel that landed between the
     * launch and the registration still cancels them.
     */
    suspend fun prepare(
        takeId: String,
        facts: TakeFacts,
        admission: Deferred<Boolean>?,
        sinceAccepted: () -> Long,
        jobs: (List<Job>) -> Unit,
        stillStarting: () -> Boolean,
    ): TakeStartPreparation {
        // The steps that need nothing from the settings start NOW (#345): the policy read runs beside the settings wait,
        // and the journal admission's own window counts from here, so neither adds to the chain behind the settings.
        // Only the matcher waits for the settings, because it compiles the terms they carry.
        val preparedFromMs = clock()
        // Read BEFORE this take's read starts (#290 review round 2): a late read updates the process's last read as it
        // lands, and this take must not fall back onto the very answer it refused as late.
        val priorPolicy = lastReadPolicy()
        val policyJob = scope.async(Dispatchers.IO) { Timed(preparing { loadPolicy() }, clock()) }
        // Take-owned (#290 review): published at once, then the state rechecked, so a cancel during the settings wait,
        // or between the launch and the publication, cancels it.
        jobs(listOf(policyJob))
        if (!stillStarting()) policyJob.cancel()
        // The readers are limbs (#193): a failed or silent read never ends the take. The start carries
        // both outcomes and the values that came with them, taken by one atomic read each, and the
        // take is built from it alone; nothing below rereads the live source after suspending.
        val start = preferences.awaitAnswers(answerBoundMs)
        facts.settingsAnswerMs = sinceAccepted()
        start.fallbackToken()?.let { token ->
            facts.settingsFallback = token
            log.warn("Settings reader fell back; the take runs on the last values: $token")
            Telemetry.breadcrumb("take", "settings_fallback", mapOf("take_id" to takeId, "settings_fallback" to token))
        }
        facts.inputDevice = TakeFacts.inputDeviceToken(start.settings.inputDevicePick)
        val termsSnapshot: List<CustomTerm> = start.terms.structuredTerms
        // The matcher and the policy are limbs with ONE deadline (#290): they run side by side as sibling jobs on
        // the owner's scope (never inside a scope that would wait for a blocked loser), each result is taken by the
        // deadline or replaced by its fallback, and a job that answers late is ignored. The policy has been running
        // since the start (#345), so it has at least as long as before.
        val deadlineMs = clock() + preparationBoundMs
        val matcherJob = scope.async(Dispatchers.Default) { Timed(preparing { compileMatcher(termsSnapshot) }, clock()) }
        // Take-owned (#290 review): a cancel of the starting take cancels both. Published, then the state is
        // rechecked, so a cancel that landed between the launch and the publication still cancels them.
        val launched = listOf(matcherJob, policyJob)
        jobs(launched)
        if (!stillStarting()) launched.forEach { it.cancel() }
        val matcher = awaitBy(matcherJob, deadlineMs)
        facts.matcherReadyMs = sinceAccepted()
        val policy = awaitBy(policyJob, deadlineMs)
        facts.policyLoadedMs = sinceAccepted()
        // Admission is written before capture starts, under a deadline that never gates the take:
        // the queued write still lands in order if this stops waiting (issue #176, plan §3.3). A failed
        // admission never gates it either (#290). Its window counts from the start of preparation (#345), so the
        // time spent on the settings and the matcher is spent on it too, and it adds nothing after them.
        if (admission != null) {
            val leftMs = (preparedFromMs + JOURNAL_ADMISSION_DEADLINE_MS - clock()).coerceAtLeast(0L)
            val landed = try {
                if (admission.isCompleted) admission.await() else withTimeoutOrNull(leftMs) { admission.await() }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                log.warn("Journal admission failed: ${error.javaClass.simpleName}; starting anyway")
                false
            }
            if (landed == null) log.warn("Journal admission did not land within $JOURNAL_ADMISSION_DEADLINE_MS ms; starting anyway")
        }
        return TakeStartPreparation(start, matcher, policy, priorPolicy, launched)
    }

    /** Runs one preparation step as a limb (#290): an ordinary exception is an `error`, cancellation is rethrown. */
    private suspend fun <T> preparing(step: suspend () -> T): Prepared<T> = try {
        Prepared.Ready(step())
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        Prepared.Failed(KIND_ERROR)
    }

    /**
     * [job]'s answer by [deadlineMs] on the host clock (#290), or `timeout`. Late is the job's OWN finish stamp past the
     * deadline, never when this looked: an answer stamped in time is taken even if the wait timed out first, and one
     * stamped late is refused even if it was already there. A job still running is left to finish unread.
     */
    private suspend fun <T> awaitBy(job: Deferred<Timed<T>>, deadlineMs: Long): Prepared<T> {
        val leftMs = deadlineMs - clock()
        val answered = if (leftMs > 0L) withTimeoutOrNull(leftMs) { job.await() } else null
        val seen = answered ?: job.takeIf { it.isCompleted && !it.isCancelled }?.getCompleted()
        return seen?.takeIf { it.finishedAtMs <= deadlineMs }?.result ?: Prepared.Failed(KIND_TIMEOUT)
    }
}
