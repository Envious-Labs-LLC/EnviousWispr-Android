# Issues #282 and #285: delete an unwired policy and an assertion-free test (2026-09-24)

GitHub issues: `#282` (REF-06) and `#285` (REF-09) of `docs/audits/2026-09-23c-senior-audit.json`. Tier: SMALL (two deletions, one test file edited, no runtime change). Status: revised after the combined coverage and grounded round (`282-cov`), all four findings adopted; round 2 (`282-g2`), the sweep wording and the catalog status adopted.

## Preface: Lane + Hardware UAT declaration

**Lane:** Code

**PAR rows closed:** none.

**Hardware UAT:** N. Nothing that runs on the phone changes: the deleted production file has no caller.

## Preface — User Rubric

User Rubric: N/A — internal-only deletions; no code path the app runs changes.

---

## 0. TL;DR

- #282: `app/src/main/java/com/envi/wispr/model/ModelLifecyclePolicy.kt` (the `ModelUnloadPolicy` (removed) enum and the `ModelLifecyclePolicy.shouldUnload` object) has no production caller. Delete the file and its only test, `ModelLifecyclePolicyTest` (removed) (2 rows). An idle-unload schedule stays #37's job and will be designed against the real service lifetimes when it is built.
- #285: `SessionOwnerShapeTest.serviceLineCountIsReported` (removed) only prints two line counts and cannot fail. Delete it; the class doc's sentence that the line count "below is REPORTED, never gated" goes with it. Sizes are read from the generated `docs/audits/senior-audit-inventory.md` (regenerated at each regrade by `.claude/knowledge/senior-audit-inventory.py`).

Consolidation: none; two deletions.

## 1. Grounding (main a96566f)

Sweep for every reference (`/usr/bin/grep -rn "ModelLifecyclePolicy\|ModelUnloadPolicy\|shouldUnload\|serviceLineCountIsReported" app/src docs .claude CLAUDE.md scripts`, audit JSON excluded):

- `app/src/main/java/com/envi/wispr/model/ModelLifecyclePolicy.kt:4,14,15`: the declarations.
- `app/src/test/java/com/envi/wispr/model/ModelLifecyclePolicyTest.kt:7,10,15,16`: its only test class.
- `SessionOwnerShapeTest.kt` line 268 (removed): the printing row.
- `docs/feature-requests/issue-72-...md:152`, `issue-186-...md:705`: historical plans, left as records.
- `docs/audits/senior-audit-inventory.md:62,303`: the generated read map; it drops the two files at the next regeneration.
- `docs/audits/2026-09-21-*.md`: review transcripts, left as records.

Only `ModelLifecyclePolicyTest` (removed) calls `shouldUnload` (removed); no other tracked source uses either deleted symbol (coverage finding 2), including `androidTest`, `debug` and the other Gradle modules. `ModelLifecyclePolicy.kt` is the only file in `model/`, so the package goes with it.

The gitignored project guidance (swept in the primary checkout, `.claude/knowledge` and `.claude/rules`, archive excluded; coverage finding 1) names the policy in three places: `.claude/knowledge/model-delivery.md:5` (an owner list), `.claude/knowledge/architecture.md:67` (the `model/` package row) and `.claude/rules/code-design-rules.md:102` (RULE: an-affordance-that-sets-a-state-must-clear-it-from-the-same-surface, whose live example is `model/ModelUnloadPolicy` (removed)). `session-log.md:871` is a dated record and stays. The cross-platform catalog (`~/.claude/knowledge/enviouswispr`) records Android `model-unload-policy` as `designed-not-built` with evidence `android.model-unload-dead`, which cites the deleted file.

## 2. Design

1. Delete `ModelLifecyclePolicy.kt` and `ModelLifecyclePolicyTest.kt`.
2. Delete `serviceLineCountIsReported` (removed); replace the class doc's last sentence ("so the line count below is REPORTED, never gated") with "No line count is gated here; the generated audit inventory provides a dated size snapshot." (coverage finding 3).
3. Guidance, in the primary checkout (`.claude/` is gitignored): drop `model/ModelLifecyclePolicy.kt` from `model-delivery.md`'s owner list; delete the `model/` row from `architecture.md`; keep the rule in `code-design-rules.md` and replace its live example with a future control: "The case it will first govern is model residency (#37): a choice that keeps a heavy model in memory indefinitely must come with its way back on the same surface. No such control exists in code today; grep before citing one."
4. Catalog, after the merge: a `data/NNN-android-282-model-unload-removed-2026-09-24.sql` file moves Android `model-unload-policy` from `designed-not-built` to `absent`, pointing at #37, and retires the `android.model-unload-dead` evidence; `./rebuild.sh`.

## 3. Tests

Deletions have no failing-before row. The evidence instead (coverage finding 4): sweep tracked source and documentation, then sweep the shared `.claude` guidance in the primary checkout after its edits; classify remaining hits in this plan, historical records, and the dated inventory; confirm no code caller or stale guidance remains. Measure the unit suite (`scripts/measure-tests.sh`, `--rerun-tasks`) against the 1,312-test baseline on a96566f: exactly 3 fewer tests (two policy rows, one printing row) with 0 failures; `:app:assembleDebug` and `:app:assembleDebugAndroidTest` build; `scripts/check-visibility.py` and `scripts/check-cited-symbols.py` stay clean.

## 4. Blast radius

None at runtime. Rollback: revert the squash commit.

## 5. Ship criteria

- [ ] Sweep clean; suite count exactly 3 lower, 0 failures; both builds; checks clean.
- [ ] Codex code review ALL-CLEAR with a confirming round.
