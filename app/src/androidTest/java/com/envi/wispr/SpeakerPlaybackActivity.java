package com.envi.wispr;

import android.app.Activity;
import android.os.Bundle;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.speech.tts.Voice;

import java.util.Locale;
import java.util.Set;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/** Test-only phone-speaker source for physical-device dictation UAT. */
public final class SpeakerPlaybackActivity extends Activity implements TextToSpeech.OnInitListener {
    private static final String UAT_UTTERANCE_ID = "enviouswispr-phone-uat";
    private static final String UAT_UTTERANCE_FILE = "speaker-utterance.txt";
    private static final String DEFAULT_UTTERANCE = "This is a physical phone insertion test.";
    private TextToSpeech textToSpeech;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        textToSpeech = new TextToSpeech(this, this);
    }

    @Override
    public void onInit(int status) {
        if (status != TextToSpeech.SUCCESS || textToSpeech == null) {
            finish();
            return;
        }
        Set<Voice> voices = textToSpeech.getVoices();
        Voice offlineVoice = null;
        if (voices != null) {
            for (Voice voice : voices) {
                if (Locale.US.equals(voice.getLocale())
                        && !voice.isNetworkConnectionRequired()) {
                    offlineVoice = voice;
                    break;
                }
            }
        }
        if (offlineVoice == null
                || textToSpeech.setVoice(offlineVoice) != TextToSpeech.SUCCESS) {
            finish();
            return;
        }
        textToSpeech.setSpeechRate(0.85f);
        textToSpeech.setOnUtteranceProgressListener(new UtteranceProgressListener() {
            @Override
            public void onStart(String utteranceId) {
                // No-op.
            }

            @Override
            public void onDone(String utteranceId) {
                runOnUiThread(SpeakerPlaybackActivity.this::finish);
            }

            @Override
            public void onError(String utteranceId) {
                runOnUiThread(SpeakerPlaybackActivity.this::finish);
            }
        });
        Bundle parameters = new Bundle();
        parameters.putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1f);
        String utterance = loadPrivateUtterance();
        int result = textToSpeech.speak(
                utterance,
                TextToSpeech.QUEUE_FLUSH,
                parameters,
                UAT_UTTERANCE_ID
        );
        if (result != TextToSpeech.SUCCESS) {
            finish();
        }
    }

    private String loadPrivateUtterance() {
        File staged = new File(getFilesDir(), UAT_UTTERANCE_FILE);
        if (!staged.isFile()) {
            return DEFAULT_UTTERANCE;
        }
        try {
            byte[] bytes = Files.readAllBytes(staged.toPath());
            if (bytes.length == 0 || bytes.length > 500) {
                return DEFAULT_UTTERANCE;
            }
            String value = new String(bytes, StandardCharsets.UTF_8).trim();
            return value.isEmpty() ? DEFAULT_UTTERANCE : value;
        } catch (Exception ignored) {
            return DEFAULT_UTTERANCE;
        } finally {
            try {
                Files.deleteIfExists(staged.toPath());
            } catch (Exception ignored) {
                // The test package is removed after UAT; never expose this app-private file.
            }
        }
    }

    @Override
    protected void onDestroy() {
        if (textToSpeech != null) {
            textToSpeech.shutdown();
            textToSpeech = null;
        }
        super.onDestroy();
    }
}
