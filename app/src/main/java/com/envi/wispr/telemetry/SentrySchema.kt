package com.envi.wispr.telemetry

import com.envi.wispr.asr.AsrFailureReason
import com.envi.wispr.audio.InputRouteKind
import com.envi.wispr.models.DeliveryFailureReason
import com.envi.wispr.models.DownloadState
import com.envi.wispr.models.ModelManifest
import com.envi.wispr.models.ModelSourceHost
import com.envi.wispr.polish.PolishContext
import com.envi.wispr.polish.PolishReason
import com.envi.wispr.providers.Provider
import com.envi.wispr.ui.TerminalReason
import com.envi.wispr.ui.TerminalResult
import com.envi.wispr.ui.TriggerSource

/**
 * What may reach Sentry, declared (#240, REF-07; `kotlin-patterns.md` RULE: no-content-in-diagnostics).
 * `SentryBootstrap.sanitize` asks this object about every string on every surface; nothing is accepted
 * for being short or for matching no deny pattern. A key maps to exactly one [Shape]: a closed set of the
 * values its producer can emit wherever the producer is finite, else a named validator for a dynamic
 * identifier. An undeclared key is dropped; a declared key with a value of the wrong shape keeps the key
 * with [PayloadSanitizer.REDACTED], so the gap is visible. `SentrySchemaTest` reads every telemetry call in
 * the source and fails naming a key or a breadcrumb that is not declared here.
 */
internal object SentrySchema {
    sealed interface Verdict {
        data class Keep(val value: Any) : Verdict
        data object Redact : Verdict
        data object Drop : Verdict
    }

    /** One declared value shape. */
    sealed interface Shape {
        fun accepts(value: Any): Boolean

        /** One of these strings exactly: a producer with a finite set of outputs. */
        class OneOf(val values: Set<String>) : Shape {
            override fun accepts(value: Any) = value is String && value in values
        }

        /** A string of this form, from the named producer. */
        class Matching(val producer: String, val pattern: Regex) : Shape {
            override fun accepts(value: Any) = value is String && pattern.matches(value)
        }

        data object Number : Shape {
            override fun accepts(value: Any) = value is Int || value is Long || value is Float || value is Double
        }

        data object Flag : Shape {
            override fun accepts(value: Any) = value is Boolean
        }
    }

    // ---- validators for dynamic identifiers, each naming its producer ------------------------------------

    private val UUID = Shape.Matching("UUID.randomUUID (take and install ids)", Regex("\\A[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\z"))
    private val PACKAGE = Shape.Matching("an Android package name from the accessibility event", Regex("\\A[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+\\z"))
    /** `javaClass.simpleName` of a caught Throwable: a class name that ends in Exception or Error. */
    private val THROWABLE_NAME = "[A-Z][A-Za-z0-9_$]{0,80}(Exception|Error)"
    private val ERROR_TYPE = Shape.Matching("Throwable.javaClass.simpleName", Regex("\\A$THROWABLE_NAME\\z"))
    /** `ReadAnswers.fallbackToken`: which reader failed, each with a fixed reason or `exception:<Throwable>`. */
    private val SETTINGS_FALLBACK = Shape.Matching(
        "SessionPreferencesSource.ReadAnswers.fallbackToken",
        Regex("\\A(settings|terms|both)(:(completed_without_value|timed_out|exception:$THROWABLE_NAME)){1,2}\\z"),
    )
    private val BUILD_NUMBER = Shape.Matching("BuildConfig.VERSION_CODE", Regex("\\A[0-9]{1,10}\\z"))

    // ---- finite producers ----------------------------------------------------------------------------------

    /** Every defect's semantic id; exhaustive over the sealed hierarchy, so a new defect will not compile here. */
    fun semanticIdOf(defect: AppDefect): String = when (defect) {
        is AppDefect.VadCallWedged, AppDefect.LocalPolishDeadline, AppDefect.PolishProtocolViolation,
        AppDefect.CaptureStillRunningAfterStop, AppDefect.CaptureReleaseWedged, is AppDefect.AsrDecodeFailed,
        AppDefect.AsrOverLimit, AppDefect.CleanupRecovered, AppDefect.LocalPolishFailed, AppDefect.PolishUnexpected,
        AppDefect.PolishWatchdogTimeout, AppDefect.PolishServiceUnavailable, AppDefect.PolishServiceDied,
        AppDefect.PolishCallFailed, AppDefect.HistorySaveTimedOut, is AppDefect.HistoryContractViolation,
        AppDefect.DebugProbe,
        -> defect.semanticId
    }

