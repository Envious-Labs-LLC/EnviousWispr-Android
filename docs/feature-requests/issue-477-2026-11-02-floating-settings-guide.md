# Issue #477 — Floating setup guide over Settings on Samsung, without hiding Allow — 2026-11-02

## Preface — Lane + Hardware UAT declaration

**Lane:** Code

**PAR rows closed:** none.

**Hardware UAT:** Y. On the founder's S26, a person taps Accessibility during setup, agrees, taps Open Settings,
and a small floating box keeps showing the current step over Samsung's Settings. On the Allow step the box says to
drag it up; after dragging, a human finger taps Allow, the box closes by itself and EnviousWispr shows auto-paste
Ready. On the emulator (not Samsung) the full-screen guide-first flow is unchanged.

## Preface — User Rubric

Persona: the first-time Android user from the brand guide who installs from Play and is not technical (here: the
founder's mother on a Samsung phone, 2026-11-02, who landed on the EnviousWispr Accessibility page with no guide
visible).

1. What does this persona notice? The steps stay on screen while they are in Settings, so they do not have to
   remember six steps from the screen before.
2. What could make it worse? The box covering Allow, or Allow refusing taps while the box floats. The box carries a
   permanent "drag this box up to see Allow" line, and it appears only on Samsung software where a human tap on Allow
   was measured to work with it floating.
3. Word of mouth: accessibility setup is the step where non-technical users give up; seeing the steps in place is
   what competitors (Wispr Flow) already do.

### Cross-persona check
Non-Samsung phones and unmeasured One UI versions keep today's guide-first screen, unchanged.

Consolidation: none. Restores one removed branch inside the existing owner (`AccessibilityGuideActivity`).

## 0. TL;DR
Restore the picture-in-picture guide removed in 8b67be1d (`git show bc799234`): Samsung + an exact measured raw One UI
value (`Build.VERSION.SEM_PLATFORM_INT` by reflection) + PiP support. Add a fixed "Drag this box up to see Allow" line
to the floating copy on every frame, and put the S26's measured value in the allowlist.

## 1. Problem
Since #417 the guide never floats over Settings; #476 shows it before Settings opens, which a non-technical user
forgets once in Settings (founder report 2026-11-02 with his mother's phone). #476 measured on the S26 (One UI 9,
`ro.build.version.oneui` 90000): the PiP guide shows over Samsung Settings, Android parks it bottom right fully over
Allow (`[560,1716,1035,2160]` vs Allow `[712,2026,984,2127]`), a tap on Allow with the box dragged over the dialog
body was accepted, and on grant the box finished and the app came forward. The raw `SEM_PLATFORM_INT` was not
recorded, so the allowlist shipped empty and the code was removed.

## 2. Goals & non-goals
### 2.1 Goals
- On measured Samsung software the guide floats over Settings, steps through, tells the user to drag it off Allow,
  and closes when auto-paste is live.
