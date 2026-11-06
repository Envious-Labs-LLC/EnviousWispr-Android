package com.envi.wispr.asr;

/** Debug-only same-UID client; its callback Binder lives in the disposable probe process. */
interface IAsrCancellationProbe {
    int pid();
    void start(String audioFilePath, String takeId);
}
