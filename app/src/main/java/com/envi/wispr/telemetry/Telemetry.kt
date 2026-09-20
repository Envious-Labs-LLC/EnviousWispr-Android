package com.envi.wispr.telemetry

import android.content.Context
import com.envi.wispr.BuildConfig
import com.envi.wispr.debug.DebugLogger
import io.sentry.Breadcrumb
import io.sentry.Sentry
import io.sentry.SentryEvent
import io.sentry.SentryLevel
import io.sentry.protocol.Message
import io.sentry.protocol.SentryId
import java.util.Date

/**
 * The one door the app talks to telemetry through (issue #176, plan §3.2). Every method is a LIMB:
 * it never throws, and it is a no-op before [bootstrap], with empty keys, or when the install identity
 * could not be resolved. Nothing here may delay or fail a dictation (`architecture-rules.md`
 * heart-and-limbs). Nothing here accepts a free string as an event or an error: events are
 * [AnalyticsEvent], defects are [AppDefect], breadcrumb data is a closed map of tokens and numbers.
 *
 * Bootstrap runs in EVERY process from `ModelBootstrapApplication.onCreate`, above its main-process
 * gate. Sentry starts everywhere; PostHog and `app.launched` only in main; pending defects left by a
 * dying helper are converted only by main.
 */
object Telemetry {
    private const val TAG = "Telemetry"

    @Volatile private var config: TelemetryConfig? = null
    @Volatile private var installId: String? = null
    @Volatile private var sentryOn = false
    @Volatile private var postHogOn = false
    @Volatile private var appContext: Context? = null

    /** What this process ended up with; read by tests and the debug screen, never by product code. */
    data class Status(val installId: String?, val sentry: Boolean, val postHog: Boolean, val environment: String?)

    fun status(): Status = Status(installId, sentryOn, postHogOn, config?.environment)

    /** Per-process. Returns quickly: no network, no database; the identity file is the only I/O. */
    fun bootstrap(context: Context) {
        val app = context.applicationContext
        appContext = app
        val cfg = runCatching { TelemetryConfig.read(app) }.getOrElse {
            DebugLogger.warn(TAG, "Telemetry config unreadable: ${it.javaClass.simpleName}")
            return
        }
        config = cfg
        val identity = InstallIdentity.resolve(app)
        val id = (identity as? InstallIdentity.Resolution.Available)?.id
        installId = id
        val dsn = BuildConfig.TELEMETRY_SENTRY_DSN
        val key = BuildConfig.TELEMETRY_POSTHOG_KEY
        if (dsn.isNotBlank() && id != null) {
            sentryOn = runCatching { SentryBootstrap.start(app, dsn, cfg, id); true }
                .getOrElse { DebugLogger.warn(TAG, "Sentry did not start: ${it.javaClass.simpleName}"); false }
        }
        if (cfg.isMainProcess && key.isNotBlank() && id != null) {
            postHogOn = runCatching { PostHogBootstrap.start(app, key, id, cfg) == PostHogBootstrap.Outcome.Enabled }
                .getOrElse { DebugLogger.warn(TAG, "PostHog did not start: ${it.javaClass.simpleName}"); false }
        }
        DebugLogger.log(TAG, "Telemetry bootstrap: process=${cfg.processTag} sentry=$sentryOn posthog=$postHogOn identity=${id != null}")
    }

    /** True when a row could leave; callers use it only to skip building a payload. */
    val analyticsEnabled: Boolean get() = postHogOn

    fun capture(event: AnalyticsEvent) {
        if (!postHogOn) return
        runCatching { PostHogBootstrap.capture(event.name, event.properties()) }
            .onFailure { DebugLogger.warn(TAG, "capture failed: ${it.javaClass.simpleName}") }
    }

    /**
     * A breadcrumb: free to record (in memory, travels only with a Sentry error). [data] values must be
     * tokens, numbers or booleans; the sanitizer rewrites anything else at send time.
     */
    fun breadcrumb(category: String, message: String, data: Map<String, Any?> = emptyMap()) {
        if (!sentryOn) return
        runCatching {
            val crumb = Breadcrumb(message)
            crumb.category = category
            crumb.level = SentryLevel.INFO
            data.forEach { (k, v) -> if (v != null) crumb.setData(k, v) }
            Sentry.addBreadcrumb(crumb)
        }.onFailure { DebugLogger.warn(TAG, "breadcrumb failed: ${it.javaClass.simpleName}") }
    }

