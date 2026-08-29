package com.envi.wispr.model

/** User-selectable idle eviction policy for memory-heavy offline models. */
enum class ModelUnloadPolicy(val idleMinutes: Int?) {
    NEVER(null),
    IMMEDIATELY(0),
    AFTER_2_MINUTES(2),
    AFTER_5_MINUTES(5),
    AFTER_10_MINUTES(10),
    AFTER_15_MINUTES(15),
    AFTER_60_MINUTES(60),
}

object ModelLifecyclePolicy {
    fun shouldUnload(policy: ModelUnloadPolicy, idleMs: Long): Boolean =
        policy.idleMinutes != null && idleMs >= policy.idleMinutes * 60_000L
}
