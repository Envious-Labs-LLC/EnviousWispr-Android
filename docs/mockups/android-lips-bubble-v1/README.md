# Android lips bubble interactive mock

Design review only. No Android application code is changed. No microphone is used; the finished text is a
fixed sample sentence.

Open `index.html` in a browser, or serve this directory with `python3 -m http.server 8770 --bind 127.0.0.1`.

## What it shows

The floating EnviousWispr lips button that becomes the primary way to dictate on Android (founder
direction 2026-09-12, grounded in `docs/internal/wispr-flow-android-overlay-study.md`).

- **Idle bubble** over a Messages-style thread with the keyboard open. Default dock: right edge, above the
  keyboard, clear of the composer and send control.
- **Tap** starts a dictation. The bubble morphs into the approved recorder pill, growing inward from the
  docked edge: mark, elapsed time, rainbow level rail, LISTENING, cancel, accept. Same order and palette as
  `app/src/main/java/com/envi/wispr/paste/RecordingAccessibilityOverlay.kt`.
- **Hold** past 500 ms starts a dictation; release finishes it. Movement past the touch slop before the
  hold threshold becomes a drag instead, so a drag never records.
- **Drag** anywhere; the bubble snaps to the nearest edge and the side plus height are remembered in the
  browser. Dropping on the bottom "Drop to hide" target hides it until the next text box.
- **Processing** shows POLISHING with the controls disabled; a tap during processing is ignored.
- **Insert failed** keeps the words in a small card with Copy, Try again and Close.
- **Onboarding practice** shows the same bubble inside the practice step; one finished dictation enables
  Finish setup.
- Keyboard open/closed and text-box focused/unfocused controls show the bubble moving clear of the keyboard
  and hiding when no text box is active. Dark and light phone looks. Reduced motion respected.

## Decisions this mock encodes

- **No new permission.** The bubble lives in the accessibility window EnviousWispr already asks for to type
  into apps, so onboarding gains no "Appear on top" step. Codex adjudication 2026-09-12 (option 1 of
  three): no permission added, all gestures supported, above the keyboard in AOSP, existing focus events
  already tell the service when an editable field is active. Samsung's keyboard layering and the
  release-during-startup case are device probes, not settled.
- Tap-to-start and hold-to-talk share one button; no mode-selection screen.
- Position memory is per phone, side plus height; the keyboard temporarily pushes the bubble clear
  without overwriting the remembered spot.

## Not established here

Real gesture timing on the phone, Samsung keyboard z-order, position across rotation and app restarts, and
the exact hide/return behaviour. These need the device probes listed in the Codex adjudication before
implementation. The mock awaits founder approval before any Android code.
