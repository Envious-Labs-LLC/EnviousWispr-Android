package com.envi.wispr.telemetry

/**
 * A defect OUR code owns, with an identity we chose (issue #176; the Mac's `StableSentryErrorIdentity`,
 * born of #1524 where a Swift enum's declaration ordinal re-pointed three live Sentry issues).
 *
 * `fingerprint` is the Sentry grouping key and is PINNED forever: `AppDefectTest` compares every member
 * to a committed literal snapshot, so a rename is a test failure, never a silent regroup. `semanticId`
 * is the `error.identity` tag, metadata only, safe to rename. `Telemetry.defect` accepts ONLY this type,
 * so an arbitrary exception cannot reach Sentry as an error: user, network, provider and OS conditions
 * are breadcrumbs plus PostHog rows (`TelemetryChannels`).
 *
 * The `cause` is attached for its TYPE and frames; its message is dropped by `PayloadSanitizer`.
 */
internal sealed class AppDefect(val fingerprint: String, val semanticId: String, val cause: Throwable? = null) {
    /** The silence detector's synchronous call passed its deadline; the process is about to kill itself. */
    class VadCallWedged(val callName: String) : AppDefect("vad_call_wedged", "vad.call_wedged")

    /** A local polish generation exceeded its deadline; reported ONCE, by main, from the typed outcome. */
    object LocalPolishDeadline : AppDefect("local_polish_deadline", "polish.local_deadline")

    /** The polish engine's reply was null, misnamed or malformed for the request the owner made. */
    object PolishProtocolViolation : AppDefect("polish_protocol_violation", "polish.protocol_violation")

    /** Capture reported it stopped, then said it was still running: our own protocol. */
    object CaptureStillRunningAfterStop : AppDefect("capture_still_running_after_stop", "capture.still_running_after_stop")

    /** A recorder was never released, so a production start ended the capture process to recover (#213). */
    object CaptureReleaseWedged : AppDefect("capture_release_wedged", "capture.release_wedged")

    /** The speech decoder call itself threw. */
    class AsrDecodeFailed(cause: Throwable?) : AppDefect("asr_decode_failed", "asr.decode_failed", cause)

    /** The capture ceiling the capture process enforces was exceeded anyway. */
    object AsrOverLimit : AppDefect("asr_over_limit", "asr.over_limit")

    /** Our deterministic cleanup failed its own safety check and the raw text shipped instead. */
    object CleanupRecovered : AppDefect("cleanup_recovered", "polish.cleanup_recovered")

    /** The local polish runtime failed or refused work after being poisoned. */
    object LocalPolishFailed : AppDefect("local_polish_failed", "polish.local_failed")

    /** The polish engine hit a state it never expected (a duplicate registration, an unexpected throw). */
    object PolishUnexpected : AppDefect("polish_unexpected", "polish.unexpected")

    /** The owner's end-to-end reply budget for polish expired. */
    object PolishWatchdogTimeout : AppDefect("polish_watchdog_timeout", "polish.watchdog_timeout")

    /** The polish service refused its bind, or was bound and never connected before speech answered; the take published the deterministic text (#234). */
    object PolishServiceUnavailable : AppDefect("polish_service_unavailable", "polish.service_unavailable")

    /** The stored polish policy could not be read at take start; the take ran on the last read policy or published the deterministic text (#278). */
    object PolishPolicyUnreadable : AppDefect("polish_policy_unreadable", "polish.policy_unreadable")

    /** The History write queue refused writes: the database is stalled past its backlog (#292). Once per episode. */
    object HistoryQueueOverloaded : AppDefect("history_queue_overloaded", "history.queue_overloaded")

    /** A take's start preparation (the vocabulary matcher) threw or missed its bound; the take started on the fallback (#290). */
    object TakePreparationFailed : AppDefect("take_preparation_failed", "take.preparation_failed")

    /** The polish process died while a take was live; the take published the deterministic text (#234). */
    object PolishServiceDied : AppDefect("polish_service_died", "polish.service_died")

    /** The polish request call itself threw; the take published the deterministic text (#234). */
    object PolishCallFailed : AppDefect("polish_call_failed", "polish.call_failed")

    /** The History save did not answer within the owner's bound; the words went to the clipboard (#235). */
    object HistorySaveTimedOut : AppDefect("history_save_timed_out", "history.save_timed_out")

    /** A Room write failed for an invalid statement or a schema contract, never for storage being full. */
    class HistoryContractViolation(cause: Throwable?) : AppDefect("history_contract_violation", "history.contract_violation", cause)

    /**
     * A step of the owner's polish preparation (vocabulary restore, cleanup, language detection) threw; the take
     * published the last good text instead (#252).
     */
    object PolishPreparationFailed : AppDefect("polish_preparation_failed", "polish.preparation_failed")

    /** A warm hold's silence writer was still running two seconds after its stop (#257); no further hold in this process. */
    object SilenceWriterExitWedged : AppDefect("silence_writer_exit_wedged", "audio.silence_writer_exit_wedged")

    /** A debug-only defect to prove the pipe end to end; never raised on a release build. */
    object DebugProbe : AppDefect("debug_probe", "debug.probe")

    companion object {
        /** Every member, for the snapshot test. Update together with the sealed set. */
        fun all(): List<AppDefect> = listOf(
            VadCallWedged("test"), LocalPolishDeadline, PolishProtocolViolation, CaptureStillRunningAfterStop,
            CaptureReleaseWedged, AsrDecodeFailed(null), AsrOverLimit, CleanupRecovered, LocalPolishFailed, PolishUnexpected,
            PolishWatchdogTimeout, PolishServiceUnavailable, PolishPolicyUnreadable, PolishServiceDied, PolishCallFailed,
            TakePreparationFailed, HistorySaveTimedOut, HistoryQueueOverloaded, HistoryContractViolation(null), PolishPreparationFailed, SilenceWriterExitWedged, DebugProbe,
        )
    }
}
