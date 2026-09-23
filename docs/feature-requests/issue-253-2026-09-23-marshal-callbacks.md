# Issue #253 — Speech and polish callbacks reach the owner on main; fallback preparation runs off it — 2026-09-23

GitHub issue: `#253`. Tier: MEDIUM (the threads the heart's decisions run on). Status: APPROVED after grounded round 1 (its three wording findings adopted verbatim, confirmed in code review) (coverage round: findings 2, 3, 5 adopted; finding 4 adopted for the file delete; finding 1 and finding 4's helper calls declined with evidence, §3).

## Preface — Lane + Hardware UAT declaration

**Lane:** Code

**PAR rows closed:** none.

**Hardware UAT:** Y. Two Gmail dictations by COMMIT on the emulator, a polish-kill take (the #234 pass), and one instrumented row through the real `:asr` binder: a typed failure (a missing audio path) reaches the listener on the main looper after the binder call returned. Blank speech and a polish disconnect through a real binder are not staged on a device (no reliable fixture); JVM rows cover them.

## Preface — User Rubric

User Rubric: the words always land (founder decision 2026-09-23), and a slow fallback never holds the speech or polish process or freezes the app.

---

## 0. TL;DR

`PipelineBindings.SpeechProxy` and `PolishProxy` call the owner's listeners directly on the binder thread the callback arrived on. The owner then commits endings, writes take facts, reserves publication and enqueues History there, and `TakePolishController` runs the whole deterministic fallback (vocabulary restore, ML Kit detection up to 400 ms, cleanup) inline, on a binder thread, or on main for a disconnect. Neither AIDL callback is `oneway`, so this holds the `:asr` or `:polish` caller (REF-02 of the second 2026-09-23 audit). Post every callback value to main in the proxy, the way the capture listener already does; move fallback preparation and answer restoration to IO; hand the prepared text back on main; run the watchdog's claim on main too, so the main queue orders every contender.

## 1. Problem

Grounded by Codex (`253-g0`), re-read by Claude:
- `SpeechProxy` / `PolishProxy` (`PipelineBindings.kt`): "Every callback invokes the listener directly on the binder thread it arrived on, with no post."
- The coordinator's speech listener deletes the audio file, writes facts, and for a blank answer commits and tears down; for a failure commits and runs failure teardown.
- `TakePolishController`'s listener claims under the lock, then restores vocabulary or runs `preparedFallback` and calls `onPrepared` (the owner's `publishResult`) on that thread; `disconnected` and `claimSpeechLossFallback` run the fallback inline on main.
- `MlKitLanguageDetector` waits up to 400 ms for identification; the first client acquisition has no bound.
- The capture path already posts every pushed event to main (`CaptureSessionController`).

## 2. Goals & non-goals

### 2.1 Goals
1. The proxies post each received callback value to main before invoking the listener, always enqueueing (never running inline, even when already on main). The binder stubs stay transport only. The post is an injected `post: (Runnable) -> Unit`, `mainHandler::post` in production.
2. `TakePolishController` claims on the thread it is called on (main for callbacks and disconnects after goal 1), then prepares the text on its IO scope and hands `PreparedText` back through a main dispatcher it receives from the owner. The watchdog's claim runs on main (`withContext(main)`), so an answer already queued on main before the timeout wins as it did before.
3. The polished path's vocabulary restore runs on IO too; the hand-back is on main.
4. The speech listener's audio-file delete runs on IO; its owner decisions run on main.
5. One main-thread hand-back for polished and fallback text (coverage finding 3): before calling `onPrepared` it rejects a destroyed, cancelled or already committed take (an owner-supplied `stillPublishable()`), because `publishResult` writes take facts and telemetry before its reservation; the arbiter reservation stays the final guard.
6. The request-throw adjudication in `prepare` is posted to main before it claims (coverage finding 2), so a callback sent synchronously before the request threw is queued ahead and wins.

### 2.2 Non-goals
- No AIDL change (the callbacks stay two-way; the fix is on the receiving side).
- No change to which answer wins or to any text.

## 3. Design

