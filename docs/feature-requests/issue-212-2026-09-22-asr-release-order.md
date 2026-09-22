# Issue #212 — The speech recognizer is never freed under a decode that is using it — 2026-09-22

GitHub issue: `#212`. Tier: MEDIUM (one ASR engine plus audio capture across two isolated services). Status: APPROVED (grounded rounds 1 to 5 folded in; round 6 PROCEED-AS-PLANNED; absorbs #221).

## Preface — Lane + Hardware UAT declaration

**Lane:** Code
mixed_pr: true — `Code` (`app/**`: `tests`, `codex-review`, `hardware-uat`) and `Docs/dev-tooling` (this plan: `cited-symbols` conditionally).

**PAR rows closed:** none.

**Hardware UAT:** Y. ASR is the heart. Success for a person: they dictate a long sentence into Gmail, cancel while the pill is processing, then dictate again at once; the second take lands in the field. Evidence: no native crash (`Fatal signal`) for the `:asr` pid in logcat; `Recognizer released` occurs after the old decode. `ASR answer discarded` appears when close wins the best-effort delivery check; an already in-flight callback may instead complete harmlessly. The second take may cold-load the model (a new service generation loads its own recognizer either way); warm latency across generations is not claimed. Run on the emulator; the founder's phone pass is queued with #115 and #161 (his instruction 2026-09-21 morning excludes the phone from the audit queue).

## Preface — User Rubric

1. **Who.** Priya Ramachandran, staff SWE, dictating a long Slack reply, changes her mind while it processes and cancels, then dictates the shorter reply straight away.
2. **Why.** "I cancelled, now let me just say it again."
3. **How.** The recorder's cancel control while the pill is processing; reactive, already in Slack.
4. **App.** Slack, Gmail, GitHub comments; the fix is below the editor.
5. **Input.** "Actually never mind." / "Can you take a look at the PR when you get a sec" / "Ship it after the build goes green" / "LGTM with one nit on the retry loop" / "Pushing the fix now".
6. **Success.** She notices nothing: the second take lands.
7. **Wrong-not-broken.** Today the speech process can crash natively on the cancel; she never sees it, but a native crash can take state with it and cost a take later.
8. **Hack.** Waiting a few seconds before dictating again.
9. **Control.** None wanted; engine hygiene with no setting.

### Cross-persona check
Frank Chen and Meera Patel cancel by accident more often than Priya; the same fix covers them and changes nothing they can see.

## 0. TL;DR
`AsrService.onDestroy` calls `ExecutorService.shutdownNow()` and then `OfflineRecognizer.release()` on the service main thread. A JNI decode ignores `Thread.interrupt`, so `release()` runs the native `delete(ptr)` while `decode(ptr, stream)` may still be inside the same object: a use-after-free in `:asr`. Fix: a small `RecognizerOwner` (proposed) confines the recognizer to the one transcription worker. Load, every decode and the release are tasks on that worker, so `close` queues the release behind the task in flight and it cannot overlap it. `close` never waits, never frees on the caller's thread, and never ends the process. An answer produced after close is discarded when close wins the best-effort delivery check (a callback already in flight may finish, but take-scoped files, §3.2, prevent it from deleting a newer take's recording); a request that reaches the worker after close is answered once with the existing `MODEL_NOT_LOADED`.

## 1. Problem
The native release has no guard. Disassembly of the pinned `app/libs/sherpa-onnx.aar` (`javap -p -c com/k2fsa/sherpa/onnx/OfflineRecognizer.class`, 2026-09-22): `release()` calls `finalize()`, which reads `ptr`, calls the native `delete(ptr)` and writes `ptr = 0`, with no lock; `decode(stream)` reads `ptr` once and calls the native `decode(ptr, streamPtr)`. A release racing a decode frees the object the decode is using.

The race is reachable by a user. `DictationSessionCoordinator.finishSession` (cancel or any session end, `DictationSessionCoordinator.kt:1649`) and the Service's destroy path (`:1811`) call `pipeline.unbind()` without waiting for the transcription in flight (#115 made that deliberate: nothing waits). When the onboarding warm-up (`EngineWarmUp`) is not also bound, that unbind is the last client, the system destroys `AsrService`, and `onDestroy` releases while `AsrTranscriptionThread` may be inside `rec.decode(stream)`. Classification: REPRODUCIBLE (input: cancel while the pill is processing a take long enough that the decode is still running).

