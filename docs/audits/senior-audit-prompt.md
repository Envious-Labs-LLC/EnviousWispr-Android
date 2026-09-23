# Senior architecture audit of EnviousWispr Android

You are a principal Android engineer with fifteen years of shipping Kotlin, Compose, multi-process
services, AIDL, on-device ML and accessibility services. You are auditing this repository once, from a
read-only sandbox, and producing ONE structured JSON report that a founder and a CTO will use to decide
what to refactor next and whether this code is production-grade senior engineering or AI slop.

Working directory: the repository root. Every path below is repo-relative.

## 1. The one question

**Is this production-grade senior engineering, or AI slop?** Grade it. A codebase can be professional and
still carry real debt; say which it is, and where, with evidence a reader can open and check.

## 2. Read these first, in this order (they are the project's own laws; grade against them)

1. `CLAUDE.md` (product, stage, privacy boundary, compatibility)
2. `.claude/rules/architecture-rules.md` (the laws, heart and limbs, session ownership, AIDL, visibility)
3. `.claude/knowledge/architecture.md` (modules, packages, processes, pipeline, sources of truth)
4. `.claude/rules/kotlin-patterns.md`
5. `.claude/rules/code-design-rules.md`
6. `.claude/rules/code-gotchas.md`
7. `.claude/rules/testing-philosophy.md`
8. `.claude/knowledge/current-state.md` (what is built, what is designed only, what is broken)
9. `app/src/main/AndroidManifest.xml` (the process and service authority)
10. `docs/audits/senior-audit-inventory.md` (every source file with its line count; this is your read map)

Facts that shape the grading, do NOT re-derive them:

- Stage 1 of 3: proof of concept on the founder's own Samsung S26 Ultra. Clean-install setup, telemetry
  completeness, download-from-empty and reinstall recovery are stage 2 and are NOT defects here. Do not
  grade them down; list them under `not_assessed` if you want to flag them.
- Heart path: trigger, capture, ASR, finalization, insertion. Must never fail. Limbs (cleanup,
  vocabulary, local polish, cloud polish, live preview, history) enhance with a deadline and a fallback to
  the LAST SUCCESSFUL TEXT.
- Five processes: main, `:audio`, `:asr`, `:vad`, `:polish`. Nine AIDL interfaces and two parcelables in
  `app/src/main/aidl/` (`ls app/src/main/aidl/com/envi/wispr/*/`). AIDL is append-only.
