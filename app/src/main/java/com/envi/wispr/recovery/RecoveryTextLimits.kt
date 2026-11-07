package com.envi.wispr.recovery

/** Shared bounds for a recovery result and its produced-text rescue record. */
internal object RecoveryTextLimits {
    const val RAW_CODE_UNITS = 65_536
    const val MODEL_FIELD_CODE_UNITS = 256
    const val MODEL_IDENTITY_CODE_UNITS = 4_096
    const val WORDS_BYTES = 16 * 1_024 * 1_024
    const val RESULT_BYTES = 160 * 1_024
    const val RESULT_WIRE_CHARS = 220_000
}
