package com.envi.wispr.paste

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Play's Accessibility API policy treats a general-purpose dictation app that declares
 * `isAccessibilityTool="true"` as misrepresenting an assistive tool, which gets the listing rejected or
 * pulled (`.claude/rules/content-brand.md` RULE: the-accessibility-disclosure-is-policy-not-copy). The
 * declaration is a compliance surface, not a style choice, so a flip to `true` or a silent removal must
 * fail here rather than at Play review. `false` is the framework default, and this test exists so the
 * explicit declaration Play reviewers look for cannot drift away unnoticed.
 */
class AccessibilityPolicyDeclarationTest {
    private val config = File("src/main/res/xml/accessibility_service_config.xml").readText()

    @Test fun declaresNotAnAccessibilityTool() {
        assertTrue(
            "accessibility_service_config.xml must declare android:isAccessibilityTool=\"false\": " +
                "EnviousWispr is a general dictation app, not an assistive tool, and Play rejects a wrong claim.",
            config.contains("android:isAccessibilityTool=\"false\""),
        )
    }
}
