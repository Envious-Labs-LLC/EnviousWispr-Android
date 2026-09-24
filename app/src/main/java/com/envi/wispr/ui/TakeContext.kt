package com.envi.wispr.ui

import com.envi.wispr.paste.DictationTargetPin
import com.envi.wispr.telemetry.TakeFacts
import com.envi.wispr.telemetry.TakeOutcomeRecorder

/**
 * What a take is from the moment it is admitted (#216): its id, the surface that started it, the one
 * holder of its facts, the one referee of how it ends, the field it aims at, and the one owner of its
 * History row. Built once by the session owner in `beginSession`, after the pin, and published in one
 * field, so nothing reads half of one take and half of another. Every property is fixed; [facts] and
 * [history] are single-owner holders whose contents change as the take runs.
 *
 * [outcome] is the take's outcome record over [facts] (#329): every take-fact stamp, journal stage and take
 * breadcrumb the owner writes goes through it, and it only records.
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
    val outcome: TakeOutcomeRecorder,
    val arbiter: TakeArbiter,
    val targetPin: DictationTargetPin,
    val history: TakeHistory,
    val acceptedAtMs: Long,
) {
    companion object {
        /** Before any admission: an empty id, an arbiter that refuses everything, no target, no History. */
        val NONE: TakeContext = TakeFacts("", TriggerSource.UNKNOWN).let { facts -> TakeContext(
            takeId = "",
            trigger = TriggerSource.UNKNOWN,
            facts = facts,
            outcome = TakeOutcomeRecorder(facts, defect = { _, _ -> }),
            arbiter = TakeArbiter.closed(),
            targetPin = DictationTargetPin.NO_TARGET,
            history = TakeHistory(null),
            acceptedAtMs = 0L,
        ) }
    }
}
