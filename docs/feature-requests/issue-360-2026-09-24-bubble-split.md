# Issue #360: the floating recorder's gesture rules and views leave the window owner (REF-05, regrade 8) (2026-09-24)

GitHub issue: `#360`. Tier: MEDIUM (a move on the recorder, no behaviour change). Status: built, before the combined Codex coverage and review round.

## Preface: Lane + Hardware UAT declaration

**Lane:** Code

**PAR rows closed:** none.

**Hardware UAT:** light. An emulator take started from the app still lands its words and draws the pill. The JVM rows exercise the controller's decisions; the overlay's touch dispatch and visual response remain unverified, since the harness cannot drive the accessibility overlay's gestures.

## Preface: User Rubric

No user-visible change. `paste/RecordingAccessibilityOverlay` (817 lines) placed its windows, built and painted every view, and turned each classified gesture into its effects (which request a tap or hold starts, which stop goes with which hold, where a drag puts the bubble, when a drop hides it). `BubbleGestureClassifier` already decided what a touch MEANT; what it DID was untested, because it lived beside `WindowManager` and `MotionEvent`.

## 0. TL;DR

- New `paste/BubbleGestureController`: pure Kotlin over a small `Geometry` interface (the last bounds, the resting box, the hide target, whether the owner is IDLE) and a `mint` function. It holds the hold request, the last held take, the drag origin and box, and turns each touch into an ordered list of `BubbleCommand`s. The rules are the old onGesture and onTouch bodies, moved unchanged.
- New `paste/BubbleViews`: the views, built and painted (`buildBubble`, `buildPillColumn`, `buildPill`, `buildHideTarget`, `actionButton`, `applyLook`, `layOutPill`, `setLook`, `setEarbuds`), moved byte for byte except that the context is named `context` and the touch, click, cancel, accept and configuration hooks come in as callbacks.
- `RecordingAccessibilityOverlay` (523 lines) keeps the windows, the placement (`render`, `showShape`, `readBounds`), the field and keyboard state, the hide target's window, and `perform`, the one exhaustive `when` that applies each command to Android.

## 1. Tests

- `BubbleGestureControllerTest` (8 rows): a tap at IDLE starts one take (m1); a busy tap starts nothing (m2); a hold starts a held take and its release stops that take (m3); a hold during another take cannot stop it (m4); a cancelled hold cancels its take; a drag follows the finger, clamped to the screen, and snaps on drop (m5); a drop on the hide target hides for the field (m6); the accessibility tap follows the same IDLE rule.
- `LipsBubbleWiringTest`, `RecorderBrandTest` and `LiveAudioMeterWiringTest` read the three files as one text; the checks that named moved code now name the new wiring.

## 2. Combined coverage and review round (Codex)

- Adopted: on the first drag move the controller judged the hide target's emphasis before `ShowHideTarget` had placed it, so a move straight onto the target left it dim; the old code showed the target first. `HideTargetEmphasis` now carries the bubble's position and `perform` judges it when applied, after the target is shown. Row 6 pins the order, row 7 the position.
- Adopted: the hardware line above overstated what the JVM rows cover.
- Rejected: minting the request before the haptic (a hold) or before the timer removal (a tap). The token is a counter with no visible effect, every step runs on the main thread, and the visible order (haptic, launch; timer removal, launch) and the IDLE check's moment are unchanged.
