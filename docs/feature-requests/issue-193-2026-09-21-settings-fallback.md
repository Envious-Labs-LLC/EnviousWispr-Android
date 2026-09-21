# Issue #193 — A settings or vocabulary read failure prevents recording instead of falling back — 2026-09-21

GitHub issue: `#193`. Tier: MEDIUM (a service's start path, new runtime behaviour; `workflow-process.md`
RULE: tier-routing). Status: DRAFT for the coverage round.

Consolidation: this plan is one document; §2.5 carries the trace and the measured premises once and §§3 to 11 point back at it.

**Build order.** Grounded against `main` at db12eb5 (after #192), the base of worktree `issue-193-settings-fallback`.

## Preface — Lane + Hardware UAT declaration

**Lane:** Code — `app/src/main/**` (the preferences source, the owner's start, one terminal reason removed, telemetry
vocabulary), `app/src/test/**` (coordinator rows, contract rows). `mixed_pr: true`: `Code` (`unit-tests.xml`,
`codex-review.md`, `visibility.txt`, `hardware-uat.json`: the heart path's start) and `Docs/dev-tooling`
(`cited-symbols`, conditional).

**PAR rows closed:** none named; the audit row is REF-02 (`docs/audits/2026-09-20-senior-audit.md`, "Let settings
and vocabulary fail open before capture").

**Hardware UAT:** Y. The heart's start. On the emulator through wispr-eyes (the founder's phone is his today): an
ordinary take still runs with the user's real settings (auto-stop honoured on a cold start); the fail-open path
is staged on the JVM rig with injected flow failures, because a DataStore or Room failure cannot be staged on
a device without corrupting the founder's data (§11.1 says which rows stand in).

## Preface — User Rubric

1. **Who.** Frank Chen, 72, on his phone at the kitchen table, pressing the side button to dictate a message to
   his daughter. Thirty seconds ago he was reading her message; thirty seconds from now he wants his reply
   sent. His phone had an update overnight and one of the app's storage files did not come back cleanly.
2. **Why.** "I pressed the button and it said settings could not be loaded. I don't have any settings. I just
   want to talk."
3. **How.** Reactive: the side button, as always. Already in Messages.
4. **Apps.** Messages, WhatsApp, Gmail: whatever he was reading.
5. **Input.** "Yes I can come Sunday", "did you get the photos", "the doctor moved it to Tuesday", "love you
   talk later", "what time is the game".
6. **Success.** He notices nothing: the recorder comes up, he talks, the words land. If a setting he never
   touched fell back to its default he cannot tell.
7. **Wrong-not-broken.** Priya turned "stop on silence" on last week; today's take runs without it because her
   settings could not be read, and she has to press stop by hand. She should get one line telling her the
   app used its last settings, and the next take should be right again.
8. **Power user.** Priya force-stops the app and tries again; if that fails she clears its storage, losing her
   word list. Neither should be needed: the take must start on the last good values.
9. **Control.** None wanted at the moment of the press. The ladder is "always start"; the only choice a user
   makes is in Settings, later, and it must still take effect on the next take.

**Cross-persona.** Diana, Marcus, Aaron, Meera and Elena: identical. Elena adds one demand: a fallback must
never write anything of hers anywhere new (the clipboard) that her settings had turned off; §3 keeps the
listening notification silent about the clipboard when the policy is a stand-in, as today. No tension.

## 0. TL;DR

- Today `DictationSessionCoordinator.beginSession` awaits two readiness signals from `SessionPreferencesSource`
  for up to `SETTINGS_WAIT_MS` (10 s) and, if either never lands, ends the take as `SETTINGS_UNAVAILABLE`
  before capture is asked to start. A collector that THROWS never completes its signal, so a failed read is a
  ten-second wait and then no dictation.
- After: each reader reports a typed answer, `PreferenceRead` (proposed): `Fresh` (the first emission landed),
  `Failed` (the collector's catch ran; the last good values stand), or `Pending` (nothing yet). The owner
  waits only for the two readers to ANSWER, under a short bound, and always starts capture: on the fresh
  values, or on the last successful snapshot (the defaults on a first run) with a typed limb outcome in the
  take's facts and one log line. `SETTINGS_UNAVAILABLE` (removed) has no producer and is deleted with its
  sentence and its telemetry rows.
- The cold-start trap stays closed: the owner still waits for the first REAL emission when the reader has not
  answered (DataStore and Room answer in milliseconds), so a user with auto-stop on gets it; the bound exists
  only so a hung storage layer cannot hold the microphone hostage, and expiring it is a `Failed`-shaped
  answer, never an end.
- Guards: rig rows with an injected settings failure and an injected vocabulary failure (capture starts
  promptly; the take transcribes and inserts on the defaults), a row for the bound expiring (starts on the
  last snapshot), the inverted `settingsNeverReadyEndsStartingWithSentence` row deleted, a row that an
  ordinary take still freezes one consistent snapshot, the telemetry contract row for the new facts.

## 1. Problem

`SessionPreferencesSource.start` launches two collectors. Each completes a `CompletableDeferred` on its first
emission; each catches `Exception`, logs one warning, and ENDS. `awaitReady(timeoutMs)` waits on both
deferreds. `beginSession` (`DictationSessionCoordinator.kt:361-369`) calls it with `settingsWaitMs` and, on
false, `showError(TerminalReason.SETTINGS_UNAVAILABLE)` ("Settings could not be loaded. Try again.",
`TakeNotices.kt:51`). Capture is never asked to start. The audit's interleaving (REF-02) holds on db12eb5.

Cleanup options, custom words, the clipboard policy and the polish policy are optional limbs; the microphone
is the heart. A limb's failure ends the heart's take.

## 2. Goals & non-goals

### 2.1 Goals
- A failed settings read or a failed vocabulary read never prevents a take: capture starts promptly on the last
  successful values (the defaults on a first run).
- The outcome is typed and observable: the take's facts carry which readers fell back and why; one log line.
- An ordinary take still freezes ONE consistent snapshot at start, with the user's REAL values on a cold start.

### 2.2 Non-goals
- Repairing a corrupt DataStore or Room file (Android's own handlers; out of scope).
- Changing what a setting means or when it applies (per-take freezing, #69, stands).
- A user-facing settings-health screen (#35 territory).

## 2.5 Grounding brief

### 1. Trace producer → owner → consumer

**Producers.** `AppPreferences.authoritativeState` = `dataStore.data.map(::mapState)` with NO catch
(`settings/AppPreferences.kt:71-72`); its sibling `state` catches `IOException` and emits
`AppPreferencesState()` for the UI. The owner consumes `authoritativeState` on purpose (`DictationSessionService.kt:206`):
a stand-in must never be mistaken for the user's answer. `CustomTermRepository.observeTerms()` is a Room
`Flow` mapped to `CustomTerm` (`vocabulary/CustomTermRepository.kt:23`); a Room open or query failure
throws into the collector. `migrateLegacyTerms` runs before the terms collector, in its own try.

**Owner.** `SessionPreferencesSource` (`ui/SessionPreferencesSource.kt`): eight `@Volatile` fields written by
the two collectors; `cleanupPreferencesReady` and `structuredTermsReady`; `awaitReady`; `freeze`.
`clipboardPolicy` is nullable on purpose (its KDoc; a stand-in promised the clipboard to a user who had
turned auto-copy off).

**Consumers of the fields.** `beginSession` (`:361` the wait, `:370` `structuredTerms`, `:382` `freeze`);
`tryStartRecording` (`:461-464` `autoStopOnSilence`, `silencePauseSeconds`, `inputDevicePick`,
`keepEarbudsReady`, read LIVE at capture start); `:334` `inputDevicePick` into `takeFacts`; `:710`
`autoStopOnSilence`; `:732` `showBluetoothTips`; `DictationSessionService.promoteToForeground`
(`:142-155`) reads the LIVE `clipboardPolicy` for the listening notification. The frozen `SessionPreferences`
feeds cleanup, the matcher (`restoreTakeVocabulary`), the clipboard policy at insertion and the polish policy.

**Consumers of `SETTINGS_UNAVAILABLE`** (closed world, `git grep`): `DictationSessionCoordinator.kt:365`
(the only producer), `TakeNotices.kt:51`, `TerminalReason.kt:66`, `TelemetryChannels.kt:152,187` (two
`when`s), tests `DictationSessionCoordinatorTest.kt:435`, `TakeNoticesTest.kt:33`, `TakeFactsTest.kt:26`.

### 2. Existing authority
`SessionPreferencesSource` already owns the two reads and the freeze (#186). The change stays inside it and
its one caller; no new manager. `TakeFacts` already carries per-take facts into `DictationTerminal` and
the Sentry breadcrumbs; the limb outcome joins it rather than a new channel.

### 3. Prior attempts and live direction
- #69 froze settings per take and made the polish policy a latched value.
- The nullable `clipboardPolicy` (its KDoc) is a recorded trap: a plausible default promised the clipboard.
- `autoStopOnSilence` is written BEFORE the readiness signal (its KDoc) because starting on defaults before
  the first emission gave a user with auto-stop a manual take after every cold start.
- #186 moved the collectors out of the Service without changing the gate. The audit (REF-02) names the gate.

### 4. Boundaries a naive design misses
- **Not-yet versus failed.** "Delete the readiness barriers" read literally would start every cold-start take
  on defaults for the milliseconds before DataStore answers, reopening the auto-stop trap. The barrier is
  kept as "the reader has ANSWERED"; only the answer's shape changes.
- **The bound.** A hung DataStore (a stuck file lock) is neither an emission nor an exception. Without a
  bound the owner would wait forever; with the old bound it ended the take. The new bound (§3, 2 s) is a
  `Failed`-shaped answer: start on the last snapshot, record `timed_out`.
- **The stand-in clipboard policy.** On a `Failed` first read, `clipboardPolicy` stays null: the notification
  keeps saying nothing about the clipboard (as today on a cold start), and `freeze` uses
  `ClipboardInsertionPolicy()` as it already does for null. Elena's demand: the stand-in's auto-copy default is
  `true`, so a user who had turned it off could see her words copied on an insertion failure during a
  fallback take. That is today's `freeze` behaviour for the null case and is NOT widened here; §14 names
  it for the coverage round to weigh (the alternative, auto-copy OFF as the stand-in, loses words for
  everyone else on a failed read).
- **Process.** The owner runs in the default process; the collectors are on the injected scope; nothing
  crosses a binder. The visibility check refuses an `else` over `TerminalReason`, so deleting a member is a
  compile-time sweep of every `when`.
- **Telemetry vocabulary.** `DictationTerminal` is a closed PostHog row (`AnalyticsEvent.kt:12`); a new
  property is a contract change pinned by `TelemetryContractsTest` and `TakeFactsTest`.

### 5. High-risk premises, with evidence
- **P1. A throwing collector ends the take today.** Evidence: `settingsNeverReadyEndsStartingWithSentence`
  passes on db12eb5 (a never-emitting flow; the throwing shape is the same path: no completion). The
  build adds the throwing shape as the FIRST row and runs it before the change to see it red-by-today
  (capture never starts) and green after.
- **P2. DataStore and Room answer in milliseconds on a warm phone, so waiting for the ANSWER keeps cold-start
  correctness without a user-visible delay.** Not measured on the emulator yet; the emulator run logs the
  time from `beginSession` to the readers' answer (one debug line) on three cold starts. If it exceeds 500 ms
  the bound and the design are re-examined (§13).
- **P3. Nothing else produces `SETTINGS_UNAVAILABLE`.** Closed-world grep above: one producer.

## 3. Design

1. **`PreferenceRead` (proposed), in `SessionPreferencesSource.kt`:** `sealed interface` with `Pending` (proposed),
   `Fresh`, `Failed(val reason: String)` (the reason is a content-free token: `exception:<SimpleName>` or
   `timed_out`; never a message, `#194` territory). Two fields, `settingsRead` (proposed) and `termsRead` (proposed),
   `@Volatile`, start `Pending`.
2. **The collectors:** on first emission set `Fresh` and complete the deferred (as today); in the catch set
   `Failed(exception:<name>)` and complete the deferred (today's catch only logs). The fields keep the last
   good values; a first-run failure leaves the constructor defaults (`CleanupOptions()`, empty terms,
   `clipboardPolicy = null`, auto-stop off, `InputDevicePick.AUTO`, tips on, earbuds hold on).
3. **`awaitAnswers(boundMs)` (proposed) replaces `awaitReady`:** waits for both deferreds under the bound;
   on expiry marks each still-`Pending` reader `Failed(timed_out)`. Returns a `PreferenceStart` (proposed):
   `settings: PreferenceRead`, `terms: PreferenceRead`. It never returns "not ready".
4. **`beginSession`:** `awaitAnswers` under `SETTINGS_ANSWER_BOUND_MS` (proposed) (2 000 ms,
   replacing `SETTINGS_WAIT_MS`); if either is `Failed`, `takeFacts.settingsFallback` (proposed) = a token
   naming which (`settings`, `terms`, `both`) and the reasons, one `log.warn`, one breadcrumb
   `take` / `settings_fallback` (proposed); then the existing snapshot, compile, policy and bind, unchanged. The
   `showError(SETTINGS_UNAVAILABLE)` branch is deleted.
5. **`SETTINGS_UNAVAILABLE` removed** from `TerminalReason`, `TakeNotices`, both `TelemetryChannels` sets,
   and the three tests that name it (the compiler finds the `when`s).
6. **Telemetry:** `DictationTerminal` gains `settingsFallback: String?` (proposed) carried from `TakeFacts`,
   null on an ordinary take; the journal row is unchanged (a Sentry breadcrumb carries the same token).
7. **The notification** (`promoteToForeground`) is unchanged: it reads the live nullable `clipboardPolicy`.

Alternatives: (a) a separate "last known settings" cache file: a second copy of the same values with its
own failure modes; rejected. (b) consume `AppPreferences.state` (the catching flow): it hides WHICH read
failed and emits a stand-in indistinguishable from a real answer; rejected, the owner keeps
`authoritativeState`. (c) no bound: a hung DataStore holds the microphone; rejected.

## 3b. Ownership justification
Lives in `SessionPreferencesSource` because it already owns both reads and the freeze; the alternative
(the coordinator deciding) would spread the reader's state across two files. The bound constant moves to
the source's caller as today's `settingsWaitMs` does (injected for tests).

## 4. Contract deltas
- `SessionPreferencesSource.awaitReady(Long): Boolean` → `awaitAnswers(Long): PreferenceStart`.
- `TerminalReason.SETTINGS_UNAVAILABLE` deleted; `TakeNotices` loses one sentence.
- `AnalyticsEvent.DictationTerminal` gains one nullable property `settings_fallback` (proposed).
- `DictationSessionCoordinator.SETTINGS_WAIT_MS` → `SETTINGS_ANSWER_BOUND_MS` (2 000).

## 5. State and lifecycle audit
Two readers, each `Pending → Fresh` (first emission), `Pending → Failed` (catch, or bound expiry), and
`Fresh` stays `Fresh` on later emissions. `Failed` on a first run means constructor defaults; `Failed`
after a `Fresh` keeps the last written fields (the collector ended; no later emission overwrites). A
second take in the same process after a `Failed` first read: the collector is not restarted (it ended), so
the take starts on the same last values, `Failed` again, one more line. Restarting a failed collector is
§14. The owner's `destroy()` cancels the scope as today.

## 6. Consumer matrix
- `beginSession`: the wait's shape changes; everything after it is unchanged.
- `tryStartRecording`, `:334`, `:710`, `:732`, `promoteToForeground`: read the same fields; unchanged.
- `TakeFacts` → `DictationTerminal`: one new nullable property; `TelemetryContractsTest` and
  `TakeFactsTest` rows updated; PostHog consumers see a new optional key.
- `TakeNotices`, `TelemetryChannels`: one member fewer.

## 7. Failure-mode × caller table

| Failure mode | Origin | Caller | What the user sees | Persisted state | Retry |
|---|---|---|---|---|---|
| settings flow throws on first read | DataStore | `beginSession` | the recorder comes up at once; the take runs on defaults; one debug line | the take's facts carry `settings_fallback=settings:exception:<name>` | next take: same, until the process restarts (§14) |
| terms flow throws | Room | `beginSession` | the take runs without custom words | `terms:exception:<name>` | same |
| both throw | both | `beginSession` | defaults, no custom words | `both:...` | same |
| neither answers within the bound | a hung store | `beginSession` | starts on the last snapshot after 2 s | `...:timed_out` | the collectors keep running; a later emission updates the fields for the next take |
| settings throw after a Fresh read | DataStore, mid-process | a later take | runs on the last good values | `settings:exception:<name>` | same |
| ordinary cold start | none | `beginSession` | unchanged: the user's real values, auto-stop honoured | `settings_fallback` null | none |

## 8. Caller-visible signals
Removed: the "Settings could not be loaded. Try again." toast and the `SETTINGS_UNAVAILABLE` ending. Added:
none visible; one `log.warn` and the facts token. §14 asks whether a one-line notice ("Using your last
settings") is wanted for the wrong-not-broken case (Priya's auto-stop); the default answer is no notice for
Frank and a facts token that the diagnostics screen (#35) could later show.

## 9. Fallback source-of-truth audit
The fallback values ARE the source's fields, written only by the collectors; there is no second copy. The
stand-in clipboard policy is `freeze`'s existing null branch. The token in the facts is derived from the two
`PreferenceRead` values at the moment of the wait, never re-read later.

## 10. File-by-file changes
- `app/src/main/java/com/envi/wispr/ui/SessionPreferencesSource.kt`: `PreferenceRead`, `PreferenceStart`,
  the two read fields, the catch completes with `Failed`, `awaitAnswers`.
- `app/src/main/java/com/envi/wispr/ui/DictationSessionCoordinator.kt`: `beginSession` wait and facts;
  `SETTINGS_ANSWER_BOUND_MS`.
- `app/src/main/java/com/envi/wispr/ui/TerminalReason.kt`, `TakeNotices.kt`,
  `telemetry/TelemetryChannels.kt`: the member removed.
- `app/src/main/java/com/envi/wispr/telemetry/TakeFacts.kt`, `AnalyticsEvent.kt`: `settingsFallback`.
- `app/src/test/java/com/envi/wispr/ui/DictationSessionCoordinatorTest.kt`: rows in §11.2; the inverted
  row deleted. `DictationSessionRig.kt`: `settingsWaitMs` → `answerBoundMs` (proposed).
- `app/src/test/java/com/envi/wispr/ui/TakeNoticesTest.kt`, `telemetry/TakeFactsTest.kt`,
  `telemetry/TelemetryContractsTest.kt`: the member and the property.
- `docs/audits/2026-09-21-193-revert-receipts.txt`, `docs/audits/2026-09-21-193-emulator-pass/`.

## 11. Testing
1. Classes: the two injected-failure rows and the bound row are Product Outcome (when they fail, Frank
   cannot dictate); the consistent-snapshot row and the telemetry contract row are Drift Guards.
2. Reverts: §11.2.
3. Not tested: a real corrupt DataStore on a device (cannot be staged without destroying the founder's data;
   the rig's flow failure is the same code path, the collector's catch).

### 11.1 Hardware UAT spec
- Emulator, wispr-eyes, debug build of the final commit: (a) three cold-start ordinary takes into Gmail with
  "Stop recording on silence" ON, each ending by silence (auto-stop honoured: the cold-start trap stays
  closed) and the debug line's answer time recorded (P2); (b) one take with the switch OFF ending by the
  toggle, `route=COMMIT`; (c) `restore()` puts the switch back. Founder's phone: NOT RUN (his instruction);
  build delivered through Play for his ordinary use.
- The failure path itself: JVM rig only, stated in `hardware-uat.json` as NOT STAGEABLE on a device.

### 11.2 Other obligations

| Test | Class | Proves | Revert that turns it red |
|---|---|---|---|
| `DictationSessionCoordinatorTest.aFailedSettingsReadStartsCaptureOnDefaults` (proposed) | Product Outcome | a settings flow that throws: capture is asked to start within the rig's bound, the take completes and inserts, `settings_fallback` names `settings` | restore the ending in `beginSession` |
| `DictationSessionCoordinatorTest.aFailedVocabularyReadStartsCaptureWithoutUserTerms` (proposed) | Product Outcome | a terms flow that throws: capture starts, the take inserts, the matcher is the built-in one | same |
| `DictationSessionCoordinatorTest.aSilentStoreStartsOnTheLastSnapshotAfterTheBound` (proposed) | Product Outcome | never-emitting flows: capture starts after the bound, `timed_out` in the facts | make the bound end the take |
| `DictationSessionCoordinatorTest.anOrdinaryTakeFreezesOneConsistentSnapshot` (proposed) | Drift Guard | the flows emit once after `onCreated`; the take waits for them and runs with those values (auto-stop on), `settings_fallback` null | start before the first answer |
| `TelemetryContractsTest` / `TakeFactsTest` rows | Drift Guard | the property and its token shape | drop the property |
| `settingsNeverReadyEndsStartingWithSentence` | deleted | | |

## 12. Blast radius & rollback
Every take's start passes through `beginSession`; the change is the shape of one wait. Rollback: revert the
PR. No schema, no migration, no stored format.

## 13. Ship criteria specific to THIS change
- P1 red-by-today then green; the four rows green; receipts red.
- P2 measured on the emulator: readers' answer time under 500 ms on three cold starts, or the plan is
  re-examined before shipping.
- Emulator (a), (b), (c) as in §11.1; Codex all-clear with a confirming rerun.

## 14. Open questions
- Should a `Failed` collector be restarted on the next take (a retry of the read) rather than left ended? Default
  here: no; the next take fails open the same way and the process restart heals it. The coverage round may
  raise it.
- The stand-in clipboard policy on a failed first read (auto-copy `true`): keep today's null branch, or make
  the stand-in auto-copy OFF? Default here: keep (a lost sentence for everyone else outweighs a surprise copy
  for the one user who turned it off, and the notification already says nothing).
- A one-line notice for the wrong-not-broken case (§8)? Default: no.

## 15. Related
#69 (frozen per take), #186 (the source extracted), #194 (content-free diagnostics), #35 (diagnostics
screen), REF-02 in `docs/audits/2026-09-20-senior-audit.md`.