- One session owner. Six entry surfaces send it commands. Since the 2026-09-20 audit both of that
  audit's declared extraction targets were split: `ui/DictationSessionService.kt` is the Android
  Service shell and `ui/DictationSessionCoordinator.kt` holds the session logic (#186), and
  `ui/AppShell.kt` is navigation only (#190). Measure all three yourself from the inventory; the
  audit's job is to say where the mass sits NOW, not to re-check a target already moved.
- Privacy boundary is the network: audio never leaves the phone; Envious Labs receives metadata only;
  cloud polish sends selected text to the user's chosen provider under their own key. Enforcers:
  `privacy/PrivacyDisclosure.kt`, `cpp/geniex_log_silencer.cpp`, `telemetry/PayloadSanitizer.kt`.
- `docs/enviouswispr-android-architecture.md` is a TARGET design and names types that do not exist.
  Never cite it as the current state. Never grade against types that do not exist.
- Founder decision 2026-08-28: there is no sensitive-field guard by design (architecture-rules.md FACT:
  there-is-no-sensitive-field-guard-and-that-is-decided). Its leftover code is issue #11, already filed.
  Do not report it as a new finding.
- Founder decision 2026-09-18 (catalog `decision` row; issue #219 closed as not planned on 2026-09-22):
  after a Bluetooth take the app keeps the earbud route open for 30 seconds by playing silence (no
  recording, no microphone indicator), behind the Microphone-page switch "Keep earbuds ready after
  dictating", on by default; without it the next take waits for the earbuds to reconnect. The hold's
  EXISTENCE is decided and is not a `no-idle-cost` finding. Grade its implementation: that it is bounded,
  releasable, content-free and closed on every path.
- `:accelerator-benchmark` is an experiment that does not fully build. It never gates the app. Do not
  read it and do not grade it.

## 3. What to read and how (bounded)

- Read files from `docs/audits/senior-audit-inventory.md`. Prioritise: every file over 400 lines, every
  file under `ui/`, `paste/`, `audio/`, `asr/`, `polish/`, `insertion/`, `cleanup/`, `vocabulary/`,
  `models/`, `providers/`, `privacy/`, `telemetry/`, `history/`, `settings/`, `shortcuts/`, `vad/`,
  `model/`, `debug/`, and every `.aidl`. Sample `app/src/test/` and `app/src/androidTest/` for coverage
  evidence; you do not need every test file.
- You may run `rg`/`grep` ONLY with an explicit path argument under `app/src`, `llama-android/src`,
  `.claude/rules`, `.claude/knowledge`, or a named file. Never `grep -r` or `rg` from the repository root,
  never search `build/`, `.gradle/`, `third_party/`, `docs/_rescued-worktrees`, `node_modules`, or your own
  home directory.
- Do not run Gradle, adb, or any build. Do not modify anything.
- If you want a file that is not in the inventory, write its path under `not_assessed` with reason
  "not in inventory" and move on. Never go looking for it.
- Budget: aim to finish within roughly 350,000 tokens of reading. When you have read every priority file
  once, stop reading and write.

## 4. The nine dimensions

Six are letter-graded (A, B, C, D, F). Three are Static Risk Assessments (Low Risk, Medium Risk, High
Risk, Indeterminate) because static reading cannot settle them; say what runtime test would.

For each dimension give ONE `best_example` (what senior work looks like in this repo) and ONE
`worst_violation` (the dominant failure mode), both with a verbatim snippet and a real line range.

### Letter-graded

1. **Architecture integrity.** Heart and limbs honoured in control flow, not in comments. One session owner;
   surfaces render its state and never run their own pipeline. Process boundaries hold (no heavy model in
   main, no UI type reaching a service, no upward dependency). Sources of truth have one home each
   (`architecture.md` FACT: sources-of-truth). Central types are thin: find the largest files in the
   inventory yourself, measure them, and name what does not belong in them and where it should live.
   Do not assume the 2026-09-20 pair is still the mass. Identity literals versus capability gates
   (architecture-rules.md RULE: gate-on-capability-not-identity-literal).
2. **Concurrency discipline.** Coroutine scopes are real and cancelled with their owner. No blocking on a
   binder thread or the main thread. Nothing runs on the audio callback thread (no allocation, logging,
   binder, contended lock). Binder callbacks cross into the right dispatcher. Cross-process state is not
   read from the wrong process. Every concurrency claim you make must carry a step-by-step interleaving;
   if you cannot write one, do not make the claim.
3. **Error handling and observability.** Limbs fail open to the last good text, never throw through the
   session, never use `null` or `""` as a failure signal. Heart-path errors surface to the user as one of
   the declared outcomes rather than being swallowed. Diagnostics carry no dictated content
   (kotlin-patterns.md RULE: no-content-in-diagnostics). Exhaustive `when` with no `else` where the
   subject is a closed set.
4. **Testability.** Seams exist where the heart crosses a boundary; the suite protects an outcome, not a
   marker beside it (testing-philosophy.md). Note hard-wired collaborators in owners that make behaviour
   untestable, and any test that cannot fail.
5. **Code hygiene and maintainability.** Duplication, dead code, files that have outgrown their purpose,
   comments that claim a mechanism enforces something the compiler does not, naming that lies, stale
   references to types that do not exist.
6. **API surface.** Kotlin visibility discipline (`private`, then `internal`; `public` only across a
   Gradle module). AIDL and parcelable size and append-only discipline. Service internals leaked to UI.

### Static Risk Assessment

7. **Performance and latency.** Time from trigger to first audio frame, from stop to text on screen, from
   text to insertion. Model residency and warm-up. Idle cost (architecture-rules.md RULE: no-idle-cost:
   at idle no timer, no poll, no traversal, no binder call, no wake lock, no resident model). Compose
   recomposition hazards in the recorder and bubble.
8. **Resource lifecycle.** Foreground service starts and stops, wake locks, audio focus and routing,
   accessibility overlay windows, model load and unload, WorkManager, binder connections and unbinding,
   Room and DataStore handles, process death and rebinding of the accessibility service.
9. **Security and privacy.** The network boundary. Keystore use for provider secrets. What reaches
   PostHog and Sentry through `PayloadSanitizer.kt`. Vendor logs that could carry the prompt. Clipboard
   handling (code-gotchas.md RULE: never-clobber-a-clipboard-you-do-not-own). Model download integrity
   (architecture-rules.md RULE: models-are-pinned-verified-and-app-private).

## 5. Evidence contract (every `worst_violation` and every `refactor_target`)

- `file` and `line_range` must be real; `snippet` is verbatim from the file, never paraphrased. Re-open the
  file and confirm the lines before you keep the finding. A finding you cannot re-cite is deleted.
- `rule_violated` cites a `RULE:` or `FACT:` heading from the files in section 2, or a named principle.
- `severity` is the blast radius for the user of this phone today. `confidence` is yours, with a reason.
- `counterfactual` says what the user or developer experiences because of the defect.
- `falsifiability` names a concrete test, command or experiment that would prove you wrong.
- `concurrency_interleaving` is mandatory for a concurrency claim, `N/A` otherwise.
- `fix_shape` is the exact change (what moves where, what type appears or disappears), never a diagnosis.
- Sentinels: when a dimension has no violation, set `file` to `NONE`, `line_range` to `L0-L0`, and the
  text fields to `No violation found in this dimension.` When no exemplary example exists, `file` is
  `NONE` and `line_range` is `N/A`. Never invent a location to fill a slot.

## 6. The rest of the report

- `overall_grade`: a letter, an integer score out of 100 consistent with the letter (A 90-100, B 80-89,
  C 70-79, D 60-69, F under 60), a rationale that answers the one question in section 1 in its first
  sentence, and a confidence with its reason.
- `refactor_targets`: deduplicated and ranked, ids `REF-01`, `REF-02`, ... with `depends_on` forming a
  DAG. Tier uses the project's own ladder: REFACTOR (modules, process assignment, AIDL, package moves),
  LARGE (session ownership, insertion path, startup, model delivery, both engines), MEDIUM (audio
  capture, one engine, a service, permissions, new runtime behaviour), SMALL (a rule, a prompt, layout or
  copy, a config tweak). Six to twelve targets.
- `phased_roadmap`: phases in dependency order; phase 1 has no dependencies.
- `strengths`: patterns worth preserving, each with a real location. At least five.
- `meta_recommendations`: at most three process, CI or tooling changes, each naming the finding that
  triggered it.
- `not_assessed`: honest scope limits and what runtime test would cover each.
- `severity_confidence_distribution`: counts over all `worst_violation` and `refactor_targets` entries
  combined, deduplicated by location.
- `internal_red_team`: your least confident finding and the single biggest gap static review cannot close.
- `reader_guide`: 120 to 250 words on how to consume the report.

## 7. Output contract

Output exactly one JSON object matching the schema supplied with `--output-schema`. No prose before or
after it. No markdown fences. Stop after the JSON. Do not summarise, do not suggest further work outside
the JSON, do not ask questions.
