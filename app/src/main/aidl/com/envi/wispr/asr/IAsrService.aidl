package com.envi.wispr.asr;

import com.envi.wispr.asr.IAsrCallback;

interface IAsrService {
    void transcribeFile(String audioFilePath, IAsrCallback callback);
    // Legacy — will hit AIDL 1MB limit for recordings >30s
    void transcribe(in byte[] audioData, IAsrCallback callback);
    boolean isReady();
}
