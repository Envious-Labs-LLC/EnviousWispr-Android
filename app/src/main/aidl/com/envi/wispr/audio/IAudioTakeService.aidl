package com.envi.wispr.audio;

import com.envi.wispr.audio.IAudioSpectrumListener;
import com.envi.wispr.audio.ITakeListener;

/**
 * The session owner's interface to the capture service (#220): the five operations a take uses, and
 * nothing else. Bound with `AudioCaptureService.ACTION_BIND_TAKE` and a fresh identifier per bind; every
 * other bind gets the legacy `IAudioCaptureService`.
 *
 * APPEND-ONLY from its first release, like the legacy interface: a client binds by transaction number,
 * and renumbering breaks it at runtime with no compile error. Never reorder or rename these.
 */
interface IAudioTakeService {
    boolean startCaptureForTake(boolean autoStopOnSilence, float pauseSeconds, String inputDevicePick, boolean keepEarbudsReady, String takeId);

    void stopCapture();

    boolean finishTake();

    void registerSpectrumListener(IAudioSpectrumListener listener);

    void registerTakeListener(ITakeListener listener);
}
