# Issue #306: invalidate the session before the language detector closes, and close it off main (2026-09-24)

GitHub issue: `#306`. Tier: MEDIUM (the dictation Service's teardown). Status: revised after the coverage round (`306-cov`), all findings adopted; grounded round 1 (`306-g1`), two statements tightened.

## Preface: Lane + Hardware UAT declaration

**Lane:** Code

**PAR rows closed:** none.

**Hardware UAT:** Y, light. Emulator: a take lands its words; the Service is destroyed after it (the ordinary path) and the detector's close is logged off the main thread.

## Preface — User Rubric

User Rubric: persona, the founder dictating on his S26. Nothing he sees changes on a normal take. Today, when the dictation Service shuts down, it first closes the language model on the main thread (a vendor call with no time limit) and only then stops the session; a slow close could freeze the screen for that moment and let a late session callback run against a closed detector. After this change the session is stopped first and the model is closed in the background.

---

## 0. TL;DR

REF-03 of `docs/audits/2026-09-24-senior-audit.json` (`.claude/rules/kotlin-patterns.md` RULE: never-block-a-binder-or-ui-thread). `DictationSessionService.onDestroy` calls `languageDetector.close()` on main, then `coordinator.destroy()`. Reverse the order, and hand the detector to `BackgroundCloser.PROCESS`, one process-wide closer whose worker thread expires after five idle seconds, so the Service's call to the vendor's synchronous `close()` never runs on main. `MlKitLanguageDetector`'s in-flight protection (#279) is unchanged: a detection still counted in keeps the client open until it leaves.

Consolidation: none; one Service's teardown changes, and one small closer is added.

Prior context: #279 (the detector's release paths), #291 (the polish process already closes its detector on its fallback lane's worker, off its binder and main threads), #115 (owner destroy never blocks).

## 1. Grounding (main aef5dde)

- `DictationSessionService.onDestroy`: `if (::languageDetector.isInitialized) languageDetector.close()`, then `if (::coordinator.isInitialized) coordinator.destroy()`, then `stopForeground`, dismiss, `super.onDestroy()`.
- `MlKitLanguageDetector.close`: sets `closed`, then releases the client only when no detection is counted in (else the last detection out does); the release is `LanguageIdentifier.close()`, a synchronous vendor call.
- The only other production closer: `PolishService.onDestroy` runs `fallbackLane.close { languageDetector.close() }`, which normally closes on the lane's worker; if that worker rejects the task, the fallback lane starts a daemon thread (#291). `AudioCaptureService`'s `detector.close(...)` calls are the silence detector, a different class.
- `git grep -n "languageDetector.close\|MlKitLanguageDetector(" -- app/src/main` lists exactly those two services.
- Scope of this change (coverage finding A): the `:polish` service queues its close behind fallback answers; if that worker rejects it, the fallback lane starts a daemon thread; its process-kill branch does not call close. In the app service, `coordinator.destroy()` has no direct detector use after the reorder, but a detection already in flight may finish later. The detector can also call the synchronous vendor `close()` on the last detection's caller thread, or when a competing client acquisition releases its duplicate. Moving the Service's close therefore does not put EVERY vendor close on `BackgroundCloser`: it moves the one the Service makes from main. The detector's other callers (worker, IO, binder and polish callback threads) are named by its class doc; their call sites are not re-read here.
- Ownership (coverage finding A): the audit asks for a single application-owned IO close worker. `BackgroundCloser.PROCESS` is one per app process, created on first use and shared by every Service instance, like the History queue; it lives in the process, not in any Service, so a Service's destroy cannot cancel it.

## 2. Design

1. `polish/BackgroundCloser(executor: Executor)` with `close(resource: Closeable)`: with `PROCESS`, queues `resource.close()` and returns without waiting for it (an injected executor may run it inline in tests); a close that throws an `Exception` is logged by class name only (a VM error still propagates, as the detector's own policy). If the executor rejects the task, it starts a short-lived daemon thread for that one close instead (coverage finding B): never the calling thread, which may be main, and a close is not dropped if the fallback thread starts; a close taken this way has no order against the queue. `BackgroundCloser.PROCESS` uses a `ThreadPoolExecutor(0, 1, 5 s keep-alive, unbounded queue)` with a daemon thread named `DetectorClose`: no permanent idle thread; the worker expires after five idle seconds (`architecture-rules.md` RULE: no-idle-cost). Accepted closes run one at a time in submission order.
2. `DictationSessionService.onDestroy`: `coordinator.destroy()` FIRST, then `BackgroundCloser.PROCESS.close(languageDetector)`, then the rest unchanged.
3. `PolishService` is unchanged: its close already runs off main on its lane.

## 3. Tests

1. `DictationSessionServiceTeardownTest` (source row): in `onDestroy`, `coordinator.destroy()` precedes the detector's close, the detector is closed through `BackgroundCloser.PROCESS.close(languageDetector)`, and the Service source never calls `languageDetector.close()` directly. RED on main aef5dde (the order is reversed there), GREEN after. MUTATION m1: restore the original order.
2. `BackgroundCloserTest`: a closeable that records its thread and blocks on a latch; `close` is called from a separate test thread joined with a timeout (so m2 fails instead of hanging); it returns while the resource is still inside `close()`, and the recorded thread is neither that caller nor the test thread. MUTATION m2: close on the calling thread.
3. A rejecting executor: the resource is closed exactly once, on a thread that is not the caller's. MUTATION m3: drop the rejection fallback (the resource is never closed).
4. A resource whose close throws, through an injected DIRECT executor (so no worker replacement can mask it; coverage finding C): the first `close` does not throw, and a second `close` then runs. MUTATION m4: drop the catch.

## 4. Blast radius

The Service's teardown order and the thread the detector closes on. A late detection counted in before the close still completes (#279). If the process dies before the background close runs, the OS reclaims the model with the process. Rollback: revert the squash commit.

## 5. Ship criteria

- [ ] Row 1 RED before and GREEN after; rows green, m1 to m4 RED; the suite green; app and androidTest build; checks clean.
- [ ] Emulator: a take lands its words; the close is logged off main after the Service's destroy.
- [ ] Codex code review ALL-CLEAR with a confirming round.
