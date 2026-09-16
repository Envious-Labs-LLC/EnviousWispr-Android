# Silent physical-phone dictation

Proven on the Samsung SM-S948U1, Android 16, unchanged Play build 133, September 16, 2026. See `PROOF.txt`.

This is an external, ADB-shell-only test helper. Nothing is compiled into the debug or release APK. No app replacement, root, permission grants, developer-toggle changes, audio upload, or volume changes are needed on this phone. Existing authorized ADB is required.

Android's privileged `AudioPolicy` injection mixer matches only the installed `com.envi.wispr` UID. Its `ROUTE_FLAG_LOOP_BACK` has no render flag. `createAudioTrackSource` feeds the mix into the app's real `AudioRecord`, then the normal file, ASR, polish and insertion pipeline runs. The helper verifies `TYPE_REMOTE_SUBMIX` before writing any speech and throughout injection. The track is a virtual source, not a media/speaker player.

Platform source: https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android16-release/media/java/android/media/audiopolicy/AudioPolicy.java (`createAudioTrackSource`), and the neighboring `AudioMixingRule.java` (`MIX_ROLE_INJECTOR`). Ordinary apps lack the MODIFY_AUDIO_ROUTING permission; this Samsung's existing shell UID has it. Context attribution explicitly identifies the actual shell UID/package. No permission or hidden-API security setting is changed.

## One call

Focus an empty editor on the unlocked phone. Use a synthetic fixture already ON THE PHONE, raw 16 kHz mono signed 16-bit little-endian, 0.1 to 30 seconds:

```sh
python3 scripts/uat/silent-audio/run.py \
  --serial 100.94.206.47:5555 \
  --pcm /data/local/tmp/my-synthetic-fixture.pcm \
  --expect 'The purple lantern is beside the quiet garden.' \
  --receipt /tmp/silent-phone-proof
```

The harness compiles with Java 21 and Android API 36, converts the class with build-tools 34.0.0 `d8`, pushes a uniquely named DEX jar, and executes it with `adb shell CLASSPATH=... app_process /system/bin SilentAudio <phone-pcm>`. It registers the mix, verifies the route, arms cancellation, starts the normal exported recorder, waits for the real capture-start log, injects PCM, stops the take, waits for the insertion result, and compares the actual editable field with `--expect`. It never submits the field. No test APK build/install is needed; an existing native test target can be used.

To recreate this run's synthetic fixture without audible playback:

```sh
say -v Samantha -o /tmp/silent-fixture.aiff 'The purple lantern is beside the quiet garden.'
ffmpeg -i /tmp/silent-fixture.aiff -ar 16000 -ac 1 -f s16le /tmp/silent-fixture.pcm
~/Android/sdk/platform-tools/adb -s 100.94.206.47:5555 push /tmp/silent-fixture.pcm /data/local/tmp/my-synthetic-fixture.pcm
```

This transfers generated test audio TO the phone, never recorded audio FROM it. The helper reads only the supplied phone-local file. A local Android TTS-to-file fixture can also be used after conversion to the specified PCM format.

## Cleanup and limits

Normal and error exits release the track and unregister the mixer. EOF cancels an armed take. An on-phone 60-second deadline cancels an armed take and exits; binder death removes its audio policy even if the host disappears. The harness removes its unique jar. The caller owns the input PCM and editor contents; remove that synthetic fixture and discard the test editor when finished. Successful takes create ordinary History entries, exactly like real dictation. Never remove unrelated history or models. Only run with exclusive use of the phone.

The PCM path bypasses the physical microphone, its acoustics and Bluetooth routing. It proves the actual installed recording/recognition pipeline under virtual input. It is not microphone quality or Bluetooth UAT. Hidden framework APIs and shell privileges can change on other Android/Samsung versions. It requires an unlocked device and a usable focused editor for insertion proof. No compatibility claim is made for other devices.

Silence evidence is software routing: both injector output and the app's active input were the same remote submix, with no render flag; media volume was untouched at 7. No external sound meter was used, and unrelated notification/system sounds are outside this helper's scope.

Chrome observations were mixed: one take visibly inserted the exact phrase but the app judged it UNVERIFIED; a repeated take reported VERIFIED while Chrome's field stayed empty. The harness correctly rejected that false-positive. This tool does not fix Chrome insertion. The final native test target run matched the exact text and reported VERIFIED with SURROUNDING evidence.