- `SpeechProxy(service, post)`: each override is `post { listener.onX(value) }` with the values captured. `PolishProxy(service, post)` the same.
- `TakePolishController` gains `mainDispatcher: CoroutineDispatcher`. `fallBack(raw, prefs, reason)` becomes: `cancelOpen()`, log, `scope.launch(Dispatchers.IO) { val text = preparedFallback(...); withContext(mainDispatcher) { if (stillPublishable()) onPrepared(text) } }`; both hand-backs call the owner-supplied `stillPublishable()` on main before `onPrepared` (grounded round 1, finding 1). The polished path likewise: restore on IO, `onPrepared` on main.
- The watchdog: `timeout.await(...)` then `withContext(mainDispatcher) { if (!claim(opened)) return; … }` then the fallback as above.
- Ordering: callback, disconnect, watchdog and request-throw claims run on main, so main's queue orders them; the existing no-link decision in `prepare` remains protected by the submission lock (grounded round 1, finding 2); the ledger and the arbiter stay the first-wins guards.
- A synchronous answer is queued. The post-call check may see the request open or already claimed; its repeat cancel is a no-op for a delivered request (grounded round 1, finding 3).
- Declined with evidence (coverage finding 1, and finding 4's helper calls): the engine cancel that `cancelOpen` and `cancelClaimed` send is `oneway void cancel(long requestId)` (`IPolishService.aidl`), so it never waits for `:polish` and may stay on main; `stopAudioService` is `Context.stopService`, a system-server request Android expects on main. The claim and the ledger close stay on main; nothing else moves.
- Main keeps the owner decisions and the History enqueue (a queue append; the Room write and the insertion hand-off already run later off main). The audio-file delete moves to IO (coverage finding 4).

## 4. Contract deltas

None across processes. `TakePolishController` gains `mainDispatcher`; the proxies gain `post`.

## 5-9. State, consumers, failure modes

| Delta | Consumer | Change |
|---|---|---|
| Proxies post | `PipelineBindings` | `mainHandler::post` passed in |
| Controller main dispatcher | `DictationSessionCoordinator` (passes its `mainDispatcher`), `TakePolishControllerTest` (a single-thread main) | new parameter |
| Hand-back on main | `publishResult` | now always on main |

Failure modes: main-thread load (the preparation is off main; only the claim and the publication decision run there); a callback after destroy (the owner's `destroyed` and the arbiter refuse it).

## 10. Files

`PipelineBindings.kt`, `TakePolishController.kt`, `DictationSessionCoordinator.kt`; tests `TakePolishControllerTest`, `PolishFailsOpenTest`, a new `PipelineProxyTest`, an androidTest row.

## 11. Testing

1. A pure dispatch seam proves posting on the JVM without Binder (coverage finding 5): the proxies' callback bodies are built by the internal `PostingSpeechListener` and `PostingPolishListener` (`post` plus the listener), and `PipelineProxyTest` invokes it from a foreign thread and sees nothing reach the listener until the post runs. Mutation: call the listener directly. The rig's FakeSpeech and FakePolish still bypass the proxies, so the rig rows prove owner ordering, not posting.
2. Controller rows, with an explicit single-thread fake-main dispatcher given to the controller (coverage finding 5; today's controller tests use only an IO scope): a fallback's preparation (the `cleanup` seam) runs off the main thread, and `onPrepared` runs on it; a held preparation leaves main free to run another task. Mutations: prepare on main; hand back off main.
3. Ordering row: an answer queued on main before the watchdog fires wins; the watchdog then claims nothing. Mutation: claim the watchdog off main.
4. Destroy rows: a fallback and a polished answer prepared after the take is no longer publishable hand nothing back. Mutation: drop the `stillPublishable()` check.
4b. Request-throw ordering row: a fake link that posts an answer and then throws inside `polishRequestForTake`; the queued answer wins and no fallback is handed back. Mutation: claim the throw on IO.
5. Existing `PolishFailsOpenTest`, `WarmUpOffMainTest`, `TakePolishControllerTest` and `DictationSessionCoordinatorTest` rows pass with their assertions unchanged.
6. Device row (`am instrument`, emulator): `PipelineBindings` exposes an internal factory for its speech proxy so `androidTest` can build the production one (coverage finding 5); bind the real `AsrService`, hold main with a latch while calling `transcribeFileForTake` with a missing audio path, assert the binder call returned and the listener has not run, release main, then assert the typed failure reached the listener on the main looper. Mutation: the direct call (the listener then runs on a binder thread, before main is released).

## 12. Blast radius

Every speech and polish callback. Rollback: revert the squash commit.

## 13. Ship criteria
- [x] Rows 1 to 4b and 6 green, each mutation red; row 5 unchanged and green.
- [x] Emulator dictations and a polish-kill take by COMMIT.

## 14. Open questions
None.

## 15. Related
#115 (the capture push pattern), #237, #252, REF-02 of the second 2026-09-23 audit.

## 16. As built

- `PipelineBindings`: `SpeechProxy` and `PolishProxy` take a `post` and wrap each request's listener in `PostingSpeechListener` or `PostingPolishListener`, which post every callback's values to main and return; the service connection passes `mainHandler::post`. `SpeechProxy` is internal and reached through `PipelineBindings.speechProxy`, so the device row builds the production proxy.
- `TakePolishController` takes `mainDispatcher` and `stillPublishable`. The watchdog and the thrown request claim on main; the answer and every fallback prepare on IO through `handBack`, which hands the text back on main only while `stillPublishable()` holds and otherwise logs the drop.
- `DictationSessionCoordinator` passes its main dispatcher and `!destroyed.get() && take.arbiter.isOpen`; the speech listener deletes the captured audio on IO.
- Tests: `PipelineProxyTest` (speech and polish), `TakePolishControllerTest` rows 2, 3, 4 and 4b, `PolishPublicationRoutesTest` (one `onPrepared` inside `handBack`, one `handBack` each in the answer and the fallback), `LiveAudioMeterWiringTest` reads the renamed proxy marker, and `SpeechCallbackDeviceTest` through the real `:asr` binder. The device row counts the failure as crossed when the proxy posts it, so it waits for the binder instead of a clock. Receipts 7 of 7 RED on the JVM and the device mutant RED (the listener ran while main was held), then green once restored; full suite 1229, 0 failures; the `ui` package 20 of 20 repeats; two emulator dictations and a polish-kill take by COMMIT, each with the editor's whole text as expected.
