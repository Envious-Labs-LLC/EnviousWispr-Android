package com.envi.wispr.telemetry

import com.envi.wispr.BuildConfig
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Product Outcome (#179, #427): when this fails, Google's review sandbox (a fake Android 11 phone that reports a
 * newer API level) counts as installs and fatal crashes in PostHog and Sentry on every upload, and a daily
 * report says something false; or a real phone is filtered and its defects never arrive.
 */
class SupportedPlatformTest {
    @Test fun aPhoneBelowTheBuildsMinSdkIsBelowMinimum() {
        assertTrue(SupportedPlatform.isBelowMinimum(sdkInt = BuildConfig.MIN_SDK - 1, minSdk = BuildConfig.MIN_SDK, frameworkHasProbeApi = true))
        assertTrue(SupportedPlatform.isBelowMinimum(sdkInt = 30, minSdk = BuildConfig.MIN_SDK, frameworkHasProbeApi = false)) // Android 11, honest
    }

    @Test fun theSandboxReportingANewLevelOverAnOldFrameworkIsBelowMinimum() {
        // #427: SDK_INT passed AndroidX's `>= 33` branches while PackageManager lacked the API 33 overload.
        assertTrue(SupportedPlatform.isBelowMinimum(sdkInt = BuildConfig.MIN_SDK, minSdk = BuildConfig.MIN_SDK, frameworkHasProbeApi = false))
        assertTrue(SupportedPlatform.isBelowMinimum(sdkInt = 36, minSdk = BuildConfig.MIN_SDK, frameworkHasProbeApi = false))
    }

    @Test fun aPhoneAtOrAboveMinSdkIsNeverFiltered() {
        assertFalse(SupportedPlatform.isBelowMinimum(sdkInt = BuildConfig.MIN_SDK, minSdk = BuildConfig.MIN_SDK, frameworkHasProbeApi = true))
        assertFalse(SupportedPlatform.isBelowMinimum(sdkInt = 36, minSdk = BuildConfig.MIN_SDK, frameworkHasProbeApi = true))
    }

    @Test fun theProbeCanNeverFilterASupportedPhone() {
        // A probe newer than the floor would turn telemetry off on real phones between the two levels.
        assertTrue("PROBE_API must not exceed the gradle minSdk", SupportedPlatform.PROBE_API <= BuildConfig.MIN_SDK)
    }

    @Test fun theProbeResolvesAgainstARealFramework() {
        // The compile SDK's android.jar carries every public framework class and method signature; a misspelt
        // class, method or parameter type would filter every phone.
        assertTrue(SupportedPlatform.frameworkHasProbeApi())
    }

    @Test fun theThresholdIsTheBuildsMinSdkNotALiteral() {
        val gradle = File("build.gradle.kts").takeIf { it.exists() } ?: File("app/build.gradle.kts")
        val declared = Regex("""minSdk\s*=\s*(\d+)""").find(gradle.readText())!!.groupValues[1].toInt()
        assertEquals("BuildConfig.MIN_SDK must be the gradle minSdk", declared, BuildConfig.MIN_SDK)
    }

    @Test fun bootstrapAsksTheGuardWithTheBuildsOwnMinSdkBeforeAnyVendorStarts() {
        // Comments are stripped first, so a commented-out guard cannot satisfy this row.
        val source = (File("src/main/java/com/envi/wispr/telemetry/Telemetry.kt").takeIf { it.exists() }
            ?: File("app/src/main/java/com/envi/wispr/telemetry/Telemetry.kt")).readText()
            .replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), "")
            .replace(Regex("""//[^\n]*"""), "")
        val guardBlock = Regex(
            """if \(SupportedPlatform\.isBelowMinimum\(Build\.VERSION\.SDK_INT, BuildConfig\.MIN_SDK, SupportedPlatform\.frameworkHasProbeApi\(\)\)\) \{(?:[^}]|\$\{[^}]*\})*?\breturn\b(?:[^}]|\$\{[^}]*\})*\}""",
        ).find(source)
        assertTrue("bootstrap must return when the phone is below minSdk (condition not negated, return present)", guardBlock != null)
        val sentry = source.indexOf("SentryBootstrap.start(")
        val postHog = source.indexOf("PostHogBootstrap.start(")
        val identity = source.indexOf("InstallIdentity.resolve(")
        assertTrue("the guard must come before identity is minted", guardBlock!!.range.first < identity)
        assertTrue("the guard must come before Sentry starts", guardBlock.range.first < sentry)
        assertTrue("the guard must come before PostHog starts", guardBlock.range.first < postHog)
    }

    @Test fun thereIsExactlyOneStartSiteForEachVendorAndNoManifestAutoInit() {
        val root = File("src/main").takeIf { it.exists() } ?: File("app/src/main")
        val kotlin = root.walkTopDown().filter { it.extension == "kt" }.map { it.readText() }.toList()
        fun sites(needle: String) = kotlin.sumOf { Regex(Regex.escape(needle)).findAll(it).count() }
        assertEquals("Sentry starts from one place", 1, sites("SentryAndroid.init("))
        assertEquals("PostHog starts from one place", 1, sites("PostHogAndroid.setup("))
        val manifest = File(root, "AndroidManifest.xml").readText()
        assertTrue("Sentry must not auto-init from the manifest", manifest.contains("""io.sentry.auto-init" android:value="false""""))
    }
}
