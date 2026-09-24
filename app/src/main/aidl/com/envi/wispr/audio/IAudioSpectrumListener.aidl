package com.envi.wispr.audio;

/**
 * The recorder's live picture, PUSHED from the audio process to the one listener the session owner
 * registered through IAudioCaptureService.registerSpectrumListener (issue #187). Replaces polling
 * getSpectrumBands thirty times a second.
 *
 * oneway: the analyser thread that computes the picture never waits on the app process. Calls to one
 * listener are dispatched in the order they were made.
 */
interface IAudioSpectrumListener {
    /**
     * One picture: SpectrumAnalyzer.BAND_COUNT pitch levels 0..1, lowest band first, at most one call per
     * analyser wake, originated while a take is open on this binding. All zeros when the analyser stopped.
     */
    oneway void onSpectrum(in float[] bands);

    // APPENDED. Never reorder or rename anything above this line (architecture-rules.md RULE:
    // aidl-is-append-only): an older installed test APK calls these methods through the app's own
    // generated classes (#330), so a removed or renamed one fails when that test invokes it.
}
