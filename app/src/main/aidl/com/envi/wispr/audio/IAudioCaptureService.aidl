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
}
