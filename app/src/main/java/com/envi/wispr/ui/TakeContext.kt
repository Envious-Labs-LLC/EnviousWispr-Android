package com.envi.wispr.ui

import com.envi.wispr.paste.DictationTargetPin
import com.envi.wispr.telemetry.TakeFacts

/**
 * What a take is from the moment it is admitted (#216): its id, the surface that started it, the one
 * holder of its facts, the one referee of how it ends, the field it aims at, and the one owner of its
 * History row. Built once by the session owner in `beginSession`, after the pin, and published in one
 * field, so nothing reads half of one take and half of another. Every property is fixed; [facts] and
 * [history] are single-owner holders whose contents change as the take runs.
 *
 * [acceptedAtMs] is `host.elapsedRealtimeMs()` when the owner accepted the start command: the origin of the
 * take's pre-capture timings (#258).
 *
 * The owner alone holds this value. The collaborators receive the fields they need as parameters, never
 * the context, so neither can reach [arbiter].
 */
internal class TakeContext(
    val takeId: String,
    val trigger: TriggerSource,
    val facts: TakeFacts,
    val arbiter: TakeArbiter,
    val targetPin: DictationTargetPin,
    val history: TakeHistory,
    val acceptedAtMs: Long,
) {
    companion object {
        /** Before any admission: an empty id, an arbiter that refuses everything, no target, no History. */
        val NONE = TakeContext(
            takeId = "",
            trigger = TriggerSource.UNKNOWN,
            facts = TakeFacts("", TriggerSource.UNKNOWN),
            arbiter = TakeArbiter.closed(),
            targetPin = DictationTargetPin.NO_TARGET,
            history = TakeHistory(null),
            acceptedAtMs = 0L,
        )
    }
}
