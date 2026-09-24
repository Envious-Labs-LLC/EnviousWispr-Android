# Issue #288: a take keeps its words when the handoff, the clipboard copy and the History save all fail (2026-09-24)

GitHub issue: `#288`. Tier: LARGE (the words' last resort; the insertion writers, History recovery, the announcement). Status: revised after the coverage round (`288-cov`), all five findings adopted by a write-ahead design; grounded round 1 (`288-g1`), all six adopted, two with the modifications declared in §0; round 2 (`288-g2`) confirmed both modifications against the code and its two findings are adopted. Built 2026-09-24.

## Preface: Lane + Hardware UAT declaration

**Lane:** Code

**PAR rows closed:** none.

**Hardware UAT:** Y, light. Emulator, debug build: the History database migrates to version 9 on a real install and History opens with its existing rows; the migration and History DAO device rows pass through `am instrument`. The triple failure itself is staged in the rig (no debug seam is added to production); the emulator's speech injection records silence (#319), so no take with words can run there today.

## Preface — User Rubric

User Rubric: persona, the founder dictating on his S26. Founder decision: the customer always gets their words. Today, when the words could not go into the field, the clipboard write failed, and History has not saved the take, the app says "Your words could not be saved. Please dictate again." and the words are gone. After this change the app writes the words to a small private file the moment they are final, keeps it until History has saved them, and puts any it still holds into History the next time the app starts; the line he reads says only what is known.

---

## 0. TL;DR

Two writers can end a take with the words in neither the clipboard nor History:

1. The owner, `SessionFinalizer.deliver`, on a non-scheduled handoff (no pinned editor, service not running, no answer): `keepOnClipboard` fails and `row.savedNow` is false.
2. The accessibility service, `AccessibilityInsertionRunner.recordAndAnnounce`, after it accepted the text and fell back: `keepTranscriptOnClipboard` fails and `pending.row.savedNow` is false.

In both, `destinationLine(WRITE_FAILED, savedInHistory = false)` says "Your words could not be saved. Please dictate again." Note `savedNow` false also covers a save that simply has not answered yet (#277), which may still succeed.

Design, revised after the coverage round (`288-cov`, all five findings adopted; see §2.1 for how each is met):

- **Write ahead** (`history/RescuedWords.kt`, proposed, application-owned like `HistorySaveObserver`, #304): when a take's final text is published, before any delivery, the owner hands it to the store, which writes `filesDir/rescued-words/<takeId>.txt` on its own single-thread executor (never the History queue, which may be what is failing; never main): temp file, a flush to the disk (`fd.sync()`), atomic rename. The owner awaits that durable result off main before the handoff (g1 finding 1), bounded by `RESCUE_WRITE_BOUND_MS` (proposed, 250 ms): the words are the heart and a slow disk must not hold them back, so past the bound delivery continues and the rescue is `PENDING` (declared modification of g1 finding 1, which asked for an unbounded await). The await runs on the owner's IO continuation (the Service's scope), and timing out cancels only the wait: the write continues (g2). The file holds the take id, the creation time and the final text. A write failure is logged by type only.
- **Settle on History only**: the file is deleted when, and only when, the take's save answers SAVED. The store observes the take's `SaveSlot` itself, so settlement survives the Service's teardown (coverage B), and a SAVED answer deletes the file only after any queued write for that take has completed, so a late write cannot recreate a settled record (g1 finding 2). The clipboard and the field are not settlement: a copy can be overwritten and a field edited, and a failed save with a verified insertion simply adds the History row a normal take would have had. This one rule covers every path coverage A names (the scheduled handoff that later fails, abandon, close, a late FAILED save, and process death), because nothing but a SAVED answer removes the words from disk.
- **Recover**: at the existing start-time recovery (`DictationSessionCoordinator.onCreated`, `HistoryViewModel` init), every rescue file for a take the store is not currently tracking (the Service and the History screen share the main process; confirmed in g2). A take is tracked from its write until its save answers, SAVED or FAILED, or the save bound `HISTORY_SAVE_BOUND_MS` passes, whichever is first; a FAILED or unanswered save leaves an untracked, unsettled file that the next recovery in the same process takes (g2 finding 2) is written into History (g1 finding 5, declared modification: the tracked set replaces the age gate, since age alone does not prove a take ended) and deleted only after the write returned. Idempotent by a new nullable, uniquely indexed `takeId` column (Room migration 8 to 9 in this change, with the version 9 schema and direct and full-chain migration tests, g1 finding 4; the draft and the fallback insert in `SessionFinalizer` set it, g1 finding 3): a row for that take already present is completed with the rescued text if it has none, else left; otherwise one row is inserted (`STATUS_INSERTION_INTERRUPTED`, `InsertionResults.INSERTION_FAILED`). A failed write keeps the file for the next start. Recovery, file deletion and History deletion are serialized on the store's executor (g1 finding 5).
- **Delete means delete**: `Delete all` clears every rescue file, and deleting one row deletes its take's rescue file, both serialized on the store's executor against a pending write, so deleted words cannot reappear (coverage C). Backup is disabled in the manifest; another profile or a restore sees nothing. With no History rows the screen offers no Delete all; a rescue file becomes a History row at the next recovery and is deletable there, so no separate control is added (declared).
- **Say only what is measured** (coverage D, g1 finding 6): both fallback surfaces read the store's per-take outcome without waiting (`KEPT`, `FAILED`, `PENDING`) and the save's answer (`SAVED`, `FAILED`, pending). With the copy failed and the save not SAVED: rescue `KEPT` says "Kept on this phone. Open EnviousWispr to find them."; the save FAILED and the rescue FAILED (the only case the words are gone) says today's "Your words could not be saved. Please dictate again."; anything still pending says "Saving your words. Open EnviousWispr to check." The unverified-insertion hedge is unchanged.
- The copy retry (issue option 2) is dropped: the write-ahead record already guarantees the words, and a retry would risk taking the clipboard back from a newer owner (coverage E).

Consolidation: one rescue store both writers use; the announcement stays the one sentence builder (`InsertionOutcomeMessages.kt`).

Prior context: #277 (words never wait on History; the save may answer after delivery), #235 (unrouted rows recovered at start), #16 (announce every fallback), #293 (failure sentences), #304 (the save observer).

## 1. Grounding (main db2e0d6)

- `SessionFinalizer.deliver` L322 to L399; `keepOnClipboard` L424 to L440 (copy, then the clipboard-only outcome on the History queue).
- `AccessibilityInsertionRunner.recordAndAnnounce` L656 to L678; `keepTranscriptOnClipboard`.
- `SessionHost.copyToClipboard` (Service: `setPrimaryClip` in `runCatching`).
- `InsertionOutcomeMessages.kt`: `wordsMissedTheirDestination`, `destinationLine`, `hedgedDestinationLine`.
- Start recovery: `DictationSessionCoordinator.onCreated` (`recoverStaleOpenRows` on its own scope) and `HistoryViewModel` init.
- `SaveSlot` (`answeredNow`, the save's answer), `HistorySaveObserver` (#304).

## 2. Tests

1. How each coverage finding is met: A (every path) by write-ahead plus settle-on-SAVED only; B (teardown) by the application-owned store listening to the save slot; C (durable, idempotent, private, deletable) by sync-then-rename, the unique `takeId`, `filesDir`, and the delete paths; D (true line) by the `kept` flag; E (seams) by the rig's failing DAO, fake clipboard and handoff for owner rows, `RescuedWordsTest` for the store alone, and the runner's existing tests for the service writer's announcement.
2. Rig row (the issue's done-when, RED on main): a non-scheduled handoff, the clipboard failing and the save failing; a rescue file for the take exists, the line is the "Kept" line, and a recovery on the same repository, once the save answered FAILED, inserts the words into History and deletes the file. The rescue outcome is `KEPT` after a completed write and `PENDING` when the 250 ms bound expires first (g2).
3. A scheduled handoff whose save answers FAILED later keeps the file; a save that answers SAVED deletes it, even after the owner is destroyed.
4. Recovery: a failed insert keeps the file; a second recovery inserts no second row; a tracked take is skipped, then recovered once its save answers FAILED (g2); a row already present for the take is completed, not duplicated.
5. `RescuedWords` alone: temp then rename, a crash between the two leaves no half file readable, the directory under `filesDir`, `clear` and `delete(takeId)` serialized against a pending write.
6. Migration 8 to 9 in the existing migration test (androidTest), and the schema export.
7. Announcement rows over every `(clipboard, save answer, rescue outcome)` combination, literal lines.
8. Mutations: m1 no write-ahead; m2 settle on COPIED too; m3 settle before the SAVED answer; m4 recovery deletes before its write returned; m5 recovery ignores the tracked set; m6 `Delete all` leaves the files; m7 the "Kept" line before the durable write.

## Results (2026-09-24)

- Mutations m1 to m13 RED (`288-mut.py`; m9 to m11 from code review round 1, m12 and m13 from round 2); row 1 of `WordsNeverLostTest` is RED on main (the line said "dictate again" and nothing held the words).
- `WordsNeverLostTest` and `RescuedWordsTest` each 20 of 20.
- Suite 1373, 0 failures, twice; app and androidTest build; visibility and cited-symbol checks clean.
- Device (emulator, `am instrument`): `EnviousWisprDatabaseMigrationTest` 6 of 6 including 8 to 9 and the whole chain to 9; `TranscriptRouteDaoTest` 4 of 4.
- Emulator: the app installed over a version 8 database; History opens with every existing row; no rescue directory is left behind.
- Test seams added: `RescuedWords.beforeWrite` (inside the lock; production passes nothing; the rig delays a write inside the owner's bound) and `RescuedWords.tracking` (the recovery rule's own question, read by its tests).
- Code review round 1, all four adopted: a user's delete runs its History delete and its file delete inside the store's lock and marks the take (or bumps the clear generation), so a queued write writes nothing (row 7b, m9); the take's save looks up a row a recovery already wrote for the take and completes it instead of colliding with the unique index (row 3b, m10), and a recovered take reads KEPT for any line still to come; the directory is flushed after the rename before KEPT is reported (`Os.fsync`; a JVM stub on the unit tests, so no mutation witness); a late SAVED answer settles through `SaveSlot.onAnswered`, with no coroutine left waiting on a save that never answers (m11), and the outcomes map is bounded to the last 64 takes.
- Code review round 2, the third round of one class ("words the user deleted come back", with disk durability and the lock's reach beside it), so the class was enumerated before fixing: the paths that can write a take's words after a delete are a queued rescue write (round 1 mark), a recovery (skips a deleted or pending take), the take's own late History save (new: it asks `deletedByUser` and never re-creates a deleted row, for a row delete or a Delete all begun after the take started; row 3c, m12), and insertion-outcome writes (update-only, harmless on a deleted row). Durability: the words file, the rename, and now the parent directory when the write creates the rescue directory. Lock reach: the History delete runs outside the lock under a pending mark that writes and recovery respect (row 7c, m13).
- The rig's `close()` now cancels the session scope as well. Each rig's polish timeout wait held a shared IO thread in `runInterruptible`; main stayed under the pool's 64 threads, and this change's seven new rig rows pushed a later take past it, so it could never be scheduled.
- `scripts/check-cited-symbols.py` no longer reads Room's generated schema JSON (`app/schemas`) as prose: its backticked SQL names are no citation.

## 3. Blast radius

A rescue file that is never recovered would hold words on the phone until the next start that can write History; that is today's History privacy boundary (app-private storage), and `Delete all` must also clear the rescue files (the grounded round checks the History delete paths). Rollback: revert the squash commit.

## 4. Ship criteria

- [ ] Rows green, mutations RED; the suite green; app and androidTest build; checks clean.
- [ ] Emulator: a staged double failure keeps the words and the next start shows them in History.
- [ ] Codex code review ALL-CLEAR with a confirming round.
