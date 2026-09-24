# Issue #346: start-up History recovery has one application owner (REF-04, regrade 7) (2026-09-24)

GitHub issue: `#346`. Tier: MEDIUM. Status: revised after the coverage round (`346-cov`), all findings adopted; built 2026-09-24.

## Preface: Lane + Hardware UAT declaration

**Lane:** Code

**PAR rows closed:** none.

**Hardware UAT:** light. On the emulator, open History while a take starts: History shows its rows, and the log shows one recovery.

## Preface: User Rubric

Persona: the founder. Two owners run the same start-up recovery: `DictationSessionCoordinator.onCreated` and `HistoryViewModel.init`. Each closes stale open rows and records their telemetry, and each writes rescued words into History (#288). Opening History around a take runs both at once, and a change to one path can leave the other behaving differently (`architecture-rules.md` RULE: keep-central-types-thin, RULE: own-state-locally).

## 0. TL;DR

New `history/HistoryRecoveryCoordinator.kt` (proposed), owned by the application beside `historyWrites` and `rescuedWords`:

- `recover(): Deferred<Outcome>` returns the run already in flight, or starts a new one on the application's own scope. So a take's Service stopping never cancels a recovery another caller is waiting on.
- It is NOT once per process. #288 depends on a rescue file from a save that failed in THIS process being written to History by the next recovery in the same process (g2 finding 2), and a stale open row from this process's own takes becomes recoverable once it is old enough. So concurrent callers share one run, and a later caller starts a fresh one.
- One run does these steps, each under its own guard, so one failing never skips the other:
  1. `recoverStaleOpenRows`, then its telemetry (`insertionsRecovered`, `deliveryUnknownRecovered`).
  2. `rescuedWords.recover`.
  3. The session owner's log lines move with them.
- `Outcome` carries each step's failure, if any, and the rescued count.

The callers:

- `DictationSessionCoordinator.onCreated` asks for a recovery and does not wait for it.
- `HistoryViewModel.init` awaits the outcome, shows the first failure in `historyError` as before, and then prunes wordless rows. The prune stays with the History owner.

Behaviour change, declared: on the History side, a failed stale-row recovery no longer skips the rescued-word recovery. This matches the session owner, and the words are the heart.

## 1. Tests

- `HistoryRecoveryCoordinatorTest`:
  - Two concurrent callers share one run (a counting DAO).
  - A caller after completion starts a new run.
  - A stale-row failure still runs the rescue step.
  - The telemetry sink receives the recovered rows.
  - MUTATION m1: every call starts a new run. MUTATION m2: one guard for both steps.
- Existing rows stay green. `HistoryViewModelTest` covers recovery and deletes, and the session rig's `onCreated` rows recover through the coordinator.
- `SessionOwnerShapeTest`: the coordinator no longer names `recoverStaleOpenRows` or `rescuedWords.recover`. MUTATION m3: the recovery body is back in the coordinator.

## Results (2026-09-24)

- Coverage round (`346-cov`):
  - Confirmed: joining a run in flight, else starting a new one, is right; once per process would miss both same-process cases. Nothing relied on Service cancellation stopping the recovery. The History side's change is acceptable.
  - Finding 1 adopted: the rescue step is bounded (`RESCUE_RECOVERY_BOUND_MS`, 5 s), because it holds the rescue store's lock while it writes to Room, and on the application's scope nothing else releases a stalled pass. A cut-off pass leaves its files for the next recovery, which is idempotent by take id. Row `aStalledRescuePassNeverHoldsANewTakesWords`: a new take's rescue write lands after the cut-off.
  - Finding 2 adopted: the History prune runs only after both steps succeeded.
  - Finding 3 adopted: a caller that asks while a run is in flight gets ONE follow-up that begins after it, shared by every caller that asks meanwhile. So a History caller arriving after the active run's stale-row scan still gets a scan with its own later cutoff. The store tests that call `rescuedWords.recover` directly are kept, and the view-model and rig wiring use the coordinator.
- `DictationSessionCoordinator` loses its `transcripts` parameter, which was used only for this recovery (GR-MIGRATION-COMPLETE).
- MUTATIONS m1 to m5 RED on fresh compiles (`346-mut.py`). m5 (the prune after a failed recovery) first passed, because no row tested the prune condition before this change. `HistoryViewModelTest.aFailedRecoveryIsShownAndSkipsThePrune` now does.
- Suite 1397, 0 failures; visibility and citation checks clean.
