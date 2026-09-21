package com.envi.wispr.telemetry

import android.content.Context
import com.envi.wispr.BuildConfig
import com.envi.wispr.debug.DebugLogger
import com.envi.wispr.history.EnviousWisprDatabase
import io.sentry.Breadcrumb
import io.sentry.Sentry
import io.sentry.SentryEvent
import io.sentry.SentryLevel
import io.sentry.protocol.Message
import io.sentry.protocol.SentryId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import java.util.Date
import java.util.concurrent.atomic.AtomicReference

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
internal object Telemetry {
    private const val TAG = "Telemetry"

    @Volatile private var config: TelemetryConfig? = null
    @Volatile private var installId: String? = null
    @Volatile private var sentryOn = false
    @Volatile private var postHogOn = false
    @Volatile private var appContext: Context? = null
    @Volatile private var journalWriter: TakeJournalWriter? = null
    private val launchScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Every vendor call runs here, on one background worker in arrival order, never on the thread that
     * asked. Both SDKs do real work synchronously in their capture calls (PostHog builds the event and
     * runs `beforeSend`; Sentry applies scope, processors, `beforeSend` and builds the envelope), and the
     * askers are the main thread, binder callbacks and teardown (code review round 1, F3).
     */
    private val vendorCalls = Channel<() -> Unit>(Channel.UNLIMITED)

    init {
        launchScope.launch { for (call in vendorCalls) call() }
    }

    private fun postVendor(label: String, call: () -> Unit) {
        vendorCalls.trySend {
            runCatching(call).onFailure { DebugLogger.warn(TAG, "$label failed: ${it.javaClass.simpleName}") }
        }
    }

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
        val freshInstall = (identity as? InstallIdentity.Resolution.Available)?.minted == true
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
        if (cfg.isMainProcess) startMainProcessWork(app, cfg, freshInstall)
    }

    /** The take journal's one writer; null outside main and before bootstrap. */
    val journal: TakeJournalWriter? get() = journalWriter

    /** One UUID per process start, on the journal and on `app.launched`; null before bootstrap. */
    val processRunId: String? get() = config?.processRunId

    private val processStartedAtNanos = System.nanoTime()

    /** The onboarding rows' clock: seconds since THIS process started, in tenths, never a wall time. */
    fun secondsSinceProcessStart(): Double = (System.nanoTime() - processStartedAtNanos) / 100_000_000L / 10.0

    /**
     * Main only. The journal writer opens synchronously (building the Room handle touches no disk), so
     * a take admitted in the first millisecond of a service-only cold start still has a journal. Then,
     * off the main thread and in this order: `app.launched` leaves with the persisted settings; the
     * defects dying helpers left are converted; earlier runs' open takes become `dictation.interrupted`;
     * old endings are pruned. Each step is a limb: a failure in one does not stop the next, and none
     * of it touches a take.
     */
    private fun startMainProcessWork(app: Context, cfg: TelemetryConfig, freshInstall: Boolean) {
        val writer = runCatching {
            TakeJournalWriter(EnviousWisprDatabase.get(app).takeJournalDao(), cfg.processRunId, ::capture)
        }.onFailure { DebugLogger.warn(TAG, "Take journal unavailable: ${it.javaClass.simpleName}") }.getOrNull()
        journalWriter = writer
        launchScope.launch {
            if (postHogOn) {
                runCatching { capture(AppLaunchFacts.read(app, freshInstall)) }
                    .onFailure { DebugLogger.warn(TAG, "app.launched failed: ${it.javaClass.simpleName}") }
            }
            convertPendingDefects(app)
            writer?.recoverAndPrune()
        }
    }

    /**
     * Recovered `ready_for_insertion` History rows (the owner died between the save and the insertion
     * outcome) are insertion outcomes, not new dictations (G2 D4): one `insertion.terminal` per row,
     * keyed to its take through the journal when the association survived.
     */
    fun insertionsRecovered(transcriptIds: List<Long>) {
        if (!postHogOn || transcriptIds.isEmpty()) return
        val writer = journalWriter
        launchScope.launch {
            for (transcriptId in transcriptIds) {
                // A row with no journal association is not in the denominator and sends nothing.
                val takeId = writer?.takeIdForTranscript(transcriptId) ?: continue
                capture(
                    AnalyticsEvent.InsertionTerminal(
                        takeId = takeId, handoff = null, result = InsertionResultKind.INSERTION_INTERRUPTED,
                        route = null, targetApp = null, latencyMs = null, clipboard = null, recovered = true,
                    ),
                )
            }
        }
    }

    /** True when a row could leave; callers use it only to skip building a payload. */
    val analyticsEnabled: Boolean get() = postHogOn

    fun capture(event: AnalyticsEvent) {
        if (!postHogOn) return
        postVendor("capture") { PostHogBootstrap.capture(event.name, event.properties()) }
    }

    /**
     * A breadcrumb: free to record (in memory, travels only with a Sentry error). [data] values must be
     * tokens, numbers or booleans; the sanitizer rewrites anything else at send time.
     */
    fun breadcrumb(category: String, message: String, data: Map<String, Any?> = emptyMap()) {
        if (!sentryOn) return
        val snapshot = data.toMap()
        postVendor("breadcrumb") {
            val crumb = Breadcrumb(message)
            crumb.category = category
            crumb.level = SentryLevel.INFO
            snapshot.forEach { (k, v) -> if (v != null) crumb.setData(k, v) }
            Sentry.addBreadcrumb(crumb)
        }
    }

    /** A defect we own. The ONLY way an error reaches Sentry. */
    fun defect(defect: AppDefect, data: Map<String, Any?> = emptyMap()) {
        if (!sentryOn) return
        val snapshot = data.toMap()
        postVendor("defect capture") {
            Sentry.captureEvent(defectEvent(defect, snapshot, eventId = null, timestamp = null, tags = emptyMap()))
        }
    }

    /**
     * The take in flight in this process, set and cleared SYNCHRONOUSLY so an automatic crash (a Java
     * uncaught exception, which runs `beforeSend` on the crashing thread) reads the live value at send
     * time and never a queued one (code review round 2, F8). Compare-and-clear: an old take's postamble
     * cannot clear a newer take's id. The Sentry scope tag is a mirror for the SDK's own events.
     */
    private val liveTakeId = AtomicReference<String?>(null)

    /** The take an event should be tagged with when it names none of its own; read by `beforeSend`. */
    fun currentTakeId(): String? = liveTakeId.get()

    fun takeStarted(takeId: String) {
        liveTakeId.set(takeId)
        if (!sentryOn) return
        postVendor("take scope start") { Sentry.configureScope { it.setTag(SentryBootstrap.TAG_TAKE_ID, takeId) } }
    }

    fun takeEnded(takeId: String) {
        liveTakeId.compareAndSet(takeId, null)
        if (!sentryOn) return
        postVendor("take scope end") {
            if (liveTakeId.get() == null) Sentry.configureScope { it.removeTag(SentryBootstrap.TAG_TAKE_ID) }
        }
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
                // The extra is the explicit take `beforeSend` honours over the live one (F8).
                record.takeId?.let { event.setTag(SentryBootstrap.TAG_TAKE_ID, it); event.setExtra("take_id", it) }
                event.setExtra("detail", record.detail)
                Sentry.captureEvent(event)
            }.onFailure { DebugLogger.warn(TAG, "pending defect conversion failed: ${it.javaClass.simpleName}") }
        }
        // A temp left by a writer that died mid-write: its process is gone by the time main runs.
        runCatching { PendingDefects.cleanTemps(context) }
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
