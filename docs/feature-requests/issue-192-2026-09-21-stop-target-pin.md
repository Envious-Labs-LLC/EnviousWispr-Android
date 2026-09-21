# Issue #192 — Stopping from the side button can send a dictation to the wrong text field — 2026-09-21

GitHub issue: `#192`. Tier: LARGE (the insertion path and the session owner's contract, `workflow-process.md`
RULE: tier-routing), although the diff deletes two calls and adds guards. Status: DRAFT for the coverage round.

Consolidation: this plan is one document; §2.5 carries the reproduction and the trace once and §§3 to 11 point back at it.

**Build order.** Grounded against `main` `6a0f5c4` (after #191), the base of worktree `issue-192-stop-target-pin`.

## Preface — Lane + Hardware UAT declaration

**Lane:** Code — `app/src/main/**` (two deletions), `app/src/test/**` (coordinator rows and a shape row),
`scripts/uat/wispr_eyes.py` (one harness call). `mixed_pr: true`: `Code` (`unit-tests.xml`, `codex-review.md`,
`visibility.txt`, `hardware-uat.json`: the heart path) and `Docs/dev-tooling` (`cited-symbols`, conditional).

**PAR rows closed:** none named; the outcome contract is `architecture-rules.md` RULE:
insertion-fails-safe-never-silently ("return to the tracked editor").

**Hardware UAT:** Y. The heart's insertion stage. On the emulator through wispr-eyes (the founder's phone is his
today): a person starts a dictation in one field, moves to another field while still speaking, presses the side
button to stop, and the words land in the FIRST field; the second field stays empty. Then the same with a
second start attempt while a take is running: refused, and the running take still lands in its own field.

## Preface — User Rubric

1. **Who.** Diana Foster, senior PM, on her S26 in Gmail: she started a reply by side button, and while
   still talking she tapped the Subject line to fix a typo, then pressed the side button to stop.
2. **Why.** "I fixed the subject while I was talking; the words should go where I started, obviously."
3. **How.** Reactive: the side button, twice, as always. Already in the app.
4. **Apps.** Gmail, Slack, Notion, Chrome forms: any app with two fields close together.
5. **Input.** "Hi Sam, quick one on the launch date", "can we push standup to ten", "looping in Priya on
   the audit", "the deck is in the shared folder", "thanks, talk tomorrow".
6. **Success.** She notices nothing: the reply body has her sentence, the subject has her fix.
7. **Wrong-not-broken.** Her sentence lands in the Subject line and she has to cut and paste it back; she
   stops trusting the side button in any app with more than one field.
8. **Power user.** She learns never to touch the screen while recording, which is the opposite of
   hands-free.
9. **Control.** None wanted: the take belongs to where it began. The ladder has one rung, "the field I
   started in", and a visible fallback (clipboard with a line) when that field is gone.

**Cross-persona.** Priya (Slack thread plus a search box), Marcus (a document plus a comment), Aaron
(one hand; a field switch by keyboard while talking is his normal), Meera (iMessage plus the contact
search), Elena, Frank: all want the same rung; none wants the words to follow their finger. No tension.

## 0. TL;DR

- Delete the two pre-command pins: `ui/VoiceInputActivity.kt:76` (`pinTargetForDictation()` before the
  START or TOGGLE command) and `paste/PasteAccessibilityService.kt:448` (`pinTarget()` inside
  `startDictationFromBubble`). The owner already pins exactly once, in `beginSession` after admission
  (`ui/DictationSessionCoordinator.kt:351`); nothing else may touch the pin.
- Guards: two coordinator rows on the JVM rig (a TOGGLE that stops, a STOP, a CANCEL, and a busy START
  each leave the pin count at one; an admitted take pins exactly once), one shape row (the two files
  contain no pin call), and the reproduction scenario on the emulator run twice (before: wrong field;
  after: right field) plus the busy-start scenario.
