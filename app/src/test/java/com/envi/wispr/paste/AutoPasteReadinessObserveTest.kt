package com.envi.wispr.paste

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Runs the join the readiness surfaces actually show, over real flows.
 *
 * [AutoPasteReadiness.evaluate] being correct says nothing about what reaches a screen. The defect
 * in issue #16 was never the rule, it was the wiring: the permission fact answering a question only
 * liveness can answer. This drives both sources through the production operator and asserts every
 * transition, so a projection that puts the permission back in charge fails here rather than
 * shipping.
 */
class AutoPasteReadinessObserveTest {

    @Test
    fun theAnswerFollowsBothSourcesThroughEveryTransition() = runBlocking {
        val permitted = MutableStateFlow(false)
        val bound = MutableStateFlow(false)
        val observed = AutoPasteReadiness.observe(permitted, bound)

        assertEquals(
            "A user who never granted the permission must be told exactly that",
            AutoPasteAvailability.NOT_PERMITTED,
            observed.first(),
        )

        permitted.value = true
        assertEquals(
            "Granted but not yet bound is the normal cold-start window, and it is not LIVE",
            AutoPasteAvailability.PERMITTED_NOT_RUNNING,
            observed.first(),
        )

        bound.value = true
        assertEquals(
            "Granted and bound is the only state that may report LIVE",
            AutoPasteAvailability.LIVE,
            observed.first(),
        )

        // The reported defect: the service dies, the Android setting still names it, and the screen
        // kept saying Ready. Only liveness changes here.
        bound.value = false
        assertEquals(
            "A service that died while the setting still names it must stop reporting LIVE. " +
                "Answering from the permission alone is issue #16.",
            AutoPasteAvailability.PERMITTED_NOT_RUNNING,
            observed.first(),
        )

        // A revoked permission outranks a binding that somehow survived it.
        bound.value = true
        permitted.value = false
        assertEquals(
            "A revoked permission must outrank a stale binding",
            AutoPasteAvailability.NOT_PERMITTED,
            observed.first(),
        )
    }
}
