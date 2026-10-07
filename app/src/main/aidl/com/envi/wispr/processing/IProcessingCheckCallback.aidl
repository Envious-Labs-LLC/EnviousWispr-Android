package com.envi.wispr.processing;
import com.envi.wispr.processing.ProcessingCheckResult;
oneway interface IProcessingCheckCallback {
    void onChecked(in ProcessingCheckResult result);
}
