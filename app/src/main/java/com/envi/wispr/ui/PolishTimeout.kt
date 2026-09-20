package com.envi.wispr.ui

import com.envi.wispr.polish.PolishPolicy
import kotlinx.coroutines.delay

/**
 * The polish watchdog's wait (#186). Production waits the policy's budget, 15 or 35 s
 * (`PolishWatchdogBudget`); a JVM test releases from a latch so the fallback path runs in milliseconds.
 */
internal fun interface PolishTimeout {
    suspend fun await(policy: PolishPolicy)
}

/** Production: `delay` for the policy's budget. */
internal object DelayPolishTimeout : PolishTimeout {
    override suspend fun await(policy: PolishPolicy) = delay(PolishWatchdogBudget.forPolicy(policy))
}
