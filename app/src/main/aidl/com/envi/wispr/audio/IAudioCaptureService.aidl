package com.envi.wispr.audio;

interface IAudioCaptureService {
    boolean startCapture();
    void stopCapture();
    boolean isCapturing();
    int getTerminalReason();
    float getCurrentAmplitude();
    String getAudioFilePath();
    long getElapsedMs();
    long getMaxDurationMs();
    boolean waitForFileReady(long timeoutMs);
    // Legacy — returns empty byte array, use getAudioFilePath() instead
    byte[] getAudioData();

    // APPENDED. Never reorder or rename anything above this line: a separately installed client binds
    // by transaction number, and renumbering breaks it at runtime with no compile error.

    /**
     * Start a take that may end itself when the speaker stops.
     * startCapture() keeps its exact meaning and is startCaptureWithSilenceStop(false, 0).
     */
    boolean startCaptureWithSilenceStop(boolean autoStopOnSilence, float pauseSeconds);

    /**
     * 0 disabled, 1 preparing, 2 ready, 3 unavailable before ready, 4 lost after ready.
     * Only 3 is worth telling the user about: 4 means the recording is still correct.
     */
    int getSilenceStopStatus();

    /**
     * The recorder's live picture: SpectrumAnalyzer.BAND_COUNT pitch levels 0..1, lowest band first,
     * from the newest 64 ms of the open take. Always that many, never empty; all zeros when no take is open.
     */
    float[] getSpectrumBands();

    /**
     * Start a take on the microphone the user picked. inputDevicePick is InputDevicePick.serialize():
     * "auto", or "<type>|<name>". startCaptureWithSilenceStop(a, p) keeps its exact meaning and equals
     * startCaptureWithInputDevice(a, p, "auto"). An unparseable pick reads as "auto".
     */
    boolean startCaptureWithInputDevice(boolean autoStopOnSilence, float pauseSeconds, String inputDevicePick);

    /**
     * The device(s) that captured the CURRENT OR MOST RECENT take, in order: "AirPods Pro 3" or
     * "AirPods Pro 3, then Phone". Set at start, updated on every route change and rescue, and kept
     * after the take ends until the next start, so a take that ends before the first poll still reports
     * its whole history when read once at stop. Empty only before the first take of this process. A
     * display label: never parse it.
     */
    String getEffectiveInputDevice();

    /** InputRouteKind.code of the device the current or most recent take STARTED on: 0 none, 1 phone, 2 wired or USB, 3 Bluetooth. */
    int getInputRouteKind();

    /** InputRouteReason.code, latest event wins: 0 auto, 1 picked, 2 pick missing, 3 link refused, 4 preferred refused. */
    int getInputRouteReason();

    /**
     * Why the last start returned false: 0 none, 1 no input device at all, 2 anything else, 3 the earbuds
     * are connected and only the phone would record (refused by rule). Reset on the next start; also set
     * when a started take is failed by the live deadline for reason 3.
     */
    int getLastStartFailure();

    /**
     * Like startCaptureWithInputDevice, plus the "keep earbuds ready" setting frozen for this take: after
     * it ends on earbuds, the service keeps the link open for 30 s with silent playback (no recording).
     */
    boolean startCaptureWithInputDeviceHeld(boolean autoStopOnSilence, float pauseSeconds, String inputDevicePick, boolean keepEarbudsReady);

    /**
     * Whether the take is delivering sound yet: 0 waiting (nothing written, no timer), 1 ready, 2 forced
     * (the deadline passed twice; the take proceeds on the earbuds without sound). Reported as 1 or 2 only
     * once the first admitted block is on disk.
     */
    int getLiveState();

    /** Milliseconds from the recorder's start to live, or 0 while waiting. Log-only. */
    long getLiveAfterMs();

    /**
     * The session owner is done with the take. True when a warm hold is running and the service has
     * given itself a started lifetime, so the owner must unbind WITHOUT stopping the service; false when
     * there is nothing to keep and the owner stops it as before.
     */
    boolean finishTake();

    /**
     * startCaptureWithInputDeviceHeld plus the take's id (the owner's per-take UUID), which the service keeps
     * as request context for this take and forwards to the silence detector. Issue #176.
     */
    boolean startCaptureForTake(boolean autoStopOnSilence, float pauseSeconds, String inputDevicePick, boolean keepEarbudsReady, String takeId);

    /**
     * The loudest sample of the CURRENT OR MOST RECENT take, 0..1 of full scale, kept after the take ends
     * until the next start, like getEffectiveInputDevice. 0 before the first take of this process. Read
     * once at stop: it is what lets an empty transcript be told apart from a quiet room (issue #176).
     */
    float getTakePeakAmplitude();
}
