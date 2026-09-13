package com.envi.wispr.paste

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * HARNESS CONTRACT: the exact shape of the one log line a field report is read from (#141). The line
 * carries no user content by construction: the builder has no String payload parameter.
 */
class InsertionOutcomeLineTest {
    @Test
    fun formatsTheExactTemplate() {
        assertEquals(
            "insertion api=36 route=PASTE written=true returned=TRUE evidence=NODE outcome=VERIFIED " +
                "attempts=1 ms=42 overrun=false target=com.google.android.gm",
            InsertionOutcomeLine.format(
                api = 36,
                route = InsertionRoute.PASTE,
                written = true,
                returned = InsertionAttempt.Returned.TRUE,
                evidence = InsertionAttempt.Evidence.NODE,
                outcome = InsertionOutcomeLine.Outcome.VERIFIED,
                attempts = 1,
                elapsedMs = 42L,
                overrun = false,
                targetPackage = "com.google.android.gm",
            ),
        )
        assertEquals(
            "insertion api=33 route=NONE written=false returned=NONE evidence=NONE outcome=NEVER_RETURNED " +
                "attempts=20 ms=2500 overrun=false target=none",
            InsertionOutcomeLine.format(
                api = 33,
                route = null,
                written = false,
                returned = InsertionAttempt.Returned.NONE,
                evidence = InsertionAttempt.Evidence.NONE,
                outcome = InsertionOutcomeLine.Outcome.NEVER_RETURNED,
                attempts = 20,
                elapsedMs = 2_500L,
                overrun = false,
                targetPackage = null,
            ),
        )
    }
}
