# Issue #5 — Stop recording on silence — 2026-09-03

GitHub issue: `#5`. Tier: REFACTOR. Status: BUILT (Gate 2 signed off 2026-09-03, slider by founder decision; code review applied, hardware run outstanding).

## Preface — Lane + Hardware UAT declaration

**Lane:** Code

Detection is re-run against the real branch before the grounded review. The change set is confined to
`app/**` plus this plan file, so `Code` is primary and `Docs/dev-tooling` is the secondary lane the plan
file alone triggers. Obligations: `tests`, `codex-review`, `hardware-uat`, `cited-symbols`.

**PAR rows closed:** `PAR-025` — *"Automatically stop after silence | Neural VAD switch with adjustable
pause duration"* (`docs/enviouswispr-android-parity-spec.md:51`). Evidence that closes it: the hardware run
in §11.1, in which a real spoken take on the S26 ends without the stop button being pressed AND the
transcript contains the spoken words, plus the settings surface that makes the pause duration adjustable.

**PAR rows deliberately NOT closed:** `PAR-023` (pre-roll) and `PAR-024` (microphone readiness). See §2.2.

**Hardware UAT:** Y

Saurabh turns on "Stop recording on silence", double-presses the side button in Gmail, says *"let me know
if Thursday works for the review"*, and stops talking. After sustained silence, nominally about 1.8 seconds
at the default setting before model-boundary effects, the recorder closes by itself, the words are
transcribed, and they land in the Gmail compose field. He never touches the phone between speaking and
reading the result.

## Preface — User Rubric

Resolved against **Aaron Wu** (RSI and accessibility, front-end engineer; wants a one-hand trigger,
technical accuracy, and no modal interruptions), from
`~/Developer/EnviousLabs/EnviousMarketing/packs/enviouswispr/brand-guide.md` §4.

1. **Who is this user in this moment?** Aaron is mid-afternoon, wrists already sore, writing a review
   comment on a pull request in Chrome. Thirty seconds ago he was reading a diff. Thirty seconds from now
   he wants a three-sentence comment posted without having typed it, and without a second deliberate press
   that his hands are the reason he is avoiding.
2. **Why would they want this?** In his words: *"I already told it I was done by shutting up. Why am I
   pressing a button to say it again?"*
3. **How would they invoke it?** He turns the switch on once and never thinks about it again. After that it
   is not invoked at all: it fires at the end of every dictation. Voluntary at setup, automatic in use.
4. **What app are they in?** From the persona list: Chrome (GitHub review comments), Slack, a
   terminal-adjacent editor, and Gmail. All are ordinary editable fields reached through the existing
   insertion path, so none of them changes what this feature has to do.
5. **What is their natural input?** Five samples in his voice, as spoken, including the pauses:
   - *"this looks good but can we pull the null check up into the caller"*
   - *"nit, the variable name here reads like a boolean and it's a count"*
   - *"I think this breaks on an empty list, can you add a case"*
   - *"approving, the flake in CI is unrelated, it's the same timeout as yesterday"*
   - *"can you split this into two commits, the rename is drowning the real change"*
   The fourth is the dangerous one: he pauses noticeably after *"approving,"* while deciding how much
   detail to give.
6. **What does success feel like?** He notices nothing. He talks, he stops, the words appear. The moment he
   would say "oh nice" is the first time he realises he has not pressed stop all afternoon.
7. **What does wrong-not-broken look like?** It cuts him off mid-thought. He pauses to choose a word, the
   recorder closes, and the rest of his sentence is never captured. He does not file a bug. He turns the
   switch off and never turns it back on.
8. **What would a power user hack around this to get?** He would lengthen the pause until it stopped cutting
   him off, and if the slider did not go far enough he would turn it off and keep pressing stop. That is the
   signal the pause duration must be adjustable, not that the detector needs to be smarter.
9. **What level of control would they want?** The ladder: off (press stop as today), on with a short pause
   for clipped dictation, on with a long pause for thinking out loud. This ships the whole ladder.

### Cross-persona check

- **Aaron Wu** — the primary win. Removes a press from every dictation.
- **Meera Patel** (one-handed iMessage, time-pressed) — same win, larger. One-handed means the second press
  is the awkward one.
- **Frank Chen** (72, arthritis, wants three settings or fewer) — **satisfied by matching macOS exactly**, which
  exposes two controls, not four. See §3.6; the plan previously claimed a divergence here that does not exist.
- **Marcus Weber** (writer, dictates long passages with thinking pauses) — the **opposing** persona.
  Auto-stop is hostile to how he works, and the slider does not fix that. Resolved by shipping the switch
  OFF, which is also the macOS decision. His four-second writing pauses exceed the slider's maximum, so the
  honest position is that this feature is not for his rhythm, rather than that the slider accommodates him.
- **Diana Foster** (PM, zero app-switching) — neutral. Her flow is hold-and-release.
- **Dr. Elena Vasquez** (privacy-first, IT-defensible) — checked, not assumed. The detector is an on-device
  model, opens no network connection, and writes no new file. Nothing here changes the disclosure.
- **Priya Ramachandran** (sub-second, technical vocabulary) — neutral on accuracy, mildly positive on speed.

**The tension between Aaron and Marcus is the whole design.** One wants the recording to end the instant he
stops; the other needs it to survive a four-second pause. A single fixed timeout cannot serve both, which is
why the pause duration is a user control and why the feature ships off.

---

## 0. TL;DR

Recording can only end two ways today: the user presses stop, or a hard two-minute ceiling cuts them off in
silence. This adds a third and better ending. When the user stops talking, the take ends on its own and runs
the normal transcribe-and-insert path.

The detector is Silero VAD through the sherpa-onnx API **already inside the AAR this app already ships**, so
no new library and no new native code. It runs in a **new `:vad` process**, because sherpa-onnx terminates
its host process on a model or input contract violation and an optional feature may not be able to kill a
recording. The auto-stop state machine is macOS's, advanced once per 256 ms block, so macOS's resolved
constants port with no arithmetic.

Tier REFACTOR: this changes an AIDL surface and adds a process. The runtime behaviour is medium-sized;
tier routing escalates upward.

## 1. Problem

`AudioCaptureService` sets exactly three terminal reasons
(`app/src/main/java/com/envi/wispr/audio/AudioCaptureService.kt:27-30`):

| Constant | Set at | Meaning |
|---|---|---|
| `TERMINAL_REASON_MANUAL` | `:229` | The user pressed stop |
| `TERMINAL_REASON_MAX_DURATION` | `:179` | The 120-second ceiling fired |
| `TERMINAL_REASON_ERROR` | `:209` | Capture failed |

Every successful dictation today requires a deliberate second press. Issue #5 states it directly: *"This is
the biggest UX gap — manual stop is clunky."*

A capability sweep across `app/src`, `llama-android/src` and `accelerator-benchmark/src` for
`preroll|pre_roll|ring ?buffer|silero|\bvad\b|voiceactivity|autostop|auto_stop|silencedetect` returns one
hit, an unrelated comment at `app/src/androidTest/java/com/envi/wispr/VoicePipelineDeviceTest.kt:236`.

**The capture loop's decision granularity is about one second, and that is already shipping.** The read
buffer is coerced to a 32,000-byte floor at `:93` (one second at 16 kHz PCM16), the same value is used as
the read length at `:173`, and the whole block is requested at `:186`. Evidence that this is live rather
than theoretical: the max-duration check at `:176-183` runs at the top of each iteration, so the
120-second cap can only fire on a block boundary and already overshoots by up to one block.

## 2. Goals & non-goals

### 2.1 Goals

1. With the setting on, a take containing speech followed by sustained silence ends on its own and runs the
   same stop path a manual press runs.
2. The user can turn it on and off and set how long the silence must last.
3. With the setting off, no detector process is started and manual capture, transcription, terminal reasons
   and insertion behave as they do today.
4. **No detector load, process, inference, binding, transport, timeout, or teardown failure can terminate,
   block, or release the `AudioRecord`.** A detector classification may request a silence stop through the
   one narrow `requestStop` (proposed) path; a FALSE classification is a behavioural error, covered
   separately in §7 as the feature's highest-cost one, not a mechanical impossibility. This is the goal the
   previous revision could not meet, and it is why §3.4 changed.
5. **The user is told when an enabled take cannot make auto-stop available before use.** A detector lost
   after it became ready fails open silently for that take.

### 2.2 Non-goals

- **The two-minute cap.** Untouched. Saurabh decided 2026-09-03 that it goes to about an hour and that it
  is a separate ticket; recorded on #41 along with the second enforcement point in `AsrService` and the
  memory arithmetic for an hour-long take.
