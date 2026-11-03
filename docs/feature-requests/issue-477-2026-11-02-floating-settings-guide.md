# Issue #477 — Floating setup guide over Settings on every Android phone — 2026-11-02

## Preface — Lane + Hardware UAT declaration

**Lane:** Code

**PAR rows closed:** none.

**Hardware UAT:** Y. On the founder's S26, a person taps Accessibility during setup, agrees, taps Open Settings, and a
small floating box lists every step over Samsung's Settings. On the Allow step the person drags the box away, a human
finger taps Allow, the box closes by itself and EnviousWispr shows auto-paste Ready.

## Preface — User Rubric

Persona: the first-time Android user from the brand guide who installs from Play and is not technical (here: the
founder's mother on a Samsung phone, 2026-11-02, who landed on the EnviousWispr Accessibility page with no guide
visible).

1. What does this persona notice? The steps stay on screen while they are in Settings, so they do not have to
   remember six steps from the screen before.
2. What could make it worse? The box covering Allow. Every frame says to drag the box away, and how to get past a
   phone that refuses the tap (close the box, then tap Allow).
3. Word of mouth: accessibility setup is the step where non-technical users give up; seeing the steps in place is
   what competitors (Wispr Flow) already do.

### Cross-persona check
Phones without picture-in-picture (Android Go) and users who switched picture-in-picture off for the app keep today's
guide-first screen, unchanged.

Consolidation: none. Restores one removed branch inside the existing owner (`AccessibilityGuideActivity`).

## 0. TL;DR
Restore the picture-in-picture guide removed in 8b67be1d (`git show bc799234`) on every phone that supports
picture-in-picture and where the user has not switched it off for the app. The box lists every step with the looping
demo's current step highlighted, and starts with the drag-away and recovery instructions.

