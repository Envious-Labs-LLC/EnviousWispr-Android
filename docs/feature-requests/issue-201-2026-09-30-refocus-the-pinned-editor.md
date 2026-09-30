# Issue #201 — Put the words back in the field you started in — 2026-09-30

GitHub issue: `#201`. Tier: LARGE (insertion path). Status: DRAFT.

## Preface — Lane + Hardware UAT declaration

**Lane:** Code

**PAR rows closed:** none known (`grep -rn "PAR-" docs` names no row on a sibling field or `#201`; re-run at Gate 0 of the build).

**Hardware UAT:** Y. On the S26 with real speech: start a dictation in Gmail's body, tap Subject while still speaking, press the side button to stop. The words appear in the body, Subject stays empty, and nothing is left on the clipboard that the user must paste. Then the back-to-back case (two takes in a row) and a take where the user leaves the app entirely (today's clipboard outcome, unchanged).

## Preface — User Rubric

1. **Who, in this moment?** The Gmail-at-the-desk user (persona list in the brand guide): mid-sentence, looks at the screen, taps the Subject line to fix it, goes back to speaking or stops. Wants the paragraph in the body.
2. **Why?** "I said it into the email; it should be in the email."
3. **How invoked?** Side button stop or the bubble; voluntary, already in the right app.
4. **Apps:** Gmail compose (body and Subject), Messages, Chrome forms, Notes: any screen with two editable fields.
5. **Natural input:** a 20 to 60 second paragraph spoken into a body while glancing at a second field.
6. **Success feels like:** nothing; the paragraph is simply in the body when they look.
7. **Wrong-not-broken:** the words land in the field the user is now in (forbidden) or the cursor jumps somewhere surprising.
8. **Power user hack today:** paste by hand from the "copied" keyboard suggestion.
9. **Control ladder:** no setting; the existing rule stays the default, this only changes the sibling-field case.

Cross-persona check: no persona wants the words in the second field; the only disagreement is the cursor jump, resolved in §3 (it moves once, at insertion, only for a sibling field in the same window).

---

## 0. TL;DR

Founder decision 2026-09-30: when a sibling field in the SAME window holds input focus at stop time, the words go into the field the take was pinned to. At insertion time, if the pinned editor is present, visible and editable in the focused window and a sibling holds input focus, call `ACTION_FOCUS` (external) on the pinned node once, then let route selection run with COMMIT disabled for that attempt (PASTE only). Anything else keeps today's clipboard outcome. LARGE tier; proof is a rig row, an emulator scene that flips, and a phone pass.

Consolidation: none. This adds one capability (refocus the pinned node) to the one owner that already holds the pin, the window roots and the focused-window check (`EditorTargetTracker`); it replaces no second mechanism and leaves no duplicate behind, so there is no dominant root to consolidate and no site to migrate.

## 1. Problem

Gmail body pinned at start, Subject tapped while speaking, stop pressed: `Pinned original editor` once, then `insertion api=36 route=NONE written=false ... outcome=NEVER_RETURNED attempts=21 ms=2615`, body and Subject empty, words on the clipboard (emulator, `50ace8e`, record `docs/audits/2026-09-21-192-emulator-pass/`). Cause: `EditorTargetTracker.withPinnedNode` (`:176-204`) requires the pinned node `isFocused` at two points (`:184`, `:192`), and `AccessibilityInsertionRunner.commitIneligibleReason` (`:352`) repeats the test; a sibling holding focus fails all three and the attempt waits out `INSERTION_TIMEOUT_MS` (2.5 s).

## 2. Goals & non-goals

### 2.1 Goals
- Sibling-focused same-window case: words in the pinned field, one write, verified by the editor's own text.
- Every other case byte-for-byte unchanged (app left, window not focused, pin gone, field not editable).
- The refocus is attempted at most once per attempt and never before the pinned window is confirmed focused.

