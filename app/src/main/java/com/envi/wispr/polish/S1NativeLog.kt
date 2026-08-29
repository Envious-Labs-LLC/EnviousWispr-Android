package com.envi.wispr.polish

/** Replaces GenieX's verbose Android logger after its JNI library loads and before SDK init. */
internal object S1NativeLog {
    init {
        System.loadLibrary("enviouswispr-geniex-log")
    }

    external fun installContentFreeLogger(): Int
}
