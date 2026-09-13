package com.envi.wispr.paste

import org.junit.Assert.assertEquals
import org.junit.Test

/** PRODUCT OUTCOME: which one write a dictation makes (#141). */
class InsertionRoutePolicyTest {
    @Test
    fun commitOnlyWhenTheSessionProvablyBelongsToThePinnedField() {
        assertEquals(InsertionRoute.COMMIT, InsertionRoutePolicy.select(commitEligible = true))
        assertEquals(InsertionRoute.PASTE, InsertionRoutePolicy.select(commitEligible = false))
    }
}
