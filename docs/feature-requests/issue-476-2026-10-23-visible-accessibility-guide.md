# Issue #476 — Visible accessibility setup guide — 2026-10-23

GitHub issue: `#476`. Tier: MEDIUM (permissions surface, one activity, manifest). Status: DRAFT.

## Preface — Lane + Hardware UAT declaration

**Lane:** Code

**PAR rows closed:** none. This is Android setup guidance; the catalog has no macOS floating-guide behavior
(query in §2.5.3).

**Hardware UAT:** Y. On the S26 (Play build) a fresh-state user taps Accessibility in setup, reads the guide,
taps Open Settings, and while in Android Settings still sees the steps (Samsung branch only, see §3). They
turn EnviousWispr on, tap Allow on the first try with no "app is obscuring" message, and land back in setup
showing Accessibility granted with the guide gone. Repeated on a Galaxy S22 if one is reachable.

## Preface — User Rubric

1. **Who.** Frank Chen, 72, retired teacher, arthritis, installing EnviousWispr on his Samsung because his
   grandson said it beats the keyboard. Thirty seconds ago he granted the microphone; thirty seconds from
   now he wants to try his first dictation in Messages.
2. **Why.** "Just tell me what to press. I don't want to get lost in the phone's settings."
3. **Invoke.** Reactive and once: the setup Permissions step's Accessibility row, or later the Permissions
   page / home readiness card when auto-paste is off. Same single route (`AccessibilityGuideActivity`).
