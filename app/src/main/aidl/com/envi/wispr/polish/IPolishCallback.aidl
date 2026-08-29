package com.envi.wispr.polish;

interface IPolishCallback {
    void onResult(String text, String engine, long latencyMs);
    void onError(String message);
}
