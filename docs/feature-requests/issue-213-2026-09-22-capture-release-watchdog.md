# Issue #213 — A recorder that was never released ends its process at the next start instead of refusing every later take — 2026-09-22

GitHub issue: `#213`. Tier: MEDIUM (audio capture, one service). Status: DRAFT (revised after grounded round 2).

## Preface — Lane + Hardware UAT declaration

**Lane:** Code
mixed_pr: true — `Code` (`app/**`: `tests`, `codex-review`, `hardware-uat`) and `Docs/dev-tooling` (this plan and `scripts/uat/`: `cited-symbols` conditionally).

**PAR rows closed:** none.

**Hardware UAT:** Y. Capture is the heart. Success for a person: after a take whose recorder was never released, one take fails with the existing "microphone stopped" message and the take after it lands, instead of every take failing until Android happens to end the capture process. The stuck recorder is staged on the emulator by suspending only the capture thread (`freeze_thread`, §11.1). Founder's phone: queued with #115, #161 and #212.

## Preface — User Rubric

1. **Who.** Meera Patel, one-handed in Google Messages, dictating short replies back to back.
2. **Why.** "It just stopped working until I restarted my phone."
3. **How.** The side button, several takes in a row.
4. **App.** Google Messages, WhatsApp; Gmail for the UAT.
5. **Input.** "On my way." / "Can you grab milk" / "Running ten minutes late" / "Yes that works" / "Call me when you land".
6. **Success.** One take says the microphone stopped; she presses again and it works.
7. **Wrong-not-broken.** Today, once a recorder is stuck, every later take refuses to start until Android ends the audio process on its own; she concludes the app is broken.
8. **Hack.** Force-stopping the app or rebooting.
9. **Control.** None; recovery is automatic.

### Cross-persona check
Frank Chen would never find the force-stop; automatic recovery matters most to him.

