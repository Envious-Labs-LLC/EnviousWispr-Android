package com.envi.wispr.asr;

import com.envi.wispr.asr.IAsrCallback;

interface IAsrService {
    void transcribeFile(String audioFilePath, IAsrCallback callback);
    // Legacy — will hit AIDL 1MB limit for recordings >30s
    void transcribe(in byte[] audioData, IAsrCallback callback);
    boolean isReady();

    // APPENDED (issue #176). Never reorder or rename anything above this line (architecture-rules.md RULE:
    // aidl-is-append-only): an older installed test APK calls these methods through the app's own
    // generated classes (#330), so a removed or renamed one fails when that test invokes it.

    /**
     * transcribeFile plus the take's id, and the ONLY request that answers a failure with
     * IAsrCallback.onFailure (a closed code) instead of onError (a sentence). takeId is the owner's
     * per-take UUID; the service keeps it as request context and never persists it.
     */
    void transcribeFileForTake(String audioFilePath, String takeId, IAsrCallback callback);
}
