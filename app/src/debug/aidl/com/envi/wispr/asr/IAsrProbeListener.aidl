package com.envi.wispr.asr;

/** Debug-only observer. No audio, transcript, model mutation or cancellation command crosses it. */
oneway interface IAsrProbeListener {
    void onEvent(String eventJson);
}
