# Issue #344: a polish engine destroyed while its model is loading ends its process (REF-02, regrade 7) (2026-09-24)

GitHub issue: `#344`. Tier: SMALL. Status: revised after the coverage round (`344-cov`), findings 1 and 3 adopted; built 2026-09-24.

## Preface: Lane + Hardware UAT declaration

**Lane:** Code

**PAR rows closed:** none.

**Hardware UAT:** none. A stalled native load cannot be staged on a device without a production seam. The predicate is a pure function, and its wiring is pinned by source.

## Preface: User Rubric

Persona: the founder. After a take that warmed the local model, a load that stalls in the vendor runtime must not keep the polish worker and the model resident until Android kills the process (`architecture-rules.md` RULE: no-idle-cost, RULE: isolate-limbs).

## 0. TL;DR

`PolishService.onDestroy` ends the process at once, rather than closing in order, when the engine is poisoned or a local request is in flight (`mustKillEngineOnDestroy`, #75). The reason is that the orderly branch queues `s1Runtime.close()` on the single worker, behind whatever it is running. A warm-up load (`warmUpWithPolicy` to `ensureModelLoaded` to `loadModel` to `S1GenieXRuntime.load`) runs on that same worker. It counts as neither case, so a stalled load leaves the orderly close queued behind it forever.

Change: the kill condition also covers a model load queued or running. That is the existing `@Volatile modelLoading` flag, set before the load is queued and cleared on every exit of `loadModel`.

- `mustKillEngineOnDestroy(poisoned, activeLocalRequests, modelLoading)` becomes `poisoned || activeLocalRequests > 0 || modelLoading`.
- `onDestroy` passes the flag, and its log names the reason ("destroyed while the model was still loading").

A healthy load that is merely in progress at destroy also ends the process. The cost is that the next take loads the model cold, which it does anyway after the process dies. The polish process is a limb, and the client has already unbound. While the service lives, the load has its own hard deadline (`MODEL_LOAD_DEADLINE_MS`, 60 s): a local request queued behind a stalled load never starts, so it never arms its own deadline (coverage round, finding 3).

## 1. Tests

- `EngineDeadlineTest`: a loading model alone forces the kill, and nothing loading, unpoisoned and idle keeps the orderly close. MUTATION m1: the predicate ignores `modelLoading`.
- A source drift guard: `onDestroy` passes `modelLoading` to the predicate. MUTATION m2: `onDestroy` passes `false`.

## Results (2026-09-24)

- Coverage round (`344-cov`):
  - Finding 1 adopted. `modelLoading` stayed set when model selection threw before the old `try`, so the whole `loadModel` body is now one `try`/`finally` that clears it. Warm-up admission and destruction's decision share `loadLock`, with a `destroyed` flag, so no load is queued after destruction decided there was none.
  - Finding 2 confirmed: killing a healthy in-progress load at destroy is acceptable.
  - Finding 3 adopted. The claim that "a queued local request arms its own deadline" was false: `work` arms it only once it runs on the worker. So the load now has its own hard deadline, `MODEL_LOAD_DEADLINE_MS` = 60 s, about six times the slowest measured load (10.63 s, the first GPU load). It poisons the engine and ends the process, and the owner's fail-open (#234) handles the lost process.
  - MUTATIONS m1 to m4 RED (`344-mut.py`, fresh compiles). m4 first passed because the guard found the constant in the log line; the guard now requires the constant as the scheduled delay.
  - Suite 1392, 0 failures. The constant is `private`, after `ComponentMemberVisibilityTest` flagged it as public. The visibility and citation checks are clean.
  - Emulator (`344-uat.py`): a spoken take into Gmail landed by COMMIT with exactly the expected text.
  - Finding 4 noted: v1 and v2 requests, cloud included, also run on the single worker. They are bounded by their own deadlines and HTTP timeouts, and bounding every worker task at destroy is out of this issue's scope.