    /** A defect we own. The ONLY way an error reaches Sentry. */
    fun defect(defect: AppDefect, data: Map<String, Any?> = emptyMap()) {
        if (!sentryOn) return
        runCatching {
            Sentry.captureEvent(defectEvent(defect, data, eventId = null, timestamp = null, tags = emptyMap()))
        }.onFailure { DebugLogger.warn(TAG, "defect capture failed: ${it.javaClass.simpleName}") }
    }

    /**
     * The take id on the process scope while a take is in flight, so every error in this process names
     * it. Cleared with compare-and-clear: an old take's postamble cannot clear a newer take's tag.
     */
    @Volatile private var scopedTakeId: String? = null

    fun takeStarted(takeId: String) {
        scopedTakeId = takeId
        if (!sentryOn) return
        runCatching { Sentry.configureScope { it.setTag(SentryBootstrap.TAG_TAKE_ID, takeId) } }
    }

    fun takeEnded(takeId: String) {
        if (scopedTakeId != takeId) return
        scopedTakeId = null
        if (!sentryOn) return
        runCatching { Sentry.configureScope { it.removeTag(SentryBootstrap.TAG_TAKE_ID) } }
    }

    /**
     * A defect a DYING process cannot send: written as a record for main to convert. Never waits for
     * the network; never runs on the caller's watchdog executor (the caller's responsibility).
     */
    fun recordPendingDefect(context: Context, defect: AppDefect, detail: String, takeId: String?): Boolean {
        val cfg = config ?: return false
        val record = PendingDefects.Record(
            eventId = PendingDefects.newEventId(),
            timestampMs = System.currentTimeMillis(),
            processName = cfg.processTag,
            appBuild = cfg.appBuild,
            installId = installId,
            takeId = takeId,
            fingerprint = defect.fingerprint,
            semanticId = defect.semanticId,
            detail = detail,
        )
        return PendingDefects.write(context, record)
    }

    /** Main only, at most once per run: convert what dying helpers left, with THEIR metadata. */
    fun convertPendingDefects(context: Context) {
        val cfg = config ?: return
        if (!cfg.isMainProcess || !sentryOn) return
        val records = runCatching { PendingDefects.readAll(context, System.currentTimeMillis()) }.getOrDefault(emptyList())
        for (record in records) {
            runCatching {
                val event = SentryEvent(Date(record.timestampMs))
                event.eventId = SentryId(record.eventId)
                event.level = SentryLevel.ERROR
                event.fingerprints = listOf(record.fingerprint)
                event.message = Message().apply { formatted = record.semanticId }
                event.setTag(SentryBootstrap.TAG_IDENTITY, record.semanticId)
                event.setTag(SentryBootstrap.TAG_PROCESS, record.processName)
                event.setTag("pending_defect.build", record.appBuild.toString())
                record.installId?.let { event.setTag(SentryBootstrap.TAG_DISTINCT_ID, it) }
                record.takeId?.let { event.setTag(SentryBootstrap.TAG_TAKE_ID, it) }
                event.setExtra("detail", record.detail)
                Sentry.captureEvent(event)
            }.onFailure { DebugLogger.warn(TAG, "pending defect conversion failed: ${it.javaClass.simpleName}") }
        }
    }

    internal fun defectEvent(
        defect: AppDefect,
        data: Map<String, Any?>,
        eventId: String?,
        timestamp: Date?,
        tags: Map<String, String>,
    ): SentryEvent {
        val event = if (timestamp != null) SentryEvent(timestamp) else SentryEvent(defect.cause)
        eventId?.let { event.eventId = SentryId(it) }
        event.level = SentryLevel.ERROR
        // The identity we chose, never the exception's class or the enum's ordinal (macOS #1524).
        event.fingerprints = listOf(defect.fingerprint)
        event.message = Message().apply { formatted = defect.semanticId }
        event.setTag(SentryBootstrap.TAG_IDENTITY, defect.semanticId)
        tags.forEach { (k, v) -> event.setTag(k, v) }
        data.forEach { (k, v) -> if (v != null) event.setExtra(k, v) }
        return event
    }
}
