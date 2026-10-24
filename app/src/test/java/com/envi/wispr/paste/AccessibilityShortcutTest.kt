package com.envi.wispr.paste

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * PRODUCT OUTCOME (#475). When this fails, a user whose accessibility shortcut keeps switching auto-paste off is not
 * told about it, or a user without one is told they have one. Inputs are the forms Android writes: a `:`-separated
 * list of full or short component names.
 */
class AccessibilityShortcutTest {
    private val ours = setOf(
        "com.envi.wispr/com.envi.wispr.paste.PasteAccessibilityService",
        "com.envi.wispr/.paste.PasteAccessibilityService",
    )

    @Test fun ourServiceInAnyPositionIsNamed() {
        assertEquals(true, AccessibilityShortcut.names("com.envi.wispr/com.envi.wispr.paste.PasteAccessibilityService", ours))
        assertEquals(true, AccessibilityShortcut.names("com.envi.wispr/.paste.PasteAccessibilityService", ours))
        assertEquals(true, AccessibilityShortcut.names("com.google.android.marvin.talkback/.TalkBackService:com.envi.wispr/.paste.PasteAccessibilityService", ours))
    }

    @Test fun otherTargetsAndEmptyValuesAreNot() {
        assertEquals(false, AccessibilityShortcut.names(null, ours))
        assertEquals(false, AccessibilityShortcut.names("", ours))
        // Samsung's TalkBack default, read from the customer phone after reinstall.
        assertEquals(false, AccessibilityShortcut.names("com.samsung.android.accessibility.talkback/com.samsung.android.marvin.talkback.TalkBackService", ours))
        // A prefix of our name is not our name.
        assertEquals(false, AccessibilityShortcut.names("com.envi.wispr/.paste.PasteAccessibilityServiceX", ours))
    }

    @Test fun theThreeShortcutKindsAreRead() {
        assertEquals(
            listOf("accessibility_button_targets", "accessibility_shortcut_target_service", "accessibility_gesture_targets"),
            AccessibilityShortcut.KEYS,
        )
    }
}
