package com.envi.wispr.audio;

import com.envi.wispr.audio.IAudioSpectrumListener;
import com.envi.wispr.audio.ITakeListener;

/**
 * The session owner's interface to the capture service (#220): the five operations a take uses, and
 * nothing else. Bound with `AudioCaptureService.ACTION_BIND_TAKE` and a fresh identifier per bind; every
 * other bind gets the legacy `IAudioCaptureService`.
 *
 * APPEND-ONLY from its first release, like the legacy interface (architecture-rules.md RULE:
 * aidl-is-append-only): an older installed test APK calls these methods through the app's own generated
 * classes (#330), so a removed or renamed one fails when that test invokes it. Never reorder or rename these.
 */
interface IAudioTakeService {
    boolean startCaptureForTake(boolean autoStopOnSilence, float pauseSeconds, String inputDevicePick, boolean keepEarbudsReady, String takeId);

    void stopCapture();

    boolean finishTake();

    void registerSpectrumListener(IAudioSpectrumListener listener);

    void registerTakeListener(ITakeListener listener);
}
