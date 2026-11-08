package com.envi.wispr.paste

import android.text.InputType
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PRODUCT OUTCOME (#523). When this fails, the lips show on a password box (or hide on an ordinary one), and
 * the insertion runner's sensitive-field divert changes what it diverts. Rows cover every password class the
 * runner's predicate covered before the move, and ordinary text and number fields carrying unrelated flags.
 *
 * REVERT: drop `TYPE_TEXT_VARIATION_WEB_PASSWORD` from the rule and the web-password row turns red; return
 * `false` from the rule and every positive row turns red.
 */
class PasswordFieldRuleTest {

    private val text = InputType.TYPE_CLASS_TEXT
    private val number = InputType.TYPE_CLASS_NUMBER

    @Test fun aTextPasswordIsAPasswordBox() =
        assertTrue(PasswordFieldRule.isPasswordInputType(text or InputType.TYPE_TEXT_VARIATION_PASSWORD))

    @Test fun aVisiblePasswordIsAPasswordBox() =
        assertTrue(PasswordFieldRule.isPasswordInputType(text or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD))

    @Test fun aWebPasswordIsAPasswordBox() =
        assertTrue(PasswordFieldRule.isPasswordInputType(text or InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD))

    @Test fun aNumericPinIsAPasswordBox() =
        assertTrue(PasswordFieldRule.isPasswordInputType(number or InputType.TYPE_NUMBER_VARIATION_PASSWORD))

    @Test fun anOrdinaryMessageBoxWithItsUsualFlagsIsNot() = assertFalse(
        PasswordFieldRule.isPasswordInputType(
            text or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or
                InputType.TYPE_TEXT_FLAG_AUTO_CORRECT or InputType.TYPE_TEXT_VARIATION_SHORT_MESSAGE,
        ),
    )

    @Test fun anEmailAddressBoxIsNot() =
        assertFalse(PasswordFieldRule.isPasswordInputType(text or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS))

    @Test fun aSignedDecimalNumberBoxIsNot() = assertFalse(
        PasswordFieldRule.isPasswordInputType(number or InputType.TYPE_NUMBER_FLAG_SIGNED or InputType.TYPE_NUMBER_FLAG_DECIMAL),
    )

    @Test fun aPhoneNumberBoxIsNot() = assertFalse(PasswordFieldRule.isPasswordInputType(InputType.TYPE_CLASS_PHONE))

    @Test fun aFieldWithNoInputTypeIsNot() = assertFalse(PasswordFieldRule.isPasswordInputType(InputType.TYPE_NULL))
}