### 2.2 Non-goals
- Never focus a node in a window that is not focused; never move focus across windows or apps (the wait is right there).
- No new setting, no new user-visible sentence (the existing success and fallback announcements stand).
- No change to pinning (`#192`: once, by the session owner).
- Not `ACTION_SET_TEXT` (external) (forbidden by `architecture-rules.md` RULE: insertion-fails-safe-never-silently).

## 2.5 Grounding brief

1. **Producer to consumer.** Pin: `DictationSessionCoordinator.beginSession` through `InsertionGateway.pinTargetForDictation` into `EditorTargetTracker.pinnedTarget` (`TargetToken`: package, windowId, node copy). Insertion: `AccessibilityInsertionRunner` (`INSERTION_TIMEOUT_MS = 2_500L`, `RETRY_INTERVAL_MS = 125L`, `:49-50`) drives `InsertionAttempt.tick` (`:193`); `prepareAndWrite` (`:200`) does `locate() ?: return Tick.Waiting`; `locate` and the writes reach the node only through `ServiceEditor` into `tracker.withPinnedNode` (callers `AccessibilityInsertionRunner.kt:310, 352, 438, 468`). Route: `InsertionRoutePolicy.select(commitEligible)` (`:19`), eligibility `commitIneligibleReason` (`:339-356`). Found with `grep -n "withPinnedNode" app/src/main`.
2. **Existing authority.** None for refocus: `python3 scripts/check-cited-symbols.py <plan>` reported `ACTION_FOCUS` absent from "any configured source root" across 609 source files (whole app, not only the four files read); in the paste files the only `performAction` is `ACTION_PASTE` in `ServiceEditor.paste` (`:453`). New authority proposed, in `EditorTargetTracker` (it owns the pin and the window checks).
3. **Prior decisions.** `architecture-rules.md` RULE: insertion-fails-safe-never-silently (a stale or missing target keeps the words on the clipboard, never inserts into whatever is focused now): this plan writes only into the PINNED node, so the rule's text holds; the device row `aTakeStartedInAAndFocusMovedToBInsertsNowhereAndKeepsTheWordsOnTheClipboard` (`VoicePipelineDeviceTest.kt:~281`) encodes today's outcome and is rewritten, its REVERT text ("write into an unfocused A") being exactly this change. Catalog `frozen-delivery-target`: macOS is `partial` (the AX direct route writes into the captured element; key and menu paste routes write only when the window or element is focused, else the words stay on the clipboard).
4. **Boundaries.** Accessibility service alive (the runner runs inside it); the IME session changes when focus moves and `commitIneligibleReason` does not prove a session NEWER than the refocus (`input started`, `session package`, `focused window`), so COMMIT stays disabled for that attempt; PASTE needs no IME. Stale completion: the refocus belongs to one attempt and one take generation; a later take re-pins.
5. **Premises to prove (chunk 1 spike, before design is final).** (a) `performAction(ACTION_FOCUS)` on the pinned Gmail body node moves input focus (AVD and S26); (b) after it, `withPinnedNode` passes (`isFocused` true) within the 2.5 s deadline; (c) PASTE succeeds on the refocused node (COMMIT is disabled for that attempt, so COMMIT eligibility is not a premise); (d) Chrome's web inputs (measured `FOCUS_INPUT` returns a different node, `AccessibilityInsertionRunner.kt:348-351`) behave. (e) the sibling-detection guard (`findFocus(FOCUS_INPUT)` returns the sibling) holds in Chrome, where the same call is documented to return a different node than the editor (`AccessibilityInsertionRunner.kt:348-351`); (f) after the refocus the refreshed pinned node advertises `ACTION_PASTE` and accepts it with no IME restart. If (a), (e) or (f) fails on an app, that app keeps today's clipboard outcome and the plan records it.

## 3. Design

