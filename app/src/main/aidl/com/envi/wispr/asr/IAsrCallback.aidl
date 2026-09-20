package com.envi.wispr.asr;

interface IAsrCallback {
    void onResult(String text);
    void onError(String message);

    // APPENDED (issue #176). Never reorder or rename anything above this line: the instrumentation APK is
    // a separately installed client that binds by transaction number.

    /**
     * A typed failure, emitted ONLY for a request made through IAsrService.transcribeFileForTake.
     * reason is AsrFailureReason.code; detail is local diagnostic text and must never leave the phone.
     * The legacy transcribeFile/transcribe requests keep answering onResult/onError exactly as before.
     */
    void onFailure(int reason, String detail);
}