## 0. TL;DR
The capture process allows one `AudioRecord` at a time through the process-wide `RecorderLease`, released only after the recorder is (#115). Two producers can leave it held forever: a `release()` that throws (`closeResources` keeps the lease on purpose), and a capture thread that never returns from its read after `stop()` (only that thread releases). Today every later start in that process is then refused ("A recorder is still held in this process") until Android ends the process.

Fix, decided AT THE NEXT START rather than by a background timer: the lease records which capture holds it and whether that capture is still recording. A start that finds the lease held by a capture that has already been told to end (or whose release failed) condemns that holder with a compare-and-set and ends the `:audio` process, after writing a content-free pending defect. The owner of the starting take sees the capture process die (`AUDIO_PROCESS_DIED`, "microphone stopped"); the next start is in a fresh process. A start that finds the lease held by a capture still recording is refused exactly as today.

Why not the audit's background watchdog: the coverage round showed that a timer ending the process can race the delivery of a finished take's ending to the owner (the ending is queued after cleanup, `TakeEventPublisher.publishEnded` only offers it), can kill a warm hold, and needs a release bound calibrated on the physical phone. A decision taken at the next production start cannot race the earlier transcript: that owner has already consumed or abandoned its ending. In the release-failed case, a warm hold may still exist in the poisoned process. Recovery necessarily drops that hold; acquire-before-handOver ensures it is not consumed first. The recovery start fails, and the following take reconnects the earbuds cold.

## 1. Problem
`RecorderLease` (`audio/RecorderLease.kt`) is a bare `AtomicBoolean`. `closeResources(record, output)` (`AudioCaptureService.kt`) releases it only when `record.release()` succeeded. The only thread that releases a live take's recorder is its capture thread, in `releaseSession`, after its loop exits; `endTakeLocked` calls `record.stop()` to unblock the read. If the read never returns or `release()` throws, `startRecording` refuses every later take. Classification: HYPOTHETICAL (a misbehaving platform dependency; no occurrence in `session-log.md`). Fixed because the failure is total and the audit ranked it HIGH.

## 2. Goals & non-goals
### 2.1 Goals
- A start that finds the lease held by a capture that was told to end, or whose release failed, ends the `:audio` process, so the take after it starts in a fresh process.
- A start never ends the process while the holder is still recording (a live take), and a holder that released in the meantime is never condemned: the decision is one compare-and-set on the holder's identity AND state.
- Every successful lease acquisition ends in exactly one of: a successful release by its token, or a condemnation. This covers the two pre-session failure paths (`failSetup`, the capture-thread start failure) as well as a live take.
- A refused or condemning start never consumes a warm hold: the lease is acquired before `warmHoldOwner.handOver()`.
- A content-free pending defect (`AppDefect.CaptureReleaseWedged`, proposed) is written, bounded, before the process ends, in the `SilenceVadService` shape.
### 2.2 Non-goals
- A proactive background watchdog (rejected above). Cost of the choice: the first start after a wedge fails once, with the existing message, before the next succeeds.
- An owner-side automatic retry after `AUDIO_PROCESS_DIED` during a start: would hide that one failed take, but is a change to the session owner; a follow-up if the phone pass shows it matters.
- An injectable `AudioRecordPort`. The decision logic is a pure lease with JVM rows; the platform wedge is staged on the emulator by suspending the capture thread.

## 2.5 Grounding brief
### 1. Producer → owner → consumer
Lease: acquired in `startRecording`; released in `closeResources(record, output)`, whose callers are `closeResources(active, keepRoute)` (from `releaseSession`), `failSetup` and the capture-thread start failure path; read nowhere else (`/usr/bin/grep -rn "RecorderLease" app/src`: `AudioCaptureService.kt` and `RecorderLeaseTest.kt`). Endings are claimed in `claimEnding`, reached from `endTakeLocked` (manual stop, silence, route failure, `onDestroy`'s stop) and directly from the capture loop (both caps, a capture error).
### 2. Existing authority
`SilenceVadService.terminateDetectorProcess`: a pending defect on its own thread bounded by `PENDING_DEFECT_BOUND_MS` (500 ms), then `Process.killProcess(Process.myPid())`, then park. `Telemetry.recordPendingDefect` converts the note at main's next start.
### 3. Prior attempts
#115 introduced the lease; its review round 2 decided a failed release keeps it. Audit `docs/audits/2026-09-22-senior-audit.json` REF-08.
### 4. Boundaries
`sessionLock` is per service instance; the lease is per process. A start in a replacement instance cannot see the old instance's `session`, only the lease, so the lease carries the state the decision needs. `:audio` also hosts the warm hold and the take-event publisher: at a new production start, the earlier take's owner session has already ended (a new take is admitted only after it), so the publisher owes it nothing; a warm hold left by a take whose release failed is dropped with the process (section 0), which is the one thing of value recovery costs.
### 5. Premises
- A production start reaches the capture process only through startCaptureForTake after the session owner has finished or abandoned the earlier take. Side button, tile, notification and lips bubble all share that owner (`DictationSessionService.sendCommand`, `DictationSessionService.kt:64`; the owner refuses a start unless IDLE, `DictationSessionCoordinator.kt:293`). Owner destruction commits interruption and unbinds before a replacement owner can use the production path.

- The legacy startCapture, startCaptureWithSilenceStop, startCaptureWithInputDevice and startCaptureWithInputDeviceHeld transactions do not carry this proof. They remain refusal-only: a held lease returns the existing start refusal and never condemns or ends the process. Only startCaptureForTake is allowed to recover an abandoned lease. This preserves the append-only instrumentation contract without trusting an old client's call ordering.

## 3. Design
`RecorderLease` becomes a state machine over one `AtomicReference`:

```kotlin
internal class RecorderLease {
    /** FREE is null; CONDEMNED is terminal for the process. */
    private val holder = AtomicReference<Holder?>(null)

    data class Holder(val token: Long, val state: State)
    enum class State { RECORDING, ENDING, RELEASE_FAILED, CONDEMNED }

    fun acquire(token: Long): Boolean = holder.compareAndSet(null, Holder(token, State.RECORDING))

    /** The capture was told to end; from here a later start may condemn it. */
    fun markEnding(token: Long) = transition(token, State.RECORDING, State.ENDING)

    /** The recorder's release threw; the lease stays held and is condemnable at once. */
    fun releaseFailed(token: Long) { while (true) { val h = holder.get(); if (h == null || h.token != token || h.state == State.CONDEMNED) return; if (holder.compareAndSet(h, h.copy(state = State.RELEASE_FAILED))) return } }

    /** Released by the capture that holds it; false if it was condemned first. */
    fun release(token: Long): Boolean { while (true) { val h = holder.get(); if (h == null || h.token != token || h.state == State.CONDEMNED) return false; if (holder.compareAndSet(h, null)) return true } }

    /** A start's decision: true exactly when the holder was not recording and now nobody can ever acquire. */
    fun condemnAbandoned(): Boolean { while (true) { val h = holder.get() ?: return false; if (h.state == State.RECORDING || h.state == State.CONDEMNED) return false; if (holder.compareAndSet(h, Holder(h.token, State.CONDEMNED))) return true } }

    val isHeld: Boolean get() = holder.get() != null

    private fun transition(token: Long, from: State, to: State) { val h = holder.get() ?: return; if (h.token == token && h.state == from) holder.compareAndSet(h, Holder(token, to)) }

    companion object { val PROCESS = RecorderLease() }
}
```

(`Holder` is a data class, so `compareAndSet` compares by reference: every loop reads `h` and swaps exactly that instance.)

`AudioCaptureService.startRecording`:
1. prepare AudioManager and the routing-listener slot, mint the token, then acquire(token) before TakeRoute.newHold and warmHoldOwner.handOver();
2. on acquisition failure: when the caller is startCaptureForTake, if RecorderLease.PROCESS.condemnAbandoned() succeeds, log `A recorder was never released; ending the capture process` and call endCaptureProcess() (never returns); otherwise refuse as today. Every legacy start transaction always refuses and never calls condemnAbandoned(). `startRecording` gains a `mayRecover: Boolean` parameter, true only from `startCaptureForTake`;
3. immediately after a successful acquire, enter one enclosing try that begins with TakeRoute.newHold and covers handOver, route resolution, buffer sizing, recorder construction, file creation, session construction and thread start. routeHold, record and output are nullable until created. Every explicit failure return and every exception before capture-thread ownership transfers calls one failSetup(..., token) cleanup, which releases the route and resources and then performs release(token) or releaseFailed(token) (through `closeResources(record, output, token)`, which releases with `release(token)` on success and calls `releaseFailed(token)` on a throw);
   failSetup gains failure: Int and routeHold: RouteHold?. It assigns lastStartFailure = failure instead of unconditionally assigning START_FAILURE_OTHER. The no-device result passes START_FAILURE_NO_INPUT_DEVICE; buffer-size failure and every other pre-session exception pass START_FAILURE_OTHER. It keeps the existing ordering: routeHold?.release(), token-scoped resource cleanup, stopSelf(), then exactly one publishStartRefused with that failure code.

   When threadStarted && active != null, callers pass START_FAILURE_OTHER and the existing first branch remains unchanged: endTakeLocked(active, TERMINAL_REASON_ERROR), then return without local resource or lease cleanup because the capture thread owns both.
4. ownership transfers only after the capture thread successfully starts. The thread-start failure still cleans up locally with the token. Any later exception claims the session ending and leaves the capture thread as the sole cleanup owner. There is no return or throwing platform call between acquire(token) and the enclosing try.
5. `claimEnding`'s winning path calls `RecorderLease.PROCESS.markEnding(active.token)`.

`endCaptureProcess()` is the `terminateDetectorProcess` shape: first-wins flag, pending defect on its own thread joined for at most 500 ms, `Process.killProcess(Process.myPid())`, park. To keep it testable it delegates to a small `ProcessEnd` (proposed) with an injected note writer and killer.

## 4-9. Contract, lifecycle, consumers, failure modes, signals, fallback
- Contract: none on AIDL. A condemning production start ends :audio during the owner's start. tryStartRecording maps a DeadObjectException from startCaptureForTake to AUDIO_PROCESS_DIED; onCaptureDisconnected proposes the same reason. The arbiter therefore makes the outcome deterministic regardless of callback order. Other start exceptions remain START_EXCEPTION. (This one mapping is the only session-owner change; `DictationSessionCoordinator.kt:526` today posts `START_EXCEPTION` for every throw.)
- Lifecycle: FREE → RECORDING (acquire) → ENDING (claim) → FREE (release) | RELEASE_FAILED (release threw) ; ENDING | RELEASE_FAILED → CONDEMNED (a later start). Pre-session failures: RECORDING → FREE | RELEASE_FAILED.
- Failure table: normal take → FREE long before any later start; stuck read → holder stays ENDING → next start condemns, process ends, the take after lands; release throws → RELEASE_FAILED → next start condemns; a replacement instance starting while a live take records in the old one → RECORDING → refused as today.
- Signals: new error `A recorder was never released; ending the capture process` (content-free, in the log baseline); new `AppDefect.CaptureReleaseWedged` (fingerprint `capture_release_wedged`, in the telemetry snapshot).

## 10. Files
- `app/src/main/java/com/envi/wispr/audio/RecorderLease.kt`: the state machine.
- `app/src/main/java/com/envi/wispr/audio/ProcessEnd.kt` (proposed): note, bounded join, kill, park.
- `app/src/main/java/com/envi/wispr/audio/AudioCaptureService.kt`: token before acquire, acquire before the route hold and hand-over, the condemn branch, token-scoped release, `markEnding` in `claimEnding`.
- `app/src/main/java/com/envi/wispr/telemetry/DefectIdentity.kt` and the telemetry snapshot test: the new member.
- `app/src/main/java/com/envi/wispr/ui/DictationSessionCoordinator.kt`: `tryStartRecording` maps `DeadObjectException` to `AUDIO_PROCESS_DIED`; row 17 in `DictationSessionCoordinatorTest`.
- Tests: `RecorderLeaseTest` (its shape rows move to the token forms), `ProcessEndTest` (proposed), `AudioServiceShapeTest` (allowlist and log baseline).
- `scripts/uat/wispr_eyes.py`: `freeze_thread` (proposed), with rows in `scripts/uat/test_wispr_eyes.py`.

## 11. Testing
`RecorderLeaseTest` (Drift Guard, pure):
1. a lease held RECORDING is never condemned;
2. a lease ENDING is condemned once, and every later acquire is refused;
3. RELEASE_FAILED is condemned;
4. a release that wins before the condemnation leaves FREE, `condemnAbandoned` then returns false, and the next acquire succeeds;
5. a release by a token that does not hold the lease changes nothing; a release after condemnation returns false;
6. `markEnding` by a stale token changes nothing (a newer capture's RECORDING state is never turned into ENDING by an old one);
7. a race row: two threads, one releasing, one condemning, from ENDING, repeated 1000 times with a start barrier: exactly one wins each time (release true XOR condemn true).
Shape rows (`AudioServiceShapeTest` / `RecorderLeaseTest`): 8. the token is minted before `acquire(token)`, and `acquire(token)` precedes `TakeRoute.newHold` and `warmHoldOwner.handOver()`; 9. the refusal branch calls `condemnAbandoned()` then `endCaptureProcess()`; 10. the first operation after successful acquire(token) enters the cleanup-owning try; TakeRoute.newHold, handOver, route resolution, the no-device result and buffer sizing are inside it; every pre-thread return and catch reaches failSetup(..., token), while the thread-start failure reaches closeResources(..., token). The no-device path reaches failSetup with START_FAILURE_NO_INPUT_DEVICE; the buffer-size and thrown preparation paths use START_FAILURE_OTHER. Both retain stopSelf and exactly one publishStartRefused. Changing the no-device argument to START_FAILURE_OTHER must make row 10 red. 11. `closeResources` releases with `release(token)` and calls `releaseFailed(token)` on the failure branch; 12. `claimEnding`'s winning path calls `markEnding`.
16. legacy-start safety: with the lease ENDING and again RELEASE_FAILED, each legacy AIDL start path refuses and never calls condemnAbandoned or endCaptureProcess; startCaptureForTake with the same lease does condemn. (A shape row over the five binder methods' `mayRecover` arguments plus the branch in `startRecording`, since the service cannot be constructed in a JVM test.)
17. owner outcome (`DictationSessionCoordinatorTest`, Contract): stage startCaptureForTake throwing DeadObjectException and onCaptureDisconnected in both orders; exactly one terminal result is committed and it is AUDIO_PROCESS_DIED in both orders.
18. production defect wiring: AudioCaptureService's ProcessEnd is constructed with Telemetry.recordPendingDefect(..., AppDefect.CaptureReleaseWedged, ...); removing or substituting that identity makes the row red. The existing telemetry snapshot separately pins capture_release_wedged.
`ProcessEndTest` (Drift Guard): 13. the note writer starts before the killer runs; 14. a note writer that never returns does not delay the kill past the bound (the join is bounded; the row injects a latch-held writer and asserts the killer ran, with the join bound injected small); 15. two concurrent ends run the note once.
Receipts: plain-set release (row 4/5), condemn without the RECORDING check (row 1), markEnding without the token check (row 6), acquire moved after the hand-over (row 8), the condemn branch removed (row 9), `markEnding` removed from `claimEnding` (row 12), kill before note (row 13), unbounded join (row 14); move TakeRoute.resolve outside the cleanup-owning try, or add a direct return after acquire that bypasses failSetup (row 10); allow a legacy binder transaction to condemn (row 16); map DeadObjectException to START_EXCEPTION or remove either callback ordering (row 17); replace CaptureReleaseWedged with another defect or remove the production pending-defect writer (row 18).

### 11.1 Hardware UAT
Emulator, `wispr-eyes`, debug build:
- (w) The wedge: add `freeze_thread(process, thread_name)`. Refactor the debugger helper into one long-lived commandable JDB session: list threads, require exactly one live `AudioCaptureThread`, issue `suspend <id>`, wait for the thread-specific suspension receipt, and assert that the process still answers a binder probe. Journal pid, port, debugger pid and thread id before suspension; `restore()` disconnects the debugger, removes the forward and verifies the surviving process answers. Refuse on zero or multiple matching threads. During a live take, suspend `AudioCaptureThread`, then stop the take. This stages the invariant being protected (the sole cleanup thread cannot reach `releaseSession`, the lease stays held); it does not prove a vendor `AudioRecord.read()` ignored `stop()`, which is NOT RUN. Expected after the fix: the owner ends that take (`AUDIO_PROCESS_UNRESPONSIVE`); the next start logs `A recorder was never released; ending the capture process`, the `:audio` pid changes and that take ends `AUDIO_PROCESS_DIED`; the take after that lands in Gmail by the editor's own text. Before the fix (a baseline build): every later take is refused.
- (o) Ordinary and back-to-back takes: no `never released` line and the `:audio` pid never changes.

## 12. Blast radius & rollback
One service in `:audio`. A defect in the lease can refuse every take (rows 1 to 7, the emulator takes) or end the capture process at a start that did not need it (rows 1 and 6; costs one failed take). Rollback: revert the PR.

## 13. Ship criteria
Rows 1 to 18 green with every receipt red; unit count reported; emulator (w) and (o); Codex code review ALL-CLEAR with a confirming rerun.

## 14. Open questions
None.

## 15. Related
#115 (the lease), #212 (a replacement service record can overlap destruction; the reason for identity-based decisions), audit REF-08.