New `EditorTargetTracker.refocusPinnedNode(): RefocusResult` (proposed) (one enum shared with `EditorWrites.refocusOriginal`, §4), refusing unless ALL hold: a pin exists; `findPinnedWindowRoot` finds the window; `isInFocusedWindow(pinned.windowId)` is true (`:288`, the guard `findPinnedWindowRoot` lacks); `expected.node.refresh()` succeeds and is visible, editable and focusable, matches `matchesPinnedTarget`, and advertises `ACTION_FOCUS` in its action list; the window's `findFocus(FOCUS_INPUT)` returns a DIFFERENT editable node (a sibling holds focus, not "nothing focused"). Then one `performAction(ACTION_FOCUS)`. `EditorWrites` gains `refocusOriginal()` (proposed); `InsertionAttempt.prepareAndWrite` calls it (flag `refocusTried` (proposed)) when `locate()` is null, then returns `Tick.Waiting` so the next tick re-locates.

Revisions from the Codex grounded review (round 1, PROCEED-WITH-REVISIONS):
- **The flag is spent only by an actual focus attempt.** A guard refusal ("not ready": window not yet focused, nothing advertised) leaves `refocusTried` false so a later tick can still recover; an accepted, rejected or throwing `performAction` sets it true. A throw counts as attempted (no loop). Exactly one focus action per attempt.
- **Deadline first.** `expired()` is checked before the refocus (the existing check at `InsertionAttempt.kt:203` does not cover the null-locate path); a blocking action can still overrun, as every other action here can.
- **After a refocus this attempt uses the PASTE route only.** `commitIneligibleReason` proves package, window and node focus but not that the IME session is NEWER than the refocus, so a stale connection to the sibling could accept a `commitText`. `InsertionRoutePolicy.select` is called with `!refocusTried && commitEligible()`; COMMIT returns to use on the next take. PASTE needs no IME (it needs the refreshed pinned node to advertise `ACTION_PASTE`; if not, `REJECTED` and the clipboard line).
- **No claim that eventual success is guaranteed.** One `Tick.Waiting` gives time, not proof of readiness; the outcome set is the existing one: `VERIFIED`, `REJECTED`, `UNVERIFIED`, `NEVER_RETURNED`, plus `SENSITIVE` and `STAGING_FAILED` (`AccessibilityInsertionRunner.kt:222,232,249`); the refocus adds none (§7).

Rejected: putting the focus inside `withPinnedNode` (it also serves read paths `readTarget`/`locateTarget`; reads must not move focus); `ACTION_SET_TEXT` (forbidden); dropping the focused test (would write into an unfocused node, the forbidden shape).

## 3b. Ownership justification

This lives on `EditorTargetTracker` because it already owns the pin, the window roots and `isInFocusedWindow`; the alternative was `ServiceEditor`, but it only writes. The attempt state machine decides WHEN (once, after a miss) so the rig can test it.

## 4. Contract deltas

- `EditorWrites.refocusOriginal()` (proposed) returns a new enum `RefocusResult` (proposed) with four values: `DECLINED` (proposed) (a guard refused before any action: window not focused, nothing advertised, no sibling; NO focus action was sent), `ACCEPTED` (`performAction` returned true), `REJECTED` (it returned false), `THREW` (it threw; focus may have moved). Implementers: `FakeEditor` (`InsertionAttemptTest.kt:20`), the delegate object (`:362`), `ServiceEditor`.
- `InsertionAttempt` gains one boolean, `refocusTried` (proposed): true for ACCEPTED, REJECTED and THREW (every case where an action was sent), false for DECLINED. Route selection is `InsertionRoutePolicy.select(!refocusTried && commitEligible())` at the one call site (`InsertionAttempt.kt:206`), so any actual focus action disables COMMIT for that attempt, including a throw that may have moved focus. `Tick` values unchanged.
- `withPinnedNode` unchanged.

## 5. End-to-end state and lifecycle audit

