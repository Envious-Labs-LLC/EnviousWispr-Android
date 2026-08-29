package com.envi.wispr.polish;

import com.envi.wispr.polish.IPolishCallback;

interface IPolishService {
    void polish(
        String rawText,
        boolean removeFillers,
        boolean spokenEmoji,
        boolean spokenPunctuation,
        IPolishCallback callback
    );
    boolean isReady();
    String getStatus();
    void warmUp();
}
