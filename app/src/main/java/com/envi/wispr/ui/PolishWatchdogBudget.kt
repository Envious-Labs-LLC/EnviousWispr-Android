package com.envi.wispr.ui

import com.envi.wispr.polish.LocalPolishModel
import com.envi.wispr.polish.PolishPolicy

/**
 * How long the session owner waits for the engine's outcome before publishing the deterministic text
 * itself (issue #75). S1 local: 15 s, the macOS local-generation precedent, against a worst observed
 * S1 total of about 5.4 s on the S26 Ultra (#72). EG-1 local: 23 s above its 20 s engine hard deadline.
 * Cloud: the client's own 30 s cap plus a margin, so a slow but valid provider answer is never thrown
 * away by the watchdog first. `PolishWatchdogBudgetTest` pins it.
 */
internal object PolishWatchdogBudget {
    const val LOCAL_MS = 15_000L
    const val EG1_LOCAL_MS = 23_000L
    const val CLOUD_MS = 35_000L

    fun forPolicy(policy: PolishPolicy): Long = when (policy) {
        PolishPolicy.Off, PolishPolicy.CloudUnconfigured -> LOCAL_MS
        is PolishPolicy.LocalS1 -> when (policy.model) {
            LocalPolishModel.S1_MINI -> LOCAL_MS
            LocalPolishModel.EG1_MICRO -> EG1_LOCAL_MS
        }
        is PolishPolicy.Cloud -> CLOUD_MS
    }
}
