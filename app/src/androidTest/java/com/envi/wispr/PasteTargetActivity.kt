package com.envi.wispr

import android.app.Activity
import android.os.Bundle
import android.view.Gravity
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout

/** Temporary installed-test target used to prove accessibility paste without touching user data. */
class PasteTargetActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val field = EditText(this).apply {
            hint = "Silent auto-paste target"
            textSize = 18f
            isSingleLine = false
            gravity = Gravity.TOP
            setPadding(48, 48, 48, 48)
        }
        setContentView(
            field,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
        field.requestFocus()
        field.postDelayed({
            getSystemService(InputMethodManager::class.java).showSoftInput(
                field,
                InputMethodManager.SHOW_IMPLICIT
            )
        }, 250)
    }
}
