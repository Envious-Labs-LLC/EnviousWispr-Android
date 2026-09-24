# Issue #356: a take stuck in Processing ends after a bounded wait (REF-01, regrade 8) (2026-09-24)

GitHub issue: `#356`. Tier: LARGE (heart path, a new terminal reason). Status: built 2026-09-24, before the code review round.

## Preface: Lane + Hardware UAT declaration

**Lane:** Code

**PAR rows closed:** none.

**Hardware UAT:** light. An emulator take still lands its words with the deadline armed. The hang itself cannot be staged on hardware without a debug seam; the unit rig stages it.

## Preface: User Rubric

Persona: the founder after pressing stop. The recorder shows Processing until the speech process answers. `DictationSessionCoordinator.continueAfterEnding` calls `speechService.transcribeFileForTake` and waits with no deadline. A dead `:asr` process is handled (`onSpeechDisconnected` ends the take with `ASR_PROCESS_DIED`). A `:asr` process that is alive but never answers (a native decode that never returns, a worker blocked behind one) leaves the take in PROCESSING until the user cancels it. The user can cancel, but nothing tells them to, and the recorder looks busy forever.

## 0. TL;DR

- The owner arms one speech deadline when it issues the request, on the same main-thread delayed post the capture silence bound uses (`CaptureSessionController`, #115).
- The deadline scales with the recording, because a long take is slow to decode even when healthy: `SpeechWait.BASE_MS` (20 s) plus `SpeechWait.PER_AUDIO_SECOND_MS` (0.5 s) per audio second (coverage round: 1 s per second held a stuck ten-minute take for ten minutes). Measured S26 decode is RTF 0.05 to 0.12 (`session-log.md`), so a ten-minute take decodes in about 72 s at worst, and its deadline is 320 s. The base covers a cold model load queued ahead of the decode. The length is the larger of the finished file's and the capture clock's last tick, so a failed file read never shrinks the bound to the base.
- On expiry the owner claims the take through `TakeArbiter` with a new `TerminalReason.ASR_PROCESS_UNRESPONSIVE(TerminalResult.ASR_INTERRUPTED)`, the twin of `AUDIO_PROCESS_UNRESPONSIVE`. It marks the History row `STATUS_ASR_ERROR`, deletes the captured file like every other speech failure, and shows one notice: "Speech service stopped answering. Try again."
- `ui/SpeechWait` owns the bound: armed BEFORE the request, so no answer can arrive ahead of it; exactly one of the first speech callback (`answer`), any other ending (`close`: disconnect, a thrown request, `finishSession`, `destroy`) or the expiry wins, once.
- A callback that loses is late and touches nothing: not the file (whatever closed the wait deleted it, and a late delete could hit the next take's reused capture file), not the take's facts, not `polishAndPublish`. The coverage round showed the old `onResult` deleted, stamped and entered publication before any arbiter check.

## 1. Scope and non-scope

- In scope: the owner-side deadline, its terminal, the late-answer guard, the notice, telemetry mapping for the new reason (both `TelemetryChannels` lists that name `AUDIO_PROCESS_UNRESPONSIVE`), and the tests.
- Not in scope, filed as #357 (REF-02): ending the stuck `:asr` process so the NEXT take gets a fresh one. Until #357 lands, a take started after a timeout queues behind the stuck decode on the same worker and times out too. That is a bounded, visible failure, never an endless Processing.
- Not in scope: keeping the recording after a speech failure. Every speech failure deletes the file today; losing a recording is #43.

## 2. Async edge enumeration (async-edge-case-enumeration)

| Class | Case | Answer |
|---|---|---|
| Interrupted | `:asr` dies while waiting | `onSpeechDisconnected` claims first; the deadline disarms. |
| Interrupted | owner destroyed | `destroy` claims `INTERRUPTED_PROCESSING`; the deadline disarms and any later firing loses the claim. |
| Concurrent | answer and expiry race (the answer may arrive on a binder thread in the rig, on main in production) | One `AtomicBoolean` in `SpeechWait`: exactly one of `answer` and the expiry wins. |
| Concurrent | user cancel during PROCESSING | `cancelProcessing` reserves the take; `finishSession` closes the wait and deletes the file; a later expiry finds the wait closed. |
| Stale | answer after the deadline, a cancel or a destroy | `answer()` is false; the callback returns before any delete, stamp or publication, with a shape-only log line. |
| Stale | a bound firing after its answer, while polish runs | Impossible: the answer removes the post and closes the wait, so the expiry finds nothing to claim. |
| Absent | `audioFilePath` missing or no speech link | Those paths end before the request; no deadline is armed. |
| Mutated | recording length unknown | The bound uses the larger of the file's length and the capture clock's last tick, so a failed file read does not shrink it to the base. |
| Deleted | the captured file deleted before decode | Handled by `:asr` as `AUDIO_MISSING`, a failure answer that disarms. |

## 3. Tests (rig: `DictationSessionRig`, which already stages PROCESSING and the speech link)

- Row 1: a speech request that never answers ends the take at the deadline with `ASR_PROCESS_UNRESPONSIVE`, the error History status, the notice, and the file deleted. MUTATION m1: no deadline armed.
- Row 2: an answer after the deadline never publishes and never reaches the next take. MUTATION m2: the late-answer guard removed.
- Row 3: an answer before the deadline disarms it; nothing fires later. MUTATION m3: no disarm on answer.
- Row 4: the deadline scales with the recording length: a long take's request is still open after the base. MUTATION m4: the base alone.
- Row 5: cancel during PROCESSING disarms. MUTATION m5: no disarm on cancel.
- The telemetry and notice tables that enumerate `TerminalReason` gain the member; exhaustive `when`s make the compiler find them.

## 4. Coverage round (Codex, 2026-09-24)

Confirmed nothing else bounds the speech wait (the polish watchdog starts after the answer; `SpeechProxy` has no timeout). Four findings, all adopted: the owner is the place; 0.5 s per audio second with the larger of the file and tick lengths; the late-answer guard at the start of every callback; close on every ending including a thrown request, since a take-id-only post would let an old bound claim a take that is still polishing.

Mutations m1 to m5 are RED on fresh compiles.

## 5. Code review round 1 (Codex)

Four findings, all adopted:

- Registration raced a cancel, disconnect or destroy: a close that ran before the arm saw nothing, and the arm then posted a bound for an ended take. `SpeechWait` is now one per owner (the owner admits one take) with states NEW, OPEN and DONE under one lock; a close before the arm records DONE, so the arm is refused, the request is never sent and the file is deleted.
- A request that threw after its bound deleted the file again and rewrote the row. The catch now cleans up only when it wins the close; otherwise it logs and returns.
- The post and its removal are under the same lock as the state, so no post outlives a close.
- Tests: row 2 recreates the capture file as the next take would and shows the late answer leaves it; row 4 fires the base and then the scaled bound; row 6 pins the capture clock source; row 7 pins the refused arm; row 8 pins the late throw (the rig's speech link can hold a request and then throw).

Mutations m1 to m8 are RED on fresh compiles.

## 6. Code review round 2 (Codex) and the class sweep

Two findings of one class, the second round of it, so the class was enumerated from the producer before fixing.

**Class:** a History write for the take made on the owner's worker before that path holds the take's ending, so it can land after a cancel, disconnect or destroy already ended the take on main.

**Members** (every `markStatus`, `discard` and `markInterrupted` in `DictationSessionCoordinator`): the speech callbacks, `onSpeechDisconnected`, `onSpeechUnresponsive` and `polishAndPublish` already claim first; the capture-phase discards and the cancel paths run on main, serialized with destroy. Four were on the worker with no claim: the PROCESSING status, `AUDIO_FILE_MISSING`, `ASR_NOT_READY` and the thrown request.

**Fix at the source:** the PROCESSING status is queued on main in `continueAfterEnding` before the worker starts, so every main-thread ending queues after it; the three worker failures go through `failFromWorker`, which claims before it writes.

**Untested, stated:** the destroy-then-throw gap is a window between two main-thread statements and a worker catch; it cannot be staged deterministically in the rig, and row 8 covers the reachable order (the bound first, then the throw). The existing `AUDIO_FILE_MISSING` and `ASR_NOT_READY` rows cover the claim-first path's outcome.
