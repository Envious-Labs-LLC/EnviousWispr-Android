package com.envi.wispr.telemetry

import android.content.Context
import io.sentry.Breadcrumb
import io.sentry.SentryEvent
import io.sentry.SentryOptions
import io.sentry.android.core.SentryAndroid
import io.sentry.android.core.SentryAndroidOptions
import io.sentry.protocol.Message
import java.io.File

/**
 * Sentry, once per PROCESS (issue #176, plan §3.2). The manifest sets `io.sentry.auto-init=false`, because
 * the SDK's content-provider init runs in the default process only and would leave `:audio`, `:asr`,
 * `:vad` and `:polish` uncovered; every process boots here from `Application.onCreate`, with its OWN
 * cache directory (`cacheDir/sentry-<process>`), since the SDK's cache holds fixed-name session files and
 * two live instances on one directory fight (G1 D1).
 *
 * Every option is read against the pinned `sentry-android:8.57.0` sources. Crashes (Java and NDK) in
 * every process; ANR and release-health sessions in main only; no automatic breadcrumbs, no tracing, no
 * profiling, no replay, no screenshots, no view hierarchy, no attachments. Retained on purpose:
 * `collectAdditionalContext` (device and OS context, content-free) and `enableRootCheck`.
 *
 * `beforeSend` and `beforeBreadcrumb` run `PayloadSanitizer` on every surface. Native crash events
 * re-enter through the Java client and pass `beforeSend` too (`OutboxSender`, verified 2026-09-19);
 * non-event envelope items do not, which is why attachments stay off.
 */
object SentryBootstrap {
    const val TAG_PROCESS = "app.process"
    const val TAG_BUILD_TYPE = "app.build_type"
    const val TAG_DISTINCT_ID = "analytics.distinct_id"
    const val TAG_TAKE_ID = "dictation.take_id"
    const val TAG_IDENTITY = "error.identity"

    fun start(context: Context, dsn: String, config: TelemetryConfig, installId: String?) {
        SentryAndroid.init(context) { options: SentryAndroidOptions ->
            options.dsn = dsn
            options.release = config.release
            options.dist = config.appBuild.toString()
            options.environment = config.environment
            options.cacheDirPath = File(context.cacheDir, "sentry-" + config.processTag).absolutePath
            options.isSendDefaultPii = false
            options.isEnableAutoSessionTracking = config.isMainProcess
            options.isAnrEnabled = config.isMainProcess
            options.isEnableNdk = true
            options.isEnableActivityLifecycleBreadcrumbs = false
            options.isEnableAppLifecycleBreadcrumbs = false
            options.isEnableSystemEventBreadcrumbs = false
            options.isEnableAppComponentBreadcrumbs = false
            options.isEnableNetworkEventBreadcrumbs = false
            options.isEnableUserInteractionBreadcrumbs = false
            options.isEnableUserInteractionTracing = false
            options.tracesSampleRate = null
            options.isEnableAutoActivityLifecycleTracing = false
            options.isEnableFramesTracking = false
            options.isEnableAppStartProfiling = false
            options.profilesSampleRate = null
            options.isAttachScreenshot = false
            options.isAttachViewHierarchy = false
            options.isAttachAnrThreadDump = false
            options.isAttachThreads = false
            options.isAttachStacktrace = true
            options.sessionReplay.sessionSampleRate = null
            options.sessionReplay.onErrorSampleRate = null
            options.isCollectAdditionalContext = true
            options.isEnableRootCheck = true
            options.beforeSend = SentryOptions.BeforeSendCallback { event, _ -> sanitize(event) }
            options.beforeBreadcrumb = SentryOptions.BeforeBreadcrumbCallback { crumb, _ -> sanitize(crumb) }
            options.setTag(TAG_PROCESS, config.processTag)
            options.setTag(TAG_BUILD_TYPE, if (config.environment == TelemetryConfig.DEVELOPMENT) "debug" else "release")
            if (installId != null) options.setTag(TAG_DISTINCT_ID, installId)
        }
    }

    /** The FINAL payload seam for an event: every surface that can carry text is rewritten here. */
    fun sanitize(event: SentryEvent): SentryEvent {
        event.serverName = null
        event.message?.let { message ->
            val formatted = message.formatted ?: message.message
            event.message = Message().apply {
                // Only a closed diagnostic passes; free prose becomes the marker, never the words.
                this.formatted = formatted?.let(PayloadSanitizer::sanitizeFreeText)
            }
        }
        event.exceptions?.forEach { exception ->
            // Exception MESSAGES are always dropped, approved types included (G2 D7); the type and the
            // frames are what group and diagnose.
            exception.value = null
            exception.mechanism?.data?.let { data -> data.keys.toList().forEach { data.remove(it) } }
        }
        event.breadcrumbs?.forEach { sanitize(it) }
        event.tags?.let { tags -> event.tags = tags.mapValues { (_, v) -> PayloadSanitizer.sanitizeFreeText(v) } }
        event.extras?.let { extras -> event.extras = PayloadSanitizer.sanitizeFreeMap(extras).toMutableMap() }
        event.request = null
        // The SDK's typed contexts (device, OS, app) are content-free by construction and carry no user
        // text without `sendDefaultPii`; a MAP-valued context is ours or free-form and is walked.
        val contexts = event.contexts
        for (entry in contexts.entrySet().toList()) {
            val value = entry.value
            if (value is Map<*, *>) {
                @Suppress("UNCHECKED_CAST")
                contexts.put(entry.key, PayloadSanitizer.sanitizeFreeMap(value as Map<String, Any?>))
            }
        }
        return event
    }

    fun sanitize(crumb: Breadcrumb): Breadcrumb {
        crumb.message?.let { crumb.message = PayloadSanitizer.sanitizeFreeText(it) }
        val data = crumb.data
        if (data.isNotEmpty()) {
            val cleaned = PayloadSanitizer.sanitizeFreeMap(data)
            data.keys.toList().forEach { crumb.removeData(it) }
            cleaned.forEach { (k, v) -> crumb.setData(k, v ?: return@forEach) }
        }
        return crumb
    }
}
