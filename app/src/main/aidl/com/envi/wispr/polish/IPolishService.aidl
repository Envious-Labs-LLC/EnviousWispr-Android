package com.envi.wispr.polish;

import com.envi.wispr.polish.IPolishCallback;
import com.envi.wispr.polish.PolishPolicy;

// Append-only (architecture-rules.md RULE: aidl-is-append-only). The v1 transactions below stay
// declared because the instrumentation APK is a separately installed client of this service; every
// caller in this repository uses the v2 methods that follow them.
interface IPolishService {
    // v1: polishes with the deterministic rules only. No caller in this repository.
    void polish(
        String rawText,
        boolean removeFillers,
        boolean spokenEmoji,
        boolean spokenPunctuation,
        IPolishCallback callback
    );
    // v1: the local model's readiness. No caller in this repository.
    boolean isReady();
    // v1: the local model's status line. No caller in this repository.
    String getStatus();
    // v1: no-op. The engine holds no policy, so it cannot decide what to warm.
    void warmUp();

    // v2: the policy rides on every request; the engine never reads a preference.
    void polishRequest(
        long requestId,
        String rawText,
        boolean removeFillers,
        boolean spokenEmoji,
        boolean spokenPunctuation,
        in PolishPolicy policy,
        IPolishCallback callback
    );
    void warmUpWithPolicy(in PolishPolicy policy);
    oneway void cancel(long requestId);
    boolean isLocalModelReady();
    String localModelStatus();
}