    /** One instance of every defect type; `SentrySchemaTest` fails naming a sealed subtype missing here. */
    val defects: List<AppDefect> = listOf(
        AppDefect.VadCallWedged("start"), AppDefect.LocalPolishDeadline, AppDefect.PolishProtocolViolation,
        AppDefect.CaptureStillRunningAfterStop, AppDefect.CaptureReleaseWedged, AppDefect.AsrDecodeFailed(null),
        AppDefect.AsrOverLimit, AppDefect.CleanupRecovered, AppDefect.LocalPolishFailed, AppDefect.PolishUnexpected,
        AppDefect.PolishWatchdogTimeout, AppDefect.PolishServiceUnavailable, AppDefect.PolishServiceDied,
        AppDefect.PolishCallFailed, AppDefect.HistorySaveTimedOut, AppDefect.HistoryContractViolation(null),
        AppDefect.DebugProbe,
    )

    /** The event messages a defect may carry: its semantic id and nothing else. */
    val eventMessages: Set<String> = defects.map(::semanticIdOf).toSet()

    /**
     * The fingerprints the pinned SDK sets itself on an ANR (`ApplicationExitInfoEventProcessor.AnrHintEnricher`
     * in sentry-android-core 8.57.0, read from its bytecode for #240): fixed words, never user text. Kept so the
     * ANR grouping the SDK chose is unchanged.
     */
    val sdkAnrFingerprints: Set<String> = setOf("{{ default }}", "system-frames-only-anr", "background-anr", "foreground-anr")

    /** The fingerprints that may leave: ours and the SDK's ANR words; any other is removed, and an absent one stays absent. */
    val fingerprints: Set<String> = defects.map { it.fingerprint }.toSet() + sdkAnrFingerprints

    /** Every breadcrumb this app writes, as category to message. */
    val breadcrumbs: Map<String, Set<String>> = mapOf(
        "take" to setOf(
            "admitted", "settings_fallback", "live", "stopped", "asr_done", "polish_done", "history_save_failed",
            "terminal", "insertion_handed_off",
        ),
        "insertion" to setOf("outcome"),
        "model_delivery" to setOf("terminal"),
        "history" to setOf("delivery_unknown_recovered"),
    )

    private val polishContexts: Set<String> =
        (listOf(PolishContext.Off, PolishContext.Local, PolishContext.CloudUnconfigured) +
            Provider.entries.flatMap { provider -> listOf(false, true).map { PolishContext.Cloud(provider, it) } })
            .map { it.encode() }.toSet()

    private val processTags = setOf("main", "asr", "audio", "polish", "vad")

    /** Every key a tag, an extra or a breadcrumb datum may carry, with its one shape. */
    val keys: Map<String, Shape> = mapOf(
        // Tags
        SentryBootstrap.TAG_PROCESS to Shape.OneOf(processTags),
        SentryBootstrap.TAG_BUILD_TYPE to Shape.OneOf(setOf("debug", "release")),
        SentryBootstrap.TAG_DISTINCT_ID to UUID,
        SentryBootstrap.TAG_TAKE_ID to UUID,
        SentryBootstrap.TAG_IDENTITY to Shape.OneOf(eventMessages),
        "pending_defect.build" to BUILD_NUMBER,
        // Extras and breadcrumb data
        "take_id" to UUID,
        "trigger_source" to Shape.OneOf(TriggerSource.entries.map { it.wire }.toSet()),
        "settings_fallback" to SETTINGS_FALLBACK,
        "route_kind" to Shape.OneOf(InputRouteKind.entries.map { it.name.lowercase() }.toSet()),
        "live_after_ms" to Shape.Number,
        "live_state" to Shape.OneOf(setOf("forced", "ready")),
        "capture_terminal" to Shape.OneOf(setOf("still_running", "max_duration", TakeFacts.MANUAL_ENDING, "silence", "failure")),
        "recording_s" to Shape.Number,
        "silence_stop_status" to Shape.OneOf(setOf("off", "preparing", "ready", "unavailable_before_ready", "lost_after_ready", "unknown")),
        "asr_ms" to Shape.Number,
        "asr_chars" to Shape.Number,
        "polish_reason" to Shape.OneOf(PolishReason.entries.map { it.name }.toSet()),
        "polish_ms" to Shape.Number,
        "polish_provider" to Shape.OneOf(polishContexts),
        "polish_status" to Shape.Number,
        "error_type" to ERROR_TYPE,
        "late" to Shape.Flag,
        "reason" to Shape.OneOf(TerminalReason.entries.map { it.name }.toSet() + DeliveryFailureReason.entries.map { it.wire }),
        "result" to Shape.OneOf(TerminalResult.entries.map { it.wire }.toSet() + InsertionResultKind.entries.map { it.stored }),
        "asr_failure_reason" to Shape.OneOf(AsrFailureReason.entries.map { it.name }.toSet()),
        "target_app" to PACKAGE,
        "model" to Shape.OneOf(ModelManifest.all.map { it.id }.toSet()),
        "outcome" to Shape.OneOf(DownloadState.entries.map { it.name.lowercase() }.toSet()),
        "source_host" to Shape.OneOf(ModelSourceHost.entries.map { it.wire }.toSet()),
        "shape" to Shape.OneOf(setOf("null", "mismatched", "blank", "v1_result", "v1_error")),
        "count" to Shape.Number,
        // A pending defect's detail: the capture release note or a VAD call name.
        "detail" to Shape.OneOf(setOf("capture_release", "start", "processBlock", "finish")),
    )