- **Pre-roll and warm capture (`PAR-023`, `PAR-024`, #27, #28).**
- **A low-frequency input filter.** macOS ships one and it is measured, but on a different model and
  runtime. See §3.3.
- **An energy gate.** Same standard, same answer.
- **Exposing VAD sensitivity.** Not exposed on macOS either. See §3.6.
- **Streaming ASR and live partial text (#6, #33).**
- **The live audio meter (#44).** `getCurrentAmplitude()` stays unread.
- **Model download machinery.** The model is bundled and committed.

## 2.5 Grounding brief

### 1. Trace producer to owner to consumer, end to end

Measured in this checkout at main, commit c93b040.

| Hop | Mechanism | Cite |
|---|---|---|
| Four entry surfaces issue commands | `Intent` action to one service | `ui/VoiceInputActivity.kt:38-53`, `shortcuts/DictationTileService.kt:43`, `shortcuts/DictationNotificationController.kt:129,140,171`, `paste/RecordingAccessibilityOverlay.kt:95,101` |
| One session owner receives them | `DictationSessionService.sendCommand` builds the intent; `onStartCommand` consumes it | `ui/DictationSessionService.kt:281-305` |
| The session waits for settings before it binds anything | `beginSession` awaits both readiness signals with a 10 s timeout, and shows `Settings could not be loaded. Try again.` if they do not arrive | `ui/DictationSessionService.kt:310-357`, await at `:326-327` |
| Owner binds capture | `bindService` to `AudioCaptureService` | `ui/DictationSessionService.kt:359-368` |
| Owner starts a take | AIDL `startCapture()` | `ui/DictationSessionService.kt:388` |
| Capture runs in another process | `android:process=":audio"` | `app/src/main/AndroidManifest.xml:60-61` |
| Capture writes PCM | `AudioRecord.read` to `cacheDir/recording.pcm` | `audio/AudioCaptureService.kt:117,186-191` |
| Capture ends a take itself | sets `terminalReason`, clears `isRecording` | `audio/AudioCaptureService.kt:177-183` |
| Owner notices the self-stop | 100 ms poll of `isCapturing` and `terminalReason` | `ui/DictationSessionService.kt:433-458` |
| Owner runs the normal ending | `stopAndTranscribe()` | `ui/DictationSessionService.kt:461` |

**There is exactly one start path.** Proof:

```
/usr/bin/grep -rn "IAudioCaptureService\|AudioCaptureService" app/src \
  --include="*.kt" --include="*.java" --include="*.aidl" --include="*.xml"
```

returns `DictationSessionService` as the only client of the interface. `app/src/androidTest` does not bind
it. So one appended AIDL method covers every start path and the four entry surfaces need no change.

### 2. Find the existing authority before proposing one

- **Silence detection**: `app/libs/sherpa-onnx.aar` (40,314,243 bytes, declared `app/build.gradle.kts:77`)
  already contains `com/k2fsa/sherpa/onnx/Vad.class`, `VadModelConfig.class`, `SileroVadModelConfig.class`,
  `TenVadModelConfig.class`, `SpeechSegment.class` (external). **The authority exists and already ships.**
- **The take-ended decision**: `AudioCaptureService.terminalReason`. Extended, and its ownership tightened
  (§3.5).
- **The self-stop notification path**: `DictationSessionService.startPolling`. Reused, extended with a
  status read.
- **User settings**: `AppPreferences` (`settings/AppPreferences.kt:16`). Two fields added.
- **Verify-before-load**: `polish/S1ModelSelection.kt:20-26` is the existing pattern and is followed, not
  reinvented.
- **Downloadable models**: `ModelManifest.all = listOf(parakeet, s1)` (`models/ModelManifest.kt:42`).
  **Deliberately NOT extended**; see §3.9.
- **Third-party notices**: `app/src/main/assets/THIRD_PARTY_NOTICES.txt`, read and displayed at
  `ui/SettingsActivity.kt:54`.

### 3. Read prior attempts and live direction

- **Session log: nothing prior.** Zero hits for `vad`, `pre-roll`, `silence`, `auto-stop`, `duration cap`
  across 1285 lines. Control on the same file: `insertion` 11, `polish` 99, `audio` 7, `record` 28.
- **The macOS decision is binding.** Catalog `decision` row of 2026-05-30: *"Fresh installs use ... silence
  auto-stop off, and a 1.5-second silence timeout with 0.5 sensitivity and the energy gate on"*.
- **macOS behaviours**, from the catalog: off plus silence means keep recording; sustained post-speech
  silence with it on runs the **normal** stop and delivery path; a model that cannot prepare shows a
  four-second in-panel notice, **keeps recording**, and leaves the duration cap active.
- **macOS user copy**, reused verbatim: toggle `Stop recording on silence`; pause explanation `How long to
  wait after you stop speaking before ending the recording.`; failure notice `Auto-stop on silence is
  unavailable right now`.

### 4. Lifecycle, trust and process boundaries a naive design would miss

| Boundary | Today | Planned |
|---|---|---|
| Default process vs `:audio` | Settings live in DataStore in the default process | Values pass as AIDL parameters at start, frozen for the take |
| **Preferences on cold start** | **`beginSession` already awaits `cleanupPreferencesReady` before binding capture** (`:326`), and refuses to start if it times out. The nullable `clipboardPolicy` at `:161-167` is nullable because the NOTIFICATION is built before that await, not because capture is | The two new fields are written by the same collector, in the same block, **before** `cleanupPreferencesReady.complete(Unit)` at `:271`. A user who enabled auto-stop gets it on the first take. No plausible fallback is substituted anywhere |
| The capture thread | Blocking `AudioRecord.read` loop | Copies PCM into a preallocated slot and advances an index. No allocation, no logging, no binder call, no lock |
| **Detector failure** | n/a | Native detector code and its exit paths execute only in `:vad`. `:audio` observes only synchronous results, binder failure and connection lifecycle events through the narrow contract in §3.4 |
| Detector ready vs not | n/a | Capture starts immediately; the detector prepares behind it. Not-ready cannot stop a take |
| Binder death, `onDestroy`, process death, immediate restart | Existing paths | Enumerated in §5 |

### 5. Prove the high-risk premises

| Premise | Evidence |
|---|---|
| The AAR already contains the VAD API | `unzip -l` on the AAR then on `classes.jar`; five class entries |
| No `.onnx` ships inside the AAR | `unzip -l ... \| grep -iE "\.onnx\|assets"` returns nothing |
| **`Vad.compute` advances the recurrent state itself** | `sherpa-onnx` tag `v1.12.29`, `sherpa-onnx/csrc/silero-vad-model.cc` (external) lines 407-440: `RunV4` (external) pushes `states_[0]`/`states_[1]` as inputs and writes `out[1]`/`out[2]` back. **So it returns a probability AND mutates state**; `acceptWaveform` must never be called on the same `Vad` |
| The bundled model is Silero v4 exported by k2-fsa | Its own embedded metadata: `model_type: silero-vad`, `comment: "This is silero-vad v4 exported to onnx by k2-fsa. Only the 16kHz branch is kept"`. Graph inputs `x`, `h`, `c`; outputs `prob`, `new_h`, `new_c` (external); no `sr` input |
| **That model faces no window-size restriction and needs no overlap buffer** | sherpa's `silero-vad-model.cc` (external) lines 199-217: `IsExportedByK2Fsa()` (external) matches exactly those six names, so `is_v5_ = false` on the first branch and the `window_size != 512 → exit(-1)` guard at its lines 209 to 212 is never reached. `window_overlap_` (external) is 0 at line 467 and is assigned 64 ONLY inside the v5 branch at line 207, so `WindowSize() == WindowShift() == window_size` for v4 |
| **sherpa terminates the host process on a contract violation** | 21 `exit(-1)` calls in that one file, at lines 43, 65, 98, 212, 216, 288, 294, 299, 304, 309, 316, 322, 327, 332, 340, 346, 352, 357, 364, 370, 376. Not catchable from Kotlin |
| macOS's shipped constants at sensitivity 0.5 | `SilenceDetector.fromSensitivity` (external) at `SilenceDetector.swift` (external) lines 55-83: onset `0.6 - 0.5*0.375 = 0.4125`, offset `max(0.1, 0.4125-0.15) = 0.2625`, alpha `0.3 + 0.5*0.2 = 0.4`, hangover `3`, confirmation `1`. **The struct defaults of 0.3/0.5/0.35 are NOT what ships** |
| `Vad` loads a plain JNI library | `javap -c` of the static initialiser: `ldc "sherpa-onnx-jni"; invokestatic System.loadLibrary`. Not a `ContentProvider` bootstrap, so `code-gotchas.md` RULE: a-contentprovider-initialised-library-is-dead-in-every-other-process does not bite |
| A second process gets its own everything | Android gives each process its own VM, linker state, native globals and heap; read-only `.so` pages may be shared, runtime allocations are not |
| An assets path exists and is already read | `app/src/main/assets/THIRD_PARTY_NOTICES.txt` (17,962 bytes), opened at `ui/SettingsActivity.kt:54` |
| **A `.onnx` asset WILL be deflated** | Measured with `unzip -v` on the existing APK: our text asset is `Defl:N` at 60%, while ML Kit's `assets/tflite_langid.tflite.jpg` is `Stored` — a TensorFlow Lite model renamed to `.jpg` because `.jpg` is on AAPT's never-compress list. `.onnx` is not |
| No `androidResources` (external), `noCompress` (external), `splits`, `buildTypes`, ProGuard or R8 exist today | Swept every `*.kts` and `*.gradle`; only two `abiFilters` lines |
| Only arm64 ships | `app/build.gradle.kts:20` |
| Current debug APK | 139,247,608 bytes, built 2026-09-03 |
| Silero VAD is MIT | the model's own metadata URL, and `~/Developer/EnviousLabs/EnviousWispr/Sources/EnviousWispr/Resources/silero-vad-coreml-LICENSE.txt` |
| `beginSession` guarantees settings before capture | `ui/DictationSessionService.kt:324-333`, `withTimeoutOrNull(10_000L) { cleanupPreferencesReady.await(); structuredTermsReady.await(); true }`, and `bindPipelineServices()` only inside the success branch |

**Not verified, and marked so rather than asserted:** what `AudioRecord.getMinBufferSize` actually returns
on the S26 for this format, and therefore whether the 32,000-byte floor binds. It almost certainly does, but
nothing logs it. §11.1 settles it with one log line. Also unverified: detector prepare cost, `:vad` resident
memory, and per-frame inference time. All measured on the phone, never asserted here.

## 3. Design

### 3.1 The signal, and the trap macOS already fell into

**Auto-stop is driven by the detector's RAW PROBABILITY through a smoothed state machine, not by the
detector's own speech-segment boundaries.** macOS states this as a contract in `SilenceDetector.swift`
(external):

> Two-signal contract (do not conflate): Segment boundaries come from FluidAudio's `VadStreamResult.event`.
> Authoritative. […] Auto-stop comes from the smoothed EMA + hangover state machine on raw probability.
> Migrating either signal onto the other path requires a deliberate decision — they have different timing,
> different thresholds, and serve different consumers.

The standard sherpa-onnx Android recipe is `acceptWaveform` (external) plus `isSpeechDetected` (external) or
the segment queue. **That is the conflation macOS names.** This plan calls `Vad.compute` (external) instead,
which returns the raw probability and advances the model's recurrent state.

**`compute` and `acceptWaveform` must never both be called on the same `Vad`**, because each advances the
recurrent state and calling both would advance it twice for the same audio.

Android gets a simplification macOS could not have: **Android does not trim ASR input by VAD segments.**
`AsrService` decodes the whole captured file, so the detector has one consumer and the segment queue is
never populated. A detector mistake therefore cannot remove bytes already written to the PCM file. **A false
silence decision can still stop FUTURE capture and omit speech spoken afterwards**, which remains the
highest-cost behavioural error in §7.

### 3.2 Auto-stop advances on 256 ms blocks

**There is no per-frame state machine and no Android-specific alpha, confirmation or hangover constant.**
The state machine advances exactly once per completed 4096-sample, 256 ms block, which is macOS's own
cadence, so macOS's numbers port with no arithmetic.

Within a block the adapter calls `Vad.compute` (external) eight times, once per 512 fresh samples. **No
rolling overlap buffer is needed**, because this artifact is a v4 k2-fsa export for which
`WindowSize() == WindowShift() == window_size` (sherpa's `silero-vad-model.cc` (external), lines 173-177,
467 and 207).

**The block probability is the EIGHTH window's probability.** Silero processes all eight windows in order,
so its recurrent `h` and `c` state has advanced across the complete 4096-sample block by the time the
eighth returns. The eighth result is the detector's state at the 256 ms decision boundary, and it is not
context-free: `h` and `c` already carry the preceding seven windows.

**A maximum is deliberately NOT used**, and this reverses an earlier proposal in this plan. A maximum
discards temporal order, lets one false-positive 32 ms window arm speech for a whole block while the user is
idle, and shifts the probability distribution under thresholds inherited from a different model. The
consonant-gap case it was meant to protect is already covered: a single low eighth window only ENTERS
hangover, and speech in any later block cancels the pending stop.

**This is a faithful port of the macOS state machine and its decision cadence. It is not a claim that a v4
512-sample probability source is numerically identical to macOS's native 4096-sample model.**

The state machine, copied from `SilenceDetector.advanceStateMachine` (external):

```
smoothed = alpha * raw + (1 - alpha) * smoothed

idle:      smoothed >= onset  -> consecutiveAboveOnset++
                                 if >= confirmation: phase = speech
           else               -> consecutiveAboveOnset = 0
speech:    smoothed <  offset -> phase = hangover(effectiveHangoverBlocks)
hangover:  smoothed >= onset  -> phase = speech
           else               -> remaining--
                                 if remaining <= 0: phase = idle; STOP
```

**macOS's values at sensitivity 0.5, resolved through `fromSensitivity` (external) rather than read off the
struct defaults:**

| Parameter | Value | Derivation in `SilenceDetector.swift` (external) lines 55-83 |
|---|---|---|
| EMA alpha | `0.4` | `0.3 + 0.5 * 0.2` |
| Onset threshold | `0.4125` | `0.6 - 0.5 * 0.375` |
| Offset threshold | `0.2625` | `max(0.1, 0.4125 - 0.15)` |
| Onset confirmation | `1` block | `sensitivity < 0.3 ? 2 : 1` |
| Minimum hangover | `3` blocks | `sensitivity > 0.7 ? 4 : 3` |
| Effective hangover | `max(3, ceil(pauseSeconds / 0.256))` blocks | as macOS |

**The struct defaults of alpha `0.3`, onset `0.5` and offset `0.35` are NOT what macOS ships.** An earlier
revision of this plan carried them as if they were.

**The first below-offset block ENTERS hangover without decrementing it**, so the countdown is consumed by
the blocks that follow. Nominal low-probability duration before a stop is therefore
`(1 + effectiveHangoverBlocks) * 0.256`, before any EMA or mixed-block delay:

| Pause setting | Countdown blocks | Below-offset blocks through stop | Nominal duration |
|---|---:|---:|---:|
| 0.5 s | 3 | 4 | 1.024 s |
| 1.0 s | 4 | 5 | 1.280 s |
| 1.5 s (default) | 6 | 7 | 1.792 s |
| 2.0 s | 8 | 9 | 2.304 s |
| 3.0 s | 12 | 13 | 3.328 s |

That is macOS's real behaviour, not a rounding of it, and porting it faithfully means reproducing it.

Detector and state-machine state are reset between takes: `Vad.reset()` (external) rebuilds `h` and `c` and
also clears the sherpa-internal `triggered_` (external), `current_sample_` (external),
`temp_start_` (external) and `temp_end_` (external) fields.

### 3.3 Android v1 uses unconditioned detector input

The first implementation feeds raw microphone samples to Silero.

macOS ships a two-section high-pass on the detector's copy of each chunk, and its result is real: on 36
labelled clips decoded by the shipped Parakeet v3, mean word survival went 90.2% to 98.0%, clips losing more
than 10% of their words went 6 to 1, and false alarms on 32 labelled non-speech clips improved 28% to 19%.

**That is IMPORTED evidence and it is treated as such.** It was measured on a different model in a different
runtime, and on macOS the failure mattered because VAD segments trimmed ASR input, which does not happen
here. This plan refuses the energy gate for want of Android evidence; a weaker standard for the filter would
be inconsistent.

Hardware observation 10 records raw-probability and false-stop behaviour in a noisy room. If Android
reproduces the low-frequency failure, a follow-up issue ports `VADInputHighPass` (external) with its corpus
and its before-and-after gates. This issue does not add it.

### 3.4 Process placement and failure isolation

**The detector runs in a dedicated `:vad` process. Audio capture stays in `:audio`.**

sherpa-onnx does not throw on a contract violation. It calls `exit(-1)`: 21 times in the VAD loader alone,
covering a missing model file, a wrong sample rate, an unrecognised model version, a wrong frame length, and
every metadata validation. **`exit(-1)` is not catchable from Kotlin**, so a detector hosted inside `:audio`
could kill a live recording. Verifying the model's hash first closes the file-shaped cases and leaves the
input-shaped ones, which is a bucket where a pipe is available.

**Preallocation, so the capture thread allocates nothing.** Before `AudioRecord.startRecording`,
`CaptureSession` receives a `readBuffer` (proposed) of 8,192 bytes, eight preallocated ring slots of 8,192 bytes each,
and the native `AudioRecord` buffer size, still coerced to at least 32,000 bytes. `captureLoop` allocates
nothing: it calls `AudioRecord.read(readBuffer, 0, readBuffer.size, AudioRecord.READ_BLOCKING)`. Android
permits a read smaller than the native recorder buffer and returns a frame-size multiple. Every downstream
loop uses the returned `bytesRead`, never the requested size. **The current code allocates its read buffer
inside `captureLoop`, on the capture thread; that allocation moves.**

**The ring.** Eight 8,192-byte slots is 65,536 bytes and about 2.048 seconds of blocks. The capture thread
only copies PCM into a slot it already owns and advances a non-blocking index: no allocation, no logging, no
binder call, no lock (`architecture-rules.md` RULE: protect-audio-asr-stability). An ordinary blocking queue
would violate that rule. **If binding, startup, or a synchronous detector call has not progressed before the
ring fills, `:audio` marks the take unavailable, clears the pending blocks, unbinds `:vad`, and never
resumes VAD for that take** — a gap breaks Silero's recurrent continuity, and resuming across one could turn
resumed speech into an early stop.

**Synchronous detector contract. There is no detector-to-audio callback interface.** `:audio` calls `:vad`
synchronously from the feeder thread, which is already allowed to block. The capture thread never makes a
binder call.

```aidl
interface ISilenceVadService {
    int start(long captureToken, float pauseSeconds);
    int processBlock(long captureToken, in byte[] pcm16);
    void finish(long captureToken);
}
```

`start` returns `READY` or `UNAVAILABLE`. `processBlock` (proposed) returns `CONTINUE` (proposed), `SILENCE` or `UNAVAILABLE`.
`finish` releases state only when its token matches the active detector session. Every return is checked
against both the active `CaptureSession` identity and its capture token before it can update status or
request a stop: **a synchronous return can still arrive after local teardown if the remote call stalled**, so
the token check remains required even with the independent callback direction gone.

**Every synchronous detector call has a process-local hard deadline.** AIDL calls are synchronous and have
no built-in timeout, so a permanently stalled `start` (proposed), `processBlock` or `finish` (proposed)
would block the feeder forever. An abandoned feeder thread and binder call is not an accepted steady state:
`architecture-rules.md` RULE: no-idle-cost sets the standard at zero, not small.

`SilenceVadService` (proposed) owns one watchdog executor, created with the service and released in `onDestroy`.
Before any of the three methods enters model code it arms a generation-stamped 2,000 ms deadline; returning
normally disarms that generation. If the deadline wins, the watchdog calls
`android.os.Process.killProcess(android.os.Process.myPid())`. **The deadline matches the eight-slot ring's
2.048 seconds of capacity**: past that point the detector cannot catch up without a discontinuity anyway.

Killing only `:vad` turns an unbounded synchronous call into ordinary binder death, which this design
already handles: the feeder receives `DeadObjectException` (external), the enabled take becomes unavailable,
the feeder terminates, and capture continues. The bounded feeder join stays as a final defence, not as the
normal mechanism for surviving a detector hang.

The callback interface was deleted rather than guarded, under a commitment made before the grounded review's
verdict was read: three or more unenumerated members of the "a message arrives after the take it belongs to
ended" class meant deleting the direction that produces them. Android's binding API supplied four.

**Detector teardown never owns capture teardown.** `CaptureSession` invalidates the detector token, clears
the ring, unbinds `:vad`, and waits only a bounded interval for the feeder. Whether the feeder returns or
not, the capture thread remains the sole owner of closing the PCM file and the `AudioRecord`, and `session`
becomes eligible for a new take once those heart resources close. A feeder still alive after the bounded
join holds no `AudioRecord`, file stream, ring slot, or reference to a later session, and any eventual
return fails the active-session and token checks. **A detector hang may disable auto-stop; it may not refuse
the next recording.**

**A broken probability is rejected, not coerced.** Added during the code review. Reading a non-finite or
out-of-range inference result as silence would walk the hangover and end the take, which is an inference
failure reaching the recording by the one path this design exists to close. The detector rejects it, the
service turns that into unavailable, and the recording continues.

**Detector state belongs to the take, not the service.** Also from the code review: the binding, the
connection, the feeder and the status all live on the `CaptureSession`, and the connection closes over the
take it was made for. A callback or a feeder from a finished take then has something to compare against,
instead of clearing the status or unbinding the detector of whatever is recording now.

**Capture teardown never waits for the detector.** The PCM file closes first, then the feeder is told to
stop and abandoned. Interrupting does not unblock a binder call, so any join would charge the user for the
detector's problem while they wait for words already recorded.

**The model is still verified before construction**, following `polish/S1ModelSelection.kt:20-26`: exact byte
count, exact SHA-256, then use, otherwise report unavailable. Isolation makes a violation survivable;
verification makes it rare. Both, not either.

### 3.5 How the app tells `:audio` what to do, and how it hears back

`architecture-rules.md` RULE: aidl-is-append-only forbids changing `startCapture()`. Two methods are
appended:

```aidl
boolean startCaptureWithSilenceStop(boolean autoStopOnSilence, float pauseSeconds);
int getSilenceStopStatus();
```

`startCapture()` remains and is redefined as `startCaptureWithSilenceStop(false, 0f)`.

**An out-of-range pause refuses auto-stop rather than substituting a value.** Added during the code
review. Requested is not the same as valid and enabled: a pause outside the slider's range reaching this
binder means a caller we do not control, so no detector is built and the take starts with the status
already `UNAVAILABLE`. Ordinary recording is untouched. Substituting the default would hand that caller a
detector configured with a number nobody chose.

**Sensitivity is not a parameter.** It is fixed inside the detector adapter at macOS's resolved 0.5
behaviour. No caller can choose another value, and an append-only interface makes a speculative parameter
permanent.

`getSilenceStopStatus()` (proposed) returns `DISABLED(0)`, `PREPARING(1)`, `READY(2)`, `UNAVAILABLE(3)` or
`LOST_AFTER_READY(4)`. **The fifth value was added during the code review**: the poller sees only the
current integer and never remembers a previous `READY`, so without it a detector lost mid-take was
indistinguishable from one that never arrived, and both would have shown the startup notice. Status 4 is
diagnostic and silent.
`startPolling` reads it alongside elapsed time. The status resets before every capture and may never outlive
its capture-session token.

**The notice is transition-specific**, which resolves a contradiction between two earlier sections of this
plan:

- `PREPARING` (proposed) to `UNAVAILABLE` means auto-stop never became available for this take. It produces
  `Auto-stop on silence is unavailable right now`.
- `READY` to `UNAVAILABLE` means the take lost auto-stop after it had begun. Recording remains correct and
  the failure is logged without interrupting the user.
- `DISABLED` (proposed) never produces a notice.

**The notice has exactly one visible surface, and it is not always the recorder.** The floating recorder
exists only while `PasteAccessibilityService` is running: `RecordingAccessibilityOverlay` is constructed at
`paste/PasteAccessibilityService.kt:202` and nowhere else. Clipboard-only mode is a supported state with no
overlay at all. So if `PasteAccessibilityService.isBound` is true, `RecordingOverlayState` shows the line in
the recorder; otherwise `DictationSessionService` posts the same single line as a toast. **Never both.**

**One atomic first-wins owner for the ending.** A detector result can race manual stop, maximum duration,
capture failure, service teardown, and a late return from the previous take. Every terminal path goes
through one `CaptureSession.requestStop(ending)` (proposed) operation, which atomically moves the ending from
`NONE` to exactly one terminal value, sets `isRecording` false, and unblocks `AudioRecord.read`. **The first
ending wins**; every later one is a no-op.

`CaptureEnding` (proposed) lives in `audio/`, not in the session service, and maps every AIDL integer to one
sealed value. **An unknown integer maps to `Failure`, never to normal transcription.**

A fourth terminal reason is appended: `TERMINAL_REASON_SILENCE` (proposed) `= 4`.

### 3.6 What the user sees, on the Transcription tab

**Founder instruction, 2026-09-03: this is a toggleable feature under Transcription.** Both controls live on
`ui/TranscriptionScreen.kt`, which is the Transcription tab, in a `SettingsGroup` placed **above** the
existing `SettingsGroup("Text cleanup")`, because when a recording ENDS precedes what is done to the text
afterwards. macOS agrees: its catalog copy row places the toggle at `Settings > Transcription`.

| Setting | Type | Default | Range | String |
|---|---|---|---|---|
| Stop recording on silence | boolean | `false` | | `Stop recording on silence` |
| Pause duration | float seconds | `1.5` | 0.5 to 3.0 | `How long to wait after you stop speaking before ending the recording.` |

Both defaults come from the binding catalog `decision` row of 2026-05-30.

**The toggle reuses what is already there**, so it looks like the three cleanup switches beside it:
`SettingsGroup(title)` at `ui/AppShell.kt:753`, `SettingsToggleRow(title, subtitle, checked, enabled, icon,
onCheckedChange)` at `ui/AppShell.kt:770`, and the screen's own `updateWithHaptic` `CLOCK_TICK` wrapper that
every toggle on it already uses.

**Two controls is exact macOS parity, not a reduction.** macOS stores four values in
`SettingsDefaultValues.swift` (external) lines 49-52 (`vadAutoStop` (external) false,
`vadSilenceTimeout` (external) 1.5, `vadSensitivity` (external) 0.5, `vadEnergyGate` (external) true) but exposes only the first
two: a sweep of its whole views tree finds the latter two in nine files, all plumbing, and zero views.

**The pause control is a SLIDER. Founder decision at Gate 2, 2026-09-03**, choosing macOS's full range over
the three named choices this plan recommended. It is the first slider anywhere in this app: `/usr/bin/grep -rn "Slider(\|Slider,\|import androidx.compose.material3.Slider" app/src/main/java --include="*.kt"`
returns nothing. So this is either the app's first slider or a different Android expression of the same
outcome, and `architecture-rules.md` RULE: parity-work-cites-its-contract-id says parity is the same user
OUTCOME, never a literal imitation.

**What ships: 0.5 to 3.0 seconds in 0.25 steps, eleven positions, literal macOS parity.** The rejected
alternative was three named choices mapping to 1.0, 1.5 and 2.5, which would have reused the existing row
shape and needed no new component, but which tops out below macOS's range.

**This introduces a `SettingsSliderRow` (proposed) to `ui/AppShell.kt`**, beside the existing
`SettingsGroup` and `SettingsToggleRow`. It is a new component in the app's design language, so it follows
`design-language.md`: the same 18 dp row padding, title and subtitle typography, and
`onSurfaceVariant` subtitle colour as `SettingsToggleRow`, with the Material 3 `Slider` (external) beneath the text
rather than beside it, because eleven positions need the full row width. It carries the same
`CLOCK_TICK` haptic on each step that the toggles use, and its own content description announcing the
current value.

**Common timing contract, whichever is chosen.** The stored value is the policy input macOS feeds its state
machine, **not an exact stopwatch promise**. The copied machine adds its transition block, and EMA or a
mixed speech-and-silence block can add more, which is why the default of 1.5 nominally waits 1.792 s. So:

**The slider presents its value as an APPROXIMATE pause.** Its readout reads `about 1.5s`, never `1.5s`,
and the group's supporting line says the recording can take slightly longer to stop while the speech
probability fades.

The stored default stays `1.5`. **Reproducing macOS's real behaviour needs no migration and no algorithm
change, but the interface must never promise that recording ends at exactly 1.500 seconds.**

### 3.7 The bucket-versus-pipe answer, three times

**On failure isolation.** The bucket was verifying the model's hash and hoping no other `exit` path is ever
reached; there are 21 and the list belongs to a third party. The pipe is putting the detector where its death
cannot reach the recording.

**On the late message.** The bucket was a token check on each callback. The pipe was deleting the callback
direction, so "a message arrives after its take ended" stops being a thing that can happen asynchronously.
The token check survives for stalled synchronous returns, which is a smaller and enumerable set.

**On the notice.** `RecordingOverlayState.Snapshot` carries only `visible` and `elapsedSeconds`
(`shortcuts/RecordingOverlayState.kt:8-11`), so the recorder cannot tell the user anything. A bucket would
log the failure. The pipe is one notice slot, which #41's final-minute warning needs next.

### 3.8 Alternatives rejected

| Alternative | Why rejected |
|---|---|
| Energy-only detection using `currentAmplitude` at `audio/AudioCaptureService.kt:193-204` | `PAR-025` says neural, and macOS measured that no energy or modulation threshold separates this class from real speech (2026-08-04) |
| `acceptWaveform` plus `isSpeechDetected`, the standard sherpa recipe | The two-signal conflation of §3.1, and it would double-advance the recurrent state if combined with `compute` |
| Host the detector in `:audio` | Its `exit(-1)` would kill the recording |
| Host the detector in `:asr` | `:asr` holds 1.5 to 1.9 GB while a model is resident and does not see live audio |
| Run inference on the capture thread | Forbidden by RULE: protect-audio-asr-stability |
| A blocking queue between capture and feeder | Allocates or locks on the capture thread |
| An asynchronous detector callback | Android's binding API produces at least four late or never-arriving connection states. Deleted rather than guarded |
| The maximum of the eight window probabilities | Amplifies one false-positive window into a whole armed block. §3.2 |
| Ship the model through `ModelManifest` | It is a downloads authority: `isAvailable` requires a `sourceUrl` containing `/resolve/<pinnedRevision>/` (`models/ModelManifest.kt:20-22`). A bundled asset has no URL |
| Upstream Silero v5 | §3.9 |
| Expose sensitivity | Not exposed on macOS, no measured meaning, and append-only means permanent |

### 3.9 The pinned model, and why it is v4 rather than v5

| Property | Value |
|---|---|
| Source | `https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/silero_vad.onnx` |
| Fetched | 2026-09-03 |
| Bytes | 643,854 |
| SHA-256 | `9e2449e1087496d8d4caba907f23e0bd3f78d91fa552479bb9c23ac09cbb1fd6` |
| Identity | its own metadata: `comment: "This is silero-vad v4 exported to onnx by k2-fsa. Only the 16kHz branch is kept"` |
| Licence | MIT |

**Both figures were independently reproduced by the grounded review**, which fetched the live artifact and
got the same byte count and hash, and which accepted this deviation from its own earlier recommendation.

1. **Bundling makes the provenance URL's mutability irrelevant.** The file is committed into
   `app/src/main/assets/` and ships inside the signed APK. The URL is provenance; the byte count and hash
   are the pin.
2. **v5 would add the exact contract bug the coverage round warned about.** For v5 a raw-probability path
   must reproduce sherpa's rolling 576-sample window with a 512-sample shift and 64-sample overlap by hand.
   For this v4 export it does not arise: `window_overlap_` (external) is 0 at line 467 of sherpa's
   `silero-vad-model.cc` (external) and is assigned 64 only inside the v5 branch at line 207.
3. **v5's claimed gains are general, not evidence about auto-stop here.** Better noise robustness, 5 to 7%
   higher clean-audio quality and about 10% faster inference are real, and none of them is a measurement of
   this feature on this phone. Revisit if Android measurements justify the extra adapter risk.

`androidResources` (external) `{ noCompress` (external) `+= "onnx" }` is required, not defensive: measured on
the existing APK, our text asset is deflated while ML Kit's model asset is stored only because it is renamed
to `.jpg`.

The MIT notice is appended to `app/src/main/assets/THIRD_PARTY_NOTICES.txt`. That file does not currently
mention sherpa-onnx or ONNX Runtime, which this app already ships; that pre-existing gap is #15's.

## 3b. Ownership justification

*Capture stays in `:audio` because it is the heart path. The detector runs in `:vad` because sherpa-onnx's
native validation failures terminate their hosting process, and an optional feature may not terminate
capture; the alternative was co-locating both in `:audio`, which saves one 8 KB call every 256 ms but makes
Goal 4 false.*

*The silence POLICY lives in `DictationSessionService` because settings live in the default process and
DataStore is not multi-process; the alternative was reading preferences inside `:audio`, which cannot be done
correctly.*

## 4. MANDATORY — contract deltas

| Type | Delta | What it now MEANS to every consumer |
|---|---|---|
| `IAudioCaptureService` | appended `startCaptureWithSilenceStop(boolean, float)` and `getSilenceStopStatus()` | A caller may ask for a take that can end itself, and may ask whether that capability is live. `startCapture()` keeps its meaning exactly. Append-only, so an older bound client is unaffected |
| `ISilenceVadService` (proposed) | new AIDL in `:vad`, **synchronous only** | `start`, `processBlock` and `finish`, every method carrying the capture token. There is no callback interface: the detector never calls back into `:audio`. It receives copied PCM blocks and returns an integer |
| `AudioCaptureService.terminalReason` | new value `TERMINAL_REASON_SILENCE = 4` | *"The take ended by itself, successfully, because the user stopped speaking."* Every reader must treat it as a NORMAL ending in the same class as `MANUAL`, never as the failure class `ERROR` |
| `CaptureSession` | new `requestStop(ending)`, atomic, first-wins | The ending is whatever the FIRST terminal path claimed, not the last writer. Every other path becomes a no-op |
| `CaptureEnding` | new sealed type in `audio/` | Maps every AIDL integer to one value. An unknown integer maps to `Failure`, never to normal transcription |
| `CaptureSession` buffers | `readBuffer` and eight ring slots are constructed BEFORE `startRecording` | `captureLoop` allocates nothing. The current `ByteArray(bufferSize)` inside the loop moves out |
| Silence-stop status | new AIDL integer, 0 to 3 | `DISABLED` and `PREPARING` show nothing. **Only the `PREPARING` to `UNAVAILABLE` TRANSITION produces user-visible copy**; `READY` to `UNAVAILABLE` is logged and silent |
| `AppPreferencesState` | new fields `autoStopOnSilenceEnabled: Boolean = false`, `silencePauseSeconds: Float = 1.5f` | **Both must be assigned in the same collector block, before `cleanupPreferencesReady.complete(Unit)` at `ui/DictationSessionService.kt:271`**, or `beginSession`'s guarantee does not extend to them. An invalid persisted float maps back to `1.5` |
| `RecordingOverlayState.Snapshot` | new field `notice: String? = null` | `null` is a SENTINEL meaning "nothing to say", never "not loaded yet". `updateElapsed` must carry it forward |
| `SilenceStopDetector` (proposed) | new | A pure per-BLOCK state machine: probability in, stop-or-not out. Owns no audio, thread, model or binder. JVM-testable |

## 5. MANDATORY — end-to-end state and lifecycle audit

| Population to enumerate | Members, and the disposition of each |
|---|---|
| **Every producer of a terminal capture ending** | Manual stop from the session owner; cancellation; session-owner teardown; maximum-duration expiry in the capture loop; `AudioRecord.read` or file-write failure; a `SILENCE` return whose token is ACTIVE; `AudioCaptureService.onDestroy`; `:audio` process death. The first seven route through atomic `requestStop`. Process death has no readable terminal reason and surfaces through `audioConnection.onServiceDisconnected` in the session owner |
| **Every path that starts a take** | Exactly one, `DictationSessionService.tryStartRecording:388`. Four entry surfaces feed it. `enumerated, one member` |
| **Every `:vad` binding outcome** | `bindService` returns false; connected normally; returns a null binding (`onNullBinding` (external)); the binding dies permanently (`onBindingDied` (external)); the service disconnects and Android could reconnect automatically (`onServiceDisconnected`); `bindService` returns true but no connection callback ever arrives because the service crashed while being created; a connection arrives AFTER the capture token was invalidated; normal unbind. **Every failure marks the enabled take unavailable.** `onNullBinding`, `onBindingDied` and `onServiceDisconnected` explicitly unbind, so Android cannot reconnect into the same take. A late `onServiceConnected` is immediately unbound without calling `start`. **The eight-slot ring is the client-owned deadline for the no-callback-ever case**, which is the one Android gives no signal for |
| **Every detector lifecycle exit** | Normal manual stop; silence stop; maximum duration; capture error; start failure after capture began; client binder death and unbind; `AudioCaptureService.onDestroy`; `:vad` process death; `:audio` process death; a stalled synchronous return; an immediate next start. Normal exits invalidate the token, clear the ring, unbind `:vad`, and bounded-join the feeder. `:vad` death disables auto-stop while capture continues. `:audio` death destroys its thread and ring with the process |
| **Every immediate-restart state** | While the old capture thread still owns the `AudioRecord` or the PCM file, a second start returns `false`. **Once those heart resources close and `session` is null, a new take may start even if an invalidated detector feeder has not returned.** The new take has a new token, ring, read buffer, binding and detector state; the old feeder owns none of them |
| **Every state the detector can be in when a take ends** | not created; binding; preparing; ready and idle; ready and in `speech`; ready and in `hangover` (external); unavailable; released. All end at the same teardown, and the token check prevents a late `SILENCE` reaching a take it does not belong to |
| **Every consumer of the new preferences** | `AppViewModel.baseUiState:171` collects `AppPreferences.state` for UI rendering; `DictationSessionService` collects `authoritativeState:268` for capture policy; `SettingsActivity` forwards write callbacks; `AppShell` carries them; `TranscriptionScreen` renders them. **UI may briefly render data-class defaults before DataStore delivers. Capture may not**: it waits for `cleanupPreferencesReady` at `:326`, completed after both new fields are written. A mid-take change reaches the UI and the collector cache but not the frozen active take |
| **Every process the detector could be constructed in** | `:vad` only, pinned by its manifest entry. `enumerated, one member`. §11.1 asserts the PID on the phone |

## 6. MANDATORY — downstream consumer matrix

| Contract delta | Consumer | Current behaviour | Required behaviour | Code change? | Verified by |
|---|---|---|---|---|---|
| `TERMINAL_REASON_SILENCE` | `DictationSessionService.startPolling:443-450` | Branches `ERROR` versus else | Maps through `CaptureEnding` and transcribes | Yes | `silenceIsANormalEndingNotAFailure` (proposed) |
| `TERMINAL_REASON_SILENCE` | Anything else reading `terminalReason` | **Nothing else.** `grep -rn "getTerminalReason\|TERMINAL_REASON" app/src` returns only the service and that one call site | n/a | No | the pasted grep |
| `CaptureSession.requestStop` | Manual, cancellation, teardown, maximum-duration, capture-error and valid silence producers | Independent reason writes | All claim the same atomic first-wins ending | Yes | race tests |
| `CaptureEnding.fromAidl` (proposed) | `DictationSessionService.startPolling` | Raw integer comparison | Exhaustive semantic mapping; unknown is failure | Yes | `anUnknownEndingIsAFailureNotATranscription` (proposed) |
| `ISilenceVadService.start/processBlock/finish` | The `AudioCaptureService` feeder | No detector service | Synchronous, token-scoped calls only; never from the capture thread | Yes | real-process device test |
| `PREPARING` to `UNAVAILABLE` | `RecordingAccessibilityOverlay`, when the accessibility service is bound | No notice slot | Show the one macOS line | Yes | bound-service device test |
| `PREPARING` to `UNAVAILABLE` | `DictationSessionService`, when it is not bound | **No fallback surface exists** | Show the same line once as a toast | Yes | clipboard-only device test |
| `READY` to `UNAVAILABLE` | diagnostics only | n/a | Keep recording; no user interruption | Yes | transition-table test |
| `startCaptureWithSilenceStop` (proposed) | `DictationSessionService.tryStartRecording:388` | Calls `startCapture()` | Calls the new method with frozen values | Yes | hardware run |
| `startCaptureWithSilenceStop` | The instrumentation APK | Does not bind `IAudioCaptureService` at all | Unchanged | No | the grep in §2.5.1 |
| `AppPreferencesState` new fields | `AppPreferences.mapState:61-72` | Maps 10 fields | Maps 12, with range validation | Yes | `autoStopPreferencesRoundTripThroughDataStore` (proposed) |
| `AppPreferencesState` new fields | `AppViewModel`, `SettingsActivity`, `AppShell`, `TranscriptionScreen` | No such rows | Two write callbacks threaded through and two rows rendered | Yes | settings screen on device |
| `Snapshot.notice` | `RecordingAccessibilityOverlay.onChanged:52-60` | Sets the timer text | Also shows or hides the notice | Yes | hardware run |
| `Snapshot.notice` | `RecordingOverlayState.updateElapsed:38` | Builds the next snapshot from the current one | **Must preserve the notice**, or the once-a-second tick erases it | Yes | `aNoticeSurvivesTheElapsedTick` (proposed) |
| The read-block split and preallocation | `AudioCaptureService.captureLoop` | One value sizes the ring and the read; the buffer is allocated in the loop | Ring keeps the coerced floor; the read block is 8,192 bytes; nothing is allocated in the loop | Yes | `captureLoopUsesOnlyPreallocatedBuffers` (proposed) plus the hardware log |
| The bundled asset | APK size | 139,247,608 bytes | Grows by 643,854 bytes plus packaging | Yes | measured after the build |
| The bundled asset | `THIRD_PARTY_NOTICES.txt` at `ui/SettingsActivity.kt:54` | No Silero entry | MIT notice present | Yes | read on the phone |

## 7. MANDATORY — failure-mode × caller table

| Failure mode | Origin | Caller | What the user sees | Persisted state | Retry |
|---|---|---|---|---|---|
| Model missing, wrong size, or wrong hash | verification, before construction | `:vad` | `Auto-stop on silence is unavailable right now`, because this is a `PREPARING` failure | None | No |
| Model rejected by the library despite verification | `exit(-1)` inside `:vad` | binder death seen by `:audio` | Same, if it happens before `READY` | None | No. **`:audio` is untouched** |
| Native library fails to load in `:vad` | `System.loadLibrary` | `:vad` | Same | None | No |
| `bindService` false, null binding, binding died, or no connection ever arrives | Android binding | `:audio` | Same, via the `PREPARING` transition; the ring is the deadline for the silent case | None | No; explicit unbind so Android cannot reconnect into this take |
| `:vad` dies or a call throws AFTER `READY` | `:vad` | `:audio` | **Nothing.** Recording is correct and a notice several seconds in would be a modal interruption Aaron Wu does not want. Logged | None | No |
| A synchronous call stalls and returns after teardown | `:vad` | `:audio` | Nothing | None | Discarded: fails the active-session and token checks |
| A detector call exceeds its 2,000 ms deadline | native or service stall in `:vad` | the process-local watchdog | A startup stall shows the unavailable notice; a post-ready stall stays silent | None | **The watchdog terminates only `:vad`.** The feeder receives binder death and exits |
| Feeder still alive after binder death and the bounded join | client-side teardown defect | `:audio` | Nothing; recording and the next take remain available | None | **Reported as a lifecycle defect, not accepted as idle state.** It holds no capture resource and cannot refuse the next recording |
| Ring has no free slot | slow detector or slow binding | `:audio` | Nothing | None | Take marked unavailable, blocks cleared, `:vad` unbound, **never resumed**: a gap breaks Silero's recurrent continuity |
| `:audio` process dies | Android or native capture failure | session owner's audio connection | The existing microphone-service failure path | Interrupted-draft behaviour already owned by the session service | A new take starts a fresh process |
| Persisted pause is NaN, infinite, or outside 0.5 to 3.0 | corrupt or foreign DataStore write | settings mapper | Nothing unusual; the UI and the next take use 1.5 | The corrected value is represented in memory, not written back merely by reading | Use the binding default |
| An enabled AIDL request carries NaN, infinite, or an out-of-range pause | a separately installed instrumentation client, or an incompatible caller | audio binder | Startup unavailable notice; manual recording remains available | None | Do not construct or bind the detector for that request |
| Detector never becomes ready before the user stops | slow prepare | `:vad` | Nothing; the manual stop worked as always | None | n/a |
| **Auto-stop fires on a pause, not an ending** | the detector was wrong | n/a | The take ends mid-thought. **Audio captured before the decision is intact and is transcribed normally. Audio spoken AFTER is never captured**, so this is the feature's highest-cost error and the one §11.1 exercises deliberately | The partial text is in History like any take | A longer pause, or the switch |
| Auto-stop never fires in a noisy room | the detector was wrong | n/a | Nothing; the take runs to the manual stop or the cap, as today | None | n/a |

All copy is macOS's existing copy, per `content-brand.md` RULE: no-dashes-in-user-facing-text.

## 8. MANDATORY — caller-visible signals audit

| Field | What its presence, absence, value, staleness or identity means beyond its type |
|---|---|
| `Snapshot.notice == null` | **Sentinel.** "Nothing to say." Never "not loaded yet" |
| `terminalReason == TERMINAL_REASON_SILENCE` | Carries "this was a SUCCESS" as well as "not manual". Misreading it as a failure discards a good transcript |
| An AIDL ending integer this build does not know | Maps to `Failure`. **Declining to classify is an action**, and the safe action is not to transcribe something whose provenance is unknown |
| The silence-stop status TRANSITION, not its value | `UNAVAILABLE` alone says nothing. `PREPARING` to `UNAVAILABLE` is a user-visible event; `READY` to `UNAVAILABLE` is a log line |
| A status or return carried past its capture token | Would report the previous take's outcome on this one. The token is what makes any of these values mean "this take" |
| The ring having no free slot | A degradation signal, logged once per take, never per block |
| `pauseSeconds` (proposed) frozen at start | Its identity is the take, not the setting |
| A persisted pause value outside its range | Means a corrupt or foreign write, never a user choice. Maps back to the default rather than being honoured |

## 9. MANDATORY — fallback source-of-truth audit

| Failure branch (§7) | Candidate expression | Source | Why authoritative here | Acceptance predicate | If none qualifies | Consumer (§6) |
|---|---|---|---|---|---|---|
| Any detector startup or runtime failure | The existing manual-ending commands | `ACTION_STOP` and `ACTION_TOGGLE` producers | Manual ending stays reachable through the side-button toggle, the Quick Settings tile, the foreground notification's Stop action, and the floating Stop control **when the accessibility service is running**. The floating button is NOT claimed to exist in clipboard-only mode | Any existing manual command ends the take | If one surface is unavailable the others remain | session owner |
| Auto-stop fires wrongly | The already-captured audio | `cacheDir/recording.pcm`, written independently by the capture loop | **`:vad` shares the app UID and is NOT a filesystem security boundary.** The protection is structural: the detector AIDL receives copied PCM blocks only, receives no file path or handle, and detector code owns no reference to the capture file or the `AudioRecord` | Non-empty file and `waitForFileReady` true | The existing no-audio path applies | ASR |
| Preferences unavailable | No take at all | `beginSession`'s existing 10 s timeout and `Settings could not be loaded. Try again.` | Already the shipped behaviour; this change does not weaken it | The readiness signal completes | n/a | session owner |
| An invalid persisted pause value | `1.5` | the mapper | A safe value from the binding decision row, not a plausible guess | Within 0.5 to 3.0 and finite | n/a | session owner |

## 10. File-by-file changes

| File | Change |
|---|---|
| `app/src/main/aidl/com/envi/wispr/audio/IAudioCaptureService.aidl` | Append two methods. Nothing reordered or renamed |
| `app/src/main/aidl/com/envi/wispr/vad/ISilenceVadService.aidl` | New synchronous interface: `start`, `processBlock`, `finish`; every method carries the capture token. **No callback interface** |
| `app/src/main/AndroidManifest.xml` | New `<service android:process=":vad" android:exported="false">` |
| `app/src/main/java/com/envi/wispr/audio/AudioCaptureService.kt` | `TERMINAL_REASON_SILENCE`; the two new binder methods; atomic `requestStop`; the eight-slot preallocated ring; the feeder thread and its bounded join; the `:vad` binding with all its outcomes; **the read-block split, and moving `ByteArray` allocation out of `captureLoop` into `CaptureSession`** |
| `app/src/main/java/com/envi/wispr/audio/CaptureEnding.kt` | New sealed type; unknown maps to `Failure` |
| `app/src/main/java/com/envi/wispr/vad/SilenceVadService.kt` | New. Hosts the model in `:vad` |
| `app/src/main/java/com/envi/wispr/vad/SileroVadSession.kt` | New. Owns the `Vad` handle, verification, 512-sample framing, the eighth-window read, prepare and release. The only file importing `com.k2fsa.sherpa.onnx` here |
| `app/src/main/java/com/envi/wispr/vad/SilenceStopDetector.kt` | New. The pure per-block state machine and macOS's resolved constants. No Android imports |
| `app/src/main/java/com/envi/wispr/settings/AppPreferences.kt` | Two fields, two keys, two setters, range validation, both mappings |
| `app/src/main/java/com/envi/wispr/ui/DictationSessionService.kt` | Collect the two new preferences **in the same block, before `:271`**; freeze and pass them at `tryStartRecording`; read the status transition in `startPolling`; publish the notice to the recorder or as a toast; map endings through `CaptureEnding` |
| `app/src/main/java/com/envi/wispr/ui/AppViewModel.kt` | Two write methods |
| `app/src/main/java/com/envi/wispr/ui/SettingsActivity.kt` | Pass both callbacks into the app shell |
| `app/src/main/java/com/envi/wispr/ui/AppShell.kt` | Carry both callbacks to the transcription screen, and add `SettingsSliderRow` beside `SettingsToggleRow` |
| `app/src/main/java/com/envi/wispr/ui/TranscriptionScreen.kt` | A new `SettingsGroup` **above** Text cleanup, holding the toggle and the pause slider |
| `app/src/main/java/com/envi/wispr/shortcuts/RecordingOverlayState.kt` | `notice: String?`; `updateElapsed` carries it; a publish entry point |
| `app/src/main/java/com/envi/wispr/paste/RecordingAccessibilityOverlay.kt` | One `TextView` below the timer, with a content description |
| `app/src/main/assets/silero_vad.onnx` | New. 643,854 bytes, SHA-256 in §3.9 |
| `app/src/main/assets/THIRD_PARTY_NOTICES.txt` | Append the Silero MIT notice |
| `app/build.gradle.kts` | `androidResources { noCompress += "onnx" }` |

## 11. Testing

1. **Class of every new test.** `SilenceStopDetector` cases are **product-outcome**: *"when this fails, the
   user sees the recorder cut them off mid-sentence, or never stop at all."* Race and lifecycle cases are
   outcome; wiring cases are drift guards.
2. **What revert would turn each red?** Named per row, performed, watched to fail, restored.
3. **Deliberately NOT tested.** The accuracy of Silero itself: it is a pinned third-party model, and a test
   asserting how it scores a sound would test the vendor.

### 11.1 Hardware UAT spec

- **Subsystem:** heart path.
- **Recipe:** a new one in `.claude/knowledge/device-testing.md`, built on `SpeakerPlaybackActivity`
  (`app/src/androidTest/java/com/envi/wispr/SpeakerPlaybackActivity.java`), which plays a 16 kHz mono PCM
  fixture through the speaker at exactly the rate the app records at.
- **Preconditions asserted before any observation**, because
  `device-testing.md` FACT: what-the-2026-08-30-run-actually-proved records a run whose microphone captured
  ambient speech instead of the fixture: media volume set and confirmed, and
  `adb shell dumpsys media.audio_flinger | sed -n '/^Input thread/,/^Output thread/p' | grep -iE "Input device:|AUDIO_DEVICE_IN"`
  printing `AUDIO_DEVICE_IN_BUILTIN_MIC` (external).

| # | Observation | Oracle |
|---|---|---|
| 1 | A spoken take ends without the stop button | **Conjunctive: the transcript contains the fixture's words AND the ending is silence.** The terminal reason proves auto-stop fired; the transcript assertion proves the spoken fixture reached the microphone and survived the complete heart path. **Failed playback cannot satisfy this oracle**, because a genuinely silent take must not auto-stop at all, which is what observation 2 asserts |
| 2 | **A silent room does NOT auto-stop** | The two-way control. The state machine cannot reach hangover without first reaching speech |
| 3 | The detector really ran in `:vad` | The log line's PID equals the `:vad` PID from `ps` |
| 4 | With the switch off, nothing changed | The ending is manual on every take, and no `:vad` process is created |
| 5 | A 2.5 s thinking pause at the default 1.5 setting stops the take | The take ends before speech resumes |
| 6 | The same 2.5 s pause at the slider's maximum of 3.0 s does not | Speech resumes and the final words reach the editor |
| 7 | The eight raw probabilities and the selected eighth value behave | Log all eight for controlled speech, silence, short-gap and isolated-noise fixtures. Confirm a low final window only ENTERS hangover, and that an isolated early spike does not arm the block |
| 8 | The actual capture-buffer contract | Log `getMinBufferSize`, the coerced constructor request, `AudioRecord.getBufferSizeInFrames()` (external) converted to bytes, the 8,192-byte read request, and every short or error return. **Android may enlarge the native buffer, so the constructor argument alone is not the actual value** |
| 9 | Prepare cost and `:vad` memory | `dumpsys meminfo` before and after, plus the prepare duration. Asserted nowhere in this plan |
| 10 | Whether a noisy room produces false speech or false stops | The evidence that would justify porting the high-pass filter of §3.3. Recorded either way |
| 11 | A missing model shows the notice and the take completes | Rename the asset in a debug build |
| 12 | **Killing `:vad` mid-take does not disturb the recording** | Resolve `com.envi.wispr:vad` to exactly ONE numeric PID and record the current `com.envi.wispr:audio` PID. Run `adb shell run-as com.envi.wispr kill -9 <vad-pid>; echo RC=$?` (this needs the debuggable build, as observation 11 already does). Require `RC=0`, require the old detector PID to disappear, and **require the audio PID to be unchanged**. The recording then continues and its words reach the editor. **This is the Goal 4 test and the reason the design pivoted** |
| 13 | Insertion into a real third-party editor, back to back | Two auto-stopped takes in a row into Gmail |
| 14 | **Detector startup failure is visible in clipboard-only mode** | Disable the accessibility service, start an enabled take with the model unavailable, observe exactly ONE toast and no overlay, then stop manually. Restore the accessibility service afterwards |
| 15 | **A detector call past its deadline kills only `:vad` and releases the feeder** | A normal model cannot stage this, so it is fault-injected. AFTER committing the clean implementation checkpoint, patch a private debug copy of `SilenceVadService.processBlock` so that, having armed the watchdog and before any model work, its binder thread waits forever on an unreleased `CountDownLatch` (external). Build and install that debug APK. Start an enabled take and require: the old detector PID disappears after the 2,000 ms deadline, the audio PID is unchanged, recording continues, and a following take starts. **Restore the patched source byte for byte before the final clean build; a result from an un-restored mutation is void** (`workflow-process.md` RULE: codex-clean-gates-the-hardware-run) |

- **Phone state to restore:** both new settings to defaults, the renamed asset restored, the accessibility
  service re-enabled, screen timeout and stay-awake restored, the release build reinstalled if a debug build
  was used.

### 11.2 Other obligations

| Test | Class | Proves | Revert that turns it red |
|---|---|---|---|
| `silenceAfterSpeechStopsTheTake` (proposed) | outcome | Speech then silence returns stop exactly once | Remove the `remaining <= 0` branch |
| `silenceWithNoSpeechNeverStops` (proposed) | outcome | Silence from the start never stops a take | Make hangover reachable from idle |
| `speechResumingInsideTheHangoverCancelsTheStop` (proposed) | outcome | A pause shorter than the setting does not end the take. **The Marcus Weber guard** | Delete the `smoothed >= onset` arm of hangover |
| `theShippedConstantsAreMacOSResolvedNotStructDefaults` (proposed) | outcome | alpha `0.4`, onset `0.4125`, offset `0.2625` | Restore `0.3`, `0.5`, `0.35` |
| `theStopConsumesTheTransitionBlockAndConfiguredCountdown` (proposed) | outcome | At 1.5 s, the first below-offset block enters hangover and six later low blocks produce the stop | Decrement on entry, or stop after only six below-offset blocks |
| `theHangoverFloorIsThreeBlocks` (proposed) | outcome | 0.5 s still waits the floor | Drop the `max(3, ...)` |
| `hysteresisMeansOnsetAndOffsetDiffer` (proposed) | outcome | A probability between the two neither starts nor ends speech | Set offset equal to onset |
| `theBlockProbabilityIsTheEighthWindow` (proposed) | outcome | The state machine reads the detector at the block boundary and no earlier isolated spike dominates | Replace the eighth value with `maxOf` |
| `manualAndSilenceRaceHasOneWinner` (proposed) | outcome | Manual and silence cannot overwrite one another | Replace the atomic first-wins claim with read-then-write |
| `timeoutAndSilenceRaceHasOneWinner` (proposed) | outcome | Maximum duration and silence cannot overwrite one another | Write the reason outside the shared claim |
| `aLateDetectorReturnCannotStopTheNextTake` (proposed) | outcome | A synchronous result from an invalidated token cannot affect a later take | Remove the active-session and token checks after `processBlock` returns |
| `aStalledFeederDoesNotRefuseTheNextTake` (proposed) | outcome | A feeder that never returns does not block a new recording | Make the new start wait on the feeder instead of on the heart resources |
| `aDetectorCallPastItsDeadlineKillsOnlyVadAndReleasesTheFeeder` (proposed) (instrumented fault-injection receipt) | outcome | A real stalled remote call terminates only `:vad`, releases the feeder, preserves `:audio`, and does not refuse the next take | Disable the watchdog's expiry action in the private debug mutation |
| `anUnknownEndingIsAFailureNotATranscription` (proposed) | outcome | An integer this build does not know does not transcribe | Add an `else` that transcribes |
| `anInvalidPauseValueFallsBackToTheDefault` (proposed) | outcome | NaN, infinite or out-of-range maps to 1.5 | Pass the persisted float straight through |
| `theNoticeFiresOnlyOnThePreparingTransition` (proposed) | outcome | A `READY` to `UNAVAILABLE` loss does not interrupt the user | Fire on any `UNAVAILABLE` |
| `captureLoopUsesOnlyPreallocatedBuffers` (proposed) | drift guard | The capture thread does not construct its read buffer or ring slots | Move `ByteArray(READ_BLOCK_BYTES)` back into `captureLoop` |
| `silenceIsANormalEndingNotAFailure` (proposed) | drift guard | Ending 4 reaches `stopAndTranscribe`, not `showError` | Add 4 to the error branch |
| `aNoticeSurvivesTheElapsedTick` (proposed) | outcome | A notice set at second 2 is still shown at second 3 | Drop the notice from `updateElapsed`'s copy |
| `autoStopPreferencesRoundTripThroughDataStore` (proposed, instrumented) | outcome | Both values survive a real write and reach the next take | Remove either key from `mapState` |
| `startCaptureIsStillTransactionOne` (proposed) | drift guard | The append did not reorder anything | Move a new method above an existing one |

**No test builds its expected value with the machinery under test**: every probability stream is a literal
`FloatArray` and every expected block count is written out, not recomputed by the production converter.

## 12. Blast radius & rollback

**Touched:** `app/src/main/java/com/envi/wispr/audio/`, a new `vad/` package and process, the session
service's start path and poller, `settings/AppPreferences.kt`, four UI files,
`shortcuts/RecordingOverlayState.kt`, `paste/RecordingAccessibilityOverlay.kt`, `app/build.gradle.kts`, the
manifest, and two asset files.

**The riskiest single change is the read-block split and the allocation move** at
`audio/AudioCaptureService.kt:90-94`, `:109`, `:141`, `:171` and `:173`. It is on the heart path. Every
dependent site was walked: the max-duration check gets finer, the read guards are length-driven,
`output.write` is length-driven, `bytesWritten` is unaffected, the amplitude loop is scale-free and has no
consumer today, and `PcmAudio.durationSeconds` is byte-based. Writes go from about 1 to about 4 per second.

**Deliberately NOT touched:** `asr/`, `polish/`, `insertion/`, `paste/PasteAccessibilityService.kt` beyond
the overlay's notice, `history/`, `models/ModelManifest.kt`, `vocabulary/`, the Room schema, the four
dictation entry points, and `llama-android/`. **Nothing on the insertion path changes**, so the back-to-back
insertion defect in `current-state.md` FACT: the-current-p1 is neither fixed nor disturbed.

**Operational disable is NOT a rollback.** Turning the switch off prevents `:vad` from starting and removes
silence-based ending. It does not roll back the capture refactor, the read-block split, buffer ownership,
the atomic ending owner, or the AIDL mapping. **Those ship and run on the heart path whatever the switch
says**, and an earlier revision of this plan wrongly called the switch a rollback.

**Code rollback:** revert the complete feature commit, rebuild the APK, install it, force-stop the prior
processes, and run one manual side-button dictation into Gmail. The feature is not rolled back until the
pre-feature capture implementation is installed and that manual heart path succeeds.

## 13. Ship criteria specific to THIS change

- [ ] With the switch on, Saurabh dictates into Gmail, stops talking, and the words arrive without him
      pressing anything.
- [ ] With the switch off, dictation is exactly what it was, confirmed on the same phone in the same session.
- [ ] Killing the detector process mid-recording does not disturb the recording.
- [ ] A silent room does not auto-stop.
- [ ] With the accessibility service off, a startup failure produces exactly one toast and no overlay.
- [ ] The detector is proven to have run in its own process, by PID.
- [ ] The real buffer-size numbers, including the native buffer Android actually gave us, are logged.
- [ ] The new APK size is measured against 139,247,608 bytes.
- [ ] Prepare cost and detector-process memory are measured and recorded.
- [ ] The Silero MIT notice is visible on the phone's own licences screen.
- [ ] A detector call held past its deadline kills only the detector process, and the audio process's
      identifier is unchanged.

## 14. Open questions

1. **Whether the low-frequency filter is needed on Android.** Decided by hardware observation 10.

The pause-control question is CLOSED: the founder chose the slider at Gate 2 on 2026-09-03.

Both earlier open questions are closed. The `compute` recurrent-state premise is settled from the pinned
source, and the artifact is chosen, fetched, hashed, and independently reproduced by the grounded review.

## 15. Related

- Issue `#5`. `PAR-025`.
- Issue `#41`, the recording cap. Saurabh's one-hour decision is recorded there, with the second enforcement
  point at `asr/AsrService.kt:28-32` and `:55-60` that REJECTS rather than truncates, and the memory
  arithmetic for an hour-long take. macOS: graceful cap 3600 s, 60 s warning lead, hard ceiling 3660 s.
- Issues `#27` and `#28`, pre-roll and warm capture. `PAR-023`, `PAR-024`.
- Issue `#44`, the unread audio meter. This change makes the amplitude a 256 ms average instead of a 1 s one.
- Issue `#50`, the recorder's appearance. This adds one line to it.
- Issue `#15`, third-party notices. This adds Silero's; the missing sherpa-onnx and ONNX Runtime entries
  stay #15's.
- **A follow-up issue to open if observation 10 shows it:** port macOS's `VADInputHighPass` with its corpus.
- **A DRY note, not a change here:** `models/ModelDelivery.kt:238` and `polish/S1ModelSelection.kt:36` each
  carry a private `sha256(File)`. The new one hashes an `InputStream`, so it is a different signature rather
  than a third copy. Neither existing one is in this blast radius.
- Catalog features `silence-auto-stop`, `first-word-pre-roll`, `soft-onset-protection`,
  `recording-duration-cap`.

---

## Checklist for the plan author

- [x] Gate 0 prior context posted before this file was written
- [x] User Rubric answered against named personas
- [x] §2.5 grounded in real code before §3 was written
- [x] §4-9 answered, none struck through
- [x] Lane declared
- [x] Coverage round run; PIVOT applied
- [x] Grounded review run; PROCEED-WITH-REVISIONS applied, both deviations adjudicated by it
- [x] Confirming rounds run to writing-level findings only; the reviewer stated no design change remains
- [x] Gate 2 sign-off, 2026-09-03. Slider chosen over named choices