- Every other phone keeps the guide-first floor unchanged.
### 2.2 Non-goals
- Any non-Samsung floating guide (AOSP Allow refuses obscured touches).
- A system overlay window ("Appear on top" permission; ruled out in #476).
- Moving or resizing the PiP away from Allow (not an app capability).
- Widening eligibility to all Samsung phones (founder Gate 1, 2026-11-02: measured Samsung only).

## 2.5 Grounding brief
### 1. Producer → owner → consumer
`AccessibilityGuideActivity.openSettings` (owner) launches `Settings.ACTION_ACCESSIBILITY_SETTINGS`; completion is
`PasteAccessibilityService.isBound` + permission through `AutoPasteReadiness.evaluate`; `complete()` finishes the
guide. The PiP branch adds one producer (`enterPictureInPictureMode` before the Settings launch) and one consumer
(the compact renderer reading `isInPictureInPictureMode`).
### 2. Existing authority
Eligibility is a new file-private object restored from bc799234 (`FloatingGuideEligibility`), the only reader of
`SEM_PLATFORM_INT`.
### 3. Prior attempts
#416/#417 removed PiP (covered Allow). #476 restored it behind an empty allowlist, measured the S26, then removed
it (8b67be1d) pending this issue. Its §3 PiP branch, §2.5.4 boundaries, §2.5.5 premises P1 to P5 and the rejected
eligibility keys carry over unchanged and are not re-litigated here.
### 4. Boundaries
- The guide task and Settings are separate tasks; PiP keeps the guide visible as a pinned window.
- PiP content takes no input; the user drags or closes it with system controls.
- Recreation: `compact` is presentation only, re-derived from `isInPictureInPictureMode`.
- Process death in Settings loses PiP; the full-screen guide returns on recreation (accepted in #476).
### 5. High-risk premises
- P6 NOT VERIFIED: the S26's raw `SEM_PLATFORM_INT`. Chunk 0 reads it on the founder's S26 with the debug log line
  restored from bc799234 (needs the phone on wireless debugging), and repeats the human-touch Allow check on the
  current S26 software, since One UI may have moved since 2026-10-23.
- P7 NOT VERIFIED: that a two-line compact layout with the drag line stays legible at the PiP size Android picks on
  the S26 (`Rational(230, 215)` aspect). Chunk 0 screenshot.

## 3. Design
1. Restore bc799234's three pieces: `android:supportsPictureInPicture="true"` on the guide activity,
   `FloatingGuideEligibility` (Samsung + `FEATURE_PICTURE_IN_PICTURE` + raw value in `MEASURED_ONE_UI`), and the
   activity's PiP entry before the Settings launch, `onPictureInPictureModeChanged`, and the compact renderer.
2. Compact copy: the current step in large type ("Tap Allow" on the Allow step, "Tap to open the guide" after a
   failed hand-off), plus a fixed second line on every frame: "Drag this box up to see Allow." Not timer-dependent.
   Copy checked under `content-brand.md`.
3. `MEASURED_ONE_UI` gets the S26's raw value from Chunk 0, with model, fingerprint, `ro.build.version.oneui` and
   date recorded in a code comment and in this plan.
4. Reduced motion: static frames in PiP (pre-#417 behaviour).
5. If Chunk 0's Allow check fails on current S26 software, the allowlist stays empty and the change stops.

## 4. Contract deltas
Manifest: one attribute on the guide activity. No AIDL, schema or preference change.

## 5. State and lifecycle audit
`GuidePhase` unchanged. `compact` is derived. Completion path unchanged (`complete()` finishes the activity, which
removes the PiP).

## 6. Consumer matrix
| Consumer | Change |
|---|---|
| Setup Permissions step | opens the same guide; floats on eligible phones |
| Settings page auto-paste row | same guide; same behaviour |
| Non-Samsung / unmeasured | none |

## 7. Failure modes
| Failure | Result |
|---|---|
| PiP entry refused | full-screen guide behind Settings, as today |
| Allow refuses obscured touch (unmeasured software) | not offered there by construction |
| User never drags | box says to drag on every frame; Allow stays reachable by drag or the PiP close control |
| Settings launch fails after PiP entry | compact says "Tap to open the guide" |

## 8. Signals
Restored content-free debug line: raw One UI value and eligibility.

## 9. Fallback source of truth
Guide-first floor stays the default on every phone.

## 10. File-by-file changes
- `app/src/main/AndroidManifest.xml`: `supportsPictureInPicture` on `AccessibilityGuideActivity`.
- `app/src/main/java/com/envi/wispr/ui/FloatingGuideEligibility.kt`: restored, with the measured value.
- `app/src/main/java/com/envi/wispr/ui/AccessibilityGuideActivity.kt`: PiP entry, mode callback, compact renderer
  with the drag line.
- Unit test: eligibility refuses non-Samsung, an unreadable value and an unmeasured value (Drift Guard).

## 11. Testing
Unit gate with count. Emulator: guide-first unchanged (not Samsung). Hardware: Chunk 0 measurement and the UAT in the
preface on the S26 with a human finger; screenshot of the box and of Allow after the drag.

## 12. Blast radius & rollback
Accessibility setup on measured Samsung phones only. Rollback: empty the allowlist or revert.

## 13. Ship criteria
Chunk 0 PASS on current S26 software; unit gate green; Codex all-clear with confirming rerun; founder's phone pass on
a Play build.

## 14. Open questions
- Founder: Chunk 0 needs his S26 on wireless debugging for a few minutes, and a human finger on Allow.
- Founder, optional: measuring his mother's Samsung would add her One UI value; unmeasured phones keep the floor.

## 15. Related
#416, #417, #476, #475, bc799234, 8b67be1d.
