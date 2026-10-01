package com.envi.wispr.paste

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * PRODUCT OUTCOME. When this fails the user is told auto-paste is ready while their words go to the
 * clipboard, is told to grant a permission they already hold, or is told Android switched auto-paste
 * off when it never did.
 *
 * Runs in the fast gate only because `evaluate` takes no `Context`; `readAppReadiness(Context)` is
 * unreachable from `app/src/test` (`android-testing-patterns.md`
 * FACT: the-two-source-sets-answer-different-questions).
 */
class AutoPasteAvailabilityTest {

    private val unclean = StopMarkerState.Available(LastServiceStop.UNCLEAN)

    /** Issue #16: the setting string still names a crashed service, so this must never be LIVE. */
    @Test
    fun aPermittedServiceThatIsNotBoundIsNeverReportedAsLive() {
        assertEquals(
            AutoPasteAvailability.PERMITTED_NOT_RUNNING,
            AutoPasteReadiness.evaluate(AccessibilityPermissionCheck.GRANTED, serviceBound = false, unclean),
        )
    }

    @Test
    fun aPermittedAndBoundServiceIsLive() {
        assertEquals(
            AutoPasteAvailability.LIVE,
            AutoPasteReadiness.evaluate(AccessibilityPermissionCheck.GRANTED, serviceBound = true, unclean),
        )
    }

    /** A binding left over from before the user revoked the permission cannot outvote the revocation. */
    @Test
    fun aStaleBindingCannotOutvoteARevokedPermission() {
        StopMarkerStates.forEach { marker ->
            assertEquals(
                "A bound service holds its marker armed, so $marker says nothing about a stop",
                AutoPasteAvailability.NOT_PERMITTED,
                AutoPasteReadiness.evaluate(AccessibilityPermissionCheck.REVOKED, serviceBound = true, marker),
            )
        }
    }

    /**
     * Issue #131: a force-stop clears the setting and kills the service before it can record a clean
     * stop. Only that combination is the new state; a user who never connected, or turned it off
     * themselves, gets today's answer.
     */
    @Test
    fun onlyARevokedPermissionAfterAnUncleanStopReadsAsSwitchedOff() {
        val expected = mapOf(
            StopMarkerState.Loading to AutoPasteAvailability.NOT_PERMITTED,
            StopMarkerState.Unavailable to AutoPasteAvailability.NOT_PERMITTED,
            StopMarkerState.Available(LastServiceStop.NEVER) to AutoPasteAvailability.NOT_PERMITTED,
            StopMarkerState.Available(LastServiceStop.CLEAN) to AutoPasteAvailability.NOT_PERMITTED,
            unclean to AutoPasteAvailability.SWITCHED_OFF_UNEXPECTEDLY,
        )
        assertEquals("every marker state has a row", StopMarkerStates.toSet(), expected.keys)
        expected.forEach { (marker, availability) ->
            assertEquals(
                "revoked, unbound, marker $marker",
                availability,
                AutoPasteReadiness.evaluate(AccessibilityPermissionCheck.REVOKED, serviceBound = false, marker),
            )
        }
    }

    /** Before the permission has been read, a default must never pass for a verified revocation. */
    @Test
    fun anUncheckedPermissionGivesTheInitialAnswerWhateverElseIsKnown() {
        StopMarkerStates.forEach { marker ->
            listOf(true, false).forEach { bound ->
                assertEquals(
                    "unchecked, bound=$bound, marker $marker",
                    AutoPasteAvailability.NOT_PERMITTED,
                    AutoPasteReadiness.evaluate(AccessibilityPermissionCheck.UNCHECKED, bound, marker),
                )
            }
        }
    }

    /** A loading or unreadable marker loses only the new distinction (plan §9). */
    @Test
    fun aMarkerThatHasNotAnsweredKeepsTheTwoInputAnswer() {
        listOf(StopMarkerState.Loading, StopMarkerState.Unavailable).forEach { marker ->
            assertEquals(AutoPasteAvailability.LIVE, AutoPasteReadiness.evaluate(AccessibilityPermissionCheck.GRANTED, true, marker))
            assertEquals(
                AutoPasteAvailability.PERMITTED_NOT_RUNNING,
                AutoPasteReadiness.evaluate(AccessibilityPermissionCheck.GRANTED, false, marker),
            )
            assertEquals(AutoPasteAvailability.NOT_PERMITTED, AutoPasteReadiness.evaluate(AccessibilityPermissionCheck.REVOKED, false, marker))
        }
    }

    private companion object {
        val StopMarkerStates: List<StopMarkerState> = listOf(StopMarkerState.Loading, StopMarkerState.Unavailable) +
            LastServiceStop.entries.map { StopMarkerState.Available(it) }
    }
}
