package com.envi.wispr.paste

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

/**
 * Whether auto-paste can actually place words in the user's editor right now.
 *
 * Two independent facts answer that question and they disagree in the state this type exists for:
 * the Android setting still names a crashed accessibility service, while the insertion path sees no
 * running service at all. Three cases rather than two, because the service is legitimately not yet
 * bound during the normal connect window at every cold start, and telling a user who already granted
 * the permission to grant it again is a wrong instruction on the screen they open when the product
 * looks broken. A fourth since #131: a setting cleared after the service died without an orderly stop
 * is not the setup a new user still has to do.
 */
internal enum class AutoPasteAvailability {
    /** The accessibility service is not enabled in Android settings. */
    NOT_PERMITTED,

    /**
     * Not enabled in Android settings, and the service's last recorded stop was not orderly (#131).
     * Words will not reach the field. Auto-paste was on, and it went off without the service
     * recording a clean stop: a force-stop clears the setting and kills the process before any
     * callback runs. It cannot say WHO switched it off (a user who turns it off after an unclean
     * death lands here too, because no live service is left to record the clean stop), so no
     * sentence built on it states a cause as certain.
     */
    SWITCHED_OFF_UNEXPECTEDLY,

    /** Enabled in settings, but no service instance is bound right now. */
    PERMITTED_NOT_RUNNING,

    /** Enabled and bound. Insertion can be attempted. */
    LIVE,
}

/**
 * The accessibility permission as the readiness join sees it: not yet read, or read with an answer.
 *
 * Three values rather than a Boolean because a surface starts before anything has been read, and a
 * default `false` there is indistinguishable from a verified revocation. Only a verified revocation may
 * report [AutoPasteAvailability.SWITCHED_OFF_UNEXPECTEDLY].
 */
internal enum class AccessibilityPermissionCheck {
    UNCHECKED,
    GRANTED,
    REVOKED,
    ;

    companion object {
        fun of(granted: Boolean): AccessibilityPermissionCheck = if (granted) GRANTED else REVOKED
    }
}

/** How the paste service last stopped, as its stop marker records it (#131). */
internal enum class LastServiceStop {
    /** No marker: the service has never connected on this install. */
    NEVER,

    /** `onUnbind` or `onDestroy` ran: turned off in settings, or an orderly teardown. */
    CLEAN,

    /** Armed on connect and never cleared: the process died with the service, or it is still running. */
    UNCLEAN,
}

/** The in-memory copy of the stop marker, read once per process off the main thread (#131). */
internal sealed interface StopMarkerState {
    /** Storage has not answered yet. */
    data object Loading : StopMarkerState

    /** Storage could not be read. Only the new distinction is lost; the other answers stand. */
    data object Unavailable : StopMarkerState

    data class Available(val stop: LastServiceStop) : StopMarkerState
}

/** Combines the permission fact, the binding fact and the stop marker. None owns the answer alone. */
internal object AutoPasteReadiness {
    /**
     * @param permission the Android `ENABLED_ACCESSIBILITY_SERVICES` answer, once it has been read.
     * @param serviceBound whether a live service instance published itself.
     * @param stopMarker how the service last stopped; only consulted once the permission is revoked.
     */
    fun evaluate(
        permission: AccessibilityPermissionCheck,
        serviceBound: Boolean,
        stopMarker: StopMarkerState,
    ): AutoPasteAvailability = when (permission) {
        // Nothing has been read, so nothing may be claimed: least capable, and never the new state.
        AccessibilityPermissionCheck.UNCHECKED -> initial
        // A revoked permission outranks a binding, so a service that is somehow still bound after
        // revocation can never report LIVE.
        AccessibilityPermissionCheck.REVOKED ->
            if (!serviceBound && stoppedUncleanly(stopMarker)) {
                AutoPasteAvailability.SWITCHED_OFF_UNEXPECTEDLY
            } else {
                AutoPasteAvailability.NOT_PERMITTED
            }
        AccessibilityPermissionCheck.GRANTED ->
            if (serviceBound) AutoPasteAvailability.LIVE else AutoPasteAvailability.PERMITTED_NOT_RUNNING
    }

    // A running service holds its marker armed (UNCLEAN) by design, which is why the caller asks only
    // when no instance is bound: then UNCLEAN means the last one died rather than stopped.
    private fun stoppedUncleanly(stopMarker: StopMarkerState): Boolean = when (stopMarker) {
        StopMarkerState.Loading,
        StopMarkerState.Unavailable,
        -> false
        is StopMarkerState.Available -> when (stopMarker.stop) {
            LastServiceStop.NEVER,
            LastServiceStop.CLEAN,
            -> false
            LastServiceStop.UNCLEAN -> true
        }
    }

    /**
     * What a surface shows before either source has answered.
     *
     * Named here rather than written at the call site so a view model never has to NAME an
     * availability. It cannot then produce one of its own, and in particular it has no way to reach
     * [AutoPasteAvailability.LIVE] except by asking [observe]. The value is the least capable state
     * on purpose: a cold start that guessed LIVE would reproduce issue #16 for the length of the
     * bind window.
     */
    val initial: AutoPasteAvailability = AutoPasteAvailability.NOT_PERMITTED

    /**
     * The derivation every readiness surface shows, as a flow so the WIRING is executable.
     *
     * [evaluate] being correct proves nothing about what reaches the screen: a view model that
     * combined these sources by hand could answer from the permission alone, or project the
     * combined answer back down afterwards, and the only guard against that was a source scan.
     * Owning the combine here makes the join itself something the fast gate can run.
     *
     * @param permission the Android `ENABLED_ACCESSIBILITY_SERVICES` answer over time.
     * @param serviceBound liveness pushed from the accessibility service lifecycle.
     * @param stopMarker the stop marker's in-memory snapshot, which changes without either of the others.
     */
    fun observe(
        permission: Flow<AccessibilityPermissionCheck>,
        serviceBound: Flow<Boolean>,
        stopMarker: Flow<StopMarkerState>,
    ): Flow<AutoPasteAvailability> =
        combine(permission, serviceBound, stopMarker) { checked, bound, marker ->
            evaluate(checked, bound, marker)
        }
}
