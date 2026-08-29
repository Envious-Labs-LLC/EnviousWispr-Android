package com.envi.wispr;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipDescription;
import android.content.ClipboardManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.Locale;
import java.util.UUID;

/** Disposable target that exposes whether EnviousWispr restored its test clipboard sentinel. */
public final class ClipboardPasteTargetActivity extends Activity {
    public static final String SENTINEL = "clipboard sentinel";
    private static final String SENTINEL_OWNER_MIME_PREFIX =
            "application/vnd.enviouswispr.uat-sentinel-";
    private final String sentinelOwnerMime = SENTINEL_OWNER_MIME_PREFIX
            + UUID.randomUUID().toString().toLowerCase(Locale.ROOT);
    private final Handler handler = new Handler(Looper.getMainLooper());
    private ClipboardManager clipboard;
    private ClipData previousClipboard;
    private boolean previousClipboardReadable;
    private boolean sentinelInstalled;
    private boolean clipboardResolved;
    private TextView clipboardProbe;
    private final Runnable refreshClipboardProbe = new Runnable() {
        @Override
        public void run() {
            ClipData clip = clipboard.getPrimaryClip();
            CharSequence value = clip != null && clip.getItemCount() > 0
                    ? clip.getItemAt(0).getText()
                    : null;
            clipboardProbe.setText("Clipboard: " + (value == null ? "<empty>" : value));
            handler.postDelayed(this, 250L);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        clipboard = getSystemService(ClipboardManager.class);

        EditText field = new EditText(this);
        field.setHint("Clipboard restoration target");
        field.setTextSize(18f);
        field.setGravity(Gravity.TOP);
        field.setPadding(48, 48, 48, 48);

        clipboardProbe = new TextView(this);
        clipboardProbe.setTextSize(16f);
        clipboardProbe.setPadding(48, 24, 48, 48);

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.addView(field, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
        ));
        content.addView(clipboardProbe, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        ));
        setContentView(content);

        field.requestFocus();
        field.postDelayed(() -> getSystemService(InputMethodManager.class).showSoftInput(
                field,
                InputMethodManager.SHOW_IMPLICIT
        ), 250L);
    }

    @Override
    protected void onResume() {
        super.onResume();
        handler.post(refreshClipboardProbe);
    }

    @Override
    protected void onPause() {
        handler.removeCallbacks(refreshClipboardProbe);
        super.onPause();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (!hasFocus || sentinelInstalled || clipboardResolved) {
            return;
        }

        previousClipboard = clipboard.getPrimaryClip();
        previousClipboardReadable = previousClipboard != null || !clipboard.hasPrimaryClip();
        if (!previousClipboardReadable) {
            clipboardProbe.setText("Clipboard unavailable; UAT not started.");
            finish();
            return;
        }

        ClipData sentinel = new ClipData(
                new ClipDescription(
                        "UAT sentinel",
                        new String[] {
                                ClipDescription.MIMETYPE_TEXT_PLAIN,
                                sentinelOwnerMime
                        }
                ),
                new ClipData.Item(SENTINEL)
        );
        clipboard.setPrimaryClip(sentinel);
        sentinelInstalled = true;
    }

    private boolean isOwnedSentinel(ClipData clip) {
        if (clip == null || clip.getItemCount() != 1
                || !clip.getDescription().hasMimeType(sentinelOwnerMime)) {
            return false;
        }
        CharSequence text = clip.getItemAt(0).getText();
        return SENTINEL.contentEquals(text);
    }

    private void restorePreviousClipboardIfOwned() {
        if (!sentinelInstalled || clipboardResolved || clipboard == null) {
            return;
        }

        ClipData current = clipboard.getPrimaryClip();
        if (current == null && clipboard.hasPrimaryClip()) {
            return;
        }

        clipboardResolved = true;
        if (!isOwnedSentinel(current)) {
            return;
        }

        if (previousClipboard == null) {
            clipboard.clearPrimaryClip();
        } else {
            clipboard.setPrimaryClip(previousClipboard);
        }
        sentinelInstalled = false;
    }

    @Override
    public void finish() {
        restorePreviousClipboardIfOwned();
        super.finish();
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacks(refreshClipboardProbe);
        restorePreviousClipboardIfOwned();
        super.onDestroy();
    }
}
