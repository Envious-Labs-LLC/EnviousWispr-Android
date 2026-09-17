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

    /** InputRouteReason.code, latest event wins: 0 auto, 1 picked, 2 pick missing, 3 link refused, 4 preferred refused, 5 rescued. */
    int getInputRouteReason();

    /** Why the last start returned false: 0 none, 1 no input device at all, 2 anything else. Reset on the next start. */
    int getLastStartFailure();
}