| Population | Answer |
|---|---|
| Every caller of `withPinnedNode` | `enumerated`: the four at `AccessibilityInsertionRunner.kt:310,352,438,468`; none changes. |
| Every `Tick.Waiting` return in `InsertionAttempt` | `enumerated` (Codex read, `InsertionAttempt.kt`): `:201` locate miss (the ONLY one that gains the refocus call), `:259` commit `SESSION_CHANGED`, `:304` paste target or context changed, `:392` continued judging. Re-run `grep -n "Tick.Waiting"` at build to confirm the four. |
| Every outcome of the refocus step | accepted (flag true, tick Waiting, then PASTE-only route); rejected by `performAction` (flag true: no further focus action, today's locate-and-PASTE-only path continues); throws (flag true, focus may have moved: no further focus action, keep locating and PASTE-only, never COMMIT); refused by a guard (flag false, today's path this tick, may retry next tick); deadline expired before the call (no call). Each has a rig row (§11.2). |
| Take generations | a refocus is scoped to one `InsertionAttempt`; `InsertionAttempt` is per take. |
| Processes | accessibility service process only; `:audio`, `:asr`, `:polish` untouched. |
| Focus side effects | the pinned field gains focus and the IME may switch input connection; the user's cursor moves once. |
| Cancel / process death | an attempt abandoned before the refocus does nothing; after it, the field simply holds focus. |

## 6. Downstream consumer matrix

| Contract delta | Consumer | Current | Required | Code change? | Verified by |
|---|---|---|---|---|---|
| `refocusOriginal` | `ServiceEditor` | n/a | calls tracker | yes | rig + emulator |
| `refocusOriginal` | `FakeEditor`, delegate | n/a | returns scripted | yes (test) | rig rows |
| `INSERTION_TIMEOUT_MS` budget | `AccessibilityInsertionRunner` | 2.5 s wait | refocus inside the same 2.5 s | no | rig: deadline respected |
| outcome lines | `InsertionOutcomeLineTest` | `NEVER_RETURNED` format | unchanged | no | existing tests |
| on-device row | `VoicePipelineDeviceTest` two-editor row | asserts clipboard | asserts body holds words | yes | device run |

## 7. Failure-mode × caller table

| Failure | Origin | Caller | User sees | Persisted | Retry |
|---|---|---|---|---|---|
| `DECLINED` (window not focused, nothing advertised, no sibling) | tracker guard | attempt | today's wait then clipboard line | copy-only row | a later tick within the deadline may try again (no action was sent) |
| `REJECTED` (`performAction` returned false) | app refuses focus | attempt | no second focus action; locating continues with PASTE-only, which normally finds the sibling still focused and ends on the clipboard line as today | per the PASTE outcome | none for focus (flag spent), PASTE within the deadline |
| `THREW` | framework or app | attempt | focus may have moved, so later ticks still locate the pinned node and try PASTE; if that fails, the clipboard line as today | per the PASTE outcome | no further focus action (flag spent), PASTE within the deadline |
| `ACCEPTED` | app | next ticks | a PASTE attempt on the pinned node (COMMIT is disabled, so the IME is not needed); delivery is judged by the editor's own text, so the outcome is `VERIFIED`, `UNVERIFIED` or `REJECTED`, never assumed | per the PASTE outcome | within the deadline |
| user left the app | window not focused | guard | unchanged | unchanged | none |
| refocus accepted but the field refuses `ACTION_PASTE` | app | attempt | `REJECTED`, clipboard line | copy-only | none |
| refocus accepted, write not confirmed by the editor's text | app or IME | judge | `UNVERIFIED` (existing handling) | as today | none |
| deadline reached before or after the refocus | clock | runner | `NEVER_RETURNED` if nothing was written, else the existing expired-after-write handling (`AccessibilityInsertionRunner.kt:249`) | copy-only or as today | none |
| the pin's field is sensitive, or staging the clip fails | existing | attempt | `SENSITIVE` or `STAGING_FAILED` as today (`:222,232`) | as today | none |

## 8. Caller-visible signals audit

Outcome line fields (`route`, `written`, `evidence`, `outcome`, `attempts`, `ms`): `attempts` rises by the refocus tick; a new counts-only breadcrumb `refocus=declined|accepted|rejected|threw` (proposed, the `RefocusResult` name) joins the local take log, never text. `not present in this change`: the History row shape, telemetry allowlist, Room schema.

