# Issue #151 — Live voice bars: a pitch picture, centre out — 2026-09-14

GitHub issue: `#151`. Tier: REFACTOR (one appended AIDL method; otherwise MEDIUM: audio capture read size, a
new thread in `:audio`, the recorder's meter). Status: DRAFT.

## Preface — Lane + Hardware UAT declaration

**Lane:** Code — `app/src/main/aidl/**`, `app/src/main/java/com/envi/wispr/{audio,ui,shortcuts,paste}/**`,
`app/src/test/**`, `app/src/androidTest/**`; plus `docs/feature-requests/**` for this plan.
`mixed_pr: true` — `Code` (tests, codex-review, hardware-uat) and `Docs/dev-tooling` (cited-symbols,
conditional).

**PAR rows closed:** none. The catalog's `live-audio-meter` Android row is updated at wind-down.

**Hardware UAT:** Y. The founder holds the bubble on the S26, says a sentence in an ordinary voice, and the
rail in the pill moves WITH the sentence: the centre bars swell on each syllable and fall in the gaps, the
outer bars flick on an "s" or a "t", the whole picture is still and grey within a quarter second of him
stopping, and the words still land in the field as before. A whisper moves the centre a little; a raised
voice fills the rail; a quiet room shows the resting bars and nothing else.

## Preface — User Rubric

1. **Who is this user in this moment?** Diana Foster, in Slack on her S26, thumb on the bubble, dictating a
   reply between meetings. Thirty seconds ago she read a message; thirty seconds from now she wants to
   have answered it without looking at anything but the words.
2. **Why would they want this?** "I can see it hearing me." A picture that moves with her voice tells her
   the microphone is live and the app is keeping up, before a single word appears. Today's rail moves in
   10-per-second steps of a quarter-second average, so it does not follow her voice and she cannot tell a
   working take from a dead one until the text lands.
3. **How would they invoke it?** She does not. It is what the pill shows for as long as a take is open,
   every take, from every entry point (side button, tile, notification, bubble tap or hold).
4. **What app are they in?** Whatever she was typing in: Slack, Gmail, Messages, Chrome. The pill floats
   over all of them; nothing about the target app changes.
5. **What is their natural input?** "Can we push the sync to Thursday?" · "Sounds good, I'll take the
   action item." · "Ping me when the deck is ready." · "Yes." · "Hmm, let me check with Priya first."
6. **What does success feel like?** She notices the bars breathing with her words and stops noticing
   them. Success is a picture she reads without thinking, exactly as she reads the Wisprflow one today.
7. **What does wrong-not-broken look like?** The bars move, but not with her: they jitter in a quiet room,
   lag her voice by a beat, or every syllable looks the same. She stops trusting the picture and goes
   back to waiting for the text.
8. **What would a power user hack around this to get?** Nothing; there is no setting to reach for. A
   power user who dislikes the picture wants it smaller or gone, which the tap pill's half-reach rail
   already anticipates (build 116).
9. **What level of control would they want?** None beyond what exists: the picture is not a control,
   it is the recorder's face. Off/size choices are a later question and not this change.

### Cross-persona check

Priya, Diana, Aaron and Meera want the same thing: a live, honest "it hears me". Marcus and Frank want it
calm: no flashing, no colour changes with loudness (the rainbow stays positional, as today). Dr. Vasquez
cares only that nothing new leaves the phone: band levels are computed in `:audio` and cross one binder to
the main process, never the network. No tension to resolve in §3.

---

## 0. TL;DR

The floating recorder's rail is a scrolling history of ONE loudness number, and that number is a 256 ms
average refreshed four times a second, so it cannot show a voice. The founder chose (2026-09-14) a live
picture like Wisprflow's: each bar a pitch band of the sound right now, lowest band in the centre,
highest at the edges, eased per frame. Capture reads 32 ms chunks instead of 256 ms; a small analyser
thread in `:audio` turns each chunk into 11 band levels; one appended AIDL method hands them out; the
session owner polls it every 33 ms on its own thread for as long as a take is open; the recorder eases
each bar toward its band at 60 fps. The scrolling history and its scale are removed in the same change.
REFACTOR tier by the AIDL append. Evidence: unit tests on the analyser (a 1 kHz tone lights the 1 kHz
band and nothing else), the emulator hearing a spoken sentence through the virtual cable with the pill
screen-recorded, and the founder's phone pass through Play.

## 1. Problem

Founder, S26 Ultra, 2026-09-14, side by side with Wisprflow: "their bars feel much more fluid and accurate
and snappy compared to ours ... I can see my actual voice in their bars ... their centre expands as I
speak, whereas ours is an active audio stream moving right to left."

Measured from the tree at d96e7d7:

1. `AudioCaptureService.kt:47` `READ_BLOCK_BYTES = 8_192` is the `AudioRecord.read` request
   (`AudioCaptureService.kt:383`), so each blocking read returns 256 ms of 16 kHz mono 16-bit audio, and
   `currentAmplitude` (`AudioCaptureService.kt:401`) is the mean absolute sample over that read: one
   number per 256 ms. A syllable is about 100 ms.
2. `DictationSessionService.kt:566-641` `startPolling` reads that number once per 100 ms tick (so two or
   three reads in a row see the same value), smooths it (`AudioLevelScale.smooth`), and
   `RecordingOverlayState.updateLevel` (`RecordingOverlayState.kt:130`) quantises it to 32 steps.
3. `RecordingLevelMeterView.pushSample` (`RecordingLevelMeterView.kt:86`) pushes one bar per poll into a
   `LevelHistory` and redraws with no motion between polls: ten discrete steps a second.

## 2. Goals & non-goals

### 2.1 Goals
- The rail follows a voice syllable by syllable: a fresh reading at least 30 times a second, from at most
  the last 64 ms of audio, drawn with per-frame easing. Verifiable on the emulator by screen-recording a
  spoken sentence and on the phone by the founder.
- Each bar is a pitch band, centre out: the lowest band in the middle, the highest at the two edges. A
  1 kHz tone lights the 1 kHz band and no other (unit test).
- A quiet room shows the resting bars only (no jitter): the per-band floor sits above room tone.
- Nothing at idle: the analyser thread, the poll thread and the frame loop all exist only while a take
  is open, and the frame loop stops once the bars have settled.
- The take is untouched: capture, the detector, the duration cap, transcription and insertion behave as
  before, and an analyser failure costs the picture only.
- The onboarding demo's drawn rail shows the new picture.

### 2.2 Non-goals
- The macOS and Windows meters. The Mac keeps its history (founder 2026-09-14).
- A setting to turn the picture off or change its size.
- Changing the pill's size, layout, colours or the rainbow's direction. The positional rainbow stays.
- Changing what the detector, the file, or transcription receive. The detector still sees whole 256 ms
  blocks; the file still receives every byte read.
- The tap pill's half reach (11 bars) and the hold pill's full reach (22 bars) stay as decided on build
  116; only what the bars show changes.

## 2.5 Grounding brief — MANDATORY before §3

### 1. Trace producer → owner → consumer, end to end

Command: `/usr/bin/grep -rn "LevelHistory\|RecordingLevelMeterView\|updateLevel\|levelTick\|currentAmplitude\|getCurrentAmplitude\|AudioLevelScale\|READ_BLOCK_BYTES" app/src -l`
(16 files; every one is in §10).

| Hop | Mechanism | Where |
|---|---|---|
| Microphone → bytes | `AudioRecord.read(buffer, 0, requested, READ_BLOCKING)` on the capture thread, `requested = min(buffer.size, remaining)` | `AudioCaptureService.kt:379-384` |
| Bytes → file | `active.output.write` (a raw `FileOutputStream`) per read | `AudioCaptureService.kt:388` |
| Bytes → detector | `offerToDetector` stages into whole `READ_BLOCK_BYTES` blocks and offers to a `BlockRing`; the feeder thread polls the ring | `AudioCaptureService.kt:425-445`, `BlockRing.kt` |
| Bytes → level | mean absolute sample of the read → `@Volatile currentAmplitude` | `AudioCaptureService.kt:392-403` |
| `:audio` → main process | `IAudioCaptureService.getCurrentAmplitude()` (AIDL, binder) | `IAudioCaptureService.aidl:9`, `AudioCaptureService.kt:203` |
| Main: read + scale | `DictationPollingThread`, last in each 100 ms tick, `AudioLevelScale.display` + `smooth` | `DictationSessionService.kt:566-641` |
| Main: publish | `RecordingOverlayState.updateLevel` → `Snapshot(level, levelTick)` under a lock, then `mainHandler.post` of the LATEST snapshot to the one attached listener, and `MutableStateFlow` for `snapshots` | `RecordingOverlayState.kt:40-66, 130-133, 176-187` |
| Overlay | `RecordingAccessibilityOverlay.onChanged`: on `levelTick` change, `meter.pushSample(snapshot.level)`; `barCount` 22 (hold pill) or `FULL_PILL_BARS = 11` (tap pill) | `RecordingAccessibilityOverlay.kt:224-231, 654-659` |
| Draw | `RecordingLevelMeterView`: `LevelHistory.push`, `bars(count)`, `onDraw` with the positional rainbow shader and the resting grey for a zero sample | `RecordingLevelMeterView.kt:86-97, 130-160` |
| Second reader of snapshots | `OnboardingViewModel.enterPractice` collects `snapshots` and reads phase, target and transcript id only | `OnboardingViewModel.kt:180, 217-235` |
| Drawn demo | `OnboardingDemo.DemoRail` draws a scrolling tape from `LEVEL_SAMPLES` | `OnboardingDemo.kt:219, 242-255` |

Both processes are confirmed in the manifest: `AudioCaptureService` `android:process=":audio"`
(`AndroidManifest.xml:79-80`); `DictationSessionService` and `PasteAccessibilityService` carry no
`process` attribute (`AndroidManifest.xml:71-72, 116-117`), so they share the default process.

### 2. Find the existing authority before proposing one

- **Hand-off from the capture thread:** `BlockRing` (`BlockRing.kt`), single-producer single-consumer,
  preallocated, refuses when full. Its one caller is `offerToDetector`. The analyser reuses it with a
  smaller block size and one extension, a per-slot tag (a `LongArray` beside the byte slots, written
  before the index is published, read after it); no new primitive.
- **A per-take worker thread in `:audio`:** the detector feeder (`feederLoop`,
  `AudioCaptureService.kt:491-580`), started per take, stops on `session !== active || stopRequested`,
  interrupted in `releaseSession` (`:696`) and `onDestroy` (`:730`), never joined on the stop path. The
  analyser thread mirrors this shape exactly.
- **Display scaling:** `AudioLevelScale` (`AudioLevelScale.kt`) owns the dB window (-55 to -10 dBFS) and
  attack/release for ONE number. It becomes unused and is deleted; its dB-window idea moves into the
  analyser's per-band scale, with per-band constants (a band carries less energy than the whole signal).
- **Per-frame animation on a plain `View`:** none in the tree. `/usr/bin/grep -rn "Choreographer\|postOnAnimation\|ValueAnimator" app/src/main/java` → `RecordingAccessibilityOverlay.kt` uses
  `ValueAnimator` for the pill's show/hide only (verified by reading the hits). `new authority proposed`:
  the meter view runs its own `postOnAnimation` (external) loop while any bar is still moving.
- **Spectrum analysis:** `/usr/bin/grep -rni "fft\|goertzel\|spectrum" app/src/main/java` → no hits.
  `new authority proposed`: `SpectrumAnalyzer` (proposed), pure Kotlin, preallocated.

### 3. Read prior attempts and live direction

Posted as the Gate 0 comment on #151. In short: the history rail is the port of the Mac's
`RainbowLevelMeter` (PR #137, build 105); the tap pill's half reach is build 116; no catalog `decision`
row fixes the meter's form; #44 is stale and is closed when this ships. The founder's option B decision is
the live direction and the Mac is explicitly not changed.

### 4. Name the lifecycle, trust and process boundaries a naive design would miss

| Boundary | Current | Planned |
|---|---|---|
| Capture thread vs everything | does one arraycopy into the detector ring and no more | adds one arraycopy into the analyser ring and an `unpark`; no FFT on it |
| Analyser thread vs session | n/a | per take, owned by `CaptureSession` like `feederThread`; exits on `session !== active || stopRequested`; interrupted in `releaseSession` and `onDestroy`; never joined |
| `:audio` → main | one float per binder call | one `float[11]` per binder call; a call while not capturing returns 11 zeros, never an empty array |
| Main poll thread vs take | one thread, meter last in the tick | a second thread for the meter only; the take's tick is unchanged; both loops exit on the same `state` condition |
| Wedged `:audio` (#115) | a poll blocked in binder hangs the polling thread | the meter thread blocks the same way and is equally abandoned; it holds nothing the take needs |
| Snapshot publication | quantised float + tick, one post per change | a `FloatArray` copy per publish, one post per change, the post reads the latest snapshot (coalesces at 30 Hz) |
| Stop racing a publish | `updateLevel` is a no-op when not visible (guards `RecordingOverlayStateTest`) | `updateBands` keeps the same guard |
| Frame loop vs pill | n/a | runs only while attached AND a bar is still moving; `reset()` and detach stop it |
| Onboarding practice collector | reads phase/target/transcript | unchanged; 30 Hz emissions assign equal values to Compose state, which does not recompose |

### 5. Prove the high-risk premises

- **256 ms per read:** 8192 bytes ÷ 2 bytes per sample ÷ 16 000 samples per second = 0.256 s.
  `PcmAudio.kt:5` `SAMPLE_RATE = 16_000`; `PcmAudio.BYTES_PER_SAMPLE` is used at
  `AudioCaptureService.kt:393`; the detector comment at `:418` says "whole 256 ms blocks".
- **Smaller reads are tolerated by the detector:** `offerToDetector` loops `while (consumed < bytesRead)`
  staging into `pendingBlock` until `pendingBytes == READ_BLOCK_BYTES` (`AudioCaptureService.kt:429-443`).
- **The byte ceiling is per read:** `remaining = MAX_AUDIO_BYTES - bytesWritten` bounds `requested`
  (`:376-383`); a smaller read only makes the ceiling tighter, never looser.
- **`CaptureBufferOwnershipTest` pins the source text** `READ_BLOCK_BYTES = 8_192` and the AudioRecord
  buffer argument (`CaptureBufferOwnershipTest.kt:50-81`); it is updated, not weakened: the AudioRecord
  constructor keeps `nativeBufferBytes` (`AudioCaptureService.kt:244-256`, the platform minimum floored at
  one second, untouched by this change), `READ_BLOCK_BYTES` stays the detector block only, and the read
  allocation and request become `READ_CHUNK_BYTES` (proposed).
- **`LEVEL_STEPS` quantisation has no consumer beyond the rail:** `/usr/bin/grep -rn LEVEL_STEPS app/src`
  → `RecordingOverlayState.kt:64, 132` only.
- **Codex problem-only consult** run before §3 was finalised (answer file
  `codex-consult-151.txt.last` in this session's scratchpad). Findings and their dispositions:
  - *The AudioRecord native buffer is sized separately* (`nativeBufferBytes`, `AudioCaptureService.kt:244-256`);
    only the read allocation (`:296`) and the request (`:383`) use `READ_BLOCK_BYTES`. Adopted: the chunk
    constant replaces those two sites and the log line at `:255` names both sizes.
  - *`CaptureBufferOwnershipTest.theReadBlockIsTwoHundredAndFiftySixMilliseconds` asserts the detector
    block, not the read* (`:46-55`), so shrinking the read while keeping the constant would pass it
    silently. Adopted: a sibling assertion pins the READ to `READ_CHUNK_BYTES = 1_024` at the call site.
  - *There is no feeder join; `FEEDER_JOIN_MS` is unused (`:60`); a feeder may outlive `releaseSession`.*
    Adopted for the analyser: its published arrays live on the `CaptureSession`, so a thread outliving
    its take writes into a dead session's arrays and `getSpectrumBands` reads only the LIVE session's.
    `FEEDER_JOIN_MS` is deleted as dead code in passing.
  - *The main polling loop checks `RECORDING`, not take identity, so an old blocked thread could resume
    during a new take.* Existing behaviour, out of scope; the meter thread inherits it and is harmless
    when it does (same source, same consumer), noted in §5.
  - *A meter call placed last in the tick still blocks the NEXT terminal check* (`:630-639`). This is the
    argument for the separate thread in §3.
  - *`Snapshot.copy` shares array storage; reference equality means every publish emits.* Adopted:
    `updateBands` copies on the way in and the array is read-only by contract (§8).
  - *Capture-thread start failure clears ownership without interrupting an already-started feeder*
    (`:321-330`). The analyser thread is started AFTER the capture thread started successfully, so this
    branch has nothing of ours to interrupt.

**Consolidation:** none. This change replaces one mechanism (a scalar history) with another (a band picture)
along the same single path; it introduces no second owner and merges none.

## 3. Design

**Producer, `:audio`.** `AudioCaptureService` reads `READ_CHUNK_BYTES = 1_024` (512 samples, 32 ms) per
`AudioRecord.read` while the detector ring and its staging keep `READ_BLOCK_BYTES = 8_192` and the
AudioRecord's own native buffer keeps its existing `nativeBufferBytes` sizing (untouched). After the file write and the detector offer, the capture thread offers the
same chunk to a second `BlockRing(SPECTRUM_RING_CHUNKS, READ_CHUNK_BYTES)` (`spectrumRing` (proposed) on the session) and `LockSupport.unpark` (external) wakes the
analyser thread. Every chunk is offered with its POSITION: the byte offset of its first sample in the take
(`active.bytesWritten` before the write, which advances for every read whether or not the ring accepted
it). `BlockRing` gains a parallel `LongArray` of tags: `offer(source, length, tag)` and, after a `poll`,
`lastPolledTag` (proposed); the detector's caller passes its own position explicitly (unused by the
detector, but an explicit argument rather than a default, `validation-discipline.md` FACT:
silent-empty-traps). A full ring drops the chunk and nothing else. Continuity is owned by the analyser:
`analyze(chunk, length, position)` remembers the position it expects next and, when a chunk's position
differs, resets its 1024-sample window to silence BEFORE analysing that chunk, so two samples that were
not adjacent in the take are never in one window. On every wake the analyser DRAINS the ring, analysing
each queued chunk in order (about 50 µs each, so a backlog of eight is under half a millisecond) and
publishing only after the last, so the published picture is always the newest audio the ring holds. The
analyser waits with `LockSupport.parkNanos` (external) and a 50 ms ceiling, and re-checks its stop
condition (`session !== active || stopRequested`) on every wake, so an ending claimed while it is parked
releases it within 50 ms even before `releaseSession` interrupts it. A ring offer carries the read's
LENGTH, and the analyser slides its window by exactly that many samples, so the short positive read
`AudioRecord.read` (external) can return at a stop (`AudioCaptureService.kt:383-386`) is analysed as far
as it goes and never padded with stale bytes. If constructing or starting the analyser thread fails, the
take carries no analyser: capture is untouched, the published arrays stay zero, and the pill shows the
resting rail; the failure is logged off the capture thread. An analyser exception publishes the zero
picture before the thread exits, so the pill rests rather than holding the last picture. A new
per-take analyser thread (`analyserThread` (proposed) on `CaptureSession`, running `analyserLoop` (proposed)) polls the ring, feeds
each chunk to a `SpectrumAnalyzer` (proposed, `audio/SpectrumAnalyzer.kt`), and publishes the result by
copying it, under a small lock shared ONLY with the binder getter (`bandsLock` (proposed); the capture
thread never touches it), into one preallocated published `FloatArray(BAND_COUNT)` (`BAND_COUNT` (proposed) = 11); `getSpectrumBands` copies out under the same lock, so a reader can never observe a
half-written picture. The lock is held for eleven float copies and nothing else. `getCurrentAmplitude`
keeps its meaning (now the mean over 32 ms) for the appended-AIDL rule; nothing in the app reads it after
this change.

`SpectrumAnalyzer`: a 1024-sample window (the newest two chunks, so 64 ms of context at a 32 ms hop), a
Hann window, a radix-2 real FFT with tables built in the constructor, 11 bands: the first one octave wide,
85 to 170 Hz, so the fundamental of nearly every speaking voice lands in the CENTRE bar (an equal log split
stopped it at 146 Hz and left the middle dark for a higher voice on the emulator's first spoken take,
2026-09-14), then ten log-spaced bands up to 6.4 kHz (bin width 15.625 Hz), per-band RMS magnitude normalised so a
full-scale sine reads 0 dBFS in its band, a mild tilt (+3 dB per octave above 300 Hz, so fricatives at the
edges show against the natural fall-off of speech), then the same dB-window shape as today's
`AudioLevelScale.display` with per-band constants (`QUIET_DBFS = -62`, `LOUD_DBFS = -18`, measured on the
emulator and re-tuned on the founder's phone pass; the numbers carry their date). Output: 11 floats in
0..1, low band first. No allocation after construction; a non-finite input reads as silence.

**AIDL.** Appended, below the existing append line: `float[] getSpectrumBands();` (`getSpectrumBands` (proposed)) — always
`SpectrumAnalyzer.BAND_COUNT` long; zeros when not capturing. A band-count getter is deliberately
NOT added: the length is the contract, and a client that needs to know reads the array's size.

**Consumer, main process.** `DictationSessionService.startPolling` starts a second thread,
`DictationMeterThread` (proposed), with the same loop condition as the polling thread. Each pass:
`runCatching { service.spectrumBands }` (`spectrumBands` (proposed), the Kotlin property for `getSpectrumBands`) → `RecordingOverlayState.updateBands(it)` on success, and
`updateBands` of the shared zero picture on a THROWING failure, so a dead binder eases the rail to rest
instead of freezing it. A read that never returns (a wedged `:audio`, #115) reaches no branch at all: the
rail holds its last targets until the pill hides, exactly as the timer does today, and no stale-reading
expiry is added here; `Thread.sleep(33)`. The thread is constructed and started inside its own
`runCatching`: a failure to start it is logged and the take, and the polling thread, carry on without a
picture (`tryStartRecording`'s catch at `DictationSessionService.kt:553` is never reached by it). Take identity is
owned by the snapshot, not by the thread: `RecordingOverlayState.show()` stamps each take with a fresh
`takeSerial` (proposed) in the `Snapshot`, `startPolling` runs after `show()`
(`DictationSessionService.kt:542-545`) and the meter thread captures that serial at start. Every publish
is `updateBands(serial, bands)`, and the serial is compared INSIDE the same locked `change` that commits
the bands, so a stale thread's picture is refused atomically however late it arrives; after each read the
thread also compares the serial with the current snapshot's and exits when it differs, so no second loop
lives on into a later take. `lastMeterLevel`, the meter block in the tick, and `AudioLevelScale` go.

**Snapshot.** `Snapshot.level` and `levelTick` (removed) become `bands` (proposed), a `FloatArray` with a shared zero
default; `updateLevel` (removed) becomes `updateBands` (proposed), which copies the array, keeps the
not-visible guard, and commits. Equality on a data class with a `FloatArray` is referential, so every
publish emits; that is the intended behaviour (the tick existed to force exactly this).

**Overlay and view.** `RecordingAccessibilityOverlay.onChanged` calls `meter.setBands(snapshot.bands)` on
every delivery. `RecordingLevelMeterView` keeps `target[barCount]` and `shown[barCount]`; `setBands` (proposed) maps
band → bars centre-out (`barBand` (proposed): bar `i` of `count` reads band
`round(|i - (count-1)/2| / ((count-1)/2) * (BAND_COUNT-1))`, so the 22-bar rail shows all 11 bands
mirrored and the 11-bar rail shows six of them mirrored) and starts the frame loop if it is not running.
Each frame moves `shown` toward `target` with a time-constant easing (`1 - exp(-dt/τ)`, `τ_attack = 35 ms`,
`τ_release = 110 ms`), invalidates, and stops itself once every bar is within 0.005 of its target. Drawing
is today's: symmetric about the centre line, `fill(level)` with the 14 % floor, the positional rainbow,
resting grey when a bar's shown value is under `RESTING_EPSILON` (proposed) = 0.02. `pushSample` and `LevelHistory`
(removed).

**Demo.** `OnboardingDemo.DemoRail` draws the centre-out picture from a scripted, deterministic
"voice" (an envelope in `t` times a per-bar shape with a slow per-bar wobble), replacing `LEVEL_SAMPLES`
(removed).

**Alternatives rejected.**
- *Compute the FFT on the capture thread.* Cheap (tens of microseconds per chunk) but a bug in the
  analyser would be caught by the capture loop's `catch` and end the take with `TERMINAL_REASON_ERROR`.
  A limb may not be able to do that (`architecture-rules.md` RULE: isolate-limbs), so it runs on its own
  thread, mirroring the detector feeder.
- *Push levels by AIDL callback from `:audio`.* Closer to RULE: no-idle-cost's wording ("pushed"), but it
  adds a callback interface, a registration lifecycle and a second binder direction for the same
  30-per-second payload. The existing rule-compliant shape is one reader polling only while a take is
  open; keeping it changes less.
- *Speed up the existing 100 ms loop to 33 ms.* The take's own tick (elapsed second, notices, the
  terminal-reason check) would run three times as often for no reason, and a slow meter read would sit
  in the same loop as the check that starts transcription. A separate thread is the stronger form of the
  isolation the old "meter is LAST in the tick" comment was reaching for.
- *A time-domain history at 30 Hz (option A).* Rejected by the founder.
- *More bands (16 or 32).* The tap pill draws 11 bars; more bands than bars would be sampled away. 11 is
  the number the hold pill can show in full.

## 3b. Ownership justification

The analyser lives in `:audio` on `AudioCaptureService` because the bytes are there and the boundary the
product cares about is the network, not the process; the alternative was shipping raw chunks over binder
to the main process and analysing there, but that is 31 binder calls a second carrying 1 KB each for a
picture, and it puts signal work in the UI process. The meter thread lives on `DictationSessionService`
because it is the one session owner and already the one reader of the capture service (RULE:
one-owner-for-the-session); the alternative was the overlay reading `:audio` itself, which makes a second
surface a second reader.

## 4. Contract deltas

| Type | Before | After, semantically |
|---|---|---|
| `IAudioCaptureService.getSpectrumBands()` (new, appended) | — | "The pitch picture of the last 64 ms": 11 floats 0..1, low band first; all zeros whenever no take is open. Never empty, never null. |
| `AudioCaptureService.READ_CHUNK_BYTES` (new) | reads were 256 ms | one read is 32 ms; the file, the ceiling and the detector see the same bytes in the same order. |
| `AudioCaptureService.getCurrentAmplitude()` | mean over 256 ms | mean over 32 ms. No in-app reader. |
| `RecordingOverlayState.Snapshot.bands` (new) | `level` + `levelTick` | the latest picture; a fresh array per publish. |
| `RecordingOverlayState.updateBands(FloatArray)` (new) | `updateLevel(Float)` | publish one picture; no-op when the pill is not visible. |
| `RecordingLevelMeterView.setBands(FloatArray)` (new) | `pushSample(Float)` | set the bars' targets; the view animates toward them. |
| `SpectrumAnalyzer` (new) | — | chunk bytes in, band levels out; pure; no allocation after construction. |
| `LevelHistory`, `AudioLevelScale` (removed) | — | gone, with their tests. |

## 5. End-to-end state and lifecycle audit

| Population | Enumeration |
|---|---|
| Places a take starts capture | `startCapture()` and `startCaptureWithSilenceStop()` both reach `startRecording` (`AudioCaptureService.kt:~260`), which builds the one `CaptureSession`; the analyser ring and thread are created there, next to the detector ring. |
| Places a take ends | `claimEnding` from the capture loop (duration, ceiling, error), `stopRecording` (manual), `endTake` (silence), `onDestroy`; all reach `releaseSession` (`:685`), which interrupts `feederThread`; the analyser thread is interrupted in the same lines and exits on its own `session !== active` check. |
| Threads per take in `:audio` | capture thread, feeder thread (optional), analyser thread (new). Each is owned by the `CaptureSession`, none is joined on the stop path, `onDestroy` joins only the capture thread. |
| Threads per take in the main process | `DictationPollingThread`, `DictationMeterThread` (new). Both loop on `state.get() == SessionState.RECORDING` and `audioService != null`. |
| Readers of `Snapshot` | the overlay listener (`RecordingAccessibilityOverlay`), `OnboardingViewModel.followOwner`, `RecordingOverlayStateTest` (androidTest). |
| Callers of `barCount` | `RecordingAccessibilityOverlay.kt:659` only. |
| Places the view must stop animating | `reset()` (a new take), detach from window, the pill hidden (`onChanged` with `!visible`). Enumerated in §10. |
| Analyser start failure | `startRecording` after the capture thread started (`AudioCaptureService.kt:326-343`): the analyser's `runCatching { start() }` failure leaves `analyserThread` null and the bands zero; the surrounding catches that close capture resources are not reached by it. |
| Ending claimed while the analyser is parked | `stopRecording` (`:620-659`) and `endTake` claim the ending BEFORE `releaseSession` runs; the analyser's 50 ms park ceiling and per-wake stop check bound its exit whatever the release timing. |
| An old meter thread resuming in a later take | `startPolling` (`DictationSessionService.kt:569-571`) loops on `state`, not take identity, so a read blocked in binder during take N can return during take N+1. Its publish carries take N's `takeSerial` and is refused inside `updateBands`'s locked `change` against the snapshot's serial (stamped by `show()` for N+1), and the thread exits on seeing the serial differ, so it neither publishes into N+1 nor loops there. A thread that passes a check and then pauses cannot win: the check IS the commit. The POLLING thread has the identical pre-existing branch (two polling threads after such a return); out of scope here and routed to an issue at wind-down. |
| Meter thread start failure | inside `tryStartRecording`'s try (`DictationSessionService.kt:545-553`): construction and `start()` sit in their own `runCatching`, so the capture-failure catch is not reached and the polling thread runs as before. |
| Process death of `:audio` mid-take | the meter thread's binder call throws `DeadObjectException` (external), caught by `runCatching`; `onServiceDisconnected` (`DictationSessionService.kt:243-249`) nulls `audioService` and, while `RECORDING`, calls `handleServiceFailure`, which moves `state` off `RECORDING` and so ends both loops. |

## 6. Downstream consumer matrix

| Contract delta | Consumer | Current | Required | Code change? | Verified by |
|---|---|---|---|---|---|
| 32 ms reads | file writer | one write per 256 ms | one write per 32 ms, same bytes | none | `CaptureBufferOwnershipTest` (updated) |
| 32 ms reads | detector staging | whole blocks | whole blocks, assembled from 8 chunks | none | existing `SilenceStopWiringTest` |
| 32 ms reads | byte ceiling | per read | per read | none | existing ceiling test in `CaptureBufferOwnershipTest` |
| `getSpectrumBands` | `DictationMeterThread` | — | read every 33 ms while recording | new | `LiveAudioMeterWiringTest` (rewritten) |
| `Snapshot.bands` | overlay | reads `level`/`levelTick` | reads `bands` | yes | `LiveAudioMeterWiringTest` |
| `Snapshot.bands` | `OnboardingViewModel` | ignores level | ignores bands | none | reading `followOwner` |
| `Snapshot.bands` | `RecordingOverlayStateTest` (androidTest) | `updateLevel` | `updateBands` | yes | the test itself |
| `setBands` | `RecordingAccessibilityOverlay` | `pushSample` per tick | `setBands` per delivery | yes | `LiveAudioMeterWiringTest` |
| `DemoRail` | onboarding demo | tape | picture | yes | `OnboardingDemoScriptTest` (unchanged; it tests the script, not the drawing) |

## 7. Failure-mode × caller table

| Failure mode | Origin | Caller | What the user sees | Persisted state | Retry |
|---|---|---|---|---|---|
| Analyser throws | `SpectrumAnalyzer` | analyser thread | the bars settle to resting grey for the rest of the take; words unaffected | none | next take |
| Analyser ring full | capture thread busy | `offer` returns false | a 32 ms gap in the picture | none | automatic |
| `getSpectrumBands` throws or `:audio` dead | binder | meter thread | the zero picture is published, so the bars ease to resting; the take ends the way `onServiceDisconnected` describes | none | next take |
| `getSpectrumBands` never returns (wedged `:audio`, #115) | binder | meter thread | the bars hold their last picture until the pill hides, as the timer holds its last second; #115 owns the wedge itself | none | next take |
| Meter thread fails to start | thread construction | `startPolling` | no picture for this take (resting rail); words unaffected | none | next take |
| Pill hidden while a publish is in flight | stop race | `updateBands` | nothing (no-op when not visible) | none | — |
| Frame loop after detach | overlay removed | view | nothing (loop checks `isAttachedToWindow` (external)) | none | — |

No new user-facing sentence; the picture's failure state is the resting rail, which already means
"quiet".

## 8. Caller-visible signals audit

| Field | Meaning beyond its type |
|---|---|
| `Snapshot.bands` all zeros | silence or "no reading yet": both draw the resting rail, as today's `level = 0f` did. A dead microphone looks quiet, which is the honest picture. |
| `Snapshot.bands` identity | a new array per publish; consumers must not keep a reference expecting it to update. |
| `getSpectrumBands().size` | always `BAND_COUNT`; a different length is a contract violation the view clamps against (extra bands ignored, missing read as zero). |
| resting grey vs rainbow on a bar | shown value under `RESTING_EPSILON`; a bar at the floor reads "quiet", as today. |

## 9. Fallback source-of-truth audit

| Failure branch | Candidate expression | Source | Why authoritative | Acceptance predicate | If none | Consumer |
|---|---|---|---|---|---|---|
| a throwing meter failure | the zero picture | published by the meter thread's failure branch, eased by the view | the rail's own resting state already means "quiet" | bars reach the floor within ~0.5 s | draw resting | overlay |
| a blocked meter read | the last picture | the view's last targets | nothing newer exists and nothing can say so | the pill hides with the take | hold | overlay |
| take continues | the take's own tick | `DictationPollingThread` | unchanged owner of the take | words land | — | session |

## 10. File-by-file changes

- `app/src/main/aidl/com/envi/wispr/audio/IAudioCaptureService.aidl` — append `float[] getSpectrumBands();`.
- `app/src/main/java/com/envi/wispr/audio/SpectrumAnalyzer.kt` (new) — window, FFT, bands, scale, position
  continuity; pure.
- `app/src/main/java/com/envi/wispr/audio/BlockRing.kt` — a tag per slot; `offer` takes it, `lastPolledTag`
  exposes it; `offerToDetector` passes the block's position explicitly.
- `app/src/main/java/com/envi/wispr/audio/AudioCaptureService.kt` — `READ_CHUNK_BYTES`; the read buffer
  (`:296`) and the read request (`:383`) use it, and the constant's documentation (`:36-47`) tells 32 ms
  reads from 256 ms detector blocks; `currentAmplitude`, its getter, its per-read calculation and its
  start/release resets (`:132, :203, :305, :401, :688`) are kept as they are; `CaptureSession` gains `spectrumRing` and `analyserThread` and the two published arrays (no
  staging: one read is one chunk); `startRecording` creates and starts the analyser;
  `captureLoop` offers and unparks after the detector offer; `analyserLoop` (new); `releaseSession` and
  `onDestroy` interrupt it; the binder implements `getSpectrumBands`; the buffer-size log line names the
  chunk.
- `app/src/main/java/com/envi/wispr/audio/AudioLevelScale.kt` — deleted.
- `app/src/main/java/com/envi/wispr/ui/DictationSessionService.kt` — meter thread; remove the meter block,
  `lastMeterLevel`, the `AudioLevelScale` import.
- `app/src/main/java/com/envi/wispr/shortcuts/RecordingOverlayState.kt` — `Snapshot.bands`;
  `updateBands`; remove `level`, `levelTick`, `LEVEL_STEPS`, `updateLevel`; rewrite the snapshot,
  publication, stop-race and delivery comments (`:44, :49, :121, :150, :174`) for band arrays at ~30 Hz.
- `app/src/main/java/com/envi/wispr/paste/RecordingAccessibilityOverlay.kt` — `setBands` on delivery;
  the `lastLevelTick` field and its hidden-branch reset (`:219`) go; the two meter-delivery comments
  (`:225, :233`) describe band publication; `reset()` on show stays.
- `app/src/main/java/com/envi/wispr/paste/RecordingLevelMeterView.kt` — targets, shown, `barBand`, the
  frame loop, `setBands`; `pushSample` and the history go; the class and method documentation (`:12-33,
  :83`) states the centre-out band contract; drawing unchanged.
- `app/src/main/java/com/envi/wispr/paste/LevelHistory.kt` — deleted.
- `app/src/main/java/com/envi/wispr/ui/OnboardingDemo.kt` — `DemoRail` draws the picture with the same
  centre-out mapping at whatever bar count it is given (the demo's hold pill keeps its 16 bars, `:219`,
  because the demo pill is narrower than the real one; the mapping is count-agnostic); `LEVEL_SAMPLES`
  and the scrolling-rail comment (`:249`) go.
- Tests: `SpectrumAnalyzerTest` (proposed), `RecordingLevelMeterViewTest` (proposed) (pure functions),
  `LiveAudioMeterWiringTest` (rewritten), `CaptureBufferOwnershipTest` (updated strings),
  `RecorderBrandTest` (update if it names `pushSample`), `LevelHistoryTest` and `AudioLevelScaleTest`
  (deleted), `androidTest/RecordingOverlayStateTest` (`updateBands`).

## 11. Testing

1. **Class of every new test.** `SpectrumAnalyzerTest`: product outcome ("when this fails, the user sees a
   bar light for the wrong pitch, or a quiet room jittering"). `RecordingLevelMeterViewTest` (`barBand`,
   easing): product outcome ("the centre bar shows the high band" / "the bars snap up and never settle").
   `LiveAudioMeterWiringTest`: drift guard on the wiring (the read happens on its own thread, is caught,
   reaches the snapshot, and a publish after stop does not reopen the pill).
2. **Revert that turns each red.** Analyser: swap the band edge table for a linear one → the 1 kHz tone
   lands in the wrong band. `barBand`: drop the mirror → the edge bar reads band 0. Easing: use one τ for
   both directions → the "rises faster than it falls" row fails. Wiring: move the read back into the
   polling tick → the "own thread" assertion fails. Each revert is performed once during the build and
   noted in the commit body.
3. **Deliberately not tested.** The drawn output of `onDraw` (a canvas cannot be asked what it drew; the
   pure `fill` and `barBand` are the asserted parts). Absolute per-band dB constants (a display choice,
   tuned by eye on hardware and dated). The frame loop's timing on a real display (emulator UAT and the
   founder's pass judge it).

### 11.1 Hardware UAT spec

- **Subsystem:** heart path (capture read size) and limb (the picture).
- **Recipe:** rung 1, emulator with the virtual cable (`device-testing.md` RULE:
  the-emulator-cannot-answer-the-questions-that-matter, the `say_into_emulator` procedure): open a take
  from the bubble in Messages, speak "Can we push the sync to Thursday?", screen-record the pill, confirm
  the words land. Rung 2, the founder on the S26 through Play, per the Preface.
- **Expected observation:** in the recording, the centre bars rise and fall with the syllables (at least
  five distinct swells for the six-syllable sentence), the edges flick on "push" and "sync", the rail is
  resting grey within half a second after the sentence, and the transcript lands. Oracle: the frames of
  the screen recording plus the landed text; the analyser's own log lines are NOT the oracle.
- **Phone state to restore afterwards:** none on the S26 (Play delivery). Emulator: the Mac's default
  input back to the built-in microphone (the recipe's restore book).

### 11.2 Other obligations

| Test | Class | Proves | Revert that turns it red |
|---|---|---|---|
| `SpectrumAnalyzerTest` tone-in-band | product | a 1 kHz tone lights only the 1 kHz band | linear band edges |
| `SpectrumAnalyzerTest` silence and full scale | product | an all-zero chunk reads 0 in every band; a full-scale chunk reads at most 1 in every band; both finite | remove the output clamp |
| `SpectrumAnalyzerTest` length | contract | the analyser's output is `BAND_COUNT` long for a normal chunk, a short chunk and an empty chunk | resize the output |
| `LiveAudioMeterWiringTest` service length | contract | `getSpectrumBands` returns the shared zero picture of `BAND_COUNT` when no session is live (source-level: the no-session branch names that constant) | return an empty array |
| `LiveAudioMeterWiringTest` failure rests | drift guard | the read's failure branch publishes the zero picture (source-level), and the analyser loop's catch does the same | drop either publish |
| `LiveAudioMeterWiringTest` take serial | drift guard | `show()` stamps a fresh `takeSerial`; `updateBands` compares the caller's serial inside `change` and refuses a mismatch; the meter loop passes the serial it captured and exits on a changed one | compare outside `change`, or drop the exit |
| `RecordingOverlayStateTest` (androidTest) stale serial | product | a publish carrying the previous take's serial, delivered after the next `show()`, changes nothing in the new take's snapshot | drop the serial check |
| `LiveAudioMeterWiringTest` publication lock | drift guard | the analyser's copy-in and the getter's copy-out both sit inside `synchronized(bandsLock)` (source-level; a single-threaded test cannot open the window, `validation-discipline.md` RULE: a-single-threaded-test-cannot-distinguish-atomic-from-check-then-act) | move either outside the lock |
| `RecordingLevelMeterViewTest` rest | product | from non-zero targets, `setBands` of zeros eases every bar to the floor | hold the last target on zeros |
| `RecordingLevelMeterViewTest` `barBand` | product | centre = band 0, edges = last band, symmetric, for 22 and 11 | drop the mirror |
| `RecordingLevelMeterViewTest` easing | product | rises faster than falls; two 8 ms steps ≈ one 16 ms step | one τ |
| `LiveAudioMeterWiringTest` | drift guard | own thread, caught, reaches snapshot, no reopen after stop; `spectrumBands` is read in the session owner ONCE and in neither surface (the band form of `theMeterIsOnlyEverReadInOnePlace`); every mutator the test inspects is asserted to EXIST before its body is read, so a renamed method fails loudly instead of `substringAfter` scanning unrelated text | move the read into the tick; add a read to the overlay |
| `RecorderBrandTest.onlyTheRailMovesWithTheVoice` | drift guard | the rail declares `setBands`; the brand mark declares neither `setBands`, `pushSample` nor `setLevel` | give the mark a `setBands` |
| `SpectrumAnalyzerTest` gap reset | product | chunks A (position 0), B (1024) analysed, C (2048) never offered, D (3072) analysed: D's picture equals the picture of D analysed after `reset()` alone, and B's older half is not in it (a tone only in B reads 0 in D's bands) | join B and D |
| `BlockRingTest` (proposed) tags | contract | `lastPolledTag` after `poll` is the tag given to the matching `offer`, in order, across a wrap of the ring | store the tag in the wrong slot |
| `BlockRingTest` publication order | drift guard | source-level: in `offer` the bytes, the length and the tag are written before `writeIndex` is set, and in `poll` the bytes and the tag are copied out before `readIndex` is set (a sequential test cannot see this order, `validation-discipline.md` RULE: a-single-threaded-test-cannot-distinguish-atomic-from-check-then-act) | move the tag write after the index |
| `LiveAudioMeterWiringTest` drain | drift guard | the analyser loop polls the ring until empty before publishing and passes `lastPolledTag` as the position (source-level) | publish per chunk, or drop the tag |
| `SpectrumAnalyzerTest` short read | product | a chunk shorter than 1024 bytes advances the window by its own samples and analyses only real bytes | pad or ignore short reads |
| `CaptureBufferOwnershipTest` | drift guard | the constructor still receives `nativeBufferBytes`, the detector block stays 8192, the read allocation and request are 1024, and the log names all three | swap the read and block constants |

## 12. Blast radius & rollback

- Touched: `app` module: `audio` (capture read size, a new thread, a new class, one AIDL append), `ui`
  (session service meter thread, onboarding demo), `shortcuts` (snapshot), `paste` (overlay, meter view).
  Not touched: `:asr`, `:polish`, `:vad` (the detector still receives identical 256 ms blocks),
  insertion, history, settings, the pill's layout, `BrandPalette`, the Mac and Windows apps, the catalog
  binary (a `data/NNN-*.sql` row at wind-down).
- Rollback: revert the one squash commit. The AIDL method stays appended (append-only) but unread; the
  instrumentation APK binds by transaction number and the existing numbers do not move.

## 13. Ship criteria specific to THIS change

- [ ] On the founder's S26, the rail moves with his voice, syllable by syllable, and rests within half a
      second of silence.
- [ ] A spoken sentence on the emulator lands as text with the new read size (the heart is untouched).
- [ ] `getSpectrumBands` is the LAST method in the AIDL file and nothing above the append line moved.
- [ ] No thread, poll or frame callback runs when no take is open (read the thread list after a take).

## 14. Open questions

- The per-band dB constants and the tilt are a first guess; the founder's phone pass tunes them. Named
  here so the review does not treat them as measured.

## 15. Related

#151 (this), #44 (stale; closed with a pointer when this ships), #135/#137 (the rail's history port),
#115 (a wedged `:audio` and the polling thread), catalog `live-audio-meter`.

---

## Checklist for the plan author

- [x] Gate 0 prior context posted before this file was written
- [x] User Rubric answered against named personas
- [x] §2.5 grounded in real code before §3 was written
- [x] §4-9 answered
- [x] Lane declared and matching detection
- [x] Self-reviewed to all-clear before any reviewer saw it (cited-symbols clean; Codex coverage one round, grounded rounds 1-5, PROCEED-AS-PLANNED on round 5)

## Checklist for the reviewer

- [ ] Is the direction right, before anything else?
- [ ] Is any load-bearing negative claim unevidenced?
- [ ] Does any §5 row describe a topic instead of enumerating a population?
- [ ] Does the design answer a question the rules already answered?
- [ ] Would any test here pass with its behaviour removed?
