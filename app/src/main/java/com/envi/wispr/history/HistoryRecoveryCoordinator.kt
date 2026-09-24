package com.envi.wispr.history

import com.envi.wispr.telemetry.Telemetry
import com.envi.wispr.ui.RescuedWords
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The start-up History recovery's one owner (#346), application-owned beside the History write queue and the rescue
 * store: stale open rows closed (with their telemetry), then rescued words written into History (#288). The session
 * owner's start and the History screen both ask here instead of each running its own copy.
 *
 * NOT once per process: a rescue file from a save that failed in THIS process, and an open row that has since
 * crossed the stale cutoff, must be taken by the next recovery in the same process. So a caller that asks while a run
 * is in flight gets ONE follow-up run that begins after it, shared by every caller that asks meanwhile; every
 * caller's recovery therefore begins after it asked, and concurrent callers share at most two runs.
 *
 * Each step has its own guard and its own bound, so one failing or stalling never skips the other and never holds a
 * later recovery: on the application's scope nothing else would release a stalled step. The stale-row step is bounded
 * by [staleBoundMs] (#346 review round 1). The rescue step, by [rescueBoundMs], also because it holds the rescue
 * store's lock while it writes to Room, the lock a new take's rescue write takes. A step cut off is retried by the next
 * recovery: the stale scan is an idempotent update, and the rescue write is idempotent by take id.
 */
internal class HistoryRecoveryCoordinator(
    private val repository: TranscriptRepository,
    private val rescuedWords: RescuedWords,
    /** The application's scope: a Service stopping never cancels a run another caller waits on. */
    private val scope: CoroutineScope,
    private val clock: () -> Long,
    private val log: (String) -> Unit,
    private val warn: (String) -> Unit,
    private val recordRecovered: (TranscriptRepository.RecoveredRows) -> Unit = { recovered ->
        Telemetry.insertionsRecovered(recovered.readyRowIds)
        Telemetry.deliveryUnknownRecovered(recovered.unknownCount)
    },
    private val rescueBoundMs: Long = RESCUE_RECOVERY_BOUND_MS,
    private val staleBoundMs: Long = STALE_RECOVERY_BOUND_MS,
) {
    /** What one run did: each step's failure, if any, and how many takes' rescued words reached History. */
    class Outcome(val staleFailure: Throwable?, val rescued: Int, val rescueFailure: Throwable?) {
        val failure: Throwable? get() = staleFailure ?: rescueFailure
    }

    /** A rescue step cut off at its bound; its files wait for the next recovery. */
    class RescueRecoveryTimeout : IllegalStateException("Rescued-word recovery timed out")

    /** A stale-row step cut off at its bound; the next recovery scans again. */
    class StaleRecoveryTimeout : IllegalStateException("Stale history recovery timed out")

    private val lock = Any()

    /** The run most recently started (it may be done), and the follow-up not yet begun. Guarded by [lock]. */
    private var current: Deferred<Outcome>? = null
    private var pending: Deferred<Outcome>? = null

    /**
     * A recovery that begins after this call: the run just started, or the one follow-up after the run in flight. A
     * follow-up is shared only while it is still active, so a cancelled one is never handed out again (#346 review round
     * 1); [current] names the run in flight until the follow-up begins, then the follow-up.
     */
    fun recover(): Deferred<Outcome> = synchronized(lock) {
        pending?.takeIf { it.isActive }?.let { return@synchronized it }
        pending = null
        val before = current?.takeIf { it.isActive }
        lateinit var run: Deferred<Outcome>
        run = scope.async(start = CoroutineStart.LAZY) {
            before?.join()
            synchronized(lock) {
                if (pending === run) pending = null
                current = run
            }
            runOnce()
        }
        if (before != null) pending = run else current = run
        run.start()
        run
    }

    private suspend fun runOnce(): Outcome {
        val staleFailure = try {
            val recovered = withTimeoutOrNull(staleBoundMs) { repository.recoverStaleOpenRows(clock()) }
            if (recovered == null) {
                warn("Stale history recovery passed its $staleBoundMs ms bound; the next recovery scans again")
                StaleRecoveryTimeout()
            } else {
                recordRecovered(recovered)
                null
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            warn("Unable to recover stale history: ${error.javaClass.simpleName}")
            error
        }
        var rescued = 0
        val rescueFailure = try {
            val written = withTimeoutOrNull(rescueBoundMs) { rescuedWords.recover(repository) }
            if (written == null) {
                warn("Rescued-word recovery passed its $rescueBoundMs ms bound; the files wait for the next recovery")
                RescueRecoveryTimeout()
            } else {
                rescued = written
                if (written > 0) log("Rescued words written to History: $written")
                null
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            warn("Unable to recover rescued words: ${error.javaClass.simpleName}")
            error
        }
        return Outcome(staleFailure, rescued, rescueFailure)
    }

    companion object {
        /** The rescue step's bound (#346): generous for a healthy disk, short enough that a new take is never held. */
        const val RESCUE_RECOVERY_BOUND_MS = 5_000L

        /** The stale-row step's bound (#346 review round 1): a stalled scan never holds the rescue step or a later recovery. */
        const val STALE_RECOVERY_BOUND_MS = 5_000L
    }
}
