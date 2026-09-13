# Issue #141 — One route, one write: commit or paste, judged without the caret — 2026-09-13

GitHub issue: `#141`. Tier: LARGE (the insertion path). Status: APPROVED (Gate 2, founder 2026-09-13, with the
Android 13 floor; round 4 PIVOT applied: one write per dictation, no tier advance, no empty-field set-text).

## Preface — Lane + Hardware UAT declaration

**Lane:** Code — `app/src/main/java/com/envi/wispr/paste/**`, `app/src/main/java/com/envi/wispr/insertion/**`,
`app/src/test/**`, `app/build.gradle.kts` (`minSdk` 30 → 33). `mixed_pr: false`.

**PAR rows closed:** none closed; PAR-076, PAR-077, PAR-079, PAR-080 and PAR-081 are RE-EVIDENCED. PAR-077's
text ("`ACTION_PASTE`, selection-preserving `ACTION_SET_TEXT`, and copy-only fallbacks") is updated in
`docs/enviouswispr-android-parity-spec.md` at ship time: the selection-preserving `ACTION_SET_TEXT` route
is REMOVED and the commit route is added. Evidence per row is the hardware UAT in §11.1.

**Hardware UAT:** Y. The founder opens a Gmail draft that already has two paragraphs with a blank line
between them, places the caret at the end of the first paragraph, double-presses the side button and says
"and I will send the deck tomorrow". The sentence appears once at the caret, the blank line and every
space in the draft are still there, no "Copied" line appears, and a second dictation ten seconds later
lands the same way. Then the same in Messages and in a Chrome web form.

## Preface — User Rubric

1. **Who.** Diana Foster, senior PM, replying to a thread in Gmail on her phone between meetings. Thirty
   seconds ago she was reading the thread; thirty seconds from now she wants the reply sent without
   touching the keyboard.
2. **Why.** "I said it, it should just be in the box. If I have to long-press and paste, I could have
   typed it."
3. **Invoke.** Side button or the lips bubble, reactive, already in the right app, caret already placed.
4. **Apps.** Gmail, Slack, Google Docs, Chrome forms, Messages, WhatsApp (Diana); Slack, GitHub, Notion
   (Priya); Notes and a long Docs draft (Marcus); iMessage-class chat, here Messages and WhatsApp (Meera).
5. **Natural input.** "and I will send the deck tomorrow"; "sounds good, let's do 3pm"; "can you loop in
   Raj on this one"; "no I think we should hold until the numbers are in"; "thanks, talk soon".
6. **Success.** She notices nothing. The words are in the box where the caret was and she taps Send.
7. **Wrong-not-broken.** The words land but the draft's blank line is gone, or a line says "Copied"
   when the words are visibly there. She stops trusting the line, then the app.
8. **Power-user hack.** Long-press, Paste, and delete the duplicate if the app pasted too.
9. **Control ladder.** Off (auto-copy only, already a setting); automatic insertion (default). No new
   setting: which route is used is not a user decision.

### Cross-persona check

Priya and Aaron care most that Slack and GitHub web forms never get a duplicate, so "one write per
dictation, ever" is theirs. Marcus cares that a long Docs draft is never rewritten, so "no whole-field
write at all" is his. Elena and Frank want no new permission prompt: the API 33 input-method flag is a
service flag inside the accessibility permission they already granted
(`AccessibilityServiceInfo.FLAG_INPUT_METHOD_EDITOR` (external),
https://developer.android.com/reference/android/accessibilityservice/AccessibilityServiceInfo#FLAG_INPUT_METHOD_EDITOR),
so nothing new is asked. Meera wants back-to-back to work, which is #132's territory and stays out of
scope here. No conflict between them.

---

## 0. TL;DR

Dictations that land are reported as "Copied too, if it did not arrive" because the service rewrites the
whole field with `ACTION_SET_TEXT` and then demands an exact caret that Gmail never reports. Replace the
write and the judge. Exactly ONE route is selected before any write and exactly ONE content write is
sent per dictation: on API 33+ the accessibility input-method commit when the input session provably
belongs to the pinned node, otherwise the editor's own paste. The floor rises to Android 13 (founder
2026-09-13), so there is no lower path. The
whole-field `ACTION_SET_TEXT` route is deleted. Success is fresh evidence that the dictated text appeared
once, unaltered, in the changed region, never the caret. After the one write the loop only judges until
the deadline, then says the existing calm line. Three chunks, each leaving the phone usable; chunk 1
alone is expected to fix the founder's phone (NOT VERIFIED until the §11.1 phone pass). Proof: the real
Gmail 0/0 fixture is green in the unit suite, and the founder's Gmail dictation keeps its blank line and
shows no line.

## 1. Problem

Founder, 2026-09-12: "it said copied pretty much every time even when it successfully pastes". Measured
2026-08-30 on the S26 (`device-testing.md` FACT: what-gmail-actually-does-to-insertion-2026-08-30): 21
attempts of one dictation logged `action=SET_TEXT textMatched=true actualLen=126 expectedLen=126
caret=0/0 expectedCaret=34`. The field held the intended text; only the caret clause of
`AccessibilityInsertionRules.isVerified` failed, because Gmail reports `0/0` and ignores
`ACTION_SET_SELECTION`. After `INSERTION_TIMEOUT_MS` the service reported `ServiceFallbackReason.UNVERIFIED`
and the toast read "Copied too, if it did not arrive. Press and hold, then tap Paste." The same rewrite
flattened the draft's whitespace, because `AccessibilityNodeInfo.text` is a lossy read of the editor, and
because Gmail's pre-write `0/0` was trusted as the splice point, the rewrite put the words at the START.