4. **App.** Our setup screens, then Samsung (or other maker's) Settings > Accessibility > Installed apps >
   EnviousWispr, then Android's Allow dialog.
5. **Natural input.** Taps, not words: "Agree", scroll, "Installed apps", "EnviousWispr", the switch,
   "Allow". What he says out loud: "Where's EnviousWispr?" "Which switch?" "It turned off again."
   "What's a shortcut?" "Did it work?"
6. **Success.** He never wonders what to tap next, Allow works the first time, and he is back in setup with
   a green check without pressing Back hunting for the app.
7. **Wrong-not-broken.** He turns on the "EnviousWispr shortcut" switch instead of (or as well as) the
   service, a floating accessibility button appears, and every tap of it switches the service off (the
   customer S22, #475). Or the guide covers Allow and his tap is silently refused.
8. **Power-user hack.** Skip the guide and go straight to Settings: keep "Open Settings" one tap away, and
   Back from the guide closes it.
9. **Control ladder.** Read the guide first (default), skip straight to Settings, pause/step through the
   frames, reduced motion shows static frames. No setting is added.

### Cross-persona check
Frank and Meera Patel want the guide; Priya Ramachandran and Aaron Wu want to skip it in one tap (kept);
Diana Foster wants the shortest path (no extra screen beyond one); Dr. Elena Vasquez reads the disclosure
(unchanged, still before anything else); Marcus Weber is neutral. Tension: an extra full-screen guide step
versus zero friction. Resolved in §3: the guide is the same screen as today's post-consent screen, now shown
BEFORE Settings instead of behind it, with Open Settings as its primary button, so skippers pay one tap.

---

## 0. TL;DR

The accessibility setup guide never appears: after "Agree and open Settings" the app swaps to the guide and
opens Android Settings on top of it in the same instant. Fix: show the guide first with an "Open Settings"
button, and (Samsung only, if a Chunk 0 measurement on the S26 passes) keep a native picture-in-picture
copy of the guide over Settings, like Wispr Flow, which closes itself the moment access turns on. The
shortcut trap (#475) becomes an explicit step in the guide. Evidence: S26 hardware pass, plus a measured
answer to "does Samsung's Allow accept a tap while our floating window overlaps its dialog".

## 1. Problem

- Customer Galaxy S22+ and the founder (2026-10-23): disclosure skipped, landed in Settings with no
  instructions, did not know the path; the customer also enabled the shortcut (logcat:
  `enableShortcutForTargets: enable true, shortcutType: 1 ... PasteAccessibilityService`) and toggled the
  service off ~20 times with the floating button.
- Code: `AccessibilityGuideActivity.openSettings` (`app/src/main/java/com/envi/wispr/ui/AccessibilityGuideActivity.kt:57-66`)
  sets `agreed = true` and, on the next pre-draw, starts `Settings.ACTION_ACCESSIBILITY_SETTINGS` with
  `FLAG_ACTIVITY_NEW_TASK`. The guide composable renders for one frame before Settings covers it.
- History: until #417 (`bb7fdf35` (external)) the guide entered native PiP over Settings; on the S26 its window
  `[555,1711,1035,2160]` covered Android's Allow button `[712,2026,984,2127]` (harness geometry, #416), so
  #417 removed PiP and the guide was left behind Settings.

## 2. Goals & non-goals

### 2.1 Goals
- G1: After Agree, the user sees the step guide in our app before Android Settings opens.
- G2: The guide names the exact path for the phone's maker and says, as its own step, to leave the
  "EnviousWispr shortcut" switch off.
- G3: Android's Allow is tappable on the first try on every supported phone (no obscured-touch refusal).
- G4: On Samsung, if and only if Chunk 0 proves G3 holds with it present, the guide also floats over
  Settings (native PiP) and disappears by itself once access is granted and the service is bound.
- G5: Granted+bound still finishes the guide and returns to the caller (unchanged contract).

### 2.2 Non-goals
- Detecting or highlighting Samsung's own rows; opening our service's detail page directly (the action is
  not in the public SDK, §2.5.5).
- Changing the disclosure text or consent semantics.
- Detecting a shortcut already enabled (#475 part 2 stays open).
- A recorded video asset; the guide stays our animated Compose frames.

## 2.5 Grounding brief

### 1. Producer → owner → consumer
Command: `grep -rn "AccessibilityGuideActivity\|onOpenAccessibility" app/src`.
- Producers of the route (all reach one launcher):
  `ui/OnboardingScreen.kt:158` (setup Permissions row), `ui/SettingsPages.kt:274` (setup continue
  `SetupContinue.AUTO_PASTE`), `ui/SettingsPages.kt:311` (`AutoPasteNoticeCard`), `ui/SettingsPages.kt:365`,
  `ui/SettingsPages.kt:469` → `AppActions.kt:51` `onOpenAccessibility` → `ui/SettingsActivity.kt:117-118`
  starts `AccessibilityGuideActivity` with `FLAG_ACTIVITY_NEW_TASK`.
- Owner: `AccessibilityGuideActivity` (manifest `app/src/main/AndroidManifest.xml:37-44`: own
  `taskAffinity`, `singleTask`, `excludeFromRecents`, `resizeableActivity`, no `supportsPictureInPicture` (proposed)).
  State: `agreed` (saved in `onSaveInstanceState`), `launchFailed`.
- Completion: `PasteAccessibilityService.isBound` collected while STARTED plus `onResume` check
  (`AccessibilityGuideActivity.kt:42-48,68-71`) call `finish()` when agreed, bound and
  `AccessibilityPermission.isGranted`.
- Consumers of the outcome: the Permissions/setup rows read `AutoPasteAvailability` from
  `paste/AutoPasteReadiness`; the guide itself has no other consumer.

### 2. Existing authority
The guide activity is the single owner of disclosure, handoff and return (decided in #416 plan §3b). The
PiP mechanism to restore is the pre-#417 code (`git show bb7fdf35^:app/src/main/java/com/envi/wispr/ui/AccessibilityGuideActivity.kt`):
`enterPictureInPictureMode(PictureInPictureParams.Builder().setAspectRatio(Rational(230, 215)).build())`
guarded by `PackageManager.FEATURE_PICTURE_IN_PICTURE`, `onPictureInPictureModeChanged` (external) → `compact`, and a
compact renderer. No other PiP code exists: `grep -rn "PictureInPicture" app/src/main` → none.

### 3. Prior attempts and live direction
- #416/#417: PiP removed for covering Allow; plan rejected repositioning ("position varies by OEM ... no
  public stable placement contract"). Still binding: we cannot place PiP; this plan does not try to.
- Onboarding tracker ONB-17 (`docs/internal/android-onboarding-tracker.md:103,113,136,138`): founder wants
  the next step visible during the Settings handoff, citing Wispr Flow; Flow's mechanism is native PiP
  (`Visperflow Onboarding Screenshots/settings-handoff-mechanism.md:36-40`); founder observed Flow's guide
  disappear after granting.
- Catalog: `SELECT ... FROM user_copy WHERE exact_text LIKE '%accessibility%' AND (... guide ... settings)`
  returns only Android readiness lines and macOS Quick Add lines; no macOS setup-guide behavior to match.

### 4. Boundaries
- Two tasks: the guide task (own affinity) and Settings (new task). PiP keeps the guide task visible as a
  pinned window; without PiP Settings fully covers it.
- Service liveness: grant writes the setting first, then the service binds; completion waits for both
  (unchanged).
- Recreation: `agreed` survives; a new `settingsOpened` (proposed)/`compact` state must survive or be derived
  (`isInPictureInPictureMode` (external)).
- Process death of the default process while in Settings: the guide task is recreated from saved state on
  return; PiP is lost (acceptable, the full-screen guide remains).

### 5. High-risk premises (evidence)
- P1 PiP position is the system's. AOSP `PipBoundsAlgorithm.getDefaultBounds` (external) uses
  `config_defaultPictureInPictureGravity` (external) or a saved snap fraction; an app only influences minimum size via
  `ActivityInfo.windowLayout` (external) (`getMinimalSize` (external)). Source fetched 2026-10-23 from
  `android.googlesource.com/.../wm/shell/common/pip/PipBoundsAlgorithm.java` lines 158-170, 299-329.
- P2 AOSP's Allow dialog refuses obscured touches: `AccessibilityServiceWarning.getTouchConsumingListener` (external)
  consumes events with `FLAG_WINDOW_IS_OBSCURED` (external) or `FLAG_WINDOW_IS_PARTIALLY_OBSCURED` (external) and toasts
  (`core/java/com/android/internal/accessibility/dialog/AccessibilityServiceWarning.java:115-129`, main).
  So on AOSP-based Settings any PiP overlapping the dialog window blocks Allow.
- P3 NOT VERIFIED: whether Samsung One UI Settings applies P2. Suggestive only: the founder granted Wispr
  Flow with its PiP showing. Chunk 0 measures it.
- P4 `ACTION_ACCESSIBILITY_DETAILS_SETTINGS` (external) is absent from the public API:
  `javap -constants -public android/provider/Settings.class` (android-36 `android.jar`) lists only
  `ACTION_ACCESSIBILITY_SETTINGS`.
- P5 S26 geometry from #416: dialog window `[0,1111,1080,2205]`, Allow `[712,2026,984,2127]`, old PiP
  `[555,1711,1035,2160]` (archived JSON in `.claude/_rescued-worktrees/codex-android-consumer-readiness-*`).

## 3. Design

**Floor, every phone: guide first.** `openSettings` no longer runs on Agree. Agree sets `agreed = true` and
shows `AccessibilityGuide`, now with a primary "Open Settings" button that performs today's Settings launch.
The guide's steps (Samsung: Installed apps → EnviousWispr → turn it on → Allow → come back; others:
find EnviousWispr → turn it on → Allow → come back) gain a dedicated frame "Leave 'EnviousWispr shortcut'
off" with a crossed-out shortcut row, instead of a 10sp footnote. "Return to EnviousWispr" and Back still
close. After Settings, returning without a grant shows the same guide with "Open Settings again".
The disclosure button becomes "Agree" (was "Agree and open Settings"; coverage gap 7): consent text is
unchanged, the label no longer promises an immediate handoff. The frames are illustrations on a timer, not
detected Settings progress; detecting Settings screens is excluded.

**Entry states (coverage gap 5, grounded finding 5).** On entry the guide reads readiness
(`AutoPasteAvailability`): `LIVE` closes immediately (no disclosure, no steps); `NOT_PERMITTED` takes the
normal path above; `SWITCHED_OFF_UNEXPECTEDLY` shows the same steps headed "Turn EnviousWispr back on";
`PERMITTED_NOT_RUNNING` keeps the off/on recovery steps and the exit controls while it waits for binding,
because a stuck connection is fixed by switching the service off and on. Exact copy is drafted in the build
chunk under `content-brand.md`.

**Return after grant (coverage gap 4, grounded findings 1-2).** Today `finish()` closes the guide task but
leaves the user in Settings. PiP visibility alone does not authorize activity starts: AOSP excludes pinned
windows from the visible-window background-start exemption (2026-06 security bulletin). A bound
accessibility service may carry its own background-start allowance (AOSP `AccessibilityServiceConnection` (external)),
NOT VERIFIED for our case. So: on completion the guide attempts to start the app shell
(`SettingsActivity`, `FLAG_ACTIVITY_NEW_TASK` | `FLAG_ACTIVITY_SINGLE_TOP` (external)) and then finishes;
if Android blocks the start, nothing breaks and the user returns by hand. Because the guide may already be
gone by then, the guide says before handoff (round 2 finding 3): "After you allow access, if EnviousWispr
does not reopen, open it from Recents or its app icon." No fallback text is assumed to stay visible after
completion. Automatic return is a UAT observation, not a promise. No
`ActivityManager.moveTaskToFront` (it needs `REORDER_TASKS` (external)); if Settings fails to launch after PiP entry,
the compact frame says "Tap to open the guide" and the user expands it to reach the error and retry controls.

**PiP branch, conditional on Chunk 0 = PASS (grounded finding 3).** Open Settings first enters native PiP
(restored pre-#417 mechanism, compact renderer showing the current step in large type), then starts Settings.
The PiP closes with the activity when granted+bound (G4). On the Allow step the compact frame reads "Tap
Allow". Eligibility is a measured allowlist, not a maker name (round 2 finding 1): Samsung AND the One UI
version we measured (Samsung's `Build.VERSION.SEM_PLATFORM_INT` (external), read by reflection; absent or
unreadable means not eligible; readable on Samsung builds is NOT VERIFIED until Chunk 0 reads it on the S26)
AND `FEATURE_PICTURE_IN_PICTURE` (external). The key is the One UI version, not the Android SDK (One UI 6.0,
6.1 and 6.1.1 all ran Android 14) and not `Build.FINGERPRINT` (external), which changes with every monthly
security patch and would switch the guide off on the founder's own phone each month. Each measurement records
model, fingerprint, SDK and One UI version so a later regression can be traced. Every other phone, including
unmeasured One UI versions, gets the full-screen guide only. A new One UI version joins the list only after
its own human-touch measurement. The raw `SEM_PLATFORM_INT` value is compared exactly (public decoders keep only
major and minor, so whether point releases differ in the raw value is NOT VERIFIED); Chunk 0 logs the raw
value and the `ro.build.version.oneui` (external) property beside it. Residual risk, accepted: a monthly
patch or point release inside a measured raw value changes the dialog; the user then sees Samsung's
"obscuring" toast and can close the floating guide.

Eligibility keys enumerated and rejected (independent refutation, `.codex/issue-476-eligibility-refutation.md`):
maker only; Android SDK or the Android 16 minor SDK (vendor-blind); model; `Build.FINGERPRINT` and security
patch level (monthly churn); Settings app version (unknown churn); a runtime probe (no public signal before
the tap); keep-clear areas (best effort, declared by the app under the PiP); an overlay window instead of
PiP (the dialog hides non-system overlays and it needs a fourth permission); PiP stashing (user-triggered).
If Chunk 0 = FAIL, the list stays
empty and every phone gets the floor. Reduced motion keeps PiP with static frames (the pre-#417 compact
numbered list).

**Prior decision (grounded finding 6).** This supersedes #416's blanket PiP removal only for measured
configurations. Unobstructed consent, single ownership in this activity and no additional user-facing
permission remain binding.

Rejected:
- PiP everywhere (Wispr Flow parity): blocks Allow on AOSP Settings by P2.
- Repositioning or resizing PiP away from Allow: not an app capability (P1); even a non-covering PiP that
  overlaps the dialog window is refused by P2.
- Closing PiP on a timer before Allow: guesses user speed; a slow user is refused, a fast one loses the guide.
- Opening our service's detail page directly: not public (P4), behavior varies by maker.
- A system overlay window: needs "Appear on top", a fourth permission the product ruled out (tracker:32).

## 3b. Ownership
Stays in `AccessibilityGuideActivity` because it already owns disclosure, handoff, recreation and completion
for every route; the alternative, a second PiP activity like Flow's `AccessibilitySettingsPipActivity` (external),
would split completion and recreation across two tasks for no user benefit.

## 4. Contract deltas
- `GuidePhase` (proposed) (sealed, saved in instance state; grounded finding 4) replaces the `agreed` and
  `launchFailed` flags: `Disclosure` (proposed); `Ready(history)`; `Launching(history)`; `AwaitingSettings` (proposed);
  `LaunchFailed(history)`; `Complete`. `history` is `NeverOpened` (proposed) or `Opened` and selects "Open Settings"
  vs "Open Settings again". Every `when` over it is exhaustive with no `else`.
- Presentation (`compact`) is derived from `isInPictureInPictureMode` and
  `onPictureInPictureModeChanged`, never stored, so "disclosure while compact" cannot be represented:
  PiP is entered only from `Launching`.
- Permission (`AccessibilityPermission.isGranted`) and binding (`PasteAccessibilityService.isBound`) stay
  separate observations, not phase fields; granted-without-bound and bound-without-granted remain
  representable as `AutoPasteAvailability` already models them.
- Manifest: `supportsPictureInPicture="true"` returns on the guide activity; harmless where never entered.

## 5. State and lifecycle audit
| Population | Enumerated |
|---|---|
| Entry routes | 5 call sites in §2.5.1, all one launcher; all get the new order. |
| Exits | Decline; Back/Return (finish); granted+bound (finish, closes PiP); Settings launch failure (`launchFailed` text); process death while in Settings (recreated full screen, no PiP). |
| Recreation | `GuidePhase` saved; `compact` re-derived; rotation handled by existing `configChanges`. |
| PiP lifecycle | Entered only from Open Settings on Samsung; user may close it via PiP X (activity stops; task remains; next open restarts guide full screen); expanding PiP returns to full-screen guide. |
| Stale completion | `LIVE` on entry or on any resume completes immediately. Granted but unbound keeps the recovery controls until bound. No transition reads the removed `agreed` or `launchFailed` flags. |
| Recreation of transient phases (round 2 finding 2) | `Launching(history)` restores as `Ready(history)` without relaunching Settings; `AwaitingSettings` on return without `LIVE` becomes `Ready(Opened)`; `Complete` finishes immediately. |
| Re-entry (coverage gap 3) | `singleTask` reuses the live guide: reopening from any caller while it exists keeps the current `GuidePhase` and does not relaunch Settings by itself; repeated Open Settings taps start Settings again (idempotent, same task); Home, lock/unlock and process death return to the guide full screen in its saved phase. Each is a UAT step. |

## 6. Consumer matrix
| Contract delta | Consumer | Current | Required | Code change? | Verified by |
|---|---|---|---|---|---|
| Agree no longer opens Settings | All 5 routes | Settings opens instantly | Guide shows; Open Settings opens it | Activity only | Emulator UAT, S26 |
| PiP restored (Samsung) | Android Settings Allow dialog | n/a | Allow accepts first tap | No | Chunk 0, S26 pass |
| Shortcut step | User | 10sp footnote | Own frame | Composable | Screenshot review |

## 7. Failure modes
| Failure | Origin | Caller | User sees | Persisted | Retry |
|---|---|---|---|---|---|
| Settings intent fails | `startActivity` | Open Settings | "Open your phone's Settings, then Accessibility." (existing) | none | Open Settings again |
| PiP entry throws, returns false, or the user disabled PiP for the app | `enterPictureInPictureMode` (external) | Open Settings | Settings opens without floating guide | none | n/a |
| Settings launch fails after PiP was entered | `startActivity` | Open Settings | the guide leaves PiP (`moveTaskToFront` (external)) so the error text and Open Settings again / Return are reachable full screen | none | Open Settings again |
| Another app's overlay blocks Allow (screen filters, chat heads) | other app | user tap | Samsung/Android toast | none | out of scope; guide text does not claim to fix it |
| Enabling is restricted by the device (work profile policy, sideload "restricted setting") | Settings | user | Settings explains it | none | out of scope (Play installs are not restricted) |
| Allow refused (P2 on a Samsung variant Chunk 0 missed) | Settings | user tap | Samsung toast; guide still floats | none | Close PiP, tap Allow; mitigated by "Tap Allow" only if Chunk 0 PASS |
| User declines Allow | Settings | user | back in Settings, guide floats | none | Toggle again |

Copy follows `content-brand.md`; no em dashes.

## 8. Signals audit
`GuidePhase` history changes button copy only. `compact` changes layout only. No telemetry fields.

## 9. Fallback source of truth
| Branch | Expression | Source | Why | Predicate | If none | Consumer |
|---|---|---|---|---|---|---|
| Any PiP failure | full-screen guide | same activity | it is the floor | `!compact` | n/a | user |

## 10. File-by-file
- `app/src/main/java/com/envi/wispr/ui/AccessibilityGuideActivity.kt`: split Agree from Settings launch;
  Open Settings button; shortcut frame; Samsung-gated PiP entry and compact renderer restored from
  `bb7fdf35^`.
- `app/src/main/AndroidManifest.xml`: `supportsPictureInPicture="true"` on the guide.
- `.claude/knowledge/device-testing.md` or `docs/internal/android-onboarding-tracker.md`: Chunk 0 result.

## 11. Testing
1. Classes: the S26 hardware pass is the product-outcome test (Allow on first try, guide visible, guide gone
   after grant). A JVM test of the Samsung gate predicate is a drift guard only.
2. Revert that turns it red: restoring `openSettings` on Agree hides the guide again (emulator UAT step
   "guide visible after Agree" fails).
3. Not tested: Samsung row positions, every maker's Settings, Allow behavior on non-Samsung with PiP (PiP
   never entered there).

### 11.1 Hardware UAT
- Subsystem: limb (setup), touching insertion readiness.
- Chunk 0 (before building the branch): a throwaway probe APK (separate package, debug, uninstalled after)
  enters PiP over Accessibility Settings; on the S26 the founder (or wispr-eyes) turns EnviousWispr off and
  on and taps Allow while the PiP overlaps the dialog. PASS = granted with no "obscuring" toast; FAIL =
  toast or no grant. Restore: EnviousWispr accessibility back on and bound, probe uninstalled. Record the
  Android and One UI versions and whether the tap was a human touch (the founder) or injected input; only a
  human touch counts. An S26 PASS does not verify other Samsung models or One UI versions; the S22 stays NOT
  VERIFIED until checked (coverage gap 1).
- Readability (coverage gap 6): pause/step state across PiP expansion, reduced motion, largest font size,
  TalkBack reading the guide, and a short landscape window, on the emulator.
- Final: Play internal build on the S26, fresh setup path via the Permissions page (no data wipe): guide
  visible, Open Settings, floating guide (if PASS), Allow first try, back to Granted, guide gone.

### 11.2 Other obligations
| Test | Class | Proves | Revert that turns it red |
|---|---|---|---|
| Full unit suite | — | no regression | — |
| Emulator UAT: Agree shows guide; Open Settings opens Settings; grant returns | product outcome (emulator, no PiP there) | G1, G5 | restoring Settings-on-Agree |

## 12. Blast radius & rollback
Touched: one activity (including the disclosure button label), one manifest attribute. Not touched:
disclosure body text, service, readiness, insertion.
Rollback: revert the PR; behavior returns to today's.

## 13. Ship criteria
- [ ] After Agree, the guide is on screen and Settings opens only from its button (emulator and S26).
- [ ] On the S26, Allow works on the first tap and setup shows Accessibility granted.
- [ ] Samsung floating guide shown and self-dismissed on grant, or explicitly dropped by Chunk 0 FAIL.

## 14. Open questions
- Q1 (Chunk 0): does Samsung's Allow accept a tap while our PiP overlaps its dialog?

## 15. Related
#416, #417, #473 (session), #475, ONB-17.
