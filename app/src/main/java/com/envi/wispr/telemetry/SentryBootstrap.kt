package com.envi.wispr.telemetry

import android.content.Context
import io.sentry.Breadcrumb
import io.sentry.SentryEvent
import io.sentry.SentryOptions
import io.sentry.protocol.App
import io.sentry.protocol.Contexts
import io.sentry.protocol.Device
import io.sentry.protocol.Gpu
import io.sentry.protocol.OperatingSystem
import io.sentry.protocol.SentryRuntime
import io.sentry.protocol.SentryStackFrame
import io.sentry.protocol.SentryStackTrace
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
 * `beforeSend` and `beforeBreadcrumb` rewrite every surface against [SentrySchema] (#240). Native crash events
 * re-enter through the Java client and pass `beforeSend` too (`OutboxSender`, verified 2026-09-19);
 * non-event envelope items do not, which is why attachments stay off.
 */
internal object SentryBootstrap {
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

    /**
     * How the seam handles every text-bearing field of the pinned 8.57.0 types (#240). `SentrySchemaTest`
     * walks the SDK classes by reflection and fails naming any field missing here, so an SDK upgrade that adds
     * a text field cannot pass unexamined. `judged` = [SentrySchema] decides; `validated` = a code-location or
     * label validator; `cleared` = removed; `kept` = set by the SDK or our build config, never by user input;
     * `walked` = its members are listed in turn.
     */
    val HANDLED_FIELDS: Map<String, String> = mapOf(
        "SentryBaseEvent.eventId" to "kept", "SentryBaseEvent.contexts" to "walked", "SentryBaseEvent.sdk" to "kept",
        "SentryBaseEvent.request" to "cleared", "SentryBaseEvent.tags" to "judged", "SentryBaseEvent.release" to "kept",
        "SentryBaseEvent.environment" to "kept", "SentryBaseEvent.platform" to "validated", "SentryBaseEvent.user" to "cleared",
        "SentryBaseEvent.throwable" to "kept", "SentryBaseEvent.serverName" to "cleared", "SentryBaseEvent.dist" to "kept",
        "SentryBaseEvent.breadcrumbs" to "walked", "SentryBaseEvent.debugMeta" to "kept", "SentryBaseEvent.extra" to "judged",
        "SentryEvent.message" to "walked", "SentryEvent.logger" to "validated", "SentryEvent.threads" to "walked",
        "SentryEvent.exception" to "walked", "SentryEvent.transaction" to "cleared", "SentryEvent.fingerprint" to "judged",
        "SentryEvent.unknown" to "cleared", "SentryEvent.modules" to "cleared",
        "Message.formatted" to "judged", "Message.message" to "cleared", "Message.params" to "cleared", "Message.unknown" to "cleared",
        "SentryException.type" to "validated", "SentryException.value" to "cleared", "SentryException.module" to "validated",
        "SentryException.stacktrace" to "walked", "SentryException.mechanism" to "walked", "SentryException.unknown" to "cleared",
        "Mechanism.thread" to "kept", "Mechanism.type" to "validated", "Mechanism.description" to "cleared",
        "Mechanism.helpLink" to "cleared", "Mechanism.meta" to "cleared", "Mechanism.data" to "cleared", "Mechanism.unknown" to "cleared",
        "SentryThread.name" to "validated", "SentryThread.state" to "validated", "SentryThread.stacktrace" to "walked",
        "SentryThread.heldLocks" to "cleared", "SentryThread.unknown" to "cleared",
        "SentryStackTrace.frames" to "walked", "SentryStackTrace.registers" to "cleared",
        "SentryStackTrace.instructionAddressAdjustment" to "kept", "SentryStackTrace.unknown" to "cleared",
        "SentryStackFrame.preContext" to "cleared", "SentryStackFrame.postContext" to "cleared", "SentryStackFrame.vars" to "cleared",
        "SentryStackFrame.framesOmitted" to "kept", "SentryStackFrame.filename" to "validated", "SentryStackFrame.function" to "validated",
        "SentryStackFrame.module" to "validated", "SentryStackFrame.absPath" to "validated", "SentryStackFrame.contextLine" to "cleared",
        "SentryStackFrame._package" to "validated", "SentryStackFrame.platform" to "validated", "SentryStackFrame.imageAddr" to "validated",
        "SentryStackFrame.symbolAddr" to "validated", "SentryStackFrame.instructionAddr" to "validated", "SentryStackFrame.addrMode" to "validated",
        "SentryStackFrame.symbol" to "validated", "SentryStackFrame.unknown" to "cleared", "SentryStackFrame.rawFunction" to "validated",
        "SentryStackFrame.lock" to "cleared",
        "Breadcrumb.message" to "judged", "Breadcrumb.type" to "validated", "Breadcrumb.data" to "judged",
        "Breadcrumb.category" to "judged", "Breadcrumb.origin" to "validated", "Breadcrumb.unknown" to "cleared",
        "Contexts.internalStorage" to "walked", "Contexts.responseLock" to "kept", "SentryValues.values" to "walked",
        "Device.name" to "cleared", "Device.manufacturer" to "validated", "Device.brand" to "validated", "Device.family" to "validated",
        "Device.model" to "validated", "Device.modelId" to "validated", "Device.archs" to "validated", "Device.timezone" to "kept",
        "Device.id" to "validated", "Device.locale" to "validated", "Device.connectionType" to "validated",
        "Device.cpuDescription" to "validated", "Device.chipset" to "validated", "Device.unknown" to "cleared", "Device.bootTime" to "kept",
        "OperatingSystem.name" to "validated", "OperatingSystem.version" to "validated", "OperatingSystem.rawDescription" to "validated",
        "OperatingSystem.build" to "validated", "OperatingSystem.kernelVersion" to "validated", "OperatingSystem.unknown" to "cleared",
        "App.appIdentifier" to "validated", "App.appStartTime" to "kept", "App.deviceAppHash" to "validated", "App.buildType" to "validated",
        "App.appName" to "validated", "App.appVersion" to "validated", "App.appBuild" to "validated", "App.permissions" to "cleared",
        "App.viewNames" to "cleared", "App.startType" to "validated", "App.splitNames" to "cleared", "App.unknown" to "cleared",
        "SentryRuntime.name" to "validated", "SentryRuntime.version" to "validated", "SentryRuntime.rawDescription" to "validated",
        "SentryRuntime.unknown" to "cleared",
        "Gpu.name" to "validated", "Gpu.vendorId" to "validated", "Gpu.vendorName" to "validated", "Gpu.apiType" to "validated",
        "Gpu.version" to "validated", "Gpu.npotSupport" to "validated", "Gpu.unknown" to "cleared",
    )

    /** The typed contexts that stay, each with its approved fields validated; every other context is removed. */
    val APPROVED_CONTEXTS: Set<String> = setOf(Device.TYPE, OperatingSystem.TYPE, App.TYPE, SentryRuntime.TYPE, Gpu.TYPE)

    /**
     * The FINAL payload seam for an event (#240): every text-bearing field of the pinned SDK types is judged
     * by [SentrySchema], validated as a code location or label, or cleared ([HANDLED_FIELDS]). Nothing is kept
     * for being short: a string that is not a declared shape becomes [PayloadSanitizer.REDACTED] or goes.
     */
    fun sanitize(event: SentryEvent): SentryEvent {
        event.serverName = null
        event.request = null
        event.user = null
        event.transaction = null
        event.setModules(null)
        event.setUnknown(null)
        event.platform = event.platform?.let(SentrySchema::identifier)
        event.logger = event.logger?.let(SentrySchema::identifier)
        event.message?.let { message ->
            val formatted = message.formatted ?: message.message
            // Only a defect's own semantic id passes; any other message becomes the marker, never the words.
            event.message = Message().apply { this.formatted = formatted?.let(SentrySchema::eventMessage) }
        }
        // An absent fingerprint stays absent; ours stay; any other is removed, never replaced with a shared one.
        event.fingerprints = event.fingerprints?.filter { it in SentrySchema.fingerprints }?.ifEmpty { null }
        event.exceptions?.forEach { exception ->
            // Exception MESSAGES are always dropped, approved types included (G2 D7); the type and the
            // frames are what group and diagnose.
            exception.value = null
            exception.type = exception.type?.let(SentrySchema::exceptionType)
            exception.module = exception.module?.let(SentrySchema::module)
            exception.setUnknown(null)
            exception.mechanism?.let { mechanism ->
                mechanism.type = mechanism.type?.let(SentrySchema::identifier)
                mechanism.description = null
                mechanism.helpLink = null
                mechanism.meta = null
                mechanism.data = null
                mechanism.setUnknown(null)
            }
            sanitizeStack(exception.stacktrace)
        }
        event.threads?.forEach { thread ->
            thread.name = thread.name?.let(SentrySchema::identifier)
            thread.state = thread.state?.let(SentrySchema::identifier)
            thread.heldLocks = null
            thread.setUnknown(null)
            sanitizeStack(thread.stacktrace)
        }
        event.breadcrumbs?.forEach { sanitize(it) }
        // The take tag is decided HERE, at send time: an event that names its take (a defect's or a
        // converted note's `take_id` extra) keeps it when it is a take id; anything else, including an
        // automatic crash that outran the queued scope update, carries the take live right now, or none
        // (round 2, F8; #240: the extra is validated before it becomes a tag).
        val explicitTakeId = (event.getExtra("take_id") as? String)?.takeIf { SentrySchema.judge("take_id", it) is SentrySchema.Verdict.Keep }
        val liveTakeId = Telemetry.currentTakeId()?.takeIf { SentrySchema.judge("take_id", it) is SentrySchema.Verdict.Keep }
        val effectiveTakeId = explicitTakeId ?: liveTakeId
        if (effectiveTakeId == null) event.removeTag(TAG_TAKE_ID) else event.setTag(TAG_TAKE_ID, effectiveTakeId)
        event.tags?.let { tags -> event.tags = judged(tags).mapValues { (_, v) -> v.toString() }.toMutableMap() }
        event.extras?.let { extras -> event.extras = judged(extras).toMutableMap() }
        sanitizeContexts(event.contexts)
        return event
    }

    /** Keys and values through [SentrySchema]: undeclared keys dropped, a bad value under a declared key redacted. */
    private fun judged(map: Map<String, Any?>): Map<String, Any> {
        val out = LinkedHashMap<String, Any>()
        for ((key, value) in map) {
            when (val verdict = SentrySchema.judge(key, value)) {
                is SentrySchema.Verdict.Keep -> out[key] = verdict.value
                SentrySchema.Verdict.Redact -> out[key] = PayloadSanitizer.REDACTED
                SentrySchema.Verdict.Drop -> Unit
            }
        }
        return out
    }

    /**
     * A frame's location fields pass their validators (a native frame's `package` is the loaded library's
     * absolute path; a Java frame's `absPath` can be one, scrubbed by the path rules first), and the fields
     * that can carry a VALUE (local variables, source context lines, registers, locks) are dropped outright
     * (code review round 1, F1).
     */
    private fun sanitizeStack(stack: SentryStackTrace?) {
        stack ?: return
        stack.registers = null
        stack.setUnknown(null)
        stack.frames.orEmpty().forEach { frame -> sanitizeFrame(frame) }
    }

    private fun sanitizeFrame(frame: SentryStackFrame) {
        frame.absPath = frame.absPath?.let(SentrySchema::path)
        frame.`package` = frame.`package`?.let(SentrySchema::path)
        frame.filename = frame.filename?.let(SentrySchema::fileName)
        frame.function = frame.function?.let(SentrySchema::function)
        frame.rawFunction = frame.rawFunction?.let(SentrySchema::function)
        frame.symbol = frame.symbol?.let(SentrySchema::function)
        frame.module = frame.module?.let(SentrySchema::module)
        frame.platform = frame.platform?.let(SentrySchema::identifier)
        frame.imageAddr = frame.imageAddr?.let(SentrySchema::identifier)
        frame.symbolAddr = frame.symbolAddr?.let(SentrySchema::identifier)
        frame.instructionAddr = frame.instructionAddr?.let(SentrySchema::identifier)
        frame.addrMode = frame.addrMode?.let(SentrySchema::identifier)
        frame.contextLine = null
        frame.preContext = null
        frame.postContext = null
        frame.vars = null
        frame.lock = null
        frame.setUnknown(null)
    }

    /** Only the approved typed contexts stay, each field validated; every other context object is removed. */
    private fun sanitizeContexts(contexts: Contexts) {
        for (key in contexts.keys().toList()) {
            if (key !in APPROVED_CONTEXTS) contexts.remove(key)
        }
        contexts.device?.let { device ->
            device.name = null
            device.manufacturer = device.manufacturer?.let(SentrySchema::contextLabel)
            device.brand = device.brand?.let(SentrySchema::contextLabel)
            device.family = device.family?.let(SentrySchema::contextLabel)
            device.model = device.model?.let(SentrySchema::contextLabel)
            device.modelId = device.modelId?.let(SentrySchema::contextLabel)
            device.archs = device.archs?.mapNotNull { arch -> arch?.let(SentrySchema::contextLabel) }?.toTypedArray()
            device.id = device.id?.let(SentrySchema::contextLabel)
            device.locale = device.locale?.let(SentrySchema::contextLabel)
            device.connectionType = device.connectionType?.let(SentrySchema::contextLabel)
            device.cpuDescription = device.cpuDescription?.let(SentrySchema::contextLabel)
            device.chipset = device.chipset?.let(SentrySchema::contextLabel)
            device.setUnknown(null)
        }
        contexts.operatingSystem?.let { os ->
            os.name = os.name?.let(SentrySchema::contextLabel)
            os.version = os.version?.let(SentrySchema::contextLabel)
            os.rawDescription = os.rawDescription?.let(SentrySchema::contextLabel)
            os.build = os.build?.let(SentrySchema::contextLabel)
            os.kernelVersion = os.kernelVersion?.let(SentrySchema::contextLabel)
            os.setUnknown(null)
        }
        contexts.app?.let { app ->
            app.appIdentifier = app.appIdentifier?.let(SentrySchema::contextLabel)
            app.deviceAppHash = app.deviceAppHash?.let(SentrySchema::contextLabel)
            app.buildType = app.buildType?.let(SentrySchema::contextLabel)
            app.appName = app.appName?.let(SentrySchema::contextLabel)
            app.appVersion = app.appVersion?.let(SentrySchema::contextLabel)
            app.appBuild = app.appBuild?.let(SentrySchema::contextLabel)
            app.startType = app.startType?.let(SentrySchema::contextLabel)
            app.permissions = null
            app.viewNames = null
            app.splitNames = null
            app.setUnknown(null)
        }
        contexts.runtime?.let { runtime ->
            runtime.name = runtime.name?.let(SentrySchema::contextLabel)
            runtime.version = runtime.version?.let(SentrySchema::contextLabel)
            runtime.rawDescription = runtime.rawDescription?.let(SentrySchema::contextLabel)
            runtime.setUnknown(null)
        }
        contexts.gpu?.let { gpu ->
            gpu.name = gpu.name?.let(SentrySchema::contextLabel)
            gpu.vendorId = gpu.vendorId?.let(SentrySchema::contextLabel)
            gpu.vendorName = gpu.vendorName?.let(SentrySchema::contextLabel)
            gpu.apiType = gpu.apiType?.let(SentrySchema::contextLabel)
            gpu.version = gpu.version?.let(SentrySchema::contextLabel)
            gpu.npotSupport = gpu.npotSupport?.let(SentrySchema::contextLabel)
            gpu.setUnknown(null)
        }
    }

    /** A breadcrumb passes as a declared category and message pair with declared data; nothing else leaves. */
    fun sanitize(crumb: Breadcrumb): Breadcrumb {
        val category = crumb.category
        crumb.message?.let { crumb.message = SentrySchema.breadcrumbMessage(category, it) }
        crumb.category = SentrySchema.breadcrumbCategory(category)
        crumb.type = crumb.type?.let(SentrySchema::identifier)
        crumb.origin = crumb.origin?.let(SentrySchema::identifier)
        crumb.setUnknown(null)
        val data = crumb.data
        if (data.isNotEmpty()) {
            val cleaned = judged(data)
            data.keys.toList().forEach { crumb.removeData(it) }
            cleaned.forEach { (k, v) -> crumb.setData(k, v) }
        }
        return crumb
    }
}
