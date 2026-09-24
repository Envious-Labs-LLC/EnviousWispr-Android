# Issue #357: a stuck speech process ends itself, so the next take gets a fresh one (REF-02, regrade 8) (2026-09-24)

GitHub issue: `#357`. Tier: MEDIUM (`:asr` lifecycle, heart path). Status: built 2026-09-24, before the code review round.

## Preface: Lane + Hardware UAT declaration

**Lane:** Code

**PAR rows closed:** none.

**Hardware UAT:** light. An emulator take still lands its words with the watchdog armed. The wedge itself is staged in the JVM tests; staging it on the emulator would need a debugger suspend of `AsrTranscriptionThread` (the #213 recipe), which is optional.

## Preface: User Rubric

Persona: the founder after a take that hit #356's bound. #356 ends that take with "Speech service stopped answering. Try again." But the `:asr` process is still alive, its one worker (`AsrTranscriptionThread`) still inside the native decode, which ignores `Thread.interrupt` (`RecognizerOwner` KDoc). The next take's request queues behind it and hits #356's bound too, and so on until Android kills the process. Unbinding does not help: `RecognizerOwner.close` queues the release behind the stuck decode and never ends the process.

## 0. TL;DR

- `:asr` bounds its own native work. `AsrService` arms a hard deadline around the model load and around each decode, on its own scheduler thread. The polish engine already has this primitive (`polish/EngineDeadline`, #75 and #344), so it is moved to a neutral package and shared, never copied.
- The decode bound is #356's request bound plus a grace: `AsrBounds.requestBoundMs(audioMs) + DECODE_GRACE_MS` (5 s). #356's bound counts from the request, which also covers the queue and the file read, so the owner's own ending ("stopped answering") usually comes first. It is not guaranteed (coverage round): the owner's bound uses the larger of the file and the capture clock while `:asr` sees only the file, the legacy entry points have no owner bound, and a delayed main-thread post can lose the race. Then the take ends `ASR_PROCESS_DIED` ("stopped before transcription finished"). Both endings are bounded. The formula moves from `SpeechWait` to `asr/AsrBounds`, so both sides read one owner.
- The load bound is 60 s, matching the polish engine's `MODEL_LOAD_DEADLINE_MS`.
- On expiry: `isReady` reports false from then on, the reason is logged (shape only), and the process ends itself (`Process.killProcess(myPid())`, as `PolishService.endProcess` and `SilenceVadService` do). Work that returns after its expiry delivers nothing.
- The owner already handles the result: a take still waiting sees the disconnect (`ASR_PROCESS_DIED`); a take #356 already ended ignores it; the next take binds a fresh `:asr` (the binding is `BIND_AUTO_CREATE`, confirmed in the coverage round).

## 1. Scope and non-scope

- In scope: the watchdog around load and decode (both the versioned and the legacy entry points share `doTranscribe`), the move of `EngineDeadline` and the bound formula, tests, a shape guard.
- Not in scope: a pending-defect note before the kill (the `SilenceVadService` pattern). The owner already records the take's `ASR_PROCESS_UNRESPONSIVE` ending; the note would only add the process side. Filed as a follow-up if the coverage round thinks otherwise.

## 2. Async edges

| Class | Case | Answer |
|---|---|---|
| Interrupted | decode finishes as the timer fires | `EngineDeadline.Handle` decides once, atomically: a decode that loses delivers nothing; a timer that loses does nothing. |
| Interrupted | `onDestroy` during a stuck decode | The release queues behind the decode as today; the scheduler is shut down with its armed task still due (default ScheduledThreadPoolExecutor policy runs existing delayed tasks after shutdown, confirmed in the coverage round), so the watchdog still ends the process. |
| Concurrent | a second request queued behind a stuck one | It never starts, so it never arms; the first one's expiry ends the process and both callbacks die with it (the owner sees one disconnect). |
| Stale | a late decode after expiry | Delivers nothing; the process is ending. |
| Absent | the model never verified | `initRecognizer` returns null quickly; the load deadline is cancelled. |

## 3. Tests

- `asr/AsrWatchdogTest` (JVM, real single-thread scheduler, latches, no sleeps): work in time returns its value and never ends the process (m1: the handle never cancelled); work past its bound ends the process once with its reason and marks the watchdog wedged (m2: no expiry action); work returning after its expiry delivers nothing (m3: late work delivers).
- `AsrWatchdogTest` row 4 is a drift guard: the load and `doTranscribe`'s decode both run inside the watchdog, and `isReady` drops once it fires (m4: the decode unguarded). `AsrServiceShapeTest` pins the new `onDestroy` statement.
- `SpeechWaitTest` keeps its literal bounds (20.5 s, 50 s, 80 s), so moving the formula cannot change them silently.

## 4. Coverage round (Codex, 2026-09-24)

- Not already solved: `:asr` had no watchdog and the owner no work bound; a new bind alone does not replace a live wedged process.
- `EngineDeadline` alone moves, to `process/EngineDeadline.kt`; the polish-only helpers stay in `polish/EngineDeadline.kt`.
- `shutdown()` keeps an armed delayed task (Android's ScheduledThreadPoolExecutor default); `shutdownNow()` would cancel it. `onDestroy` uses `shutdown()`, and `AsrServiceShapeTest` pins the new statement.
- After a self-kill the open take handles the disconnect; a take #356 already ended ignores it; the next take binds with `BIND_AUTO_CREATE` and gets a fresh process.
- The ordering is "usually", not "always" (section 0).
- The load bound: the emulator's cold Parakeet load measured 4.2 s (`Recognizer initialized in 4160ms`, 2026-09-24), so 60 s is about fourteen times it. A load expiry during a waiting take ends it `ASR_PROCESS_DIED`.
- `isReady` includes the wedged state.

Mutations m1 to m4 are RED on fresh compiles.
