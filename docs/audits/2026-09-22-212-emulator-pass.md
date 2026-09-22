# #212 emulator pass — 2026-09-22

Device: `emulator-5554` (AVD `EnviousWispr_Android_16_Play`), debug build from commit `4e183fd`
(installed 16:44 local; later commits change tests and docs only). Driven through `scripts/uat/wispr_eyes.py`.
Target editor: Gmail compose body.

## (a) Cancel while a long take is processing, then an immediate take

- A 58.7 s rendered sentence was injected (196 packets); the take was stopped, then cancelled.
- `Transcribing 846848 bytes (26.5s)` at 16:44:34.489; `AsrService destroyed; recognizer release queued`
  at 16:44:35.540; `Recognizer released` at 16:44:35.610, on the same pid 1800.
- `ASR answer discarded` did not appear: the emulator decoded the 26.5 s take in about one second, so the
  decode finished before the cancel's unbind closed the service. The overlap was not staged on the
  emulator; `RecognizerOwnerTest` rows 1 and 2 stage it deterministically.
- `Fatal signal`: 0 lines. `logcat -b crash` lines naming `com.envi.wispr`: 0.
- Cache after (a): no `recording-take-*.pcm` and no `recording.pcm`.
- The immediate take: `VERIFIED` ended by a stop request, 45 characters transcribed,
  `api=36 route=COMMIT written=true evidence=SURROUNDING outcome=VERIFIED` into `com.google.android.gm`.
  The editor holds `The quarterly marker sentence lands tomorrow.` followed by U+00A0: the harness
  expectation carried an ASCII space, and Gmail stores the inserted trailing space as a no-break space
  (recorded in `session-log.md` for #161). The words are exact.

## (b) An ordinary take

- `VERIFIED` ended by a stop request, 41 characters, `route=COMMIT written=true outcome=VERIFIED` into Gmail.
- The editor holds `Quarterly marker sentence lands tomorrow.` + U+00A0: the speech engine did not
  hear the leading "the" in this rendering; insertion delivered exactly what it transcribed.
- Cache after (b): no capture file left (the owner deleted its own take's file).

## Not run

- The founder's phone: queued with #115 and #161 (his instruction 2026-09-21 morning).
- The device-test classes changed by this PR (`@After` cleanup) were compiled, not executed.
