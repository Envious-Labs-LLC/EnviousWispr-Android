package com.envi.wispr.audio;

/**
 * The take's events, PUSHED by the capture process to the session owner (#115). Until then the owner asked
 * for every one of them over synchronous binder calls from a polling thread, which a capture process that
 * stopped answering parked forever, and the take was lost in silence.
 *
 * Every method is oneway: the publisher's worker never waits on the owner, and a dead owner costs nothing.
 * Delivery order across a oneway interface is per-sender, so the owner serialises these onto its main
 * thread before acting.
 *
 * Every event names its take: takeId is the id the owner passed to startCaptureForTake. The publisher is
 * service-scoped and the listener slot is replaced by whichever owner registers last, so without the id a
 * previous take's ending (queued after its session closed) would land on the next take's owner and be
 * read as its own. An owner discards every event whose takeId is not its take's.
 */
interface ITakeListener {
    /**
     * The route delivered sound and the first admitted block is on disk. forced: the earbuds sent nothing
     * and the take proceeded on the phone. routeKind and routeReason are InputRouteKind.code and
     * InputRouteReason.code; liveAfterMs is the wait from start to live.
     */
    oneway void onLive(String takeId, boolean forced, int routeKind, int routeReason, long liveAfterMs);

    /**
     * A heartbeat, at most once per wall-clock second from the first positive read, live or not: elapsedMs
     * is 0 before live and the recording's elapsed time after. Its arrival is liveness; its value is the
     * timer.
     */
    oneway void onTick(String takeId, long elapsedMs);

    /** The silence detector's status changed (the getSilenceStopStatus codes). */
    oneway void onSilenceStatus(String takeId, int status);

    /**
     * The take is over and nothing about it will change again. terminalReason is the getTerminalReason
     * code (TERMINAL_REASON_NONE for a start refused before capture began), startFailure the
     * getLastStartFailure code, audioFilePath the CLOSED file (empty when there is none), silenceStatus
     * the detector's last status, takePeakAmplitude the whole take's peak and effectiveInputDevice the
     * display label of what captured it. Published exactly once per take, as the last thing the capture
     * process does for it. A start refused before capture began carries no file, SILENCE_STATUS_DISABLED,
     * a zero peak and an empty label: nothing of the previous take.
     */
    oneway void onEnded(String takeId, int terminalReason, int startFailure, String audioFilePath, int silenceStatus, float takePeakAmplitude, String effectiveInputDevice);

    // APPENDED. Never reorder or rename anything above this line: a separately installed client binds
    // by transaction number, and renumbering breaks it at runtime with no compile error.
}
