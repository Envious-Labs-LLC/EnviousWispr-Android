package com.envi.wispr;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.text.InputType;
import android.widget.EditText;
import android.widget.LinearLayout;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Test-only target for the floating bubble's visibility (#523): an ordinary editor ("Name") and a password
 * editor ("Secret") in another package than the app, so the bubble host treats them as a third-party app's
 * fields and the hide list can name this package.
 *
 * JAVA ON PURPOSE, like {@link PasteTargetActivity}: this activity runs in the TEST package's process, which
 * has no Kotlin standard library on its class path.
 *
 * Which editor holds focus is chosen by {@link #EXTRA_FOCUS} on every start, including a re-delivered intent
 * ({@code singleTop}), and the READY receipt carries the run's {@link #EXTRA_TOKEN} only once that editor
 * reads {@code hasFocus()}, so a test never judges the bubble against a focus that did not land.
 */
public final class BubbleFieldsActivity extends Activity {

    public static final String EXTRA_FOCUS = "focus";
    public static final String FOCUS_NAME = "name";
    public static final String FOCUS_SECRET = "secret";
    public static final String EXTRA_TOKEN = "token";
    /** Read with {@code run-as com.envi.wispr.test cat files/<this>}. */
    public static final String READY_NAME = "bubble-fields-ready.txt";

    private EditText name;
    private EditText secret;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        name = new EditText(this);
        name.setHint("Name");
        name.setInputType(InputType.TYPE_CLASS_TEXT);
        secret = new EditText(this);
        secret.setHint("Secret");
        secret.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        // targetSdk 36 draws edge to edge, which put "Name" under the status bar: keep both editors in the
        // middle of the screen, clear of the bars, the keyboard and the bubble's own dock.
        int pad = (int) (160 * getResources().getDisplayMetrics().density);
        column.setPadding(pad / 4, pad, pad / 4, 0);
        column.addView(name, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        column.addView(secret, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        setContentView(column);
        apply(getIntent());
    }

    /**
     * Asks the system to dismiss a credential-free keyguard this activity is shown over, WITHOUT touching
     * focus, so a test can unlock and still claim "same field, no re-tap". A swipe cannot do it here: this
     * activity occludes the keyguard, so the swipe lands on the activity (Pixel 9 Pro, Test Lab 2026-11-08).
     */
    public static final String EXTRA_DISMISS_KEYGUARD = "dismiss_keyguard";

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (intent != null && intent.getBooleanExtra(EXTRA_DISMISS_KEYGUARD, false)) {
            android.app.KeyguardManager keyguard = getSystemService(android.app.KeyguardManager.class);
            if (keyguard != null) keyguard.requestDismissKeyguard(this, null);
            return;
        }
        setIntent(intent);
        apply(intent);
    }

    private void apply(final Intent intent) {
        final File ready = new File(getFilesDir(), READY_NAME);
        if (ready.exists() && !ready.delete()) {
            android.util.Log.w("BubbleFields", "stale ready receipt could not be deleted");
        }
        final String token = intent == null ? null : intent.getStringExtra(EXTRA_TOKEN);
        final boolean wantSecret = intent != null && FOCUS_SECRET.equals(intent.getStringExtra(EXTRA_FOCUS));
        final EditText target = wantSecret ? secret : name;
        target.post(new Runnable() {
            @Override
            public void run() {
                if (target.requestFocus() && target.hasFocus() && token != null) {
                    write(ready, token);
                }
            }
        });
    }

    private static void write(File file, String text) {
        try (OutputStream out = new FileOutputStream(file)) {
            out.write(text.getBytes(StandardCharsets.UTF_8));
        } catch (IOException error) {
            android.util.Log.w("BubbleFields", "receipt write failed: " + error.getClass().getSimpleName());
        }
    }
}
