package com.envi.wispr.polish;

import com.envi.wispr.polish.IPolishCallback;
import com.envi.wispr.polish.PolishPolicy;
import com.envi.wispr.polish.CleanupRequestOptions;
import com.envi.wispr.processing.IProcessingCheckCallback;

// Append-only (architecture-rules.md RULE: aidl-is-append-only). The v1 transactions (polish, isReady,
// getStatus, warmUp) were removed in #70: the current instrumentation APK calls none of them and defines
// neither the policy parcel nor these generated classes (#330), so both sides of the Binder are the
// installed app's. Recheck that assumption if tests or another client package their own copies.
interface IPolishService {
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

    // APPENDED (issue #176). Never reorder or rename anything above this line.

    /**
     * polishRequest plus the take's id. requestId stays the arbitration and cancellation key; the engine
     * binds takeId to the request entry as context and never persists it.
     */
    void polishRequestForTake(
        long requestId,
        String rawText,
        boolean removeFillers,
        boolean spokenEmoji,
        boolean spokenPunctuation,
        in PolishPolicy policy,
        String takeId,
        IPolishCallback callback
    );

    // Appended for #413. Previous methods keep their American/default protection semantics.
    void polishRequestWithCleanupForTake(
        long requestId,
        String rawText,
        in CleanupRequestOptions cleanup,
        in PolishPolicy policy,
        String takeId,
        IPolishCallback callback
    );
    // Appended for #429. Qualification is not a user polish request.
    void qualifyProcessing(long operationId, String backend, IProcessingCheckCallback callback);
    oneway void cancelQualification(long operationId);
}
