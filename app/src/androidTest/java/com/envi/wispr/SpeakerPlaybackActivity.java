package com.envi.wispr;

import android.app.Activity;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.speech.tts.Voice;
import android.view.WindowManager;

import java.util.Locale;
import java.util.Set;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * Test-only phone-speaker source for physical-device dictation UAT.
 *
 * <p>TWO REASONS THIS WINDOW IS NOT FOCUSABLE. Dictation pins the editor the user was in, and this
 * activity is launched while that editor is meant to stay the focused one. An ordinary window took
 * focus away from the app under test, so the pin found nothing and every run reported "No editor
 * was pinned for this dictation" — the harness producing the exact outcome it was there to measure.
 * The flags are the same three {@code ui.VoiceInputActivity} uses for the same reason.
 *
 * <p>IT PREFERS A RECORDED FIXTURE OVER THE PHONE'S OWN VOICE. When
 * {@code cache/enviouswispr-uat.pcm} exists in this package it is played through the media output
 * as 16 kHz mono signed PCM, which is what the app records at. That is the path for audio generated
 * off the phone, Azure Speech being the one this project uses, and it removes the phone's offline
 * voice as a variable. With no fixture present it falls back to that offline voice and the sentence
 * in {@code files/speaker-utterance.txt}.
 */
public final class SpeakerPlaybackActivity extends Activity implements TextToSpeech.OnInitListener {
    private static final String UAT_UTTERANCE_ID = "enviouswispr-phone-uat";
    private static final String UAT_UTTERANCE_FILE = "speaker-utterance.txt";
    private static final String UAT_FIXTURE_FILE = "enviouswispr-uat.pcm";
    private static final String DEFAULT_UTTERANCE = "This is a physical phone insertion test.";
    private static final int SAMPLE_RATE = 16_000;
    private static final int BYTES_PER_SAMPLE = 2;
    private TextToSpeech textToSpeech;
    private AudioTrack audioTrack;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
        );
        if (playFixture()) {
            return;
        }
        textToSpeech = new TextToSpeech(this, this);
    }

    /** @return true when a recorded fixture was queued, so the offline voice is not needed. */
    private boolean playFixture() {
        File fixture = new File(getCacheDir(), UAT_FIXTURE_FILE);
        if (!fixture.isFile() || fixture.length() == 0) {
            return false;
        }
        try {
            byte[] pcm = Files.readAllBytes(fixture.toPath());
            audioTrack = new AudioTrack.Builder()
                    .setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build())
                    .setAudioFormat(new AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(SAMPLE_RATE)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build())
                    .setBufferSizeInBytes(pcm.length)
                    .setTransferMode(AudioTrack.MODE_STATIC)
                    .build();
            if (audioTrack.write(pcm, 0, pcm.length) <= 0) {
                return false;
            }
            audioTrack.play();
            // Finish on the clip's own length plus a margin. The activity holds nothing the test
            // needs afterwards, and a marker listener would add a second way for this to hang.
            long millis = (pcm.length / (long) (SAMPLE_RATE * BYTES_PER_SAMPLE)) * 1000L + 1_500L;
            new Handler(Looper.getMainLooper()).postDelayed(this::finish, millis);
            return true;
        } catch (Exception ignored) {
            return false;
        }
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
        if (audioTrack != null) {
            audioTrack.release();
            audioTrack = null;
        }
        super.onDestroy();
    }
}
