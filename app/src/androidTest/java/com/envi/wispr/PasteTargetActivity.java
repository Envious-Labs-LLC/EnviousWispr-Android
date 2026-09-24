package com.envi.wispr;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.view.ViewGroup;
import android.widget.TextView;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Temporary installed-test target used to prove accessibility paste without touching user data.
 *
 * JAVA ON PURPOSE, like the other two rigs. This activity runs in the TEST package's own process, which
 * has no Kotlin standard library on its class path (the test APK leans on the app's for everything that
 * runs inside the instrumented process). The earlier Kotlin version called {@code File.writeText} inside
 * {@code runCatching}: both are stdlib, the write threw {@code ClassNotFoundException}, the catch swallowed
 * it, and the receipt file was never written (found live 2026-09-22, #161). Nothing here may reach a
 * Kotlin class, and {@code android.util.Log} is called directly because the app's {@code DebugLogger} is
 * an app class this process does not have; the two lines it can write carry a class name, never content.
 *
 * One editor (A) by default; with {@link #EXTRA_TWO_FIELDS} a second editor (B) below it, for the
 * two-editor side-button case (#161 T3, REF-01): a take started with A focused, with focus moved to B
 * before insertion, must land in NEITHER (the product's fail-safe keeps the words on the clipboard).
 * Each editor writes its WHOLE text to its own receipt file on every change, and both receipts are
 * written EMPTY at creation, before the watchers are installed, so "untouched" is an existing empty file
 * and "the editor never received anything" is not the same as "the file is missing". The READY receipt
 * ({@link #READY_NAME}) carries the run's {@link #EXTRA_RIG_TOKEN} and is written only once A holds
 * focus, so a stale receipt from an earlier run cannot stand in for this one (code review round 1).
 */
public final class PasteTargetActivity extends Activity {

    /**
     * The live field, so a test in THIS process could read what the editor received. The instrumented
     * test runs in the app's process and cannot; it reads the receipt files instead.
     */
    private static volatile EditText field;

    /** Read from outside the process with {@code run-as com.envi.wispr.test cat files/<this>}. */
    public static final String RECEIPT_NAME = "paste-target-received.txt";

    /** Editor B's receipt, present only when {@link #EXTRA_TWO_FIELDS} was set. */
    public static final String RECEIPT_B_NAME = "paste-target-b-received.txt";

    public static final String EXTRA_TWO_FIELDS = "two_fields";

    /**
     * #331: the no-field host. A full-screen page with no editable node, holding window focus: the paste
     * service's window scan pins only a FOCUSED editable node (`EditorTargetTracker`), and while this window has
     * input focus no other app's editor does. Its ready receipt is written once THIS window holds focus.
     */
    public static final String EXTRA_NO_FIELD = "no_field";

    /** A random token the test mints per run; the rig echoes it into {@link #READY_NAME} once A has focus. */
    public static final String EXTRA_RIG_TOKEN = "rig_token";

    /** Holds the run's token, written after {@code A.requestFocus()} succeeded and {@code A.hasFocus()} reads true. */
    public static final String READY_NAME = "paste-target-ready.txt";

    /**
     * An ORDERED broadcast that moves focus to editor B and answers with result code 1 only once
     * {@code B.requestFocus()} returned true and {@code B.hasFocus()} reads true; 0 otherwise. Sent by
     * the instrumentation from the APP's process, so the runtime receiver is exported (this activity runs
     * under the test package's UID) and the intent is explicit to {@code com.envi.wispr.test}.
     */
    public static final String ACTION_FOCUS_B = "com.envi.wispr.test.FOCUS_B";

    public static EditText getField() {
        return field;
    }

    public static String currentText() {
        EditText live = field;
        return live == null ? "" : live.getText().toString();
    }

    private EditText fieldB;
    private BroadcastReceiver focusReceiver;
    /** The no-field host's token, written to the ready receipt at its first window focus (#331). */
    private String noFieldToken;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Intent intent = getIntent();
        boolean twoFields = intent != null && intent.getBooleanExtra(EXTRA_TWO_FIELDS, false);
        final String rigToken = intent == null ? null : intent.getStringExtra(EXTRA_RIG_TOKEN);
        if (intent != null && intent.getBooleanExtra(EXTRA_NO_FIELD, false)) {
            showNoField(rigToken);
            return;
        }
        final EditText a = editor("Silent auto-paste target");
        final EditText b = twoFields ? editor("Second editor, never the target") : null;
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.addView(a, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        if (b != null) {
            column.addView(b, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        }
        setContentView(column);
        field = a;
        fieldB = b;

        // The field writes what it receives to a file, because instrumentation runs in the APP's process
        // while this activity runs in the TEST package's, so a static here is not readable from a test.
        // The file is the oracle: it is the editor's own content, written by the editor. BOTH receipts
        // exist and are EMPTY before either watcher is installed.
        final File receiptA = new File(getFilesDir(), RECEIPT_NAME);
        final File receiptB = new File(getFilesDir(), RECEIPT_B_NAME);
        final File ready = new File(getFilesDir(), READY_NAME);
        if (ready.exists() && !ready.delete()) {
            android.util.Log.w("PasteTarget", "stale ready receipt could not be deleted");
        }
        write(receiptA, "");
        if (b != null) {
            write(receiptB, "");
        } else if (receiptB.exists() && !receiptB.delete()) {
            android.util.Log.w("PasteTarget", "stale B receipt could not be deleted");
        }
        a.addTextChangedListener(watcher(receiptA));
        if (b != null) {
            b.addTextChangedListener(watcher(receiptB));
            BroadcastReceiver receiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent received) {
                    boolean moved = b.requestFocus() && b.hasFocus();
                    setResultCode(moved ? 1 : 0);
                }
            };
            if (Build.VERSION.SDK_INT >= 33) {
                registerReceiver(receiver, new IntentFilter(ACTION_FOCUS_B), Context.RECEIVER_EXPORTED);
            } else {
                registerReceiver(receiver, new IntentFilter(ACTION_FOCUS_B));
            }
            focusReceiver = receiver;
        }

        boolean focused = a.requestFocus() && a.hasFocus();
        if (focused && rigToken != null) {
            write(ready, rigToken);
        } else if (rigToken != null) {
            android.util.Log.w("PasteTarget", "editor A did not take focus; no ready receipt written");
        }
        a.postDelayed(() -> getSystemService(InputMethodManager.class)
                .showSoftInput(a, InputMethodManager.SHOW_IMPLICIT), 250);
    }

    /**
     * #331: a page with one plain line of text and no editor, filling the screen. The ready receipt waits for this
     * window's own focus, which is what proves no other app's editor holds input focus, the only kind the paste
     * service's scan pins. Whether focus stayed here through the take is read from the system's own focus log by
     * the row, since this window cannot tell a brief null focus from another window's.
     */
    private void showNoField(String rigToken) {
        field = null;
        fieldB = null;
        new File(getFilesDir(), READY_NAME).delete();
        TextView line = new TextView(this);
        line.setText("No field here");
        line.setTextSize(18f);
        line.setPadding(48, 48, 48, 48);
        setContentView(line, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        noFieldToken = rigToken;
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        String token = noFieldToken;
        if (hasFocus && token != null) {
            noFieldToken = null;
            write(new File(getFilesDir(), READY_NAME), token);
        }
    }

    private EditText editor(String hintText) {
        EditText view = new EditText(this);
        view.setHint(hintText);
        view.setTextSize(18f);
        view.setSingleLine(false);
        view.setGravity(Gravity.TOP);
        view.setPadding(48, 48, 48, 48);
        return view;
    }

    private static TextWatcher watcher(final File receipt) {
        return new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void afterTextChanged(Editable s) {
                write(receipt, s == null ? "" : s.toString());
            }
        };
    }

    /** Plain java.io, whole file, so a receipt is the editor's text and nothing else. */
    private static void write(File receipt, String text) {
        try (OutputStream out = new FileOutputStream(receipt, false)) {
            out.write(text.getBytes(StandardCharsets.UTF_8));
        } catch (IOException error) {
            android.util.Log.w("PasteTarget", "receipt write failed: " + error.getClass().getSimpleName());
        }
    }

    @Override
    protected void onDestroy() {
        if (focusReceiver != null) {
            try {
                unregisterReceiver(focusReceiver);
            } catch (IllegalArgumentException ignored) {
                // already unregistered
            }
            focusReceiver = null;
        }
        // A reference to a destroyed activity's field would let a test read the PREVIOUS run's text and
        // call it this run's result, which is the plausible-value trap wearing a green tick.
        EditText live = field;
        if (live != null && live.getContext() == this) {
            field = null;
        }
        fieldB = null;
        super.onDestroy();
    }
}
