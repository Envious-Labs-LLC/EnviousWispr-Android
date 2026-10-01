# Issue #131 — Say when auto-paste was switched off without an orderly stop — 2026-10-01

GitHub issue: `#131`. Tier: MEDIUM (permissions, new runtime behaviour). Status: DRAFT.

## Preface — Lane + Hardware UAT declaration

**Lane:** Code

**PAR rows closed:** none. `PAR-003` (accessibility setup with live status) is touched, not closed: this adds
one status it lacked; the rest of the row is unchanged.

**Hardware UAT:** Y. On the S26 the founder force-stops EnviousWispr from App info, then opens it: the
Permissions page says auto-paste was switched off and that this can happen when the app is stopped, with one
button that opens Accessibility settings (through the existing disclosure); after he turns it back on, the page reads Ready. Then he dictates into Messages with
auto-paste still off: the words go to the clipboard and one calm line says why, where today nothing is said.

## Preface — User Rubric

1. **Who.** The Busy Founder persona: dictates replies in Slack and Gmail between meetings; yesterday it
   worked, today the words stop landing and he does not know why.
2. **Why.** "It just stopped pasting. Did I break something?"
3. **How invoked.** Reactive: he notices the words did not land, or opens the app to find out.
4. **Apps.** Slack, Gmail, Messages, Chrome; the fix is in our own Permissions page and recorder line.
5. **Natural input.** "why isn't it pasting", "it stopped working", "fix auto paste", "it was on yesterday",
   "do I have to set it up again".
6. **Success.** One sentence that names the cause, one tap that goes to the switch, and Ready afterwards.
7. **Wrong-not-broken.** A message that asserts a cause it cannot know (blaming him, or blaming Android when
   he did it), or a message on every dictation that nags.
8. **Power-user hack.** Re-installs the app, or turns every accessibility service off and on.
9. **Control.** None needed: this is a status, not a feature. Turning auto-paste off himself stays silent.

### Cross-persona check
Every persona wants the same thing: be told once, accurately, with the fix one tap away. The tension is
nagging versus silence; resolved by saying it on the Permissions page always and in the recorder only when
words actually missed the field (§3).

---

## 0. TL;DR