- One harness call, `toggle_dictation()`, the bare side-button intent during a live take.

## 1. Problem

The side-button launcher pins the focused editor BEFORE the owner decides whether its toggle means start or
stop (`VoiceInputActivity.kt:70-77`). `pinTarget()` (`PasteAccessibilityService.kt:788-816`) keeps the
remembered field only while it still has focus; when the user has moved to another field it drops the
remembered one and adopts the focused one. So a take started in field A, with B focused at the second
press, is delivered to B. Reproduced (§2.5.5).

## 2. Goals & non-goals

### 2.1 Goals
- A take's target is acquired once, by the owner, after it admits the take, and no STOP, CANCEL, TOGGLE
  or refused START mutates it.
- The reproduction lands in A after the change and B is unchanged.

### 2.2 Non-goals
- No change to `pinTarget()`'s own rules (what counts as a safe focused editor), to the insertion routes,
  to the announcement table, or to the bubble's ledger. The stale-target fallback (clipboard with a line)
  is already the design and is not touched.

## 2.5 Grounding brief

### 1. Trace producer → owner → consumer
The pin is `PasteAccessibilityService.pinnedTarget`, written only by `pinTarget()` (`:788`), which is
reached from three call sites (`grep -rn "pinTargetForDictation\|pinTarget()" app/src/main/java`):
- `ui/VoiceInputActivity.kt:76`, on START and TOGGLE, before `DictationSessionService.sendCommand` (`:79`);
  the answer is discarded (the comment at `:71-75` says the owner's later pin is the judged one).
- `paste/PasteAccessibilityService.kt:448`, `startDictationFromBubble`, before `sendCommand(ACTION_START)`;
  the answer is discarded.
- `ui/DictationSessionCoordinator.kt:351`, `beginSession`, AFTER admission (`:279-297` for the bubble's
  ledger decisions; the plain command path enters `beginSession` from the TOGGLE/START arms at `:244-262`),
  through `InsertionGateway.pinTargetForDictation` (`ui/InsertionGateway.kt:13,24`), and its answer is
  `targetPinAtStart`, judged at `:1154` by `InsertionJudgement.handoffToJudge`.
Consumers of the pin: `PasteAccessibilityService.pasteWhenTargetReturns` (the insertion) and
`pinnedFieldId()` (the surface's target name). Both read `pinnedTarget` at insertion time.

### 2. Existing authority
The owner's single pin at `:351` IS the authority; the two launcher pins are the duplicates the audit
named. No other component writes `pinnedTarget` (`grep -n "pinnedTarget =" PasteAccessibilityService.kt`:
`:808` in `pinTarget`, and the clears in `clearPinnedTarget`/`releasePinnedTarget`).

### 3. Prior attempts and live direction
`VoiceInputActivity.kt:71-75` documents the launcher pin as deliberate ("this window is closing and the
user's editor is still focused") from before #186, when the service pinned later on its own thread. Since
#186 the coordinator pins in `beginSession` on the main thread and judges every announcement against
that value, so the launcher pin is a second writer of one fact (`architecture-rules.md` RULE:
own-state-locally). The bubble's direct route (`:436-450`) was added for #135 so Chrome keeps its keyboard;
its pin was copied from the launcher. Catalog: macOS pins at the owner (`decision` rows on
`multi-route-paste`; no Android decision on pre-command pinning).

### 4. Boundaries a naive design misses
- **Focus at the owner's pin.** The launcher is a 1x1 `FLAG_NOT_FOCUSABLE` window (`:88-96`), so the user's
  editor keeps input focus while it is up; the owner's pin at `:351` runs after the foreground start,
  tens of milliseconds later, and on the harness path (`open_recorder` = the launcher) every take today
  already gets its judged pin from `:351`. Deleting the launcher pin changes the START path only by
  removing the earlier write; the emulator run proves the START pin still lands (`Pinned original editor`
  once, `route=COMMIT`).
- **The bubble's direct start** runs in the accessibility service's own process, the DEFAULT process, the
  same one the coordinator pins from; deleting `:448` leaves the owner's pin as the only one on that path
  too.
- **A busy START** (`RefusedBusy`, `:286`) must not pin: today the launcher and bubble pins run before the
  refusal, so a second start attempt while recording mutates the running take's target; after the change
  nothing runs before the refusal.
- **STOP/CANCEL** never reach `beginSession`, so they never pin after the change.

### 5. High-risk premises, with evidence
- **The bug reproduces on the emulator** (2026-09-21 11:45, debug build of `6a0f5c4`, `scratchpad/
  emu_wrong_field.py` through wispr-eyes): Gmail compose body focused, `open_recorder`, 2.6 s injected
  speech, `focus_field("Subject")`, `toggle_dictation()`. `PasteService` logged `Pinned original editor`
  at 11:45:53 (start) and again at 11:46:06 (the toggle), then `route=COMMIT … outcome=VERIFIED`; the tree
  afterwards shows the Subject field (focused) holding "These words belong to the first field." and the
  compose body empty (its hint showing).
- **The owner's pin alone is enough for START**: the same run's first pin came from the launcher; whether
  the owner's pin at `:351` succeeds without it is proven by the same scenario after the change (expected:
  one `Pinned original editor` line, insertion into the body).
- Codex problem-only consult: the coverage round's axis A.

## 3. Design

1. Delete lines `:70-77` of `ui/VoiceInputActivity.kt` (the `if` and its comment) and `:448` of
   `paste/PasteAccessibilityService.kt` (`pinTarget()` in `startDictationFromBubble`); update that
   function's KDoc (`:437-443`) to say the owner pins after admission. Alternative rejected: carrying the
   launcher's answer into the command so the owner reuses it, which keeps two writers.
2. Guards, §11.2.
3. Harness: `toggle_dictation()` in `scripts/uat/wispr_eyes.py`, allowed only while `recording()`.

## 3b. Ownership justification
The pin stays on `PasteAccessibilityService` (it owns the node) and its ONE writer becomes the owner's
`beginSession`; the alternative, moving the pin into the coordinator, would move the accessibility node
across a component boundary for no gain.

## 4. Contract deltas
- `VoiceInputActivity` and `startDictationFromBubble`: pure command senders; no side effect on the pin.
- The owner: unchanged contract, now the only writer.

## 5. State and lifecycle audit
| Population | Members |
|---|---|
| Writers of `pinnedTarget` | `pinTarget()` `:808` (from the three call sites above; two deleted) and the clears (`clearPinnedTarget`, `releasePinnedTarget`) |
| Commands that reach `beginSession` | TOGGLE when IDLE (`:257`), START admitted (`:292`); neither STOP nor CANCEL nor a refused START |
| Commands that today mutate the pin before the owner sees them | TOGGLE and START through the launcher (`:70-77`), START through the bubble (`:448`) |

## 6. Consumer matrix
| Contract delta | Consumer | Current | Required | Change? | Verified by |
|---|---|---|---|---|---|
| no launcher pin | the owner's `beginSession` | a valid pin already exists on START | pins from `lastTarget`/discovery | none | the emulator START run; rig row `aStoppingToggleNeverRepinsTheTarget` |
| no launcher pin on TOGGLE-as-stop | the running take's target | replaced by the focused field | untouched | the deletion | rig row; the emulator reproduction after |
| no bubble pin | the owner on a bubble start | a valid pin already exists | pins after admission | none | code review; the bubble path is not stageable on the emulator without the pill tap (declared) |

## 7. Failure-mode × caller table
| Failure mode | Origin | Caller | What the user sees | Persisted state | Retry |
|---|---|---|---|---|---|
| A is gone at insertion | the user closed A | insertion | the existing clipboard line | row `insertion_interrupted`-class as today | none |
| the owner's START pin finds no editor | focus not on an editor at start | `beginSession` | today's `NO_PINNED_TARGET` behaviour | as today | none |

## 8. Caller-visible signals
`targetPinAtStart` (unchanged meaning, now the only pin of a take). Nothing else.

## 9. Fallback source-of-truth audit
Unchanged: `InsertionJudgement.handoffToJudge` with `targetPinAtStart` and the clipboard fallback.

## 10. File-by-file changes
- `app/src/main/java/com/envi/wispr/ui/VoiceInputActivity.kt`: delete `:70-77`.
- `app/src/main/java/com/envi/wispr/paste/PasteAccessibilityService.kt`: delete `:448`; KDoc.
- `app/src/test/java/com/envi/wispr/ui/DictationSessionCoordinatorTest.kt`: two rows; `DictationSessionRig.FakeInsertion` counts pins.
- `app/src/test/java/com/envi/wispr/ui/SessionOwnerShapeTest.kt`: one row.
- `scripts/uat/wispr_eyes.py`: `toggle_dictation()`; `scripts/uat/test_wispr_eyes.py` unchanged (no pure logic).
- `docs/audits/2026-09-21-192-revert-receipts.txt`, `docs/audits/2026-09-21-192-emulator-pass/` (the before and after logs).

## 11. Testing
1. Classes: the two rig rows are Product Outcome (when they fail, the user's words land in the wrong
   field); the shape row is a Drift Guard; the harness call has no logic of its own.
2. Reverts: §11.2.
3. Not tested: the bubble's direct start on the emulator (needs the pill tap; declared NOT RUN).

### 11.1 Hardware UAT spec
- Emulator, wispr-eyes, debug build of the final commit: (a) the reproduction scenario, expected body holds the
  sentence and Subject stays empty, `Pinned original editor` logged ONCE; (b) the busy-start scenario: start in
  the body, inject, focus Subject, `toggle_dictation(start=True)` (the launcher's `--ez start true`), expect a
  refusal line and, after a real stop, the body holding the sentence; (c) three ordinary takes into Gmail
  (the START pin still lands); (d) `restore()`. Founder's phone: NOT RUN (his instruction); build delivered
  through Play for his ordinary use, and the issue's "two real apps" phone pass is listed for him.

### 11.2 Other obligations
| Test | Class | Proves | Revert that turns it red |
|---|---|---|---|
| `DictationSessionCoordinatorTest.aStoppingToggleNeverRepinsTheTarget` (proposed) | Product Outcome | start (pin count 1), then TOGGLE while RECORDING, STOP and CANCEL paths: the fake gateway's pin count stays 1 | make the coordinator pin on every command |
| `DictationSessionCoordinatorTest.aRefusedBusyStartNeverPinsTheTarget` (proposed) | Product Outcome | a START while RECORDING is refused and the pin count stays 1 | pin before admission |
| `SessionOwnerShapeTest.onlyTheOwnerPinsTheTarget` (proposed) | Drift Guard | `VoiceInputActivity.kt` and `startDictationFromBubble` contain no `pinTarget`; the coordinator calls `pinTargetForDictation` exactly once, inside `beginSession` | restore either deleted line |
| the emulator scenario | Product Outcome | body holds, Subject empty, one pin line | the build before the change (recorded) |

## 12. Blast radius & rollback
Two deleted lines on the START path of every entry point; rollback is one revert. The risk is a START whose
owner pin misses where the launcher's used to hit; the emulator START runs and the START-path rows watch it.

## 13. Ship criteria specific to THIS change
- The reproduction inverted on the emulator with one `Pinned original editor` line.
- The three rows green; the suite count reported; the Code lane's `visibility` obligation satisfied.

## 14. Open questions
None.

## 15. Related
#186 (the owner), #135 (the bubble's direct route), `docs/audits/2026-09-20-senior-audit.md` REF-01.
