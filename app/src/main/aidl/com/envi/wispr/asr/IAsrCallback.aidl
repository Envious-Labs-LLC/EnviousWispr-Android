package com.envi.wispr.asr;

interface IAsrCallback {
    void onResult(String text);
    void onError(String message);
}