    /** The verdict for [value] under [key]. */
    fun judge(key: String, value: Any?): Verdict {
        val shape = keys[key] ?: return Verdict.Drop
        if (value == null) return Verdict.Drop
        return if (shape.accepts(value)) Verdict.Keep(value) else Verdict.Redact
    }

    /** A breadcrumb's category and message pass only as a declared pair; either alone is judged too. */
    fun breadcrumbCategory(category: String?): String? = category?.takeIf { it in breadcrumbs.keys } ?: category?.let { PayloadSanitizer.REDACTED }

    fun breadcrumbMessage(category: String?, message: String): String =
        if (breadcrumbs[category].orEmpty().contains(message)) message else PayloadSanitizer.REDACTED

    fun eventMessage(message: String): String = if (message in eventMessages) message else PayloadSanitizer.REDACTED

    // ---- code locations --------------------------------------------------------------------------------------

    /** A JVM class name, simple or qualified, with `$` nesting and synthetic lambda classes. */
    private val JVM_CLASS = Regex("\\A[A-Za-z_$][A-Za-z0-9_$]*(\\.[A-Za-z_$][A-Za-z0-9_$]*)*\\z")
    /** A JVM method: an identifier, `<init>`, `<clinit>`, or a synthetic `lambda$…$0` / `access$000` name. */
    private val JVM_METHOD = Regex("\\A(<init>|<clinit>|[A-Za-z_$][A-Za-z0-9_$-]*)\\z")
    /** A native symbol with no whitespace (mangled, or `name+0x10`). */
    private val NATIVE_SYMBOL = Regex("\\A[A-Za-z0-9_$.:<>~+@-]{1,512}\\z")
    /** A demangled C++ signature: a qualified name then a parameter list; the only symbol form with spaces. */
    private val CXX_SIGNATURE = Regex("\\A[A-Za-z0-9_$:<>~]+\\([A-Za-z0-9_$:<>~,*& \\[\\]]*\\)( const)?\\z")
    /** A source file name. */
    private val FILE_NAME = Regex("\\A[A-Za-z0-9_$.-]{1,128}\\.(kt|java|c|cc|cpp|cxx|h|hpp|so)\\z")
    /** A path with no whitespace (after the path rules rewrote the private roots to `[PATH]`). */
    private val PATH = Regex("\\A(\\[PATH\\]|/[\\x21-\\x7E]{1,511})\\z")
    /** A short identifier with no whitespace: a thread name, a mechanism type, a platform. */
    private val IDENT = Regex("\\A[A-Za-z0-9_.:#@$()\\[\\]-]{1,128}\\z")

    fun exceptionType(text: String): String = if (JVM_CLASS.matches(text)) text else PayloadSanitizer.REDACTED
    fun module(text: String): String = if (JVM_CLASS.matches(text)) text else PayloadSanitizer.REDACTED
    fun function(text: String): String =
        if (JVM_METHOD.matches(text) || NATIVE_SYMBOL.matches(text) || CXX_SIGNATURE.matches(text)) text else PayloadSanitizer.REDACTED
    fun fileName(text: String): String = if (FILE_NAME.matches(text)) text else PayloadSanitizer.REDACTED
    fun path(text: String): String = PayloadSanitizer.redactPatterns(text).let { if (it == PayloadSanitizer.REDACTED || PATH.matches(it)) it else PayloadSanitizer.REDACTED }
    fun identifier(text: String): String = if (IDENT.matches(text)) text else PayloadSanitizer.REDACTED

    /** A field of an approved typed context: a build property or an SDK-computed label, never with spaces. */
    private val CONTEXT_LABEL = Regex("\\A[\\x21-\\x7E]{1,128}\\z")
    fun contextLabel(text: String): String = if (CONTEXT_LABEL.matches(text)) PayloadSanitizer.redactPatterns(text) else PayloadSanitizer.REDACTED
}
