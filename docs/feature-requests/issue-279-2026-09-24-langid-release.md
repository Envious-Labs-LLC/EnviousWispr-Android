# Issue #279: the language detector's client is released only when no detection holds it (2026-09-24)

GitHub issue: `#279`. Tier: MEDIUM (one limb: ML Kit language identification, used by cleanup and polish). Status: revised after the coverage round (`279-cov`), its one finding adopted; grounded round 1 (`279-g1`), three wording findings adopted; grounded round 2 (`279-g2`), one wording finding adopted; grounded round 3 (`279-g3`), the pre-committed deletion applied.

## Preface: Lane + Hardware UAT declaration

**Lane:** Code

**PAR rows closed:** none.

**Hardware UAT:** Y, light. Emulator: a take still lands its words (the detector runs in the app process for cleanup and in `:polish`).

## Preface — User Rubric

User Rubric: persona, the founder dictating on his S26. Nothing he can see changes. Today a rare interleaving at service teardown can close the language model while another detection is still using it; ML Kit does not document what that does, so the worst case is a crash in a limb during teardown. After this change the model is closed only when nothing is using it.

---

## 0. TL;DR

REF-03 of `docs/audits/2026-09-23c-senior-audit.json` (`.claude/rules/code-design-rules.md` RULE: async-edge-case-enumeration). `MlKitLanguageDetector.acquire` releases the published client in its post-CAS `closed` branch even while another counted-in detection may already hold that client. Delete that release; the branch returns null. Who releases the published client is stated once, in §1.

Consolidation: the published client's release paths are the two in §1; the CAS loser still releases the client it built itself, which nobody else ever saw.

Prior context: #107 (the detector and its limb contract), review rounds 5 to 9 (no lock, three atomics, the active-detection count added to stop a use-after-close).

## 1. Grounding (main 3fa1d42)

- `detect`: returns null when blank or closed; `activeDetections.incrementAndGet()`; re-checks `closed`; `identify`; `finally { if (activeDetections.decrementAndGet() == 0 && closed.get()) releaseClient() }`.
- `identify`: `client.get() ?: acquire() ?: return null`, then calls through the client.
- `acquire`: builds a client (timer, `MlKit.initialize`, `LanguageIdentification.getClient()`), then `if (!client.compareAndSet(null, created)) { release(created); return client.get() }`, then `if (closed.get()) { releaseClient(); return null }`.
- `close`: `closed.compareAndSet(false, true)`, then `if (activeDetections.get() == 0) releaseClient()`.
- Every `acquire` caller is inside `detect`'s counted section, so when the post-CAS branch sees `closed`, its caller is still counted. If `close` sees a positive count, a last-out detection releases the client. If it sees zero after that caller exits, either `close` or the last-out detection releases it; the atomic swap makes the other attempt a no-op. The post-CAS branch must not release it: its release is never needed, and it is wrong when a second counted-in detection read the pointer between the CAS and the branch: it closes a client that detection is calling through.
- No JVM test covers the class: the only JVM reference (`git grep -ln MlKitLanguageDetector -- app/src/test`) is a source check in `DeterministicFallbackTest`. `EnginePolishLanguageTest` (androidTest) also names it; its behaviour is not relied on here.

## 2. Design

Grounded rounds 1 to 3 found the same class (who releases the published client) three times; per the pre-committed consequence, §1 is now its only statement.

1. Delete `releaseClient()` from the post-CAS `closed` branch; it returns null. The comment points to the class doc.
2. A test seam for the client's construction: the primary constructor is `internal` and takes `newClient: () -> LanguageIdentifier?`; the production constructor `MlKitLanguageDetector(context)` passes today's build (timer, initialize, `getClient()`, the catch), moved unchanged into the companion's `buildClient(context)`. As built, this replaces the planned default argument so JVM rows need no `Context` and no Kotlin default hides the production path. Plus `afterPublish: () -> Unit = {}`, called once right after a successful CAS; it gives the test a deterministic pause immediately after publication, the window the defect lives in. Neither seam guards anything; production passes neither.
3. The class doc states §1's two release paths, copied from §1, and that the atomic swap allows one release.

## 3. Tests

`MlKitLanguageDetectorTest` (JVM), with `newClient` injected (it holds the timer too, so no row reaches `SystemClock`). A fake `LanguageIdentifier` records `close()`; its `identifyPossibleLanguages` blocks on a latch where a row needs it, counts callers inside it, then throws a controlled exception before `Tasks.await` runs (coverage finding C), which the detector's own catch turns into null. The rows assert ownership and close counts, never a language result:
1. The audit's interleaving: detection A builds client X and, in `afterPublish`, waits while detection B counts in, reads X and blocks inside `identifyPossibleLanguages`; `close()` runs (it must defer); A continues and sees `closed`. Assert X is NOT closed while B is inside the call; release B; assert X is closed exactly once after B leaves. MUTATION m1: restore the branch's `releaseClient()` (X closed while B is inside).
2. `close` with nothing active closes the published client once; a detection after `close` returns null and builds nothing. MUTATION m2: `close` never releases.
3. The CAS loser releases only its own client: a barrier in `newClient` makes both detections construct before either publishes; the row identifies the winner (the client that saw `identifyPossibleLanguages`) and the loser, and asserts the loser alone is closed before the detector's `close`, the winner only after it. MUTATION m3: the loser releases the published client instead.

## 4. Blast radius

A limb only; production behaviour differs only in the interleaving above, where the client is now closed a moment later, by a path §1 names. Rollback: revert the squash commit.

## 5. Ship criteria

- [ ] Rows green, m1 to m3 RED; the suite green; app and androidTest build.
- [ ] Emulator: a take lands its words.
- [ ] Codex code review ALL-CLEAR with a confirming round.