## 9. Fallback source-of-truth audit

Every failure branch keeps today's fallback unchanged: the outcome set in §7 is the existing one, and the words for the clipboard line come from the transcript the take already holds, never from the pin (the pin only names the target). No new fallback expression, source or consumer is introduced.

## 10. File-by-file changes

`paste/EditorTargetTracker.kt` (`refocusPinnedNode`), `paste/AccessibilityInsertionRunner.kt` (`ServiceEditor.refocusOriginal`), `paste/InsertionAttempt.kt` (`EditorWrites.refocusOriginal`, one call site, `refocusTried`), tests: `InsertionAttemptTest.kt` (FakeEditor and delegate), `VoicePipelineDeviceTest.kt` (two-editor row rewritten), a new `RefocusGuardTest` (proposed); knowledge: `architecture.md:34-38`, `current-state.md`, `device-testing.md`. `LipsBubbleWiringTest.kt:188,230` read source text on `isFocused`; re-check before editing.

## 11. Testing

1. **Class:** the rig rows are product outcomes ("when this fails, the words go to the clipboard again"); the source-shape guard is a drift guard.
2. **Revert that turns it red:** deleting the `refocusOriginal()` call turns the sibling-focused rig row red and flips the emulator scene back to the clipboard outcome; performed at build.
3. **Not tested:** other Samsung or OEM apps beyond Gmail, Chrome, Messages; the ordering of an IME switch on other keyboards.

### 11.1 Hardware UAT

- **Subsystem:** heart path.
- **Recipe:** emulator scene of the `#192` reproduction first (Gmail compose, `device-testing.md` FACT: the-play-store-emulator-for-gmail-and-chatgpt), then the S26: Gmail body, tap Subject, stop; Messages; Chrome form. Silent audio injection for the take (`scripts/uat/silent-audio/`).
- **Expected:** editor's own text holds the words in the pinned field; the other field is empty; `route=PASTE outcome=VERIFIED` (COMMIT is disabled after a refocus); no `NEVER_RETURNED`.
- **Restore:** none beyond `restore()`.

### 11.2 Other obligations

| Test | Class | Proves | Revert that turns it red |
|---|---|---|---|
| sibling-focused rig row | product outcome | one refocus then one write | remove the call |
| refused-focus rig row | product outcome | falls through unchanged | remove the guard |
| window-not-focused rig row | product outcome | no focus action | remove `isInFocusedWindow` guard |
| once-per-attempt row | drift guard | at most one refocus action | remove `refocusTried` |
| guard-refusal-keeps-the-flag row | product outcome | a not-ready refusal does not spend the one action | set the flag on refusal |
| rejected-and-throwing rows | product outcome | both spend the flag, neither loops | leave the flag false on a throw |
| paste-only-after-refocus row | product outcome | the attempt never commits through a possibly stale session after a refocus | drop `!refocusTried &&` from the `select` call |
| deadline-before-refocus row | product outcome | no focus call once the deadline passed | remove the `expired()` check |

## 12. Blast radius & rollback

Touches only `:app` `paste/` and tests; `:audio`, `:asr`, `:polish`, models, Room untouched. Revert: one `git revert` of the squash commit; the device row returns to its current assertion.

## 13. Ship criteria specific to THIS change

- [ ] In Gmail, words spoken into the body, Subject tapped while speaking, stop: the body holds the words and Subject is empty (emulator and S26).
- [ ] Leaving the app mid-take still ends on the clipboard with the announcement.

## 14. Open questions

- Does `ACTION_FOCUS` move input focus in Gmail and in Chrome inputs? (premise 5a-d; the spike answers it before any other work.)
- Does the cursor land where the user left it in the body, or at its start? Measured in the spike; if at the start, the plan records it as a known cost.

## 15. Related

`#201`, `#192`, `#141`, `architecture-rules.md` RULE: insertion-fails-safe-never-silently, catalog `frozen-delivery-target`, `docs/audits/2026-09-21-192-emulator-pass/`.
