package com.envi.wispr.telemetry

import android.content.Context
import com.envi.wispr.debug.DebugLogger
import com.posthog.PostHog
import com.posthog.PostHogBeforeSend
import com.posthog.PostHogEvent
import com.posthog.android.PostHogAndroid
import com.posthog.android.PostHogAndroidConfig
import java.util.UUID

/**
 * PostHog, in the MAIN process only (issue #176, plan §3.2). Every option is read against the pinned
 * `posthog-android:3.67.0` / `posthog:6.40.0` sources.
 *
 * Identity: `getAnonymousId` is installed BEFORE `setup` and returns the install UUID. The SDK consults
 * it only when it has nothing persisted, so after setup the SDK's own ids are checked against the file
 * and a mismatch DISABLES analytics for this run rather than splitting one person in two (G1 D5a).
 * `identify` and `reset` are never called.
 *
 * Every SDK collector is off: lifecycle rows, screen views, deep links, push, element interactions,
 * session replay, surveys, feature-flag preload and its event, default person properties, exception and
 * native-crash capture. The app's own `app.launched` is the process-start marker. The one `beforeSend`
 * stamps `app`, `environment`, `app_version`, `app_build` and `process_run_id`, runs the volume policy
 * (which also reduces SDK context to the allowlist), then the sanitizer. It never touches the journal.
 */
object PostHogBootstrap {
    private const val TAG = "PostHogBootstrap"
    const val HOST = "https://us.i.posthog.com"
    const val APP_TAG = "enviouswispr-android"

    sealed class Outcome {
        object Enabled : Outcome()
        data class Disabled(val why: String) : Outcome()
    }

    fun start(context: Context, apiKey: String, installId: String, config: TelemetryConfig): Outcome {
        val android = PostHogAndroidConfig(apiKey, HOST).apply {
            captureApplicationLifecycleEvents = false
            captureDeepLinks = false
            captureScreenViews = false
            capturePushNotificationSubscriptions = false
            capturePushNotificationOpened = false
            // Element, rage and dead click capture do not exist on 3.67.0 (they arrived later on main);
            // an SDK bump must re-read the config and switch them off here.
            sessionReplay = false
            surveys = false
            preloadFeatureFlags = false
            sendFeatureFlagEvent = false
            setDefaultPersonProperties = false
            errorTrackingConfig.autoCapture = false
            errorTrackingConfig.captureNativeCrashes = false
            // An identity mismatch opts out for THIS run only; the SDK would otherwise persist the
            // opt-out and every later, correct launch would stay silent (code review round 1, F6).
            persistOptOut = false
            flushAt = 20
            flushIntervalSeconds = 30
            maxQueueSize = 1000
            getAnonymousId = { _ -> UUID.fromString(installId) }
            addBeforeSend(
                PostHogBeforeSend { event -> process(event, config) },
            )
        }
        PostHogAndroid.setup(context, android)
        val distinct = runCatching { PostHog.distinctId() }.getOrNull()
        val anonymous = runCatching { PostHog.getAnonymousId() }.getOrNull()
        if (distinct != installId || anonymous != installId) {
            // Never reconcile by minting: analytics stays off until a deliberate migration exists.
            DebugLogger.warn(TAG, "PostHog identity differs from the install id; analytics disabled for this run")
            PostHog.optOut()
            return Outcome.Disabled("identity mismatch")
        }
        return Outcome.Enabled
    }

    /** The EXACT body the PostHog `beforeSend` runs; SDK-free in its inputs so a test drives the bytes. */
    fun processProperties(name: String, properties: Map<String, Any?>, config: TelemetryConfig): Map<String, Any>? {
        val stamped = LinkedHashMap<String, Any?>(properties)
        stamped["app"] = APP_TAG
        stamped["environment"] = config.environment
        stamped["app_version"] = config.versionName
        stamped["app_build"] = config.appBuild
        stamped["process_run_id"] = config.processRunId
        val kept = when (val decision = TelemetryVolumePolicy.decide(name, stamped)) {
            TelemetryVolumePolicy.Decision.Drop -> return null
            is TelemetryVolumePolicy.Decision.Keep -> decision.properties
        }
        return PayloadSanitizer.sanitizeProperties(kept)
    }

    private fun process(event: PostHogEvent, config: TelemetryConfig): PostHogEvent? {
        val properties = processProperties(event.event, event.properties ?: emptyMap(), config) ?: return null
        return event.copy(properties = properties.toMutableMap())
    }

    fun capture(name: String, properties: Map<String, Any?>) {
        val nonNull = LinkedHashMap<String, Any>()
        properties.forEach { (k, v) -> if (v != null) nonNull[k] = v }
        PostHog.capture(name, properties = nonNull)
    }
}
