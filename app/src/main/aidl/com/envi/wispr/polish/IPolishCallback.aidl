package com.envi.wispr.polish;

import com.envi.wispr.polish.PolishOutcome;

interface IPolishCallback {
    // v1, answered only by the v1 polish transaction.
    void onResult(String text, String engine, long latencyMs);
    // v1, never produced by the engine; kept declared for the v1 surface.
    void onError(String message);
    // v2: exactly one per request.
    void onOutcome(in PolishOutcome outcome);
}
