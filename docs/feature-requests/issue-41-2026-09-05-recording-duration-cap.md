# Issue 41 — dictation stops dead at two minutes with no warning

## 1. What the user gets
Speak longer than two minutes today and recording stops with no warning and no explanation. The words
up to that point ARE transcribed (`CaptureEnding.MaxDuration` routes to `stopAndTranscribe`), but
anything after is gone and nothing says so.

After: a ten minute limit, a line in the recorder in the last minute, and a line afterwards saying the
limit was reached.

## 2. Grounding (done before design)
- macOS: graceful cap 3600 s, hard backstop 3660 s, warning lead 60 s
  (`EnviousWisprCore/Constants.swift` `maxRecordingDuration`, `maxDurationWarningLeadSeconds`;
  `EnviousWisprAudio/AudioCaptureManager.swift` `maxRecordingDurationSeconds = 3660`). DEBUG overrides
  `EWDebugMaxRecordingSeconds` / `EWDebugWarningLeadSeconds` exist so UAT drives the whole cycle in ~90 s.
- macOS copy: "Recording auto-stops in under a minute (60-minute cap)" and
  "60-minute limit reached. Transcribing..." (catalog `user_copy`, `recording-duration-cap`).
- Catalog: macOS shipped, Windows deliberately-different (5 min watchdog, no warning), Android partial.

## 2.5 The founder decision this obeys, and where it is not met

Founder on #41, 2026-09-03: *"we need to increase the recording window to the max we can (like 1 hour if
possible)"*, and on the same issue: **"Name the measurement, do not pick the number from parity alone."**

An hour is NOT delivered here and this issue does not close. The reason is memory rather than a constant,
and it is routed to #117 with the arithmetic.

**Ten minutes is PROVISIONAL and it is not a proven maximum.** The arithmetic below covers two arrays;
it says nothing about what else `:asr` holds while the speech model is loaded. It is five times the
current cap and comfortably inside the two-array budget, which is enough to ship it and not enough to
call it the largest safe value. #117 takes the measurement that settles that.

## 3. Why NOT 60 minutes here
Measured on the founder's S26 Ultra 2026-09-05 by `getprop`: `dalvik.vm.heapsize=512m`,
`dalvik.vm.heapgrowthlimit=256m`. `app/src/main/AndroidManifest.xml` declares no `android:largeHeap`,
so every process including `:asr` is held to the 256 MB growth limit.

`AsrService.transcribeFile` holds two Java-heap copies of the whole take at once:
`file.readBytes()` (32,000 B/s) and `PcmAudio.toFloatSamples` (64,000 B/s) = 96,000 B/s.

| Cap | Java heap for the two copies |
|---|---|
| 2 min (today) | 11.5 MB |
| 10 min (proposed) | 57.6 MB |
| 60 min (macOS) | 345.6 MB — over the growth limit on its own |

Transcription is also one-shot: sherpa-onnx `OfflineRecognizer`, no streaming (issue #6). Decode time
grows linearly with the take, so a 60 minute cap would also mean a multi-minute wait with no progress.

Ten minutes is a deliberate product number: five times today's, comfortably inside the heap budget, and
it covers a long email or note. Anything beyond needs streaming ASR (#6) and is routed there, not
smuggled in here.

## 4. The DRY defect this closes
The cap exists twice and the two do not know about each other:
- `AudioCaptureService.MAX_RECORDING_DURATION_MS = 120_000L`
- `AsrService.MAX_AUDIO_BYTES = 120L * SAMPLE_RATE * 2`, whose refusal string hardcodes "120 second".
Raising one without the other either truncates silently or refuses a legal take with a wrong number.

New owner: `audio/RecordingLimits.kt`, a plain object both processes compile against, carrying the
duration, the derived byte ceiling, the warning lead, and the user-facing minute count.

## 5. Dead code this uses rather than leaves
- `IAudioCaptureService.getMaxDurationMs()` exists and has NO caller. The plan reached for it, so that
  the number the user is warned about would be the number the capture process is using. Review showed
  that bought nothing: both processes compile the same `RecordingLimits`, so there is no drift to
  catch, while the call added a place the polling thread could hang before its loop ran once (#115).
  It stays declared, because AIDL is append-only, and it stays uncalled.
- `AudioCaptureService.onMaxDurationReached` was declared and invoked and never assigned, on this
  change's own path. It is REMOVED. The terminal reason remains the only signal that a take hit the cap.

## 6. Surfaces
- Warning, at 60 s remaining: recorder notice, same route as the auto-stop notice, with the same Toast
  fallback when the accessibility service is not bound.
- Ended: one calm line, matching `multi-route-paste` house style. No haptic, no notification, no
  History label.

## 6.5 The clock does not bound the file, and that nearly lost the take

`record.startRecording()` runs before `startedAtMs` is taken, so the hardware is already buffering when
the clock starts. The elapsed check can therefore pass on its final pass with the file already at the
byte ceiling, and one more whole 8,192-byte block goes in. `AsrService` REFUSES an oversized file rather
than truncating it, so a few bytes over discards the entire ten-minute take this change exists to keep.

Fixed at the producer: the capture loop asks for no more than the ceiling has room for, and reaching the
ceiling ends the take the same way the clock does. Found by Codex review, 2026-09-05.

## 7. Async edges (code-design-rules RULE: async-edge-case-enumeration)
- Interrupted: user presses Stop inside the warning window. The warning stays on a recorder that is
  already going away; harmless, hide() clears it.
- Concurrent: warning latch is per take, reset beside `silenceNoticeShown` in `tryStartRecording`.
- Absent: not applicable any more. The plan reached for `getMaxDurationMs` over the binder for
  cross-process authority; review showed it bought none, because both processes compile the same
  constant, while adding a place the polling thread could hang before its loop ran once (#115). The
  warning moment now comes from `RecordingLimits.WARNING_AT_MS` and there is no call to fail.
- Stale: a warning from a previous take. Prevented by resetting the latch, same as `silenceNoticeShown`.
- Process death of `:audio`: the take ends through the existing terminal path; nothing new.
- Mutated: none, the cap is a compile-time constant.

## 8. Tests
- `RecordingLimitsTest` — the byte ceiling matches the duration; the copy names the same minute count
  as the constant; the warning lead is inside the cap.
- Wiring guard — the session reads the warning moment from the one owner and makes no cap binder call;
  the warning is published below the terminal check; the warning fires once per take; the ASR refusal
  message is generated, not hardcoded; the capture loop bounds the FILE and not only the clock.
- Hardware — a take driven past the cap on the phone, warning seen, ended line seen, words transcribed.