Two smaller defects in the same lines: queued tasks dropped by `shutdownNow()` never answer their callback, and `recognizer` is written on the worker and read on main without a happens-before edge.

## 2. Goals & non-goals
### 2.1 Goals
- The recognizer is touched on exactly one thread: load, decode, result and release are tasks on the transcription worker, in submission order; the release is always last.
- `onDestroy` returns without waiting, without touching the recognizer and without ending the process.
- An answer whose delivery has not begun when close is observed is discarded. A synchronous binder callback already in flight may finish after close; take-scoped files make that harmless to newer takes.
- An otherwise-valid request that reaches the worker after close is answered once with `MODEL_NOT_LOADED` and never decodes. `transcribeFile*` keeps its synchronous precedence: a missing file answers `AUDIO_MISSING` and an oversized one `OVER_LIMIT`, even during close.
- `isReady` answers false from the moment close begins and can never turn true again, even if a load finishes afterwards.
- Every take records to its own file (#221, absorbed): an answer that reaches an ended session's listener can delete only that take's file, never a newer take's. The delivery check above is best-effort (grounded round 2); this is what makes the late answer harmless.
### 2.2 Non-goals
- Ending the `:asr` process at teardown (the audit's "terminate only the `:asr` process"). Rejected with evidence in §3.1.
- A native call that never returns. The worker then keeps the old recognizer until Android ends the process. Classification: HYPOTHETICAL (no wedged Parakeet decode has been observed; `session-log.md` has none). Recovering from it needs a process-generation protocol, a separate design.
- Any AIDL change: no new failure code. `MODEL_NOT_LOADED` already reads "ASR model not loaded" on the legacy callback and is a closed code on the typed one.
- Warm latency across service generations: a new `AsrService` instance loads its own recognizer; unchanged.

## 2.5 Grounding brief
### 1. Trace producer → owner → consumer
Recognizer/readiness population: binder-pool entry points are `transcribeFile`, `transcribeFileForTake`, legacy `transcribe`, and `isReady`. The first two route through `transcribeFromFile`; all three transcription requests reach the single worker. Main-thread lifecycle entry points are `onCreate` and `onDestroy`. Production binders are `PipelineBindings.bind` and `EngineWarmUp.start`. The separately installed instrumentation APK also binds directly from `VoicePipelineDeviceTest.transcribesThenPolishesWithSavedCustomWords` and `SilenceStoppedTakeTranscribesDeviceTest`; both use legacy `transcribeFile`. No implementation caller of `IAsrService.isReady` was found under `app/src`; the transaction remains for binary compatibility. `AsrService` is the only service in `:asr` (`app/src/main/AndroidManifest.xml:98`).
### 2. Existing authority
The single-thread `transcriptionExecutor` is already the de facto owner of load and decode; the change makes it the owner of release too. FIFO order on a single-thread executor is the happens-before edge.
### 3. Prior attempts
None on this file since #176 (typed failures). The audit that found it: `docs/audits/2026-09-22-senior-audit.json` REF-03.
### 4. Boundaries
Process: `:asr` only. Lifecycle: Android can create a replacement service record while the old one is still being destroyed (AOSP `ActiveServices` removes the old record before scheduling its `onDestroy`, and handles another instance being started in parallel; grounded round 1), and binder methods run on binder-pool threads concurrently with main. So the old instance's close must not touch anything process-wide; it only closes its own owner, whose worker, recognizer and flags are per instance. For the window in which the old worker finishes its task, both recognizers can be resident.
### 5. High-risk premises, proven
- JNI decode ignores `Thread.interrupt`: the decode is one native call (`decode:(JJ)V`); a Java interrupt sets a flag nothing reads. Proven by the bytecode.
- `release()` is unsynchronised: proven by the bytecode above.

## 3. Design
New file `app/src/main/java/com/envi/wispr/asr/RecognizerOwner.kt`:

```kotlin
package com.envi.wispr.asr

import java.util.concurrent.ExecutorService
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The one owner of a native recognizer (#212). Every touch, the release included, is a task on [worker],
 * so a release is ordered after every task admitted before it and can never free the object a load or a
 * decode is inside. [close] returns at once, never frees on the caller's thread and never ends the process.
 */
internal class RecognizerOwner<R : Any>(
    private val worker: ExecutorService,
    private val free: (R) -> Unit,
    private val discarded: () -> Unit,
) {
    private val closed = AtomicBoolean(false)

    /** Read and written only on [worker]. */
    private var recognizer: R? = null

    @Volatile
    private var ready = false

    /** A close permanently masks readiness, even if a load finishes afterwards. */
    val isReady: Boolean
        get() = !closed.get() && ready

    fun load(open: () -> R?) = submit(refused = {}) {
        val opened = open()
        recognizer = opened
        ready = opened != null
    }

    /**
     * Runs [work] on the worker with the recognizer (null if it never loaded). [work] does the decode and
     * RETURNS the delivery; the delivery runs only if the owner is still open when the work returns, else
     * [discarded] runs. After close, [refused] runs instead, exactly once.
     */
    fun use(refused: () -> Unit, work: (R?) -> () -> Unit) = submit(refused) {
        val deliver = work(recognizer)
        if (closed.get()) discarded() else deliver()
    }

    /** Idempotent. Queues the release behind every admitted task, then stops the worker. */
    fun close() {
        if (!closed.compareAndSet(false, true)) return
        ready = false
        try {
            worker.execute {
                val current = recognizer
                recognizer = null
                ready = false
                if (current != null) free(current)
            }
        } catch (_: RejectedExecutionException) {
            // Only reachable if the worker was stopped outside this owner; nothing can be ordered then.
        }
        worker.shutdown()
    }

    private fun submit(refused: () -> Unit, task: () -> Unit) {
        try {
            worker.execute { if (closed.get()) refused() else task() }
        } catch (_: RejectedExecutionException) {
            refused()
        }
    }
}
```

The `closed` read at the head of a worker task is that task's admission point. If it reads false the task may finish even if `close` flips immediately afterwards; the release is queued behind it. If it reads true the task refuses exactly once. A refused wrapper may run after the release task, but no recognizer-using work can: every such wrapper observes `closed`, calls `refused` once, and never reads `recognizer`. The delivery check after `work` returns is a second read of `closed`: an answer is delivered only if the owner was still open when the decode ended. The delivery check is best-effort. `close` may flip after the second read or while the synchronous binder callback is already in flight, and a replacement session can already be capturing, because its audio binding starts capture independently of the speech binding (`PipelineBindings.kt:92`, `DictationSessionCoordinator.kt:451`). Safety therefore does not depend on this window: with take-scoped capture files (§3.2), an old callback can delete only its own take's file, and the ended coordinator's arbiter and cancelled scope prevent publication.

`AsrService` keeps its worker and hands it to the owner: `RecognizerOwner<OfflineRecognizer>(transcriptionExecutor, free = { it.release(); DebugLogger.log(TAG, "Recognizer released") }, discarded = { DebugLogger.log(TAG, "ASR answer discarded: the service closed during the decode") })`. `onCreate` calls `owner.load { initRecognizer() }`, where `initRecognizer` now RETURNS the recognizer (or null, logging as today). `transcribeFromFile` keeps its synchronous missing-file and over-limit checks and then calls `owner.use(refused = { failure.report(AsrFailureReason.MODEL_NOT_LOADED, "") }) { rec -> … }`; the legacy `transcribe` calls the same. `doTranscribe` (and the file read before it) RETURN the delivery instead of calling it: `{ runCatching { callback?.onResult(rawText) }… }` for a result, `{ failure.report(reason, detail) }` for every failure, the null-recognizer `MODEL_NOT_LOADED` answer included; logging and the decode itself are unchanged. `isReady()` returns `owner.isReady`. `onDestroy` becomes exactly `owner.close()`, `super.onDestroy()`, `DebugLogger.log(TAG, "AsrService destroyed; recognizer release queued")`. The `recognizer` and `modelReady` fields are removed.

### 3.2 Take-scoped capture files (#221, absorbed)
Every take records to `recording.pcm` today (`AudioCaptureService.kt:32`, opened at `:437`), and the speech listener deletes the path it was given before checking whether its take is still open (`DictationSessionCoordinator.kt:1016`, `:1027`, `:1039`). New pure object `app/src/main/java/com/envi/wispr/audio/CaptureFiles.kt` (proposed):

```kotlin
package com.envi.wispr.audio

/**
 * The name of one capture file (#212, #221). One file per capture, so a late answer for an ended take can
 * only ever delete its own recording. A production take is named by the owner's take id; a legacy start
 * (no id, or an id of the wrong shape) is named by the capture's own token, so two legacy captures never
 * share a file either.
 */
internal object CaptureFiles {
    /** The single shared name every take used before #212; swept so an upgrade leaves none. */
    const val PRE_212_NAME = "recording.pcm"
    private val TAKE_ID = Regex("[A-Za-z0-9-]{1,64}")
    private val PRODUCTION = Regex("recording-take-[A-Za-z0-9-]{1,64}\\.pcm")

    fun nameFor(takeId: String, fallbackToken: Long): String =
        if (TAKE_ID.matches(takeId)) "recording-take-$takeId.pcm" else "recording-legacy-$fallbackToken.pcm"

    fun isProductionTake(takeId: String): Boolean = TAKE_ID.matches(takeId)

    /**
     * What a PRODUCTION take's start removes: the pre-#212 name and every other production take's file.
     * Never a legacy capture (a separately installed client may still hold its path) and never another
     * cache file.
     */
    fun isSweptAtTakeStart(name: String): Boolean = name == PRE_212_NAME || PRODUCTION.matches(name)
}
```

`AudioCaptureService.startRecording` mints `nextCaptureToken()` once, before opening the file, and uses that one value for both `CaptureFiles.nameFor(takeId, token)` and `CaptureSession.token` (it never mints a second). It opens only that file and keeps the existing exact-file stale check (and its `IOException`) for that name.

A production take's start then sweeps `cacheDir`, after capture has started and `lastAudioFile` names the new file (code review round 1: never on the trigger-to-capture path): every entry for which `CaptureFiles.isSweptAtTakeStart` is true, other than the new take's own name, is deleted individually, a failure counted and never thrown; one content-free line reports the counts (`Removed N earlier capture files; M could not be removed`), added to `AudioServiceShapeTest.everyLogTemplateAndThreadNameSurvivesTheMove`'s literal baseline. Why no still-wanted reader needs a swept file: a production take is admitted only after the earlier take has ended. A running old `continueAfterEnding` coroutine may still retain the path because teardown cancels without joining; the sweep may make its late duration read zero or its not-yet-open ASR request fail. That coroutine belongs to the ended take: its draft has already been discarded, later updates match no row, and every failure or callback loses the committed arbiter. If ASR already opened the file, unlinking does not disturb its descriptor. Thus an old reader may still exist, but no live production outcome still depends on the path. Legacy captures are never swept (a separately installed client may hold the path across an unbind and submit it later, `SilenceStoppedTakeTranscribesDeviceTest.kt:87`); a legacy start sweeps nothing. The ended take's own listener still deletes its own path, as today, so an ordinary day leaves no file at all; the sweep is what removes the files of takes whose ending never reached the owner (a discarded answer at close, a capture or owner process death).

`AUDIO_FILENAME` is removed. The ending, `lastAudioFile` and `getAudioFilePath` already carry the session's own file. `getAudioData` remains the append-only empty-byte legacy stub and reads no file. Fixture paths such as `enviouswispr-uat.pcm` (`VoicePipelineDeviceTest.kt`) do not match `isSweptAtTakeStart` and are never deleted.

### 3.3 What a late answer can still touch (enumerated, grounded round 3)
From the producer, the speech listener (`DictationSessionCoordinator.kt:1014`-`1050`) and `polishAndPublish`: (a) the capture file, now take-scoped (§3.2); (b) `takeFacts` of the ended coordinator; (c) logs and take-id-scoped telemetry; (d) `arbiter.commitNow`, `updateDraftStatus`, `endAsFailure`, behind the arbiter the ended take already committed; (e) `polishAndPublish`'s nonblank path, which checks state and the arbiter before sending; (f) `polishAndPublish` first writes `rawTranscript` on the ended coordinator, and its null-polish-service fallback runs before that check; the resulting ledger operation, `takeFacts` writes, logs and telemetry still belong to the ended coordinator and lose the arbiter before publication. On the speech-process side the decode may also finish process-scoped `DebugLogger` marks and its pipeline summary after close, then either the discard log or the callback, then the old owner's release log: diagnostics only. The callback is the only cross-process side effect, and (a) is the only member that reached a newer take's state.

### 3.1 The audit's process kill, rejected
The audit's fix shape adds "if bounded shutdown cannot complete, terminate only the `:asr` process". Rejected: Android can create a replacement `AsrService` record and binding while the old one is still being destroyed (§2.5 item 4), and a new session binds immediately through `PipelineBindings.kt:98` after the prior session unbinds at `DictationSessionCoordinator.kt:1662`. Killing `:asr` from the old instance would kill that replacement, and a take in processing would fail through `onSpeechDisconnected` (`DictationSessionCoordinator.kt:470`). The queued release is safe without a kill: it runs when the native call returns. The residual case, a native call that never returns, is a non-goal (§2.2).

## 3b. Ownership justification
This will live on `RecognizerOwner` in `asr/` because the rule it enforces (one thread touches the native object, and it is freed only after the last admitted task) is about the recognizer, not about the Android Service; the alternative was the same logic inline in `AsrService`, but the Service cannot be constructed in a JVM test (no Robolectric in this module: `app/build.gradle.kts` has only `junit` for `testImplementation`), so the ordering would have no test.

## 4. Contract deltas
None on AIDL. Behavioural: a request that reaches the worker after close gets `MODEL_NOT_LOADED` (legacy: "ASR model not loaded") where before a queued one got nothing and a racing one could crash the process; an answer finished after close is discarded when close wins the best-effort delivery check; an already in-flight callback may still arrive but cannot act on a newer take's capture file.

## 5. State and lifecycle audit
States: not loaded → loading (worker) → loaded → closed (release queued, worker shut down) → released (worker). `load` after `close` does nothing; `use` after `close` refuses; `close` twice is one close. `isReady` is false from the start of `close` and stays false.

## 6. Downstream consumer matrix
`IAsrCallback` consumers: `DictationSessionCoordinator`'s speech listener maps `onFailure` to `ASR_FAILED` after claiming the arbiter (`DictationSessionCoordinator.kt:1038`); the legacy instrumentation clients read `onError` text, unchanged sentence. `isReady()` has no implementation caller under `app/src`; `PipelineBindings` and `EngineWarmUp` only bind. It remains an append-only AIDL transaction for version-skewed clients; its observable change is that it answers false as soon as close begins.

## 7. Failure-mode × caller table
| Failure | Before | After |
|---|---|---|
| Close during a decode | native use-after-free, `:asr` crashes | decode finishes; close either discards the answer or races an already in-flight callback; release follows in both cases, and the old callback cannot delete the newer take's file |
| Close during the model load | load dropped by `shutdownNow`, or freed under the load | load finishes, then release; `isReady` stays false |
| Close while idle | release on main | release on the worker, then the worker stops |
| Request queued behind the decode at close | dropped, no callback | `MODEL_NOT_LOADED` once |
| Request after the worker stopped | `RejectedExecutionException` on the binder thread | `MODEL_NOT_LOADED` once |
| Missing or oversized file during close | `AUDIO_MISSING` / `OVER_LIMIT` | same |
| Load failed (model not verified) | `MODEL_NOT_LOADED` | same |
| A native call that never returns | release on main under it (crash) | old recognizer kept until the OS ends the process (non-goal) |

## 8. Caller-visible signals
Changed log: `AsrService destroyed; recognizer release queued` means only that close returned. Completion receipt: the worker logs `Recognizer released` only after `OfflineRecognizer.release()` returns. New: `ASR answer discarded: the service closed during the decode`. None contains content.

## 9. Fallback source of truth
The owner's own fallback (clipboard, History) is untouched; a refused request is a failed transcription the owner already handles.

## 10. File-by-file changes
- `app/src/main/java/com/envi/wispr/asr/RecognizerOwner.kt` (proposed): the class above.
- `app/src/main/java/com/envi/wispr/asr/AsrService.kt`: fields removed, the three entry points and `onCreate`/`onDestroy` routed through the owner, `initRecognizer` returns the recognizer, the decode returns its delivery.
- `app/src/test/java/com/envi/wispr/asr/RecognizerOwnerTest.kt` (proposed): rows 1 to 10.
- `app/src/test/java/com/envi/wispr/asr/AsrServiceShapeTest.kt` (proposed): rows 11 to 13.
- `app/src/main/java/com/envi/wispr/audio/CaptureFiles.kt` (proposed): §3.2.
- `app/src/main/java/com/envi/wispr/audio/AudioCaptureService.kt`: the take's file name and the sweep, `AUDIO_FILENAME` removed.
- `app/src/test/java/com/envi/wispr/audio/CaptureFilesTest.kt` (proposed): rows 14 to 16.
- `app/src/test/java/com/envi/wispr/audio/AudioServiceShapeTest.kt`: row 17 added, the one new log template added to the literal baseline, `sweepEarlierTakeFiles` added to the function allowlist, and the pre-existing publisher-order assertion (which compared against a join #115 removed, so it could not fail) replaced (code review round 1).
- `app/src/test/java/com/envi/wispr/audio/CaptureTokenTest.kt`: adapt the existing wiring guard to the one-token start.
- `app/src/androidTest/java/com/envi/wispr/SilenceStoppedTakeTranscribesDeviceTest.kt`, `app/src/androidTest/java/com/envi/wispr/CaptureWithSilenceStopDeviceTest.kt`: an `@After` deletes the class's legacy capture files (`CaptureFiles.isLegacyCapture`), since every legacy capture now has its own file and only its client knows when it is done with it (code review round 1).

## 11. Testing
Both classes: **Drift Guard** (they protect an internal ownership and ordering property; not counted as product coverage). JVM, no timing assertions; every wait is a latch with a deadline used only to fail fast. "Gate" = a task submitted straight to the executor that parks on a latch.

`RecognizerOwnerTest`:
1. `aCloseDuringADecodeFreesOnlyAfterItAndDiscardsItsAnswer`: loaded; a use parks inside its work; `close()` returns; `free` has not run; open the latch; await worker termination; literal order `work-end, discarded, free`; the delivery never ran.
2. `aCloseDuringTheLoadFreesOnlyAfterTheLoad`: `open` parks; `close()`; `free` has not run; unpark; await termination; `free` ran once with the opened recognizer; `isReady` false throughout.
3. `anIdleCloseFreesOnTheWorkerOnce`: loaded and idle; `close()`; await termination; `free` ran once, on the worker's thread (not the test thread).
4. `aQueuedRequestThatReachesTheWorkerAfterCloseRefusesThenFrees`: loaded; a gate parks the worker; queue use A; `close()`; open the gate; A's `refused` ran once, A's work never ran, then `free`, literal order `refused, free`.
5. `aRequestAfterTheWorkerStoppedIsRefusedOnceWithoutThrowing`: close, await termination, then `use`; `refused` exactly once, no exception.
6. `aLoadAfterCloseNeverOpens`: close, then `load`; `open` never invoked.
7. `closeTwiceSubmitsOneReleaseAndFreesOnce`: a counting single-thread executor; after load completes, record its submission count, call `close()` twice, and assert exactly one additional executor submission and one `free`.
8. `isReadyStaysFalseAfterClose`: loaded; `close()`; `isReady` false at once and after the release.
9. `loadUseAndFreeRunOnOneWorkerInOrder`: record thread identity and literal order for open, use, delivery, free; one identity, `open, use, delivery, free`.
10. `anAnswerFinishedWhileOpenIsDelivered`: loaded, no close; use; the delivery ran once and `discarded` never.

`AsrServiceShapeTest` (source read of `AsrService.kt`):
11. `onDestroyOnlyClosesTheOwner`: the normalized `onDestroy` body is exactly `owner.close()`, `super.onDestroy()` and the approved log statement; any additional call fails the row.
12. `closedRequestsAreWiredToModelNotLoaded`: every `owner.use(` call passes a refusal that reports `AsrFailureReason.MODEL_NOT_LOADED`, and there are exactly two such calls (the file path and the legacy byte path).
13. `fileValidationPrecedesOwnerAdmission`: inside `transcribeFromFile`, the `AUDIO_MISSING` and `OVER_LIMIT` returns occur before `owner.use(`.

`CaptureFilesTest` (Contract: the naming contract the late-answer safety rests on), literal expected values:
14. `twoTakesNeverShareAFile`: `nameFor` of two literal UUIDs (token 1) differ and equal their literal `recording-take-<id>.pcm` strings.
15. `aHostileOrMissingIdUsesTheUniqueLegacyName`: with fallback token `42`, `""`, `"../etc"`, `"a/b"`, a 65-character id and `"x.pcm"` each map to the literal `recording-legacy-42.pcm`, and `isProductionTake` is false for each; token `43` gives `recording-legacy-43.pcm`.
16. `theTakeStartSweepMatchesOnlyProductionFiles`: `isSweptAtTakeStart` is true for `recording.pcm` and a literal `recording-take-<uuid>.pcm`; false for `recording-legacy-42.pcm`, `enviouswispr-uat.pcm`, `recording-take-.pcm`, `recording-take-a.pcm.tmp`, `xrecording-take-a.pcm` and `recording-take-a/b.pcm`.

`AudioServiceShapeTest` (existing Drift Guard, one row added):
17. `everyTakeOpensOnlyItsOwnFile`: `AudioCaptureService.kt` opens through `CaptureFiles.nameFor(takeId, token)` with the same token value it passes to `CaptureSession`, calls `nextCaptureToken()` once in `startRecording`, contains no capture filename literal, retains the exact-file stale check, and deletes another file only inside a block guarded by `CaptureFiles.isProductionTake(takeId)` whose filter is `CaptureFiles.isSweptAtTakeStart`.

Revert receipts (each must turn a row red, then restore): R1 free inline in `close` (rows 1 and 3; row 2 cannot see it, because the recognizer does not exist yet while the load is parked, so an inline free has nothing to free); R2 drop the in-worker `closed` check (row 4); R3 drop the `RejectedExecutionException` catch in `submit` (row 5); R4 replace the compare-and-set with an unconditional `closed.set(true)` (row 7); R5 make `isReady` return `ready` alone (row 2); R6 run `free` on a new thread (rows 3, 9); R7 add `transcriptionExecutor.shutdownNow()` to `onDestroy` (row 11); R8 deliver regardless of `closed` (row 1); R9 always discard (rows 9, 10); R10 change one refusal to `AsrFailureReason.UNKNOWN` (row 12); R11 move the missing-file check after `owner.use(` (row 13); R12 make `nameFor` return the constant `recording.pcm` (rows 14, 15); R13 drop the id shape check (row 15); R14 make `isSweptAtTakeStart` match any `recording-*.pcm` (row 16: the legacy name turns true); R15 restore a `"recording.pcm"` literal as the opened file (row 17); R16 remove the `isProductionTake` guard around the sweep (row 17).

### 11.1 Hardware UAT spec
Emulator, `wispr-eyes`, debug build: (a) a long spoken take into Gmail (`dictate_emulator` with a long sentence), cancel while processing, then a second `dictate_emulator`; the second take lands in Gmail by the editor's own text; `logcat -d -b crash` has no `Fatal signal` for the `:asr` pid; the log shows `AsrService destroyed; recognizer release queued` and then `Recognizer released`, with `ASR answer discarded: the service closed during the decode` between them when the cancel landed inside the decode. (b) An ordinary take lands as before; during the take `run-as com.envi.wispr ls cache` shows a uniquely named `recording-take-<take-id>.pcm` for the current take and no `recording.pcm`; after completion that path may already be deleted, and no other `recording-take-*.pcm` remains after the NEXT take has started. The discard line is a receipt only when close wins the delivery check; its absence does not prove the decode ended before the cancel, because an already in-flight callback is also allowed. The JVM rows stage the overlap deterministically.

### 11.2 Other obligations
`scripts/measure-tests.sh` count before and after; `./gradlew :app:assembleDebug`.

## 12. Blast radius & rollback
Two isolated services: recognizer ownership changes in `:asr`, and capture-file naming plus production-only cleanup in `:audio`. An ASR-owner defect can refuse or discard every transcription; rows 1 to 13 and the emulator take cover that. A capture-file defect can refuse capture, reuse a path, or delete a legacy/unrelated cache file; rows 14 to 17 and the back-to-back emulator path cover that. Rollback: revert the PR.

## 13. Ship criteria
Rows 1 to 17 green with R1 to R16 red; unit count up by 17; the existing log-template row green with its one new baseline entry; emulator (a) and (b); Codex code review ALL-CLEAR with a confirming rerun.

## 14. Open questions
None.

## 15. Related
#115 (the owner never waits at teardown, which is what makes this race reachable), #176 (typed ASR failures), #221 (take-scoped capture files, absorbed here), audit `docs/audits/2026-09-22-senior-audit.json` REF-03.
