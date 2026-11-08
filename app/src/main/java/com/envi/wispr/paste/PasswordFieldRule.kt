package com.envi.wispr.paste

import android.text.InputType
import android.view.accessibility.AccessibilityNodeInfo

/**
 * The one answer to "is this field a password box" (#523). Read by the floating bubble, which does not draw
 * itself on a password box unless the user asked for it, and by the insertion runner's sensitive-field
 * divert until #11 removes that divert.
 */
internal object PasswordFieldRule {

    /** `isPassword` first, and the input type is read only when that is false, as the runner always did. */
    fun isPasswordField(node: AccessibilityNodeInfo): Boolean =
        node.isPassword || isPasswordInputType(node.inputType)

    /** The input-type half, pure so it can be tested on every class and variation. */
    fun isPasswordInputType(inputType: Int): Boolean {
        val variation = inputType and InputType.TYPE_MASK_VARIATION
        return when (inputType and InputType.TYPE_MASK_CLASS) {
            InputType.TYPE_CLASS_TEXT -> variation == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
                variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD ||
                variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD
            InputType.TYPE_CLASS_NUMBER -> variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD
            // Phone and date/time classes carry no password variation; an unknown class is not a password box.
            else -> false
        }
    }
}
