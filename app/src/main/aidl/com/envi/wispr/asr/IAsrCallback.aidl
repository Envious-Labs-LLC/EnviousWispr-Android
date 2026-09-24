package com.envi.wispr.asr;

interface IAsrCallback {
    void onResult(String text);
    void onError(String message);

    // APPENDED (issue #176). Never reorder or rename anything above this line (architecture-rules.md RULE:
    // aidl-is-append-only): an older installed test APK calls these methods through the app's own
    // generated classes (#330), so a removed or renamed one fails when that test invokes it.

    /**
     * A typed failure, emitted ONLY for a request made through IAsrService.transcribeFileForTake.
     * reason is AsrFailureReason.code; detail is local diagnostic text and must never leave the phone.
     * The legacy transcribeFile/transcribe requests keep answering onResult/onError exactly as before.
     */
    void onFailure(int reason, String detail);
}
