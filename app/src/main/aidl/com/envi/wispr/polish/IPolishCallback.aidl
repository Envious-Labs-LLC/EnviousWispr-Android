package com.envi.wispr.polish;

import com.envi.wispr.polish.PolishOutcome;

interface IPolishCallback {
    // Exactly one per request. The v1 onResult and onError left with the v1 service transactions (#70).
    void onOutcome(in PolishOutcome outcome);
}