A force-stop clears Android's accessibility setting (measured 2026-09-06), and the app then treats the user
as someone who never enabled auto-paste: the Permissions page shows the first-time "Setup needs attention"
card and a dictation that falls back to the clipboard says nothing. A Samsung sleep instead leaves the
setting on with the service crashed (measured 2026-10-01), which the app already words as "not connected".
This plan adds one state, auto-paste switched off without an orderly stop, derived from a fact the service
already records: whether its last stop was clean. The marker proves whether an orderly stop was recorded,
not who switched auto-paste off, so the words name the state and a likely cause ("can happen when the app is
stopped"), never a certain one. The user turning it off runs `onUnbind`/`onDestroy` and marks the
stop clean; a force-stop or a sleep kill does not. The state gets its own Permissions-page wording and one
recorder line when words miss the field. Evidence: unit rows over the derivation and the copy, and an S26
force-stop UAT.

## 1. Problem

- Force-stop, S26 2026-09-06: `bound=True setting=<ours>` before, `bound=False setting=null` after
  (`device-testing.md` FACT: force-stopping-the-app-CLEARS-the-accessibility-permission).
- Deep sleep, S26 2026-10-01: setting kept, service in "Crashed services", opening the app does not rebind
  (`device-testing.md` FACT: samsung-deep-sleep-crashes-auto-paste; "Put unused apps to sleep" was on).
- With the setting cleared, `AutoPasteReadiness.evaluate` answers `NOT_PERMITTED`, so:
  - the Permissions page shows the red "Setup needs attention" card (`ui/SettingsPages.kt`), the same words
    a new user sees;
  - `InsertionOutcomeMessages.autoPasteWasExpectedToWork` returns false for `NOT_PERMITTED`, so the
    fallback announcement is null and the user is told nothing (`InsertionOutcomeMessagesTest`
    `:79` pins this silence for a user who never enabled it, which is correct for that user).
- The founder hit this twice in one session (2026-09-06) and fixed it by hand once before.

## 2. Goals & non-goals

### 2.1 Goals
1. A user whose auto-paste was switched off without an orderly stop sees, on the Permissions page, a sentence
   saying it was switched off and how it can happen, and one action that opens Accessibility settings
   through the existing disclosure, never the first-time setup card.
2. A dictation that falls back to the clipboard in that state says one calm line naming the destination and
   the cause, once per take, as the existing delivery lines do.
3. A user who turned auto-paste off himself, or never turned it on, sees exactly today's behaviour.
4. The sleep case keeps today's "not connected" wording (it is already accurate and actionable); optional
   copy tweak in §3.4 for the founder to choose.

### 2.2 Non-goals
- Re-enabling the service from the app (needs a privileged permission; refused in the issue).
- Asking the user to change battery optimisation as the primary fix.
- Detecting the sleep case by reading Samsung's sleeping list (no public API).
- Telemetry for this state (stage 2).

## 2.5 Grounding brief

### 1. Producer → owner → consumer
- Permission: `paste/AccessibilityPermission.kt` `isGranted` reads `Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES`
  once per call; callers `ui/ReadinessViewModel.kt` (refresh on resume), `ui/DictationSessionService.kt`
  (`autoPasteAvailability()` at session start and fallback), `ui/AccessibilityGuideActivity.kt`,
  `telemetry/AppLaunchFacts.kt`. No ContentObserver (negative grep in the trace).
- Liveness: `paste/PasteAccessibilityService.kt` `publishBinding` is the only writer of `isBound`
  (`onServiceConnected`, `onUnbind`, `onDestroy`).
- Stop marker: same file, SharedPreferences `paste_service_lifecycle` key `stop_was_clean`; armed false in
  `reportPreviousStop` on every connect, set true by `markStopWasClean` in `onUnbind` and last in
  `onDestroy`. Today only logged.
- Join: `paste/AutoPasteAvailability.kt` `AutoPasteReadiness.evaluate`/`observe`; consumers enumerated in §6.

### 2. Existing authority
The derivation already has one owner (`AutoPasteReadiness`), and every surface reads it
(`AutoPasteWiringTest` `:298` pins that no surface treats the permission as liveness). The stop marker is
the existing authority for "how did the service last stop". No persisted "ever granted" flag exists
(negative grep: `everEnabled|everGranted|wasGranted|accessibilityGranted|grantedOnce|hadAccessibility`); this
plan does not add one, because the marker's existence already means the service connected at least once.

### 3. Prior attempts and direction
#131 proposed a remembered "the user turned this on" flag. The stop marker answers the sharper question
(did the USER turn it off) without a new flag. macOS catalog: macOS says only "Auto-paste needs
Accessibility" (no lost-versus-never distinction; queried 2026-10-01), so Android words this state itself
under `content-brand.md`.

### 4. Boundaries a naive design misses
- The service shares the default process: its death is the app's death, so no callback runs on a force-stop
  or a sleep kill. That is WHY the marker stays false, and it is the signal this plan reads.
- A low-memory kill also leaves the marker false, but the setting stays and Android restarts the service;
  the new state requires the setting to be CLEARED, so a low-memory kill never reaches it.
- An app update kills the process too (unclean) and keeps the setting (Play 242 → 243, 2026-10-01), so the
  new state is not reached.
- The cold-start connect window (`PERMITTED_NOT_RUNNING`) is untouched.

### 5. Premises to prove before code (each one an `adb` step on the emulator, chunk 1)
1. Turning the service off in Settings while the app runs writes `stop_was_clean=true`.
2. A force-stop leaves `stop_was_clean=false` and the setting `null`.
3. Re-granting arms it false again on connect (`reportPreviousStop`).
4. Turning off ALL accessibility (the master switch, if present) also runs `onUnbind`.
If 1 or 2 fails, the design is wrong and returns to review. Premise 4 is the REAL master switch, verified
separately from writing the enabled-services setting.

**Run 2026-10-01 on emulator-5554 (debug build), reading `shared_prefs/paste_service_lifecycle.xml` with
`run-as` and the setting with `settings get`:** bound with the setting naming us: `stop_was_clean=false`.
Premise 1, setting written empty (the write the Settings switch makes): `true`, setting empty. Premise 3,
setting restored: `false`, bound True. Premise 2, `am force-stop`: `false`, setting `null`. Premises 1 to 3
hold. Premise 4 not run: this Android 16 image has no master accessibility switch; the S26 is checked in UAT.

## 3. Design

### 3.1 The state
`AutoPasteAvailability` gains `SWITCHED_OFF_UNEXPECTEDLY` (proposed): the setting no longer names our
service, the service has connected at least once (the marker key exists), and its last stop was not clean.
`AutoPasteReadiness.evaluate` takes the marker as a third input (`lastStop`, proposed, a small sealed type:
`Never`, `Clean`, `Unclean`), read from the existing preference. Ordering stays "a revoked permission outranks
a binding", so a stale binding can never report `LIVE`.

### 3.2 Surfaces (exhaustive `when`s break the build until each answers)
The marker proves whether an orderly stop was recorded, not who disabled auto-paste: a user who turns it off
after an unclean death also lands here, because no live service is left to write Clean. So no sentence states
a cause as certain.
- Permissions page: a calm card, not the red setup card: "Auto-paste was switched off" / "This can happen
  when the app is stopped. Turn EnviousWispr back on in Accessibility settings." / button "Accessibility
  settings", which opens the existing `AccessibilityGuideActivity` disclosure and consent route. The
  auto-paste row reads "Switched off. Turn it back on in Accessibility settings." Status label "Needs
  attention". When core setup also fails (microphone or model), the existing red setup card shows as today
  and the auto-paste row carries the new sentence; the calm card shows only when core setup is ready.
- Recorder fallback: `autoPasteWasExpectedToWork` returns true for the new state, so a missed field gets the
  existing delivery line, which names the MEASURED destination first, then "Auto-paste is off; turn it back
  on in Accessibility settings." It never says "Copied" when the copy failed or auto-copy is off (the existing
  `ClipboardOutcome` decides the first half). Once per take. Final copy to the founder at Gate 2.
