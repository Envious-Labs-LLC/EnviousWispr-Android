package com.envi.wispr

import android.app.Activity
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import java.io.File

/** Temporary installed-test target used to prove accessibility paste without touching user data. */
class PasteTargetActivity : Activity() {

    companion object {
        /**
         * The live field, so a test can assert what the EDITOR received.
         *
         * Asserting the clipboard has not verified insertion: the clipboard is the FALLBACK, and it is
         * populated whether or not a single character reached anything the user was typing into.
         */
        @Volatile
        @JvmStatic
        var field: EditText? = null
            private set

        fun currentText(): String = field?.text?.toString().orEmpty()

        /** Read from outside the process with `run-as com.envi.wispr.test cat files/<this>`. */
        const val RECEIPT_NAME = "paste-target-received.txt"
    }

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
        Companion.field = field

        // The field writes what it receives to a file, because instrumentation runs in the APP's
        // process while this activity runs in the TEST package's, so a static here is not readable
        // from a test. The file is the oracle: it is the editor's own content, written by the editor.
        val receipt = File(filesDir, RECEIPT_NAME)
        runCatching { receipt.delete() }
        field.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                runCatching { receipt.writeText(s?.toString().orEmpty()) }
            }
        })

        field.requestFocus()
        field.postDelayed({
            getSystemService(InputMethodManager::class.java).showSoftInput(
                field,
                InputMethodManager.SHOW_IMPLICIT
            )
        }, 250)
    }

    override fun onDestroy() {
        // A reference to a destroyed activity's field would let a test read the PREVIOUS run's text and
        // call it this run's result, which is the plausible-value trap wearing a green tick.
        if (Companion.field?.context === this) Companion.field = null
        super.onDestroy()
    }
}
