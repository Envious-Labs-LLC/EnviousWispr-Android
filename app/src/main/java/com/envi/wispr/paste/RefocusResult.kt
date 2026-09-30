package com.envi.wispr.paste

/**
 * How one try to give input focus back to the pinned editor ended (#201). The insertion attempt spends its
 * single focus action only when one was actually SENT, so the four cases differ in exactly that.
 */
internal enum class RefocusResult {
    /** A guard refused before any action was sent (window not focused, field not focusable, no sibling holds focus). */
    DECLINED,

    /** `ACTION_FOCUS` was sent and the framework accepted it. */
    ACCEPTED,

    /** `ACTION_FOCUS` was sent and the framework answered false. */
    REJECTED,

    /** `ACTION_FOCUS` was sent and threw: focus may have moved. */
    THREW,
    ;

    /** True when a focus action reached the framework, so focus may have moved and the one action is spent. */
    val sentAnAction: Boolean get() = this != DECLINED
}
