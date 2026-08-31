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
 * looks broken.
 */
enum class AutoPasteAvailability {
    /** The accessibility service is not enabled in Android settings. */
    NOT_PERMITTED,

    /** Enabled in settings, but no service instance is bound right now. */
    PERMITTED_NOT_RUNNING,

    /** Enabled and bound. Insertion can be attempted. */
    LIVE,
}

/** Combines the permission fact with the binding fact. Neither owns the answer alone. */
object AutoPasteReadiness {
    /**
     * @param permittedInSettings the Android `ENABLED_ACCESSIBILITY_SERVICES` answer.
     * @param serviceBound whether a live service instance published itself.
     */
    fun evaluate(permittedInSettings: Boolean, serviceBound: Boolean): AutoPasteAvailability = when {
        // A revoked permission outranks a binding, so a service that is somehow still bound after
        // revocation can never report LIVE.
        !permittedInSettings -> AutoPasteAvailability.NOT_PERMITTED
        serviceBound -> AutoPasteAvailability.LIVE
        else -> AutoPasteAvailability.PERMITTED_NOT_RUNNING
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
     * combined these two sources by hand could answer from the permission alone, or project the
     * combined answer back down afterwards, and the only guard against that was a source scan.
     * Owning the combine here makes the join itself something the fast gate can run.
     *
     * @param permittedInSettings the Android `ENABLED_ACCESSIBILITY_SERVICES` answer over time.
     * @param serviceBound liveness pushed from the accessibility service lifecycle.
     */
    fun observe(
        permittedInSettings: Flow<Boolean>,
        serviceBound: Flow<Boolean>,
    ): Flow<AutoPasteAvailability> =
        combine(permittedInSettings, serviceBound) { permitted, bound ->
            evaluate(permitted, bound)
        }
}
