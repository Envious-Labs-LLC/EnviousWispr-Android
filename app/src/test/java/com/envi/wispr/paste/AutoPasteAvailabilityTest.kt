package com.envi.wispr.paste

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * PRODUCT OUTCOME. When this fails the user is told auto-paste is ready while their words go to the
 * clipboard, or is told to grant a permission they already hold.
 *
 * Runs in the fast gate only because `evaluate` takes no `Context`; `readAppReadiness(Context)` is
 * unreachable from `app/src/test` (`android-testing-patterns.md`
 * FACT: the-two-source-sets-answer-different-questions).
 */
class AutoPasteAvailabilityTest {

    /** Issue #16: the setting string still names a crashed service, so this must never be LIVE. */
    @Test
    fun aPermittedServiceThatIsNotBoundIsNeverReportedAsLive() {
        assertEquals(
            AutoPasteAvailability.PERMITTED_NOT_RUNNING,
            AutoPasteReadiness.evaluate(permittedInSettings = true, serviceBound = false),
        )
    }

    @Test
    fun aPermittedAndBoundServiceIsLive() {
        assertEquals(
            AutoPasteAvailability.LIVE,
            AutoPasteReadiness.evaluate(permittedInSettings = true, serviceBound = true),
        )
    }

    @Test
    fun anUnpermittedServiceNeedsThePermissionNotAReconnect() {
        assertEquals(
            AutoPasteAvailability.NOT_PERMITTED,
            AutoPasteReadiness.evaluate(permittedInSettings = false, serviceBound = false),
        )
    }

    /** A binding left over from before the user revoked the permission cannot outvote the revocation. */
    @Test
    fun aStaleBindingCannotOutvoteARevokedPermission() {
        assertEquals(
            AutoPasteAvailability.NOT_PERMITTED,
            AutoPasteReadiness.evaluate(permittedInSettings = false, serviceBound = true),
        )
    }
}
