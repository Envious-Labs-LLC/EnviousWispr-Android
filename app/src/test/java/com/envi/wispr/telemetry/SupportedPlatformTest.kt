package com.envi.wispr.telemetry

import com.envi.wispr.BuildConfig
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Product Outcome (#179): when this fails, Google's review sandbox (a fake Android 11 phone) counts as
 * installs and fatal crashes in PostHog and Sentry on every upload, and a daily report says something false.
 */
class SupportedPlatformTest {
    @Test fun aPhoneBelowTheBuildsMinSdkIsBelowMinimum() {
        assertTrue(SupportedPlatform.isBelowMinimum(sdkInt = BuildConfig.MIN_SDK - 1, minSdk = BuildConfig.MIN_SDK))
        assertTrue(SupportedPlatform.isBelowMinimum(sdkInt = 30, minSdk = BuildConfig.MIN_SDK)) // the sandbox: Android 11
    }

    @Test fun aPhoneAtOrAboveMinSdkIsNeverFiltered() {
        assertFalse(SupportedPlatform.isBelowMinimum(sdkInt = BuildConfig.MIN_SDK, minSdk = BuildConfig.MIN_SDK))
        assertFalse(SupportedPlatform.isBelowMinimum(sdkInt = 36, minSdk = BuildConfig.MIN_SDK))
    }

    @Test fun theThresholdIsTheBuildsMinSdkNotALiteral() {
        val gradle = File("build.gradle.kts").takeIf { it.exists() } ?: File("app/build.gradle.kts")
        val declared = Regex("""minSdk\s*=\s*(\d+)""").find(gradle.readText())!!.groupValues[1].toInt()
        assertEquals("BuildConfig.MIN_SDK must be the gradle minSdk", declared, BuildConfig.MIN_SDK)
    }

    @Test fun bootstrapAsksTheGuardWithTheBuildsOwnMinSdkBeforeAnyVendorStarts() {
        val source = (File("src/main/java/com/envi/wispr/telemetry/Telemetry.kt").takeIf { it.exists() }
            ?: File("app/src/main/java/com/envi/wispr/telemetry/Telemetry.kt")).readText()
        val guard = source.indexOf("SupportedPlatform.isBelowMinimum(Build.VERSION.SDK_INT, BuildConfig.MIN_SDK)")
        val sentry = source.indexOf("SentryBootstrap.start(")
        val postHog = source.indexOf("PostHogBootstrap.start(")
        assertTrue("the guard is missing from bootstrap", guard >= 0)
        assertTrue("the guard must come before Sentry starts", guard < sentry)
        assertTrue("the guard must come before PostHog starts", guard < postHog)
    }
}