## 1. Problem
Since #417 the guide never floats over Settings; #476 shows it before Settings opens, which a non-technical user
forgets once in Settings (founder report 2026-11-02 with his mother's phone).

## 2. Goals & non-goals
### 2.1 Goals
- On every phone with picture-in-picture the guide floats over Settings, lists the steps, tells the user to drag it
  off Allow, and closes when auto-paste is live.
- Phones without it keep the guide-first floor unchanged.
### 2.2 Non-goals
- A system overlay window ("Appear on top" permission; ruled out in #476).
- Moving or resizing the PiP away from Allow (not an app capability).
- A device allowlist. Founder decision 2026-11-02 after the Test Lab evidence below: all Android phones (superseding
  the Gate 1 "measured Samsung only" choice of the same day).

## 2.5 Grounding brief
### 1. Producer → owner → consumer
`AccessibilityGuideActivity.openSettings` (owner) launches `Settings.ACTION_ACCESSIBILITY_SETTINGS`; completion is
`PasteAccessibilityService.isBound` + permission through `AutoPasteReadiness.evaluate`; `complete()` finishes the
guide. The PiP branch adds one producer (`enterPictureInPictureMode` before the Settings launch) and one consumer
(the compact renderer reading `isInPictureInPictureMode`).
### 2. Existing authority
`FloatingGuideEligibility` is the only reader of the picture-in-picture feature and the app's picture-in-picture
permission (`AppOpsManager.OPSTR_PICTURE_IN_PICTURE`); `decide` is the pure rule and is unit tested.
### 3. Prior attempts
#416/#417 removed PiP (covered Allow). #476 restored it behind an empty Samsung allowlist, measured the S26, then
removed it (8b67be1d) pending this issue.
### 4. Boundaries
- The guide task and Settings are separate tasks; PiP keeps the guide visible as a pinned window.
- PiP content takes no input; the user drags or closes it with system controls.
- Recreation: `compact` is presentation only, re-derived from `isInPictureInPictureMode`.
- Process death in Settings loses PiP; the full-screen guide returns on recreation (accepted in #476).
### 5. Evidence (measured, not assumed)
- **The filter exists; the predicted refusal was not observed.** AOSP Settings' AccessibilityServiceWarning filters obscured
  touches, but no phone in these injected-tap runs refused the tap with the box on screen. Human-touch behaviour
  remains NOT VERIFIED until the S26 finger check.
- **Emulator** (Android 16, AOSP Settings, 2026-11-02): box over Settings, Allow partly covered, tap accepted, service
  bound, box closed itself.
- **Firebase Test Lab** (personal project `titanium-vigil-488402-s1`, 2026-11-02, matrices
  `matrix-365fozeso4feh` (harness defects only), run 2 `9104876244025811510`, run 3 (results folder 2026-11-02_21:58:26.955894_XVWN), run 4
  `matrix-311gptl8gf02u` and `matrix-2ges35ifdld5q`). Probe test, logs and IDs:
  `~/Developer/AgentArtifacts/enviouswispr-android/runs/2026/11/testlab-477-probe/`. Each phone: guide → Agree → Open
  Settings → PiP present → EnviousWispr page → switch → Allow → auto-paste enabled AND service bound → box finished.

| Phone (API) | Box vs Allow | Allow accepted |
|---|---|---|
| Pixel 10 (36) | partly covered | yes, tap on uncovered part |
| Pixel 8a (35) | partly covered | yes, tap on uncovered part |
| Motorola edge 50 fusion (35) | dialog drawn above the box | yes |
| Galaxy A16, One UI 7 (35) | dialog drawn above the box | yes |
| Galaxy S24, One UI 8 (36) | fully covered | yes, after dragging the box away |
| Galaxy A35, One UI 6.1 (34) | fully covered | yes, after dragging the box away |
| OnePlus Nord CE 3 Lite (34) | covered except an 8 px sliver | yes, after dragging the box away |
| OPPO A79 (34) | covered except an 8 px sliver | yes, after dragging the box away |

- **Limit:** every tap was injected by UiAutomation, not a finger. The founder's S26 finger check (Chunk 0 below)
  remains the human confirmation. The S26 itself was measured on 2026-10-23 (#476): box fully over Allow, injected tap
  accepted after dragging.
- **Product consequence:** on Samsung, OnePlus and OPPO the drag step is required, so the drag line stays on every
  frame.

## 3. Design
1. Restore bc799234's pieces: `android:supportsPictureInPicture="true"` on the guide activity, the PiP entry before the
   Settings launch, `onPictureInPictureModeChanged`, and the compact renderer.
2. `FloatingGuideEligibility.allows`: `FEATURE_PICTURE_IN_PICTURE` AND the app's picture-in-picture permission reads
   exactly `MODE_ALLOWED` (an unreadable or default mode does not float).
3. Compact box: every step listed by a short label (`GuideFrame.short`: "Turn on", "Allow"); while the demo plays,
   the current row is highlighted; paused or reduced motion highlights nothing. After a failed launch: "Tap this box
   and open it full screen to see the guide." Every frame STARTS with "Drag this box off Allow. Allow not working?
   Close this box, then tap Allow." The box follows the user's text size; because Android fixes the box size, the
   recovery line comes first so large text clips the list first (Codex round 1 rejected a fixed
   text size). Names no OEM-specific control ("X", "expand"). Fit at default text measured on the emulator at a
   Galaxy S24-sized box (480 x 449 px at 450 dpi, the size Test Lab's S24 reported) with Samsung's six rows forced
   for the measurement. A near-square 230:215 aspect is kept: a 3:4 portrait box came out narrower (Android keeps
   the area) and wrapped more.
4. Full guide before the hand-off, only where `allows` is true: "Your phone may keep these steps in a small box over
   Settings. If the box covers Allow, drag it away. If Allow still doesn't respond, close the box, then tap Allow."
   ("may", because PiP entry can still be refused at runtime.)

## 4. Contract deltas
Manifest: one attribute on the guide activity. No AIDL, schema or preference change.

## 5. State and lifecycle audit
`GuidePhase` unchanged. `compact` is derived. Completion path unchanged (`complete()` finishes the activity, which
removes the PiP). A user who closes the box destroys the guide task; returning to the app shows the Permissions
row as before.

## 6. Consumer matrix
| Consumer | Change |
|---|---|
| Setup Permissions step | opens the same guide; floats where eligible |
| Settings page auto-paste row | same guide; same behaviour |
| No picture-in-picture / switched off | none |

## 7. Failure modes
| Failure | Result |
|---|---|
| PiP entry refused | full-screen guide behind Settings, as today |
| A phone refuses the Allow tap with the box on screen (none measured) | box says to close it, then tap Allow |
| User never drags on a phone where the box covers Allow | every frame says to drag it away |
| Settings launch fails after PiP entry | box says "Tap this box and open it full screen to see the guide." |
| Very large text | At the measured 130% text size, recovery remains visible and later steps clip. Larger text and smaller boxes are NOT VERIFIED. |

## 8. Signals
Content-free debug line: `floating=<bool>`.

## 9. Fallback source of truth
Guide-first floor remains whenever the box cannot float.

## 10. File-by-file changes
- `app/src/main/AndroidManifest.xml`: `supportsPictureInPicture` on `AccessibilityGuideActivity`.
- `app/src/main/java/com/envi/wispr/ui/FloatingGuideEligibility.kt`: feature plus permission rule.
- `app/src/main/java/com/envi/wispr/ui/AccessibilityGuideActivity.kt`: PiP entry, mode callback, compact renderer.
- `app/src/test/java/com/envi/wispr/ui/FloatingGuideEligibilityTest.kt`: the decision table over feature and
  app-operation modes (Drift Guard; does not verify permission wiring or PiP behaviour).

## 11. Testing
Unit gate with count. Emulator: box layout over Settings (default and large font). Test Lab evidence above.
Hardware: Chunk 0 on the S26 with a human finger after the Play build.

## 12. Blast radius & rollback
Accessibility setup on phones with picture-in-picture. Rollback: make `allows` return false, or revert.

## 13. Ship criteria
Unit gate green; Codex all-clear with confirming rerun; founder's S26 finger check on a Play build.

## 14. Open questions
None for the founder beyond the S26 finger check.

## 15. Related
#416, #417, #476, #475, bc799234, 8b67be1d.
