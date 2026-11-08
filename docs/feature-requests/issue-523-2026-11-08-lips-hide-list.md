# Issue #523 — Keep the floating lips off the lock screen, password boxes and chosen apps — 2026-11-08

GitHub issue: `#523`. Tier: MEDIUM (new runtime behaviour in the paste service, a new setting; touches the
primary trigger's visibility). Status: APPROVED (founder Gate 2, 2026-11-08: approved as planned, both recommended defaults).

## Preface — Lane + Hardware UAT declaration

**Lane:** Code

**PAR rows closed:** none. The catalog has no floating launch button on macOS or Windows
(`floating-launch-bubble` rows: macos `absent`, windows `absent`), so there is no parity contract.

**Hardware UAT:** Y. The bubble is the primary trigger (catalog decision 2026-09-12), so its visibility is
heart-adjacent. Success on the S26: the lips do not appear on the lock screen's password box or on a
password field in a real app (Chrome login page, an app sign-in screen); with Gmail added to the hide list
the lips never appear in a Gmail compose box, while Messages still shows them; removing Gmail from the list
brings them back in Gmail on the next field. The side button still dictates and inserts into a normal
editor in a hidden app. On a password field, compare before and after: preserve the existing sensitive-field
fallback (`InsertionResults.COPY_ONLY_SENSITIVE`, clipboard plus the user line). Successful password insertion
is not an acceptance criterion for #523; changing that behaviour remains #11.

## Preface — User Rubric

1. **Who.** Meera Patel, one-handed on her phone, unlocking it to reply in Messages; and Diana Foster, whose
   company chat app (Slack) has its own voice-note button and where the lips sit over the send area.
2. **Why.** Meera: "Why is the lips thing on my lock screen? I'm typing my PIN." Diana: "I never dictate in
   this one app, stop putting the button there."
3. **How.** Reactive. They see the bubble somewhere it does not belong and want it gone for good, not just
   until the next tap ("Drop to hide" today lasts only until the next field).
4. **Apps.** Lock screen, any sign-in screen (Chrome, banking, Google account prompts), and user-chosen apps:
   Slack, games with chat boxes, a terminal app, a password manager.
5. **Natural input.** Not voice. Taps: "hide it here", "not in this app", "never on passwords", "show it in
   Slack again", "where did my button go?".
6. **Success.** They stop noticing the lips anywhere they do not want them, and they are still there in
   every other app.
7. **Wrong-not-broken.** The lips quietly stop appearing in a box where the user DOES want to dictate (a
   field an app wrongly marks as a password, or an app they hid months ago and forgot). They conclude the
   app broke.
8. **Power-user hack.** Turn the Accessibility service off entirely, which kills auto-paste everywhere, or
   drop-to-hide every single time.
9. **Control ladder.** Automatic, no setting: lock screen (nothing can be inserted there). Automatic with
   one switch to turn back on: password boxes. Explicit, user-managed: the app list, added and removed on
   one Settings page.

### Cross-persona check

- Priya, Aaron: want password-box hiding and a per-app list (terminal, IDE apps with their own voice).
- Marcus: indifferent; dictates long text in a few apps.
- Elena: strongly wants nothing hovering over password and medical sign-in screens.
- Meera, Frank: want the lock screen fixed with zero setup; would never open a list. Frank must not lose
  the button by accident, so nothing is hidden per app without an explicit choice on a Settings page.
- Diana: wants the per-app list.
- Tension: Frank (never lose the button) versus Diana (hide it for good). Resolved in §3: automatic hiding
  only where dictation cannot or should not happen (lock screen, password box with a switch); per-app hiding
  only by explicit choice, listed and reversible on the same page.

---

## 0. TL;DR

A customer (Sentry `ENVIOUSWISPR-ANDROID-Q`, 2026-11-07) asked to keep the floating lips off the unlock
screen, off "anything requiring a password", and out of apps they choose. Today the idle bubble appears on
every focused editable field in any other package. Add one display decision in the bubble host: never draw
the idle bubble while the phone is locked; by default do not draw it on password fields (one switch to turn
it back on); never draw it in apps on a user-managed list. Only the idle bubble is affected: a running
take's recorder, the side button, the tile and insertion are unchanged. Evidence: unit tests on the pure
decision, then an S26 pass on the lock screen, a real password field, and a hidden versus unhidden app.

## 1. Problem

- User report, verbatim message in issue #523: the bubble appears on the unlock screen and on password
  screens, and there is no lasting way to keep it out of chosen apps.
- Code: `AccessibilityBubbleHost.revalidateBubbleField` calls `RecordingAccessibilityOverlay.fieldActivated`
  for any key `EditorTargetTracker.refreshFocusedField` returns; `updateBubbleFromEvent` does the same on
  `TYPE_VIEW_FOCUSED`/`TYPE_VIEW_CLICKED` via `rememberedFieldKey`. The only package filter is
  `OwnFieldAdmission.accepts` (rejects our own package only). No keyguard, password or app check exists on
  this path.
- Lock screen: NOT reproduced by us. The 2026-11-08 emulator attempt could not lock the AVD from the
  harness (sleep and power keys left `_keyguard_showing()` false). The report is consistent with the code:
  the keyguard bouncer's password box (`passwordEntry`, `com.android.systemui`) is an editable focused node
  in another package. Chunk 1 measures it on the emulator before any code.
- Chunk 1 result (2026-11-08, emulator `EnviousWispr_Android_16`, installed build of 2026-11-07, temporary
  PIN set and removed afterwards): with the PIN bouncer up, the harness's overlay eye reported the idle lips
  shown and visible (168 px, bottom right), and still there 2.5 s later. REPRODUCED. Return-after-unlock was
  not measured (the harness unlock looks for a password-style field and the PIN layout differs); it is measured
  in the post-change UAT, and the screen-off / user-present receiver is added only if the lips do not return.

## 2. Goals & non-goals

### 2.1 Goals
- G1. While the keyguard is locked, the idle bubble is never drawn.
- G2. On a field whose input type is a password variation, or `isPassword`, the idle bubble is not drawn,
  unless the user turned "Show on password boxes" (proposed) on. Default: off (hidden).
- G3. A user-managed list of apps where the idle bubble is never drawn; add and remove on one Settings page.
- G4. In every suppressed case the side button, the tile and insertion behave exactly as today.
- G5. A take already running is never hidden or interrupted by any of these.

### 2.2 Non-goals
- No change to insertion into password fields (founder decision 2026-08-28 stands; #11 untouched).
- No per-screen (per-activity) hiding: a screen identity is not stable or user-meaningful on Android.
- No in-bubble "hide in this app" gesture in v1 (see §3 alternative B and §14).
- No telemetry of package names. At most a count-only event (§8).

## 2.5 Grounding brief

### 1. Producer → owner → consumer
- Producer: accessibility events into `PasteAccessibilityService`, forwarded to
  `AccessibilityBubbleHost.updateBubbleFromEvent` (`paste/AccessibilityBubbleHost.kt`, `updateBubbleFromEvent`).
- Field identity: `EditorTargetTracker.rememberEditableTarget` (remembers a `TargetSnapshot` with
  `packageName` and the node) and `refreshFocusedField` (returns the key or null).
- Decision today: two call sites into the overlay: `tracker.rememberedFieldKey()?.let { overlay.fieldActivated(it) }`
  in `updateBubbleFromEvent`, and `revalidateBubbleField` (`if (key != null) overlay.fieldActivated(key) else overlay.fieldLost()`).
- Consumer: `RecordingAccessibilityOverlay.render`, branch `fieldActive && hiddenForKey == null` draws the
  idle bubble; branch `snapshot.visible` draws a running take first, independent of field state.
- Preferences reach the host through the one `AppPreferences(...).state.collect` in `attach()` (look, input
  pick). That collect does NOT call `refresh()` today (Codex coverage, 2026-11-08), and `AppPreferencesState`
  feeds `app.launched` and the settings telemetry (comment above `AppPreferences.developerStored`), so the
  hide list must NOT join `AppPreferencesState`. It gets its own flow beside `developerStored`:
  `bubbleVisibilityStored` (proposed), collected by the host in the same `lookScope`.
- Side-button pinning reads the tracker (`pinnedPackage`, `withPinnedNode`), not the overlay, so suppressing
  the overlay cannot change what the side button inserts into.

### 2. Existing authority
- Password predicate exists once: `AccessibilityInsertionRunner.isSensitive` (private; `isPassword` plus
  text/number password variations). It is due for removal from insertion by #11. This plan moves the
  predicate to one pure owner, `PasswordFieldRule` (proposed), used by the bubble decision and, until #11
  lands, by the runner. No second copy.
- App enumeration: `OnboardingViewModel` already queries launcher activities under the manifest's
  `<queries>` launcher intent; the picker reuses that query shape. Negative grep for an existing per-app
  setting: `grep -rn "stringSetPreferencesKey" settings/AppPreferences.kt` shows only
  `model_mobile_data_approved_revisions` and `model_update_prompts_answered`. New authority proposed:
  `BubbleVisibility` (proposed), a pure decision.

### 3. Prior attempts and live direction
- Gate 0 comment on #523. Binding: bubble is primary trigger (2026-09-12); no sensitive-field insertion guard
  (2026-08-28); three looks (2026-09-14). No prior per-app hiding attempt.

### 4. Boundaries
- Paste service runs in the default process with the app UI (`PasteServiceProcessManifestTest`), so the
  Settings page and the host share DataStore in one process.
- Service reconnects (`onServiceConnected` again on any package install) re-run `discoverOnConnect`; the
  decision is evaluated on every activation, so a reconnect re-applies it.
- Keyguard: read `KeyguardManager.isKeyguardLocked` (external) at decision time, on the events that already
  run. Unlock emits window events (the keyguard window goes away), which already trigger discovery.
- Own onboarding practice field: our package; never in the hide list (the picker excludes our package) and
  not a password field, so practice is unaffected.

### 5. High-risk premises
- P1 "A running take is drawn regardless of field state": `render()` checks `snapshot.visible` first. Read.
- P2 "The bouncer password box is an editable node the tracker accepts": inferred from code and the
  harness's `passwordEntry` id; NOT measured. Chunk 1 measures with `overlay()` on a locked emulator.
- P3 "Unlocking produces an event that re-runs discovery": not measured. Chunk 1 measures that the bubble
  returns on the first field after unlock with no re-tap.
- P4 "Launcher query lists the apps a user types in": open-world. Apps without a launcher icon cannot be
  picked; stated as a limit.

## 3. Design

One pure decision, evaluated at the single point where the host tells the overlay a field is active:

`BubbleVisibility.decide(keyguardLocked, passwordField, packageName, hiddenApps, showOnPasswords)` (proposed)
returns a sealed `BubbleVisibility.Decision` (proposed): `Show` (proposed), `HiddenLocked` (proposed), `HiddenPassword` (proposed),
`HiddenApp` (proposed). The host routes both existing `fieldActivated` call sites through one private
`activateField(key)` (proposed): `Show` → `overlay.fieldActivated(key)`; any `Hidden*` →
`overlay.fieldSuppressed()` (proposed), which does what `fieldLost` does AND cancels an idle drag and its
hide target (`render()` draws `dragBox` before checking `fieldActive`; Codex coverage finding 1). It never
touches a running take (`snapshot.visible` is drawn first and the take's own gestures are not idle drags).

No idle bubble is drawn until the first `bubbleVisibilityStored` emission has arrived, the same rule the host
already applies to the earbud colour (`applyEarbuds` waits for both inputs). This closes the startup window
instead of documenting it. A read failure (`IOException`) emits defaults: nothing hidden by app, password
boxes hidden, i.e. the bubble fails OPEN in every app, because a false hide loses the primary trigger.
Every change to either preference calls `refresh()` on main, so editing the list while a field stays focused
takes effect at once. The tracker keeps remembering the field, so pinning and insertion are untouched.

Order: locked first, then app list, then password. Every hidden case is logged as a reason name only.

Settings: on the existing bubble page (Settings > Appearance, where the look is chosen), add
"Hide the lips in these apps" (proposed copy, to be checked against `content-brand.md`): the hidden apps
with a Show again control each, and an Add app picker listing launcher apps (excluding EnviousWispr). Plus
one switch "Show on password boxes", default off.

Alternatives rejected:
- A. Hide on password fields with no switch. Simpler, but a field an app wrongly marks as a password would
  lose the trigger with no way back (Rubric 7). The switch costs one row.
- B. In-bubble "Hide in this app" drop target. Most discoverable, but once hidden the bubble cannot offer the
  way back in that app, breaking `code-design-rules.md` RULE:
  an-affordance-that-sets-a-state-must-clear-it-from-the-same-surface unless it only deep-links to the
  Settings list. Deferred to §14 as a founder choice.
- C. Treat password fields by refusing insertion. Contradicts the 2026-08-28 decision. Rejected.

**Consolidation:** the password-field predicate. Dominant root: "is this field a password box", today
answered only inside `AccessibilityInsertionRunner.isSensitive`. One owner after this change:
`PasswordFieldRule` (proposed). Sites: the insertion runner (delegates until #11 removes its use) and the
bubble decision. Nothing else is consolidated; the bubble decision is a new authority, not a merge.

## 3b. Ownership

The decision lives in `paste/` as a pure object beside `OwnFieldAdmission` because it answers the same kind
of question (may the bubble treat this field as a place to offer dictation) and must run in the service's
process on the main thread; the alternative was the overlay, but the overlay is deliberately free of
preferences and nodes (it takes keys and states), and putting a DataStore read there would couple drawing to
storage.

## 4. Contract deltas
- `AppPreferences` gains `bubbleVisibilityStored` (proposed): a `Flow` of `BubbleVisibilityStored` (proposed)
  with `hiddenApps: Set<String>` and `showOnPasswords: Boolean`, kept OUT of `AppPreferencesState` so it can
  never reach telemetry. Absent keys = nothing hidden, password boxes hidden. Two setters, one writer page.
- `RecordingAccessibilityOverlay` gains `fieldSuppressed()` (proposed): field lost plus idle-drag cancel.
- `EditorTargetTracker` gains a read of the remembered field's package and password flag (`rememberedFieldTraits()` (proposed)), read from the existing `TargetSnapshot`; content-free.
- `AccessibilityInsertionRunner.isSensitive` delegates to `PasswordFieldRule` (no behaviour change).

## 5. Lifecycle and state audit

| Population | Answer |
|---|---|
| Events that activate the bubble | Two call sites in `AccessibilityBubbleHost` (enumerated §2.5.1); both routed through `activateField`. |
| Events that change an input of the decision | Focus moves (handled). Preference change: new collect → `refresh()`. Lock/unlock over an editor that stays focused, including a `showWhenLocked` activity: assumed to arrive as `TYPE_WINDOWS_CHANGED` with ADDED/REMOVED (handled by `discoveryWarranted`), NOT VERIFIED; chunk 1 measures it, and if no handled event arrives the plan adds an `ACTION_SCREEN_OFF` (external) / `ACTION_USER_PRESENT` (external) receiver registered by the service (event-driven, no idle cost). Same node turning into a password field with no focus change: HYPOTHETICAL, loud not silent (the lips show on that one field until the next focus), display only; recorded as a known limit, no handler. |
| A take running when an input changes | `render()` draws `snapshot.visible` first; suppression only sets field state. None found that hides a take. |
| Service reconnect | `discoverOnConnect` → `revalidateBubbleField` → decision re-applied. |
| Stale preference at first paint | Closed: no idle bubble until the first emission (§3). Read failure emits defaults and the bubble fails open. |
| Idle drag in progress when suppression starts | `fieldSuppressed()` cancels the drag and removes the hide target. |
| Uninstalled app still on the list | Harmless; the Settings list shows it with its package name and Show again. |
| Process death | The list is in DataStore; reread on start. |

## 6. Consumer matrix

| Delta | Consumer | Current | Required | Code change? | Verified by |
|---|---|---|---|---|---|
| Decision | Overlay idle bubble | Shown on any field | Hidden per decision | Yes | Unit + S26 |
| Decision | Side button / tile / pin | Reads tracker | Unchanged | No | S26 side-button take in hidden app and password field |
| Decision | Onboarding practice | Own field admitted | Unchanged | No | Existing onboarding wiring tests |
| Prefs | Settings page | n/a | Lists, adds, removes | Yes | Compose test + emulator |
| `PasswordFieldRule` | Insertion runner | Private predicate | Delegates, same result | Yes | `PasswordFieldRuleTest` (proposed) plus a runner wiring drift guard; existing insertion tests cover downstream handling only |

## 7. Failure modes

| Failure | Origin | Caller | User sees | Persisted | Retry |
|---|---|---|---|---|---|
| Keyguard read throws | `KeyguardManager` (external) | host | Treated as unlocked: bubble behaves as today (fail open; a false hide is worse) | none | next event |
| Prefs not yet delivered | DataStore | host | No idle bubble for the milliseconds until the first emission; side button and tile work | none | first emission |
| Prefs read fails (`IOException`) | DataStore | host | Defaults: bubble shows in every app, password boxes hidden; the Settings list may show empty | none | next process start re-reads |
| Field wrongly flagged password | target app | host | No lips there; switch brings them back; side button still works | none | user switch |
| User hid an app and forgot | user | host | No lips in that app; Settings list shows it | list | Show again |
| Launcher query fails | PackageManager | Settings | Picker shows "Couldn't list your apps" (copy TBD) | none | reopen |

## 8. Signals
- Debug log: `Bubble suppressed reason=<LOCKED|APP|PASSWORD>`; this change adds no package name to any log.
  The tracker already logs the target package locally (`EditorTargetTracker` remember and pin lines), and
  PostHog already receives target-app metadata under the declared allowlist (`telemetry.md`); this change
  neither widens nor narrows that.
- Telemetry: none. The hide list lives outside `AppPreferencesState`, so the settings telemetry and
  `app.launched` cannot pick it up. A test pins that `AppPreferencesState` has no hide-list field.

## 9. Fallback source of truth

| Branch | Expression | Source | Why | Predicate | If none | Consumer |
|---|---|---|---|---|---|---|
| Keyguard unknown | `Show` | today's behaviour | false hide loses the primary trigger | read failed | Show | overlay |
| Prefs not yet delivered | no idle bubble | host gate | prevents a hidden app flashing the lips | no emission yet | wait | overlay |
| Prefs read failed | defaults | `bubbleVisibilityStored` catch | same as a fresh install; fails open | `IOException` | defaults | host |

## 10. File-by-file
- `paste/BubbleVisibility.kt` (proposed): pure decision + sealed result.
- `paste/PasswordFieldRule.kt` (proposed): the moved predicate.
- `paste/AccessibilityBubbleHost.kt`: `activateField`, keyguard read, prefs into the existing collect.
- `paste/EditorTargetTracker.kt`: traits read.
- `paste/AccessibilityInsertionRunner.kt`: delegate `isSensitive`.
- `settings/AppPreferences.kt`: two keys and `bubbleVisibilityStored`, outside `AppPreferencesState`.
- `paste/RecordingAccessibilityOverlay.kt`: `fieldSuppressed()`.
- `ui/SettingsPages.kt` (or a new `ui/BubbleHideListPage.kt` per `architecture-rules.md` keep-central-types-thin), `ui/AppActions.kt`, `ui/AppViewModel.kt`: page, picker, actions.

## 11. Testing
1. Classes: `BubbleVisibilityTest` (proposed) Product Outcome ("when this fails, the lips show on the lock screen or
   vanish in an app the user did not hide"); `PasswordFieldRuleTest` (proposed) Product Outcome; host wiring test
   (both call sites go through the decision) Drift Guard; Settings Compose test Product Outcome;
   `AppPreferencesState` has no hide-list field, Drift Guard (privacy); overlay test that suppression during an
   idle drag removes the bubble and the hide target, Product Outcome.
2. Revert: removing the `activateField` routing turns the wiring test red; making `decide` always `Show`
   turns `BubbleVisibilityTest` red. Both reverts performed before commit.
   Password predicate: verify `PasswordFieldRule` against the current predicate: `isPassword`, all three text
   password variations, numeric password, and ordinary text/number negatives with unrelated flags. Add a
   runner wiring drift guard proving `locateTarget` derives `sensitive` through `PasswordFieldRule`. Preserve
   the existing `isPassword` short-circuit before reading `inputType`. Before commit, replace the runner
   classification with `false` and confirm the wiring guard fails. Existing insertion tests (`InsertionAttemptTest`
   supplies `sensitive` directly) verify downstream handling only.
3. Not tested: every OEM keyguard (S26 and emulator only); apps without launcher icons.

### 11.1 Hardware UAT
- Subsystem: trigger (heart-adjacent).
- Recipe: chunk 1, before code, on the emulator: lock with PIN over a focused Gmail editor and raise the
  bouncer, record whether `overlay()` shows the lips today and which accessibility events arrive on lock and
  unlock (keyguard over the same focused editor, and over a `showWhenLocked` activity); after the change,
  `overlay()` absent while locked and present again on unlock with no re-tap; edit the hide list while Gmail
  stays focused and see the lips go and come back with no other event; then S26 by the founder for the lock screen (we never send input at his lock
  screen) and by harness for a Chrome password page, hidden Gmail, unhidden Messages, side-button take into
  hidden Gmail landing by the editor's own text, and a side-button take on a Chrome password field compared
  before and after the change (same sensitive-field fallback).
- Oracle: `overlay()` presence plus the editor's own text for insertion; never a log line alone.
- Restore: remove Gmail from the hide list, password switch back to default.

## 12. Blast radius & rollback
- Touched: `paste/` bubble host and tracker, preferences, one Settings page. Not touched: session owner,
  capture, ASR, polish, insertion routes, AIDL, Room (no migration).
- Rollback: revert the PR; preferences keys are ignored by the old build.

## 13. Ship criteria
- [ ] The lips no longer appear on the lock screen or on password boxes, and never in an app the user hid.
- [ ] Confirmed on the S26 (lock screen by the founder) and the emulator; side button still inserts.

## 14. Open questions (founder, Gate 2)
- Q1. Password boxes: hide by default with a switch to show (recommended), or no switch?
- Q2. Also add a "Hide in this app" spot to the existing drag-to-hide target, which adds the app to the list
  and points to Settings to undo? (Recommended: not in v1; ship the Settings list, add it if users ask.)

## 15. Related
#523, #11, Sentry `ENVIOUSWISPR-ANDROID-Q`, catalog `floating-launch-bubble`, decisions 2026-08-28 and
2026-09-12.

## Review digest

- Coverage round (Codex, 2026-11-08, `coverage.txt` in run folder `20261108-090728-ewa-523-lips-plan-review-8e1e`):
  6 findings. Adopted: idle drag bypassed suppression (new `fieldSuppressed()`); preference changes did not
  re-evaluate (explicit `refresh()`); lock over a still-focused editor unmeasured (chunk 1 measurement plus a
  named fallback receiver); startup flash and read failure (closed by waiting for the first emission, fail
  open on failure); package names and telemetry (hide list kept out of `AppPreferencesState`). Rejected as a
  hypothetical, loud, display-only case: a same-node switch into a password field (known limit).
- Grounded round 1 (Codex, `grounded-r1.txt`): PROCEED-WITH-REVISIONS, 2 findings, both adopted: the
  password-field UAT now preserves today's sensitive-field fallback (#11 owns changing it); the predicate move
  gets a runner wiring guard with a revert receipt, since `InsertionAttemptTest` passes `sensitive` in directly.
  Pre-committed condition (another bypassing draw path) NOT MET; host routing kept.
- Grounded round 2 (confirming resume, `grounded-r2.txt`): PROCEED-AS-PLANNED, no new findings. Gate 2 unlocked.

## Build deviations (declared)

- The overlay idle-drag row is a source DRIFT GUARD (`BubbleVisibilityWiringTest`), not a Product Outcome
  test: the unit suite has no harness that constructs `RecordingAccessibilityOverlay` (a window-manager view),
  and building one for a single branch is a second rig. The behaviour is covered on the emulator UAT.
- The Settings Compose test is replaced by the emulator UAT through Wispr Eyes on the real Appearance page
  (add Gmail, see it listed, Show again): Compose tests here are instrumented, and the real page through
  the harness reaches the same outcome plus the DataStore round trip.
- Two existing drift guards were updated to the new wiring with the same intent: `LipsBubbleWiringTest`
  (revalidation now calls `activateField`) and `AppShellShapeTest` (the actions `remember` keys on seven
  view models).
- Revert receipts (2026-11-08, on checkpoint `8e5a8fda`, files restored with `git checkout` afterwards):
  `decide` forced to `Show` turned 5 `BubbleVisibilityTest` rows red; runner `isSensitive` forced to `false`
  turned `theInsertionRunnerAsksTheSharedPasswordRule` red; revalidation calling `overlay.fieldActivated`
  directly turned `onlyActivateFieldTellsTheOverlayAFieldIsActive` red; dropping the web-password variation
  turned `aWebPasswordIsAPasswordBox` red. Full suite on the checkpoint: 2055 tests, 0 failures, 1 skipped
  (`scripts/measure-tests.sh`).

## Real-phone validation (Firebase Test Lab, personal project, 2026-11-08)

New `BubbleVisibilityDeviceTest` (Product Outcome) with the `BubbleFieldsActivity` rig (an ordinary and a
password editor in the test package; showWhenLocked). Every hidden check is a transition from shown.

- Final run `matrix-61xhkc3qtr2sa`, HEAD `336dc771`: Galaxy S24 Ultra (e3q, Android 16) 3/3 pass, Pixel 9 Pro
  (caiman, Android 15) 3/3 pass; JUnit 3 tests, 0 failures, 0 errors, 0 skipped on each. S24 logcat: lips
  `reason=SHOW` 25 ms after `KeyguardViewMediator: keyguardGone`.
- Defects the phones found, fixed on this branch: (1) after unlock over a shown-over-lock app the lips stayed
  hidden until a re-tap (S24; no window event); fixed by re-checking on SCREEN_ON/SCREEN_OFF/USER_PRESENT with
  the existing retry sequence; (2) that receiver was registered NOT_EXPORTED and never heard USER_PRESENT,
  which SystemUI sends (S24 logcat: no app log after `keyguardGone`); now EXPORTED (protected broadcasts).
- Earlier runs, kept for the record: `matrix-2gp7sel5yug6b`, `matrix-32skk5u4yv4i0`, `matrix-17ihfy4fh57pt`.
- Not covered: the founder's S26 itself; OEM lock screens beyond Samsung One UI 8 and Pixel.