Both shipping competitors avoid this (`docs/internal/competitor-insertion-study.md`, local-only, read from
their decompiled APKs on the founder's phone 2026-09-12): neither checks the caret, and neither rewrites a
non-empty Gmail field.

## 2. Goals & non-goals

### 2.1 Goals

- G1. A dictation whose words appear once in the field, unaltered by the editor, is reported as
  inserted, whatever the caret reads and whatever caret the editor reported BEFORE the write.
  Verifiable: the real Gmail `0/0` fixture in `AccessibilityInsertionRulesTest` is green; the founder's
  Gmail dictation shows no line.
- G2. The service never writes the whole field. Verifiable: `ACTION_SET_TEXT` does not appear in
  `PasteAccessibilityService.kt` after chunk 1 (`grep -c ACTION_SET_TEXT` = 0).
- G3. On API 33+ the selected route is `AccessibilityInputConnection.commitText` (external) whenever the
  input session provably belongs to the pinned node, so no clipboard write happens on that path.
  Verifiable: the outcome log line reads `route=COMMIT` on the emulator's Android 16 Gmail.
- G4. At most one content write per dictation, whatever any call returns. Verifiable: `InsertionAttempt`
  (proposed) unit tests count writes under `false`, `true`, `void` and thrown returns and never see two.
- G5. The app declares Android 13 as its floor. Verifiable: `minSdk = 33` in `app/build.gradle.kts`, the
  `PasteServiceProcessManifestTest`-style manifest test asserts it, and every `Build.VERSION.SDK_INT >= 33`
  guard the plan adds is a compile-time constant true.
- G6. One content-free log line per insertion attempt names phone, API, target package, the route,
  evidence source and outcome. Verifiable: grep of the line on the emulator; the builder's signature has
  no payload parameter.

### 2.2 Non-goals

- Back-to-back dictation while an insertion is pending (#132) is not changed here.
- The sensitive-field guard (#11) is not changed; `isSensitive` stays as it is.
- Keeping Android 11 and 12. Founder decision 2026-09-13: the floor is Android 13. Statcounter August
  2026 puts Android 11+12 at 18% India, 26% China, 16% Europe, 15% US (round 1 §D), but a phone still on
  those versions in 2026 stopped receiving updates years ago and is a budget or older mid-range phone;
  the 2020 and 2021 flagships that could run a 670 MB speech model comfortably were all updated past 13.
  That is reasoning from update policy, NOT VERIFIED by a device census. Consequence: the dual path, the
  API 30 and 32 emulators, and the API 30 class-loading premise are all deleted.
- An empty-field `ACTION_SET_TEXT` route for editors that refuse paste. It was the third generation of
  the "dispatched but treated as unavailable" class across rounds 2 to 4, and the pre-committed
  consequence deleted it. It may return only with phone evidence that a real editor refuses
  `ACTION_PASTE`, as its own plan.
- A per-app trust list like Wispr Flow's 37 packages. An unreadable field after the write is
  `UNREADABLE` with the existing hedged line; a list is added only after measured phone evidence.
- An own IME. It requires the user to switch keyboards, which the product forbids (`CLAUDE.md`
  Compatibility).
- Wispr Flow's session "nudge" (`ACTION_FOCUS` (external) / `ACTION_CLICK` (external) to restart an
  input session). An ineligible input session means the paste route. Revisit with phone evidence.
- A new insertion-owned recovery store (round 1 §C asked for one). History already holds the row before
  insertion starts (`DictationSessionService.kt:1014-1030`, `HistoryPublicationPolicy.route`) and
  `InsertionHandoff.HISTORY_NOT_DURABLE` already diverts to the clipboard when it does not. A second store
  is a second answer to one question.
- Changing any user-facing sentence. `InsertionOutcomeMessages.kt` stays word for word; round 1's
  "Inserted" line on success is rejected because silent success is the product contract (macOS delivery
  pill only on fallback, `FallbackAnnouncement` doc).

## 2.5 Grounding brief — MANDATORY before §3

### 1. Trace producer → owner → consumer, end to end

All in the default process; `PasteAccessibilityService` has no `android:process`
(`app/src/main/AndroidManifest.xml:108-118`).

1. **Pin at dictation start.** `DictationSessionService.kt:449` calls
   `PasteAccessibilityService.pinTargetForDictation()` (`PasteAccessibilityService.kt:169`), which hops to
   the service's main thread through `MainThreadHandoff` (`:663`) and runs `pinTarget()` (`:571`): the
   focused editable node is copied with `AccessibilityNodeInfo.obtain` into `pinnedTarget: TargetToken`
   (package, window id, class name, view id). Result: `DictationTargetPin`.
2. **Final text.** After ASR, cleanup and polish, `DictationSessionService.kt:1021-1030` builds the
   handoff: `InsertionJudgement.handoffToJudge(startPin, PasteAccessibilityService.pasteWhenTargetReturns(
   persistedId, finalText, policy = sessionPreferences.clipboard))`. `pasteWhenTargetReturns`
   (`PasteAccessibilityService.kt:147`) hops to main and calls `requestInsertion` (`:519`), which refuses
   `EMPTY_TEXT`, `INSERTION_ALREADY_PENDING`, `NO_PINNED_TARGET`, else creates `PendingInsertion` with
   `deadlineMs = now + INSERTION_TIMEOUT_MS` (`:553`, 2 500 ms) and calls `tryPendingInsertion()`.
3. **Dispatch loop.** `tryPendingInsertion` (`:665`) → `performInsertion` (`:731`): re-find the pinned
   window root (`findPinnedWindowRoot`, `:775`), refresh the pinned node or re-acquire the focused node
   with the same identity (`matchesPinnedTarget`, `:799`; then framework equality at `:752`). If
   `pending.verification != null` it only judges (`verificationResult`, `:994`) and NEVER re-dispatches;
   else `insertIntoNode` (`:806`). A caught exception anywhere in the attempt returns `RETRY` (`:765-767`),
   which today re-enters `insertIntoNode` on the next tick if `verification` was never set.
4. **Write.** `insertIntoNode`: read `observableEditorText` + `normalizedSelection`
   (`AccessibilityInsertionRules.kt:27-40`); a null selection falls to `pasteIntoNode` (`:891`); else
   compose the smart payload (`InsertionText.smartPayloadPlan`, `InsertionText.kt:22`), merge the WHOLE
   field (`InsertionText.mergeAtSelection`, `:77`), re-read to guard against a stale snapshot,
   `performAction(ACTION_SET_TEXT)` (`:868`), `ACTION_SET_SELECTION` (`:878`), then set
   `pending.verification` with `expectedCaret` (`:884`) and call `verifyAcceptedAction` (`:969`). A refused
   `SET_TEXT` falls to `pasteIntoNode`, which writes the clipboard (`writeTranscriptClipboard`, `:1097`,
   token + fingerprint ownership), re-reads, `performAction(ACTION_PASTE)` (`:951`), sets verification
   (`:959`).
5. **Judge.** `verificationResult` (`:994`) calls `AccessibilityInsertionRules.isVerified`
   (`AccessibilityInsertionRules.kt:42-73`): exact text AND exact caret for `SET_TEXT`; exact text AND
   caret, or `isSingleInsertion` with caret, for `PASTE`. A miss is recorded in shapes
   (`pending.lastVerifyMiss`) and returns `RETRY`; `tryPendingInsertion` retries every 125 ms until the
   deadline, then `recordAndAnnounce(UNVERIFIED | TARGET_NEVER_RETURNED)` (`:1152`).
6. **Consumers of the outcome.** (a) `finalizeInsertion` (`:1115`) → `TranscriptRepository
   .finalizeInsertionOutcome` → `TranscriptDao` (`TranscriptDao.kt:46-80`) writes `status` and
   `insertionResult` (vocabulary in `InsertionResults.kt`); History renders
   `InsertionOutcomeMessages.historyStatusLine`, which is empty for both terminal statuses. (b) The
   toast from `FallbackAnnouncement.serviceFallbackAnnouncement` (`InsertionOutcomeMessages.kt:76-92`).
   (c) The haptic (`performResultHaptic`, `:1174`). (d) Clipboard restore on success
   (`restorePreviousClipboardIfSafe`, `:1048`) or keep on failure (`keepTranscriptOnClipboard`, `:1075`).
7. **The path that never reaches the service.** `DictationSessionService.kt:1032-1060`: a handoff other
   than `SCHEDULED` copies to the clipboard (or History only) and speaks through
   `FallbackAnnouncement.fallbackAnnouncement` (`:1141`). Unchanged by this plan.

Commands: `grep -n "fun \|companion object" PasteAccessibilityService.kt`, `grep -n
"pasteWhenTargetReturns\|pinTargetForDictation\|releasePinnedTarget" DictationSessionService.kt`,
`grep -rn "insertionResult\|STATUS_INSERTION_INTERRUPTED" app/src/main/java/com/envi/wispr/history`.

### 2. Find the existing authority before proposing one

- **Judging an accepted write:** `verificationResult` is the single judge by design (comment at
  `PasteAccessibilityService.kt:981-992`); `AccessibilityInsertionRules.isVerified` is its pure rule.
  Both stay the authority; the rule changes.
- **Which write runs:** no pure owner exists; it is control flow inside `insertIntoNode`. Grep for
  `ACTION_PASTE|ACTION_SET_TEXT|performAction\(` in `app/src/main`: only `PasteAccessibilityService.kt`
  (`:868`, `:878`, `:951`) and `RecordingAccessibilityOverlay` for its own buttons. `new authority
  proposed`: `InsertionRoutePolicy` (proposed), a pure object in `paste/`, and `InsertionAttempt`
  (proposed), the one-write loop over a port.
- **Input method on an accessibility service:** grep `InputMethod|flagInputMethodEditor|
  FLAG_INPUT_METHOD_EDITOR` across `app/src`: no hits. `new authority proposed`:
  `EditorInputSession` (proposed), the `android.accessibilityservice.InputMethod` (external) subclass.
- **Outcome vocabulary:** `InsertionResults.kt` and `ServiceFallbackReason.kt` are exhaustive `when`
  owners; the `UNVERIFIED` member already means "dispatched, could not confirm". No new member needed.
- **Content-free diagnostics:** `pending.lastVerifyMiss` shape line (`:1010-1018`) and
  `kotlin-patterns.md` RULE: no-content-in-diagnostics are the precedent; the new outcome line extends
  that shape.

### 3. Read prior attempts and live direction

Posted as Gate 0 on #141. Binding: the 2026-08-30 FACT's prohibition on relaxing the caret check while the
whole-field rewrite stays (this plan deletes the rewrite, which is the condition that prohibition names);
`FallbackAnnouncement`'s one-calm-line contract from #16 (kept); the catalog's `smart-insertion` Android
row (kept, payload only). No `decision` row constrains the Android order; macOS AX-direct writes a range,
not the whole field, so the macOS order is not evidence for `SET_TEXT` first.

### 4. Name the lifecycle, trust and process boundaries a naive design would miss

- **Service alive vs enabled-but-crashed:** unchanged; `instance`/`boundState` (`:118-141`) gate the
  handoff. The input method exists only while the service is bound; `onCreateInputMethod` (external)
  is called by the framework when the flag is set
  (https://developer.android.com/reference/android/accessibilityservice/AccessibilityService#onCreateInputMethod()).
  **`configureEventMode` (`:240-253`) REPLACES `serviceInfo` with a fresh `AccessibilityServiceInfo` on
  every call**, at connect (`:209`), at request start (`:555`) and at each terminal exit (`:674`, `:685`,
  `:704`, `:719`), so a flag added only in `onServiceConnected` would be wiped at the first request. The
  flag is therefore added inside `configureEventMode`, gated on API 33+, and nowhere else.
- **Input session vs pinned node:** two identities. The input session belongs to whatever editor last
  got input focus; the pin belongs to the editor at dictation start. Two same-package moves defeat a
  package-equality gate: field A pinned, input moved to field B in another window of the same app while
  A still reports focused (the reason `fieldKey` exists, `:349-360`); and fields A and B in the SAME
  window sharing a class with no view ids, which `matchesPinnedTarget` (`:799-803`) cannot tell apart.
  The commit route is therefore eligible ONLY when: the window holding input focus (`windows`
  (external), `isFocused`) is the pinned window; its `FOCUS_INPUT` node is FRAMEWORK-EQUAL to the pinned
  node (`node == expected.node`, the test the service already applies at `:752`, never
  `matchesPinnedTarget` alone); the session is started; and the session generation (a counter bumped by
  `onStartInput` (external) and `onFinishInput` (external)) observed at that check is unchanged at the
  commit call. The connection captured at the check is the one used to commit and to judge; the loop
  never substitutes the current connection later. A generation change after the write yields
  `UNREADABLE`, never a write. When correspondence cannot be established the paste route is selected.
- **Dispatched vs unknown:** `pending.verification != null` already means "a write was sent" and the
  loop never re-dispatches (`performInsertion`, `:757-759`). What the loop does NOT do today is survive a
  throw: the catch at `:765-767` returns `RETRY` with `verification` unset, so the next tick re-enters
  the write. The plan closes this by installing the attempted-write record BEFORE the mutating call and
  by allowing exactly one content write per dictation, so the question "did that call mutate" no longer
  has to be answered.
- **Deadline expiry with a dispatched write:** stays `UNVERIFIED` (hedged line). Expiry with no write
  ever dispatched stays `TARGET_NEVER_RETURNED` (plain line).
- **Clipboard ownership:** token + fingerprint (`ownsClipboard`, `:1108`) already guards restore and
  keep. The commit route never touches the clipboard, so `clipboardOverwritten` stays false and the
  restore branch is a no-op there.
- **API gate:** none needed. With `minSdk = 33` the flag, the `InputMethod` subclass and the commit route
  are unconditional; the flag is still added in `configureEventMode` via `serviceInfo` because that is
  the only writer of `serviceInfo`. Lint's `NewApi` (external) check is the guard that the floor is really 33
  everywhere the new types are referenced.
- **Process death mid-insertion:** unchanged; `TranscriptDao.recoverStaleReadyRows` marks the row
  `INSERTION_INTERRUPTED` on next start.

### 5. Prove the high-risk premises

| Premise | Evidence |
|---|---|
| Gmail reports caret `0/0` before AND after the write and ignores `ACTION_SET_SELECTION` | `device-testing.md:577-626`, 21 logged attempts; the pre-write `0/0` is why `normalizedSelection` returned `EditorSelection(0, 0)` and the rewrite went to the start |
| `AccessibilityInputConnection.commitText` is void, and the connection offers `getSurroundingText` (external), `setSelection` (external), `performContextMenuAction` (external) | `javap` (external) on `platforms/android-36/android.jar`, output recorded at `docs/internal/competitor-insertion-study.md:139-143`; API reference https://developer.android.com/reference/android/accessibilityservice/InputMethod.AccessibilityInputConnection |
| The API 33 route works from an accessibility service without replacing the keyboard | Wispr Flow 2.4.1 ships it: `flagInputMethodEditor` (external) in its service config, `WisprInputMethod extends InputMethod`, `commitText(text, 1, null)`; `docs/internal/competitor-insertion-study.md:23-58`; platform contract https://developer.android.com/reference/android/accessibilityservice/AccessibilityServiceInfo#FLAG_INPUT_METHOD_EDITOR. Whether it works on THIS phone: NOT VERIFIED until chunk 2's AVD and phone runs |
| Paste at the editor's cursor with a trusted boolean works in the field | Dictaflow 0.5.4 ships it; `docs/internal/competitor-insertion-study.md:99-127`. Our own `pasteIntoNode` (`PasteAccessibilityService.kt:891-966`) exists but has NOT been observed succeeding on a device: `device-testing.md` FACT: the-lips-bubble-uat P3 shows SET_TEXT; paste NOT RUN there. NOT VERIFIED until chunk 1's AVD run |
| The loop never re-dispatches after a write that SET `verification` | `performInsertion` `:757-759`: `pending.verification?.let { return verificationResult(...) }`. It CAN re-dispatch after a throw (`:765-767`); closed by design in §3 |
| `SurroundingText` (external) carries the offset of its text within the document, so a short before-window at offset 0 is the document start, not truncation | https://developer.android.com/reference/android/view/inputmethod/SurroundingText#getOffset() |
| Adding `FLAG_INPUT_METHOD_EDITOR` at runtime via `serviceInfo` takes effect | NOT VERIFIED. Executed in chunk 2 on the Android 16 AVD: `onStartInput` must fire when a Chrome field is focused |
| `getSurroundingText` returns null for some editors | Chromium `ThreadedInputConnection` (external) returns null when input state is unavailable: https://chromium.googlesource.com/chromium/src/+/refs/heads/main/content/public/android/java/src/org/chromium/content/browser/input/ThreadedInputConnection.java (round 1 §B); handled as `UNREADABLE` |

Problem-only Codex consult: round 1 (`docs/internal/insertion-design/round1-codex-answer.md`) traced the
naive-design traps (focus race, late duplicate write, null-read false success, blind clipboard restore).
Each is a row in §7 or §11. Rounds 2 to 4 (same session) are in `docs/internal/insertion-design/`.

## 3. Design

**One route, selected before any write** (`InsertionRoutePolicy.select(commitEligible)`, proposed):

| Condition (floor is API 33) | Route |
|---|---|
| the commit eligibility predicate (§2.5.4) holds at selection time | `COMMIT` |
| otherwise | `PASTE` |

**One content write per dictation, across all ticks.** `InsertionAttempt` (proposed) is a pure class over
an `EditorWrites` (proposed) port that the service implements (`paste(): Boolean`, `commit(payload)`,
`readNode()` (proposed), `readSurrounding()` (proposed), `stageClipboard(): Boolean`). It:

1. checks the deadline at tick entry, after every blocking read, and immediately before the write;
   expiry forbids the write and the attempt ends in recovery;
2. on the first tick where the pinned target is present (window root found, node refreshed as focused
   and editable, framework-equal to the pin), selects the route, composes the payload, and for `PASTE`
   takes the clipboard restore snapshot and stages the clipboard (a failed staging ends the attempt in
   recovery with no write);
3. installs the attempted-write record (`pending.verification`) BEFORE invoking the write;
4. invokes the write exactly once. `false`, `true`, `void` and a thrown exception are all recorded; NONE
   of them permits another content write. A plain `false` from `ACTION_PASTE` is recorded as
   `REJECTED` and ends the attempt in recovery immediately (the words are already on the clipboard);
5. from then on only judges, every 125 ms, until `VERIFIED` or the deadline.

There is no tier advance and no second route. This is the pre-committed consequence from round 4: three
review rounds each found a new way for a write to be "dispatched but treated as unavailable", and the
class was deleted rather than patched a fourth time.

| Route | Eligibility (checked at selection, on the captured connection) | Write | Evidence read | Verified when |
|---|---|---|---|---|
| `COMMIT` | §2.5.4 predicate: focused window is the pinned window; `FOCUS_INPUT` node framework-equal to the pin; session started; generation unchanged; not sensitive | `getSurroundingText(payload.length + 32, 32, 0)` (capped 4096) for the smart payload and the before-windows, then `commitText(payload, 1, null)` on the captured connection | `getSurroundingText` again with the same lengths on the captured connection; if null, the node text; if that is null, `UNREADABLE` | the returned before-window differs from the pre-write before-window AND ends with `oldBefore.takeLast(32) + payload`, AND the after-window starts with `oldAfter.take(32)`. Coverage: a before-window shorter than requested is complete when its `SurroundingText.offset` (external) is 0 (document start), else INSUFFICIENT and the node judge is used. A predicate already satisfied BEFORE the write cannot verify it. A generation change between check and judge yields `UNREADABLE`, never a write |
| `PASTE` | pinned node refreshed as focused, editable, not sensitive; `actionList` (external) contains `ACTION_PASTE`; restore snapshot taken now; clipboard staged | existing `pasteIntoNode` minus the caret expectation and minus any fallback; `false` → `REJECTED` → recovery | node text after refresh, as a raw nullable read with hint status | `AccessibilityInsertionRules.isSingleInsertion(before, selection, payload, after)`: when the reported selection is a valid RANGE (`start != end`, both within `before`), `after` equals exactly `before.take(start) + payload + before.drop(end)` (the range is trusted as a position because the user made it); a range outside `before` is `UNREADABLE`; when the selection is collapsed or absent, `after.length == before.length + payload.length` and `after` equals `before` with `payload` spliced at any one index (the reported index is NOT trusted: Gmail reports `0/0` while the caret is at the end). Replacing a range with identical text is `MISS` (no observable change). A null read, a failed refresh, or a hint-only read after the write is `UNREADABLE`; a shorter read on its own is NOT truncation |
| recovery | the write was rejected, or was judged `MISS`/`UNREADABLE` past the deadline, or the target never returned, or staging failed | existing `recordAndAnnounce` | | `UNVERIFIED` (hedged line) after a dispatched write; `NO_INSERTION_ACTION` / `TARGET_NEVER_RETURNED` (plain line) when nothing was written |

**The judge** (`AccessibilityInsertionRules.isVerified`, rewritten): no caret clause on any action. The
`Verification` type drops `expectedCaret` and `expectedText`; it carries the RAW pre-write read
(`beforeText: String?`, `beforeWasHint: Boolean`), `selection`, `insertedText`, and for the commit route
`beforeWindow` (proposed)/`afterWindow` (proposed). The judge also receives the RAW post-write read:
nullable text and `isShowingHintText`. `observableEditorText`'s fold to `""`
(`AccessibilityInsertionRules.kt:28-29`) is no longer applied to either side of the judgement: an empty
baseline exists only when the pre-write read was a positively identified hint or a non-null empty
string; a null pre-write text is an UNKNOWN baseline and the node judge returns `UNREADABLE` for it (the
commit route may still verify through its windows, which do not depend on the node baseline). A hint
after the write is not content. `MISS` means a complete, comparable read failed the exact delta test.
Editor transformations of the payload (smart quotes, autocorrect, a stripped trailing newline) are
therefore `MISS`, which at the deadline becomes the hedged line, the honest sentence for "very likely
there, could not prove it"; no broad normalisation of the user's draft is attempted. Both `MISS` and
`UNREADABLE` keep judging to the deadline without a write. `verifyAcceptedAction` records the judgement
in `lastVerifyMiss` and returns `InsertResult.RETRY`, so its return type is unchanged.

**Smart insertion** (PAR-079) is untouched in mechanism: `InsertionText.smartPayloadPlan` composes the
payload from the readable context. The commit route feeds it `getSurroundingText` windows; the paste
route feeds it the node text as today. It only ever changes the payload.

**Clipboard** (PAR-078): the paste route stages, ownership is token + fingerprint, restore on success only
if still owned, keep on failure. The restore SNAPSHOT is taken immediately before the first staging, not
at request time (`requestInsertion`, `:547-548`), so a clip the user copied during the wait is never
overwritten by an older one on restore; an unreadable snapshot (null `primaryClip`) is distinct from an
empty one and means no restore and no clear. The commit route does not write the clipboard.

**Announcements** are untouched: silent on success, one calm line otherwise, `FallbackAnnouncement` the
only source.

**Diagnostics:** one `Log.i` line per attempt end, `insertion api=<int> route=<COMMIT|PASTE|NONE>
written=<bool> returned=<TRUE|FALSE|VOID|THREW|NONE> evidence=<SURROUNDING|NODE|UNREADABLE|NONE>
outcome=<VERIFIED|UNVERIFIED|REJECTED|NEVER_RETURNED|SENSITIVE|STAGING_FAILED> attempts=<n> ms=<n>
overrun=<bool> target=<package>`, built by `InsertionOutcomeLine.format(...)` (proposed), whose
parameters are enums, ints, a boolean and the target package: no payload parameter exists.

**Rejected alternatives.**
- Drop only the caret clause and keep `SET_TEXT` first: forbidden by the 2026-08-30 FACT; the rewrite
  is the data-loss mechanism.
- A cascade with tier advance (rounds 1 to 3 of this plan): deleted by the pre-committed consequence.
- Paste only and skip the commit route: works, but keeps the clipboard write on every happy path, while
  the commit route needs no clipboard write at all. Any latency difference is NOT VERIFIED. Paste stays
  as the route whenever commit eligibility cannot be proven.
- `performContextMenuAction(android.R.id.paste)` on the input connection as a second paste route:
  NOT VERIFIED, adds a third mechanism; left for phone evidence.
- A trust list of null-read packages: rejected for v1 (§2.2).

**Consolidation:** the dominant root is "how an accepted write is judged", and its one owner stays
`AccessibilityInsertionRules.isVerified`, read only by `verificationResult`. The consolidation sites are
the three places that each held part of the answer: the caret expectation set in `insertIntoNode`
(`:884`) and `pasteIntoNode` (`:959`), and the caret clause inside `isVerified`. All three collapse into
the one rule. The second root, "which write runs", has no owner today (control flow in `insertIntoNode`)
and gets one, `InsertionRoutePolicy`, with `InsertionAttempt` owning the single write. Nothing else is
consolidated.

## 3b. Ownership justification

The route choice and the judge live in pure objects in `paste/` (`InsertionRoutePolicy`,
`AccessibilityInsertionRules`, `InsertionAttempt`) because the existing rules object is already the pure
half of the service and its tests already exist; the alternative was more control flow inside
`insertIntoNode`, which is the shape that hid the caret clause for four weeks and the throw-then-rewrite
path for longer. The input session lives in `EditorInputSession` inside `paste/` because it is an
`InputMethod` bound to this service and no other class may hold it.

## 4. MANDATORY — contract deltas

| Type | Delta | What it now means |
|---|---|---|
| `AccessibilityInsertionRules.Verification` | drops `expectedText`, `expectedCaret`; `beforeText` becomes nullable with `beforeWasHint` (proposed); adds `selection: EditorSelection?`, `beforeWindow: String?`, `afterWindow: String?` | "what the field looked like before the write, as read, and what was written", never "where the caret should be" |
| `AccessibilityInsertionRules.isVerified` | returns `Judgement` (proposed) with members `VERIFIED`, `MISS`, `UNREADABLE` instead of `Boolean`; takes the raw post-write read | a miss and an unreadable read are different answers to every caller |
| `AccessibilityInsertionRules.Action` | `SET_TEXT` (removed); adds `COMMIT` | two producers of a write, never a whole-field one |
| `PasteAccessibilityService.InsertResult` | `SET_TEXT` (removed); adds `COMMITTED` and `REJECTED`; `RETRY` unchanged | `tryPendingInsertion`'s `when` is exhaustive so the compiler names every site |
| `InsertionRoutePolicy` (proposed) | new pure object | which ONE route runs, from commit eligibility alone |
| `InsertionAttempt` (proposed) | new pure class over `EditorWrites` | the one-write loop, the deadline checks, the attempted-write record |
| `EditorInputSession` (proposed) | new `InputMethod` subclass, API 33+ | holds `started`, `editorInfo` (proposed), `connection`, `generation`; nothing else may read the connection |
| `InsertionResults` | `"set_text"` stays as a historical row value; `"committed"` (proposed) added; `forServiceFallback` unchanged | History row records the route; no screen reads it: `grep -rn insertionResult app/src/main/java/com/envi/wispr/ui/` hits only `DictationSessionService.kt` writers (14 lines), zero in `HistoryScreen.kt` |

## 5. MANDATORY — end-to-end state and lifecycle audit

| Population | Enumeration |
|---|---|
| Every producer of `pending.verification` | today `insertIntoNode` (`:884`) and `pasteIntoNode` (`:959`), both AFTER their write; after the change ONE producer, `InsertionAttempt`, BEFORE the write, for either route |
| Every site that decides RETRY vs terminal | Terminal decisions: `tryPendingInsertion` (`:665-723`): `PASTED|SET_TEXT`, `REJECTED_SENSITIVE`, `RETRY` (deadline branch), `COPIED_ONLY`. After the change: `PASTED|COMMITTED`, `REJECTED`, `REJECTED_SENSITIVE`, `RETRY` (deadline branch), `COPIED_ONLY`. Producers of `RETRY` today: `performInsertion` (`:732` no pin, `:733` no window root, `:757-759` verification-only, `:765-767` caught exception), `insertIntoNode` (`:810`, `:850`, `:862`), `pasteIntoNode` (`:915`, `:940`, `:951`), `verifyAcceptedAction` (`:973-976`), `verificationResult` (`:1005-1019`). After the change every `RETRY` is either "target not present yet, nothing written" or "written, judging"; the record installed before the write is what distinguishes them, and a throw after the record is installed is "written, judging" |
| Every reader of `expectedCaret` | `isVerified` (`AccessibilityInsertionRules.kt:51,60`), `verificationResult` miss line (`:1016`), `AccessibilityInsertionRulesTest`. All three change |
| Every `performAction` on the pinned node | `:868` SET_TEXT, `:878` SET_SELECTION, `:951` PASTE. After the change: PASTE only |
| Every clipboard write in the service | `writeTranscriptClipboard` (`:1097`) from `pasteIntoNode` and `keepTranscriptOnClipboard`; `restorePreviousClipboardIfSafe`. The WRITES are unchanged; the restore SNAPSHOT moves from `requestInsertion` (`:547-548`) to the paste route immediately before its first staging, and distinguishes UNREADABLE from EMPTY |
| Every reader of `InsertResult` | `tryPendingInsertion` only (exhaustive `when`) |
| Every consumer of `insertionResult` strings | `TranscriptDao` writes; `HistoryScreen` reads `status` only through `historyStatusLine`; grep `insertionResult` in `ui/`: writers only. A new value is safe |
| Lifecycle of `EditorInputSession` | created in `onCreateInputMethod` (framework), `onStartInput`/`onFinishInput` flip `started` and bump `generation`; cleared in `onDestroy` (`:407`). Eligibility is the full §2.5.4 predicate on the captured connection, never package equality. The flag that makes the framework create it is set in `configureEventMode` (`:240-253`), the ONLY writer of `serviceInfo`; its callers (`:209`, `:555`, `:674`, `:685`, `:704`, `:719`) all go through it, so the flag survives every rebuild |
| Generation: a stale completion | `pendingInsertion` is single-slot; `requestInsertion` refuses a second (`:531`); the retry runnable reads the slot each tick. Unchanged |
| Every catch in the attempt | `performInsertion` (`:765-767`): today returns `RETRY` and can re-enter the write; after the change the catch sits INSIDE `InsertionAttempt`, and because the record is installed before the write, a throw from the write is "written, judging"; a throw from a read BEFORE the record exists is "not written, retry the preparation"; a throw from a read AFTER the record exists records `UNREADABLE` and retries only the judgement. Exception classification never clears or reconstructs the record |

## 6. MANDATORY — downstream consumer matrix

| Contract delta | Consumer | Current behaviour | Required behaviour | Code change? | Verified by |
|---|---|---|---|---|---|
| `isVerified` returns `Judgement` | `verificationResult` | reads Boolean | maps `VERIFIED` → success result, `MISS`/`UNREADABLE` → RETRY with the shape line naming which | yes | `AccessibilityInsertionRulesTest` |
| `Verification` without caret, nullable baseline | `InsertionAttempt` | set `expectedCaret` in two sites | one producer, before the write | yes | compile; `InsertionAttemptTest` (proposed) |
| `InsertResult.COMMITTED`, `REJECTED`, `SET_TEXT` removed | `tryPendingInsertion` | n/a | success arm gains `COMMITTED`; `REJECTED` → `recordAndAnnounce(NO_INSERTION_ACTION)` | yes | compile (exhaustive `when`) |
| `serviceInfo` flag at runtime | framework | none | `onCreateInputMethod` called on API 33+ | yes | AVD 16 `onStartInput` log |
| `InsertionResults` new value | `TranscriptDao`, History | stores string | stores `"committed"`; History shows nothing for `STATUS_COMPLETED` | no | `historyStatusLine` test exists |
| Outcome log line | logcat readers, `wispr-eyes` | `Insertion completed via ...` | one structured line | yes | emulator grep |

## 7. MANDATORY — failure-mode × caller table

| Failure mode | Origin | Caller | What the user sees | Persisted state | Retry |
|---|---|---|---|---|---|
| Commit ineligible (session for another node or window, generation moved, not started) | `EditorInputSession` | route selection | nothing; the paste route is selected | none | same tick |
| `commitText` throws | framework | `InsertionAttempt` | nothing; the record is already installed; judge to deadline; hedged line at expiry | `INSERTION_INTERRUPTED`, `copy_only_unverified` | no write |
| Surrounding text null after commit | editor | judge | nothing if node text confirms; else hedged line at expiry | as above | judge only |
| Surrounding coverage insufficient (short window, offset not 0) | editor | judge | node judge; else hedged line | as above | judge only |
| Generation changed after commit | editor / user | judge | `UNREADABLE`; hedged line at expiry | as above | no write |
| `ACTION_PASTE` not advertised | editor | route precondition | plain line "Copied. Press and hold, then tap Paste." (clipboard staged first) | `copy_only` | none |
| `ACTION_PASTE` returns false | editor | `InsertionAttempt` | `REJECTED`; plain line, words on the clipboard | `copy_only` | none, ever |
| Paste written, node unreadable | editor | judge | hedged line at expiry | `copy_only_unverified` | judge only |
| Paste written, editor transformed the payload | editor | judge | `MISS`; hedged line at expiry; the words are in the field | `copy_only_unverified` | judge only |
| Paste landed twice (editor pasted twice) | editor | judge | words twice, hedged line (length clause) | as above | none; §11 asserts the judge never approves a double |
| Pinned window never returns | app switch | `InsertionAttempt` | plain line at expiry | `copy_only` | 125 ms ticks to deadline, no write |
| Clipboard staging fails on the paste route | system | `InsertionAttempt` | no write; plain line "Saved in History. Open EnviousWispr to copy." if History has the row, else "Your words could not be saved." | `insertion_failed` | none |
| Deadline reached by a slow read before the write | editor | `InsertionAttempt` | plain line; nothing written | `copy_only` | none |
| Write call overruns the deadline | framework | `InsertionAttempt` | judged normally; `overrun=true` in the line | as judged | none |
| Service interrupted mid-attempt (`onInterrupt`, `:380-393`) | system | `recordAndAnnounce(SERVICE_INTERRUPTED)` | existing plain line | existing `copy_only_interrupted` | none; unchanged |
| Sensitive field (`isSensitive`, `:813`; arm `:681-686`) | policy | `REJECTED_SENSITIVE` | existing plain line | existing `copy_only_sensitive` | none; unchanged (#11 owns its removal) |
| Clipboard no longer ours before staging (`:900-904`) | another app wrote the clipboard | `InsertionAttempt` | staging refused; plain line | `copy_only` | none; the keep path (`:1077-1080`) also refuses to overwrite |
| Smart context changed between compose and paste (`:926-950`) | user moved the caret | `InsertionAttempt` | literal words are pasted instead of the smart payload; no line | `pasted` | none; unchanged |
| Pinned node fails refresh or identity, or a READ throws (`:742-772`, `:915`, `:974-976`) | editor | `InsertionAttempt` | nothing until the deadline, then plain line (no write) or hedged line (written) | `copy_only` / `copy_only_unverified` | 125 ms ticks; a throw after the record never writes again |
| Previous clipboard cannot be restored after success (`:1060-1071`), or the snapshot was unreadable | URI grant / clipboard access | `restorePreviousClipboardIfSafe` | nothing; the words are in the field and on the clipboard | `pasted` | none |
| History finalization fails (`:1115-1119`) | Room | `finalizeInsertion` | nothing; the field and clipboard are unaffected | row keeps `pending` until `recoverStaleReadyRows` | none; unchanged |
| Handoff refused before the service (`:153-158`, `:527-537`) | session / service | `DictationSessionService.kt:1032-1060` | existing `fallbackAnnouncement` line | `clipboard` / `history_only` | none; unchanged |
| Service destroyed mid-attempt | system | `onDestroy` | existing `SERVICE_DESTROYED` line | existing | none |

Copy for every row is the existing sentence in `InsertionOutcomeMessages.kt:250-276`; nothing new is
worded.

## 8. MANDATORY — caller-visible signals audit

| Field | Meaning beyond its type |
|---|---|
| `pending.verification == null` | no write has been sent; the route may still be selected and written |
| `pending.verification.action` | which route's write is being judged; `COMMIT` means the clipboard was not touched |
| `pending.clipboardOverwritten` | whether restore/keep must consider ownership; false on the commit route |
| `Judgement.UNREADABLE` vs `MISS` | staleness of the read, not absence of the words; both retry, only the log line differs |
| `Verification.beforeText == null` | unknown baseline; the node judge cannot verify, only the windows can |
| `EditorInputSession.started`, `generation`, and the captured connection | identity of the input session; the full §2.5.4 predicate is the gate, on the captured connection |
| `InsertionResults` value `"committed"` | which route succeeded; no screen reads it, the row is for diagnosis |
| `lastVerifyMiss` | shape only; gains `evidence=` and loses `expectedCaret=` |
| `returned=` in the outcome line | `FALSE` is the only value that proves no mutation, and it still ends the attempt |

## 9. MANDATORY — fallback source-of-truth audit

| Failure branch (§7) | Candidate expression | Source | Why authoritative here | Acceptance predicate | If none qualifies | Consumer (§6) |
|---|---|---|---|---|---|---|
| written, unconfirmed at deadline | hedged line | `FallbackAnnouncement.serviceFallbackAnnouncement(UNVERIFIED, clipboard, savedInHistory)` | the only type a surface can be built from | clipboard write result measured | History-only or "could not be saved" line | toast, History row |
| rejected, never written, never returned, staging failed | plain line | same, `NO_INSERTION_ACTION` / `TARGET_NEVER_RETURNED` | same | same | same | same |
| commit route context | surrounding text windows | `getSurroundingText` on the captured connection | editor's own view of the caret | non-null `SurroundingText` with non-null text and sufficient coverage | node text; if null, `UNREADABLE` | judge |

## 10. File-by-file changes

**Chunk 1 — one route (paste) and the new judge on the mechanisms we have (expected to fix the
founder's phone).**
- `paste/AccessibilityInsertionRules.kt`: `Verification` without caret, nullable baseline; `Judgement`;
  `isVerified` returns `Judgement` and takes the raw post-write read; `isSingleInsertion(before,
  selection, inserted, after)` with the range-aware length clause; `Action.SET_TEXT` (removed).
- `paste/InsertionRoutePolicy.kt` (proposed): `enum Route { COMMIT, PASTE }`,
  `select(commitEligible: Boolean): Route`. In chunk 1 `commitEligible` (proposed) is always false.
- `app/build.gradle.kts`: `minSdk = 33`; `README.md:30` and the local `CLAUDE.md` Compatibility line say
  Android 13 (API 33).
- `paste/InsertionAttempt.kt` (proposed): the one-write loop over `EditorWrites`; owns the attempted-write
  record, the deadline checks, the restore snapshot timing, and the write count.
- `paste/InsertionOutcomeLine.kt` (proposed): the log line builder.
- `paste/PasteAccessibilityService.kt`, path by path: `pasteWhenTargetReturns` (`:147-158`) UNCHANGED;
  `requestInsertion`'s clipboard snapshot (`:539-551`) MOVED into the paste route's first staging;
  `PendingInsertion` (`:78-94`) keeps its fields, `verification` with the new shape; `tryPendingInsertion`
  (`:669-720`) CHANGED in the success arm (`COMMITTED`, result string), a new `REJECTED` arm, and the
  deadline branch reading the `Judgement` kind; `performInsertion` (`:731-772`) becomes the port
  implementation's "find the target" step and its catch moves into `InsertionAttempt`; `insertIntoNode`
  (`:806-889`) DELETED with the whole-field merge and `ACTION_SET_SELECTION`; `pasteIntoNode`
  (`:891-966`) becomes the port's `stageClipboard` (proposed) and `paste` steps, dropping `expectedText`/
  `expectedCaret` and checking `actionList` (external) before staging; `verifyAcceptedAction`
  (`:969-978`) CHANGED to record `UNREADABLE` on a failed refresh and return `RETRY`;
  `restorePreviousClipboardIfSafe` (`:1048-1073`) CHANGED only to treat an unreadable snapshot as "do
  nothing"; `keepTranscriptOnClipboard`, `writeTranscriptClipboard`, `ownsClipboard` (`:1075-1112`)
  UNCHANGED; `onInterrupt`/`onDestroy` → `recordAndAnnounce` (`:387-388`, `:419-420`, `:1152-1171`)
  UNCHANGED; `InsertResult.SET_TEXT` (removed), `REJECTED` added.
- `insertion/InsertionText.kt`: `mergeAtSelection` (`:77`) loses its only caller in the service; it stays
  for the tests and the judge's expected-string construction, or is deleted if no caller remains (decided
  at build time by the grep, recorded in the commit).
- Tests: `AccessibilityInsertionRulesTest` (real Gmail 0/0 fixture, range replacement with shortening,
  identical replacement, double paste, unreadable, hint after write, null baseline),
  `InsertionRoutePolicyTest` (proposed), `InsertionAttemptTest` (proposed) with a fake `EditorWrites`,
  `InsertionOutcomeLineTest` (proposed).

**Chunk 2 — the API 33 commit route.**
- `paste/EditorInputSession.kt` (proposed), with `generation`.
- `PasteAccessibilityService.kt`: `configureEventMode` adds `FLAG_INPUT_METHOD_EDITOR` (its only writer of
  `serviceInfo`); `onCreateInputMethod` override; the port's `commit`, `readSurrounding` and the
  eligibility predicate; `InsertResult.COMMITTED`; `InsertionResults.COMMITTED` (proposed).
- `AccessibilityInsertionRules.kt`: `Action.COMMIT`, window-based judge with the offset coverage rule.

**Chunk 3 — parity text, rules, matrix.**
- `docs/enviouswispr-android-parity-spec.md` PAR-077 text; catalog `data/NNN` row for
  `multi-route-paste` Android; `architecture-rules.md` RULE: insertion-fails-safe-never-silently
  rewritten to "one route, one write: commit on 33+ when eligible, else paste; copy-only otherwise",
  which resolves the rule-versus-code conflict the 2026-08-30 FACT flagged.
- `scripts/enviouswispr-emulator.sh` gains an API 33 AVD name once its image is installed (disk only, no
  spend), so the floor itself is exercised; recipe rows in `device-testing.md` FACT: the-insertion-uat-matrix.

## 11. Testing

1. **Class.** Product outcome: the real Gmail `0/0` fixture ("when this fails, the user sees a Copied
   line on a dictation that landed"); the double-paste refusal ("the user sees their words twice and a
   success haptic"); the one-write ceiling ("the user's words appear twice"); the route choice ("a
   dictation takes the clipboard path when the pipe was provably the user's field"). Harness contract:
   the outcome line's exact format. Drift guard: the `minSdk` assertion.
2. **Revert that turns it red.** Named per row in §11.2. Each revert is performed once during chunk 1
   and its red run pasted in the validation folder.
3. **Deliberately not tested.** The framework's delivery of `onStartInput` (device fact, §11.1); Gmail's
   real caret behaviour (device fact); OEM service survival (no phones; chunk 3 names the list).

### 11.1 Hardware UAT spec

- **Subsystem:** heart path.
- **Recipe:** `device-testing.md` FACT: the-insertion-uat-matrix rows Gmail, Messages, Chrome form, plus
  back-to-back. Emulator Android 16 first (Gmail, Chrome, Messages, Gboard are on the image; `say`
  through the Mac speaker with `-allow-host-audio`), then the founder's S26 through Play internal
  testing. The API 33 AVD run in chunk 3 is a bind-and-insert check at the floor, not a speech check,
  unless that image also carries the editors.
- **Expected observation:** the dictated sentence appears exactly once at the caret; the draft's blank
  line and spaces are unchanged (compare a screenshot before and after); no toast; logcat carries one
  `insertion ... outcome=VERIFIED` line with `route=COMMIT` on Android 16, or `route=PASTE` where the
  commit eligibility could not be proven. The oracle is the field content, not the log line.
- **Phone state to restore afterwards:** none changed; the Play build is the founder's daily build.

### 11.2 Other obligations

| Test | Class | Proves | Revert that turns it red |
|---|---|---|---|
| `isVerified` Gmail fixture, the REAL shape: before "Hi team, " with reported pre-write selection 0/0, inserted "and I will", after "Hi team, and I will", post-write caret 0/0 → VERIFIED | product outcome | G1 | caret clause restored, OR trusting the reported collapsed index |
| `isVerified` range replacement that shortens: before "Hi team", selection 3/7, inserted "Jo", after "Hi Jo" → VERIFIED | product outcome | round 4 finding 2 | treating `after.length < before.length` as UNREADABLE |
| `isVerified` identical replacement: before "Hi team, old", selection 9/12, inserted "old", after "Hi team, old" → MISS | product outcome | §3 judge | drop the observable-change requirement |
| `isVerified` double paste: before "Hi " sel 3/3, inserted "there", after "Hi therethere" → MISS (length clause) | product outcome | §7 double row | replace the length-and-splice rule with `after.contains(inserted)` |
| `isVerified` null post-write text with non-empty inserted → UNREADABLE | product outcome | G4 | collapse to MISS |
| `isVerified` hint after write → UNREADABLE, never VERIFIED against an empty baseline | product outcome | round 3 finding 3 | fold the hint to "" before judging |
| `isVerified` null pre-write baseline, node judge → UNREADABLE even when `after == inserted` | product outcome | round 4 finding 4 | fold null to "" |
| `InsertionRoutePolicy.select(false)` → PASTE; `select(true)` → COMMIT | product outcome | G3 | swap |
| manifest/gradle test: `minSdk` is 33 | drift guard | G5 | lower the floor |
| `InsertionAttempt` one-write ceiling: paste `false` → total writes 1, outcome REJECTED; paste `true` → 1; paste throws → 1 and the record is present; commit `void` → 1; commit throws → 1; a read throws before the write → 0 then 1 on the next tick | product outcome | G4, round 4 finding 1 | allow a second write on `false` or on a throw |
| `InsertionAttempt` deadline: a read that consumes the budget → 0 writes; a write that overruns → judged, `overrun=true` | product outcome | round 3 finding 6 | check the deadline only at tick entry |
| `InsertionAttempt` snapshot timing: the clipboard changes between request and first staging; the restore uses the later value | product outcome | round 3 finding 7 | snapshot at request time |
| commit window judge: payload of 200 chars with 32-char context, returned before-window of 232 chars → VERIFIED; the same payload with a returned before-window of 40 chars at `offset` 0 → cannot contain the payload, node judge used (document start never bypasses payload matching); payload of 20 chars with 20 chars of preceding context, returned before-window of 40 chars at `offset` 0 → VERIFIED (document start, full payload present); returned before-window of 40 chars at `offset` 500 for the 200-char payload → INSUFFICIENT, node judge used; a before-window that already ends with the payload BEFORE the write and is unchanged after → not VERIFIED | product outcome | round 3 finding 5, round 4 adoption 5, round 5 finding 2 | fixed 64-char windows; drop the change or coverage requirement; let offset 0 bypass the payload match |
| `isVerified` range landed elsewhere: before "abcDEFghi", selection 3/6, inserted "X", after "Xabcghi" → MISS | product outcome | round 5 finding 3 | splice-at-any-index for a trusted range |
| `InsertionAttempt` read throws AFTER the write: commit returns, the next read throws, three more ticks → total writes 1, judgement UNREADABLE on the throwing tick | product outcome | round 5 finding 1 | classify a post-write read throw as "not written" |
| commit eligibility: same-window fields A and B, same class, null view ids, session bound to B → COMMIT ineligible; bound to the pinned node object → eligible | product outcome | round 4 finding 3 | `matchesPinnedTarget` instead of framework equality |
| `InsertionOutcomeLine.format(...)` fixture → exact template line | harness contract | G6 | change the template |

## 12. Blast radius & rollback

- Touched: `paste/` (service, rules, four new files), `insertion/InsertionResults.kt` (one constant,
  chunk 2), possibly `insertion/InsertionText.kt` (one dead function), tests; parity spec and rules docs
  in chunk 3. No XML change (the flag is runtime).
- Not touched: `DictationSessionService`, `InsertionJudgement`, `InsertionOutcomeMessages`, History,
  the overlay, AIDL, the `:asr` and `:polish` processes, `isSensitive`.
- Rollback: revert the PR; no schema change, no new permission, no persisted format change (the
  `"committed"` string is one more value in an existing free-text column; `"set_text"` rows already
  written stay readable).

## 13. Ship criteria specific to THIS change

- [ ] A Gmail draft with a blank line keeps it after a dictation into it, on the founder's S26, and no
      line is shown.
- [ ] The same dictation on the Android 16 AVD logs `route=COMMIT outcome=VERIFIED`; on the API 33 AVD
      the service binds and Chrome receives the words.
- [ ] `grep -c ACTION_SET_TEXT app/src/main/java/com/envi/wispr/paste/PasteAccessibilityService.kt` is 0.
- [ ] The real Gmail fixture, the double-paste test and the one-write ceiling are green, and each was
      seen red under its named revert.

## 14. Open questions

- Whether `getSurroundingText` on the accessibility connection returns the editor's text for Gmail (it
  may be one of the null-returning editors). Settled on the AVD in chunk 2; the fallback is the node
  judge either way.
- Whether Samsung Keyboard's clipboard suggestion (`device-testing.md` FACT:
  samsung-keyboard-can-float-a-clipboard-suggestion-over-an-inline-button) reacts to the paste route's
  clipboard write on the S26. Observed during the phone pass.
- How often a real editor returns `false` from `ACTION_PASTE`. Measured from the `returned=FALSE` count
  in the founder's logs after chunk 1; that number decides whether an empty-field route ever earns its
  own plan.

## 15. Related

#141, #132, #11, #16 (closed by #130); PAR-076 to PAR-081; catalog `multi-route-paste`,
`smart-insertion`; `docs/internal/competitor-insertion-study.md`;
`docs/internal/insertion-design/` (rounds 1 to 4).

---

## Checklist for the plan author

- [x] Gate 0 prior context posted before this file was written
- [x] User Rubric answered against named personas
- [x] §2.5 grounded in real code before §3 was written
- [x] §4-9 answered
- [x] Lane declared and matching detection
- [x] Self-reviewed to all-clear before any reviewer saw it (round 4 pivot applied in full)
