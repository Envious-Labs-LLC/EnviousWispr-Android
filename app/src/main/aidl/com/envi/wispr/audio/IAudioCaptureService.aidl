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
}
