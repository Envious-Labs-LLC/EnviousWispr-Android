package com.envi.wispr.paste

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * PRODUCT OUTCOME. Runs the join the readiness surfaces actually show, over real flows.
 *
 * [AutoPasteReadiness.evaluate] being correct says nothing about what reaches a screen. The defect
 * in issue #16 was never the rule, it was the wiring: the permission fact answering a question only
 * liveness can answer. This drives every source through the production operator with ONE subscribed
 * observer and awaits each emission in turn, so a join that stops listening to a source (the stop
 * marker changes on its own, #131) fails here rather than shipping. A fresh `first()` per step would
 * pass against a join that only ever read one snapshot.
 */
class AutoPasteReadinessObserveTest {

    @Test
    fun oneObserverFollowsEverySourceThroughEveryTransition() = runBlocking {
        val permission = MutableStateFlow(AccessibilityPermissionCheck.UNCHECKED)
        val lifecycle = MutableStateFlow(PasteLifecycle())
        val emissions = Channel<AutoPasteAvailability>(Channel.UNLIMITED)
        val observer = launch(start = CoroutineStart.UNDISPATCHED) {
            AutoPasteReadiness.observe(permission, lifecycle).collect { emissions.send(it) }
        }
        suspend fun next(): AutoPasteAvailability = withTimeout(5_000) { emissions.receive() }

        assertEquals("Nothing read yet is the least capable answer", AutoPasteAvailability.NOT_PERMITTED, next())

        // The marker answers before the permission refresh does: an unread permission must not
        // qualify for the switched-off state, whatever the marker says.
        lifecycle.update { it.copy(marker = StopMarkerState.Available(LastServiceStop.UNCLEAN)) }
        assertEquals(
            "A loaded unclean marker with the permission still unread must not say switched off",
            AutoPasteAvailability.NOT_PERMITTED,
            next(),
        )

        permission.value = AccessibilityPermissionCheck.GRANTED
        assertEquals(
            "Granted but not yet bound is the normal cold-start window, and it is not LIVE",
            AutoPasteAvailability.PERMITTED_NOT_RUNNING,
            next(),
        )

        lifecycle.update { it.copy(bound = true) }
        assertEquals("Granted and bound is the only state that may report LIVE", AutoPasteAvailability.LIVE, next())

        // Issue #16: the service dies, the Android setting still names it. Only liveness changes.
        lifecycle.update { it.copy(bound = false) }
        assertEquals(
            "A service that died while the setting still names it must stop reporting LIVE",
            AutoPasteAvailability.PERMITTED_NOT_RUNNING,
            next(),
        )

        // Issue #131: the setting was cleared and the last stop was not orderly.
        permission.value = AccessibilityPermissionCheck.REVOKED
        assertEquals(
            "Revoked after an unclean stop is the switched-off state",
            AutoPasteAvailability.SWITCHED_OFF_UNEXPECTEDLY,
            next(),
        )

        // Marker-only transitions: neither permission nor binding moves.
        lifecycle.update { it.copy(marker = StopMarkerState.Available(LastServiceStop.CLEAN)) }
        assertEquals("A clean stop recorded later must be followed", AutoPasteAvailability.NOT_PERMITTED, next())
        lifecycle.update { it.copy(marker = StopMarkerState.Available(LastServiceStop.UNCLEAN)) }
        assertEquals("And back again", AutoPasteAvailability.SWITCHED_OFF_UNEXPECTEDLY, next())

        // A revoked permission outranks a binding that somehow survived it.
        lifecycle.update { it.copy(bound = true) }
        assertEquals("A revoked permission must outrank a stale binding", AutoPasteAvailability.NOT_PERMITTED, next())

        observer.cancel()
    }
}