- The sleep case keeps the existing not-connected wording, because the setting survives a sleep.
- Onboarding: unchanged route (anything not `LIVE` goes to Permissions), the row shows the new sentence.
- Listening line: as `NOT_PERMITTED` (states only what is decided).

### 3.3 Rejected
- A new "ever granted" DataStore flag (the issue's proposal): it cannot tell the user's own turn-off from
  Android's, so it would nag a user who chose to turn auto-paste off.
- `ApplicationExitInfo` `REASON_USER_REQUESTED`: names force-stops only, not sleep kills, and is read in a
  later process; the marker already covers both.

### 3.4 Founder choice at Gate 2
Whether the sleep case's existing "not connected" card should also suggest adding EnviousWispr to Samsung's
"Never auto sleeping apps" (prevents the sleep kill; Samsung-only wording).

Consolidation: none. The change adds one state to the existing single owner (`AutoPasteReadiness`) and one
input it reads; no second owner of readiness exists to fold in, and the stop marker keeps its one writer.

## 3b. Ownership
The state lives in `AutoPasteReadiness` because it is the one owner every surface already reads; the
alternative, a separate banner owner reading the marker, would be a second readiness answer that can
disagree with the first.

## 4. Contract deltas
- `AutoPasteAvailability`: one member more; every exhaustive `when` must place it. Meaning: "words will not
  reach the field; auto-paste was on, and it went off without an orderly stop being recorded".
- `AutoPasteReadiness.evaluate(permitted, bound, lastStop)` and `observe(..., lastStop flow)`.
- The stop marker becomes a product input, not only a log: its writes must stay exactly where they are.

## 5. State and lifecycle audit
| Population | Answer |
|---|---|
| Ways the service stops | Derived from setting membership × binding × marker, never from an event name. Retained setting + no binding stays `PERMITTED_NOT_RUNNING` (crash inside the service, deep sleep, low-memory kill, update until reconnect). Removed setting + marker Never or Clean is `NOT_PERMITTED` (never set up; user switch; master switch if premise 4 holds). Removed setting + Unclean is the new state (force-stop; user turn-off after an unclean death, accepted, which is why the words claim no cause). System revocation of a misbehaving service and an explicit Samsung restriction: NOT VERIFIED, recorded before and after on the S26 in UAT. Reinstall and Clear data: the marker file is removed with app data, so the state is `NOT_PERMITTED` (a stage 2 boundary, not verified now). |
| Marker timing | Binding publishes before the background arm (`reportPreviousStop` runs on an IO task); every write uses `apply()`, which updates memory at once and disk later. A kill can lose either write: a lost arm leaves Clean after a connect (missed detection), a lost clean leaves Unclean after a user turn-off (the accepted false attribution above). A completed `apply` is not proof of persistence. The readiness reader observes the marker independently of `previousStopReported`, which gates logging only. |
| Publication | Marker changes are published to the readiness flow even when permission and binding do not change (a marker-only transition). Cases covered by rows: delayed arm, unbind before arm, reconnect within one process, replacement connection before the outgoing teardown, a UI read between binding publication and the marker update. The existing instance guards stay. UI and service share a process: a timing race, not cross-process storage. |
| Readers of the marker | `reportPreviousStop` today; the readiness producer after this change |
| When the marker is read | each readiness refresh (resume) and each session start; a SharedPreferences read, cheap, main process |
| Stale | a grant made while the app is dead: the next connect arms false and the setting names us, so the state clears |

## 6. Consumer matrix
| Delta | Consumer | Now | Required | Change | Verified by |
|---|---|---|---|---|---|
| new member | `SettingsComponents.statusDescription` | 3 labels | 4th label | yes | compile + copy row |
| new member | `SettingsPages` cards and row | red setup card | calm cause card | yes | row in a new copy test |
| new member | `OnboardingScreen` row | generic | sentence | yes | copy row |
| new member | `OnboardingPolicy` | not LIVE → PERMISSIONS | same | no | `OnboardingPolicyTest` |
| new member | `InsertionOutcomeMessages` (listening, expected, fallback) | silent | one line | yes | `InsertionOutcomeMessagesTest` new rows |
| evaluate input | `ReadinessViewModel`, `DictationSessionService` | two inputs | three | yes | `AutoPasteAvailabilityTest`, wiring test |

## 7. Failure modes
| Failure | Origin | Caller | User sees | Persisted | Retry |
|---|---|---|---|---|---|
| marker unreadable | SharedPreferences | readiness | today's two-input answer (`LIVE`, `PERMITTED_NOT_RUNNING` or `NOT_PERMITTED`); only the new distinction is suppressed | none | next refresh |
| arm lost to a kill right after connect | service | readiness | after a later force-stop, today's `NOT_PERMITTED` (missed detection) | marker Clean | next connect re-arms |
| user turned off, marker write lost (process died inside onDestroy) | service | readiness | "switched off by Android" once | marker false | clears on re-grant or by turning off again |

## 8. Caller-visible signals
`stop_was_clean` key ABSENT = never connected; `false` = last stop unclean; `true` = clean. Absence is the
signal that keeps a brand-new user out of the new state.

## 9. Fallback source of truth
The marker is the only source of "how it stopped". An unreadable marker suppresses only the new distinction:
the result is the existing two-input answer, `LIVE` when permitted and bound, `PERMITTED_NOT_RUNNING` when
permitted and unbound, otherwise `NOT_PERMITTED`.

## 10. File-by-file changes
- `paste/AutoPasteAvailability.kt`: member, `evaluate`/`observe` third input.
- `paste/PasteAccessibilityService.kt`: expose the marker read (a small reader object), writes unchanged.
- `ui/ReadinessViewModel.kt`, `ui/DictationSessionService.kt`: pass the marker.
- `ui/SettingsComponents.kt`, `ui/SettingsPages.kt`, `ui/OnboardingScreen.kt`,
  `insertion/InsertionOutcomeMessages.kt`: the new state's words.
- Tests: `AutoPasteAvailabilityTest`, `AutoPasteReadinessObserveTest`, `InsertionOutcomeMessagesTest`,
  `AutoPasteWiringTest`, `OnboardingPolicyTest`, a copy row for the Permissions page.
- Knowledge and rules in the same PR: `code-gotchas.md` (four states, the marker's limits),
  `architecture.md` (current ownership), `device-testing.md` (force-stop and sleep as distinct outcomes).
- Harness (`scripts/uat/wispr_eyes.py`): correct the comment equating Samsung sleep with a force-stop;
  document that `stop_app` restores accessibility and so cannot stage this UAT (the UAT uses an explicitly
  unrepaired force-stop with recorded cleanup); make `enable_auto_paste` cycle only EnviousWispr while
  preserving other enabled services and verify binding afterwards, with a harness row for another enabled
  service; re-grant through it must exercise the marker re-arm.

## 11. Validation
- Unit: derivation truth table (permission × binding × marker, including an unreadable marker), copy rows,
  the listening and fallback lines for copied, History-only, copy-failed and rescued-word outcomes, the new
  state at onboarding steps 3 and 4, and the combined state (microphone or model missing plus auto-paste off)
  naming which cards show.
- One observer subscribed across permission, binding and marker transitions, awaiting each emission without
  restarting collection, including a marker-only transition (today's `AutoPasteReadinessObserveTest` calls
  `first()` afresh and would pass with a single snapshot). Each of: marker subscription, arming, clean marking,
  caller marker wiring, the new fallback sentence, removed alone, must turn a row red. Wiring scans stay
  drift guards, not product evidence.
- Emulator: premises 1 to 4 (§2.5.5) before code; after code, force-stop then open the app and read the
  Permissions page; turn off in Settings then open the app and read today's card.
- S26 UAT through Play (Hardware UAT above): after recovery, auto-paste is switched off again before the
  fallback take; assert exactly one visible line and that the words are recoverable where it says.
