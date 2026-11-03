package com.envi.wispr.ui

import android.app.AppOpsManager
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Drift Guard: pins the feature and app-operation decision table behind the floating setup guide (#477). It does not
 * verify Android's permission wiring, picture-in-picture entry or what the user sees. Mode values are Android's
 * published constants, written as literals so the expectation cannot share the subject's machinery.
 */
class FloatingGuideEligibilityTest {
    @Test
    fun floatsOnlyWhenThePhoneSupportsItAndTheAppOperationIsAllowed() {
        assertEquals(0, AppOpsManager.MODE_ALLOWED)
        val cases = listOf(
            Triple(true, 0, true), // MODE_ALLOWED on a phone with picture-in-picture
            Triple(false, 0, false), // no picture-in-picture on this phone (for example Android Go)
            Triple(true, 1, false), // MODE_IGNORED: denied
            Triple(true, 2, false), // MODE_ERRORED: denied (Android's switch-off for the app) or the read-failure fallback
            Triple(true, 3, false), // MODE_DEFAULT: never treat an unresolved mode as allowed
        )
        for ((supported, mode, expected) in cases) {
            assertEquals("supported=$supported mode=$mode", expected, FloatingGuideEligibility.decide(supported, mode))
        }
    }
}
