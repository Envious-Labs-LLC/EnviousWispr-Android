package com.envi.wispr.ui

import android.app.AppOpsManager
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Product Outcome: when this fails, a user either loses the floating setup steps over Settings on a phone that
 * supports them, or gets a picture-in-picture attempt after switching picture-in-picture off for the app (#477).
 * Mode values are Android's published constants, written as literals so the expectation cannot share the subject's
 * machinery.
 */
class FloatingGuideEligibilityTest {
    @Test
    fun floatsOnlyWhenThePhoneSupportsItAndTheUserAllowsIt() {
        assertEquals(0, AppOpsManager.MODE_ALLOWED)
        val cases = listOf(
            Triple(true, 0, true), // supported, allowed
            Triple(false, 0, false), // no picture-in-picture on this phone (for example Android Go)
            Triple(true, 1, false), // MODE_IGNORED: the user switched it off for the app
            Triple(true, 2, false), // MODE_ERRORED: the permission could not be read
            Triple(true, 3, false), // MODE_DEFAULT: never treat an unresolved mode as allowed
        )
        for ((supported, mode, expected) in cases) {
            assertEquals("supported=$supported mode=$mode", expected, FloatingGuideEligibility.decide(supported, mode))
        }
    }
}
