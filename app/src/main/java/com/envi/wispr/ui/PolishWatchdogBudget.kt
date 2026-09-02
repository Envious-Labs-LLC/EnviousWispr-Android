package com.envi.wispr.ui

import com.envi.wispr.polish.PolishPolicy

/**
 * How long the session owner waits for the engine's outcome before publishing the deterministic text
 * itself (issue #75). Local: 15 s, the macOS local-generation precedent, against a worst observed local
 * total of about 5.4 s on the S26 Ultra (#72). Cloud: the client's own 30 s cap plus a margin, so a slow
 * but valid provider answer is never thrown away by the watchdog first. `PolishWatchdogBudgetTest` pins it.
 */
object PolishWatchdogBudget {
    const val LOCAL_MS = 15_000L
    const val CLOUD_MS = 35_000L

    fun forPolicy(policy: PolishPolicy): Long = when (policy) {
        PolishPolicy.Off, PolishPolicy.LocalS1, PolishPolicy.CloudUnconfigured -> LOCAL_MS
        is PolishPolicy.Cloud -> CLOUD_MS
    }
}
