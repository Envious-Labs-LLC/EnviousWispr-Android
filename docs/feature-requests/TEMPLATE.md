# Issue #<number> — <Short Title> — <YYYY-MM-DD>

GitHub issue: `#<number>`. Tier: <SMALL | MEDIUM | LARGE | REFACTOR>. Status: <DRAFT | APPROVED | SHIPPED>.

> **File.** Save as `docs/feature-requests/issue-<N>-<YYYY-MM-DD>-<slug>.md`. It must exist on disk before
> the coverage round. No-issue plans: `plan-<YYYY-MM-DD>-<slug>.md`.
>
> **Post.** After Gate 2 approval, `gh issue comment <N>` with the plan link. That comment is the audit trail
> a future session uses to skip straight to sign-off.
>
> **Cite what exists in backticks; mark what does not.** `scripts/check-cited-symbols.py` checks every
> backticked name against the tree. A name this plan PROPOSES is written `Name` (proposed) on its first
> mention; a framework API or a file on the phone is `Name` (external). Both marks are status claims the
> reviewer reads, so never put one on a name the plan asserts already exists. Delete the (proposed) marks
> when the plan moves to SHIPPED and re-run the check.
>
> **Sections 4-9 are MANDATORY for every change, whatever the tier.** Answering them is how you discover the
> real tier; skipping them lets a bias toward SMALL through unchecked. For a genuinely small change each one
> can be a single sentence. **The discipline is answering every section, not filling every section at
> length.** A one-sentence "no consumers beyond X" IS a valid consumer matrix. `MANDATORY ~~§6~~` is not.
>
> Ported from the macOS template 2026-08-30. Android differences are marked where they exist.

## Preface — Lane + Hardware UAT declaration

**Lane:** Code | Benchmark | CI/workflow | Docs/dev-tooling

> **Write the lane token FIRST on the line** — `**Lane:** Code` — then any paths or notes after it.
> `Mixed` is not a lane. A multi-lane change declares one primary lane here, then `mixed_pr: true` on its own
> line, enumerating each detected lane and its obligations.

Detect the lane from the actual change set — `git diff --name-only $(git merge-base origin/main HEAD)..HEAD`
— never plain `origin/main`, which attributes files main advanced through after the branch was cut. The
declared lane MUST match detection. Globs, artifacts and obligation IDs are owned by
[`.claude/rules/workflow-process.md`](../../.claude/rules/workflow-process.md) FACT: lanes.

**PAR rows closed:** `PAR-###`, `PAR-###` — or `none`. Name the evidence that closes each.

**Hardware UAT:** Y | N

If **Y**, describe in plain English what success looks like when a real person does this on the S26 with
real speech. Example: *"User adds 'fooFlux' as a custom word, dictates 'this is a fooFlux test' into
Messages, and fooFlux survives cleanup and lands in the field."*

If **N**, give a one-sentence reason. Valid only when the change has no runtime surface: docs, dev tooling,
or test-only. **Anything on the heart path — trigger, capture, ASR, text finalization, insertion — is
always Y.**

## Preface — User Rubric

**Every plan answers this or states why it does not.** Replace with
`User Rubric: N/A — <specific internal-only reason>` when there is genuinely no user-visible effect.
**A bare `N/A` is not an answer**, and "user-visible" is not limited to UI: a pipeline change alters the
user's result without touching a screen.

Answer every question before touching design, prompts or code. One-sentence answers are valid; blank ones
are not. Resolve each against a **named persona** from
`~/Developer/EnviousLabs/EnviousMarketing/packs/enviouswispr/brand-guide.md`. "A user" is not an answer.
The seven archetypes are shared across macOS, Windows and Android because they describe the PRODUCT.

1. **Who is this user in this moment?** Named persona, specific context, specific app. What were they doing
   thirty seconds ago and what do they want thirty seconds from now?
2. **Why would they want this?** In their words. If they would not say it out loud to a friend, it is not
   the real motivation.
3. **How would they invoke it?** When in their day does it fire? Voluntary or reactive? Already in the right
   app, or switching?
4. **What app are they in?** Enumerate the target apps from the persona's list. Different apps mean
   different conventions and different tolerance for being wrong.
5. **What is their natural input?** Five realistic samples in the persona's voice. The sounds leaving their
   mouth, not demo-engineered text.
6. **What does success feel like?** The moment they say "oh nice", or notice nothing and move on. Success
   invisible to the user is engineering success, not theirs.
7. **What does wrong-not-broken look like?** It works mechanically but misreads intent. They do not file a
   bug; they quietly stop trusting it. One sentence.
8. **What would a power user hack around this to get?** When the designed path fails, what do they try next?
   That is the real signal about what they wanted.
9. **What level of control would they want?** The full ladder, from off, through a deterministic command, to
   automatic inference. Below 100% accuracy users self-select where they sit, and collapsing the ladder to
   on/off forces the risk-averse and the flow-preferring into one compromise.

### Cross-persona check

Note briefly how each of the seven personas reacts. **Disagreement between them is the real design tension**
and must be resolved explicitly in §3.

---

## 0. TL;DR

One paragraph for a fresh session. What problem, what fix, what tier, what evidence will prove it.

## 1. Problem

What specifically fails today, with concrete evidence: logcat, a device run, a user report, a measurement.
No abstractions.

## 2. Goals & non-goals

### 2.1 Goals
Each one verifiable.

### 2.2 Non-goals
Deliberately excluded. This is what protects scope.

## 2.5 Grounding brief — MANDATORY before §3

Ground the current system before choosing an owner, an algorithm, a hook or a test shape. Answer all five
with pasted evidence. **A summary of the repo is not an answer.**

### 1. Trace producer → owner → consumer, end to end

Trace the real thing being changed from where it is created to every place that carries, transforms,
decides, persists or consumes it. Name every hop and its concrete mechanism: an Intent, a bound service
call, an AIDL method, a coroutine, a StateFlow, a Room write, a process boundary, a hook matcher. Cite
`file:line` for each and paste the command that found it.

**Do not stop at the file you plan to edit. Prove the proposed interception point can observe the real
payload on every path.** The failure this question exists to catch: a file written through a Bash heredoc is
carried by a Bash tool event, and a PreToolUse hook on `Edit|Write` cannot see it. If a producer reaches the
consumer through a different tool, process, callback or persistence path, list it separately.

### 2. Find the existing authority before proposing one

Search by CAPABILITY and synonyms, not the symbol name you expect. Name the existing owner, primitive,
script or rule that already handles the concern, and enumerate every caller of any shared primitive the plan
would wrap. If none exists, paste the negative grep and mark `new authority proposed`.

### 3. Read prior attempts and live direction

Read the issue with its comments, the [session log](../../.claude/knowledge/session-log.md), the owning
knowledge FACTs, the `PAR-###` rows, and the
[cross-platform catalog](~/.claude/knowledge/enviouswispr/catalog.db) for what macOS already decided. State
what was tried, what landed, what failed, and which decision is still binding. **Do not redesign a settled
decision without new evidence** — the catalog's `decision` table exists to stop exactly that.

### 4. Name the lifecycle, trust and process boundaries a naive design would miss

Mark each relevant boundary with its current and planned behaviour: app process versus `:asr` and `:polish`;
foreground service versus background; a bound service versus a dead one; the accessibility service alive
versus enabled-but-crashed; live state versus restored; success versus cancellation versus process death;
current generation versus a stale completion; user action versus background retry.

### 5. Prove the high-risk premises

List every negative claim, topology claim, lifecycle claim, count, and quoted literal the design relies on,
and paste the evidence. Before §3, run a problem-only Codex consult when the plan depends on who-calls-whom,
on process or coroutine lifecycle, on a negative claim local evidence cannot settle, or on an owner search
that stays uncertain. **Ask Codex to trace current reality and name naive-design traps. Do not ask it to
design the solution.**

## 3. Design

The chosen approach, and the alternatives rejected with their trade-offs.

## 3b. MANDATORY for placement-affecting plans — ownership justification

*This will live on X because Y; the alternative was Z but trade-off.*

## 4. MANDATORY — contract deltas

For every new or modified type, state the contract change **semantically**, not just the Kotlin signature:
what it now means to every consumer.

## 5. MANDATORY — end-to-end state and lifecycle audit

Map the pipeline in writing before writing code. §5 owns lifecycle and state; consumers are §6, failure
modes §7, signals §8, fallbacks §9.

**Each row names a POPULATION to enumerate, not a topic to describe.** Answer with a named counterexample
and its `file:line`, or `enumerated, none found`. **A bare `N/A`, "none", or "cannot happen" is not an
answer.**

## 6. MANDATORY — downstream consumer matrix

| Contract delta | Consumer | Current behaviour | Required behaviour | Code change? | Verified by |
|---|---|---|---|---|---|

## 7. MANDATORY — failure-mode × caller table

| Failure mode | Origin | Caller | What the user sees | Persisted state | Retry |
|---|---|---|---|---|---|

**What the user sees** is answered against [`content-brand.md`](../../.claude/rules/content-brand.md), and
against the macOS copy in the catalog where a matching surface exists. Android inventing its own sentence
for a state macOS already words is how the two products drift.

## 8. MANDATORY — caller-visible signals audit

Every field whose *presence*, *absence*, *value*, *staleness* or *identity* carries meaning beyond its
literal type. These are the implicit signals UI and persistence read. Leave a row out only by writing
`not present in this change`.

## 9. MANDATORY — fallback source-of-truth audit

| Failure branch (§7) | Candidate expression | Source | Why authoritative here | Acceptance predicate | If none qualifies | Consumer (§6) |
|---|---|---|---|---|---|---|

## 10. File-by-file changes

Use the evidence from §2.5 to prove every path, symbol, owner and process boundary named here. Do not repeat
the evidence.

## 11. Testing

**Answer these three before the table.** [`testing-philosophy.md`](../../.claude/rules/testing-philosophy.md)
owns them, and it can legitimately say a test should not exist.

1. **Class of every new test** — product outcome, drift guard, or harness contract. Decide with *"when this
   fails, the user sees ___."* Cannot finish that sentence? It is not a product-outcome test.
2. **What revert would turn it red?** Name it. **A test that passes with the behaviour removed is worse than
   no test**, and the only way to know is to perform the revert, watch it fail, and restore.
3. **What is deliberately NOT tested, and why?**

### 11.1 Hardware UAT spec — required when the Preface declared Hardware UAT: Y

- **Subsystem:** heart path | limb
- **Recipe:** the named recipe in [`device-testing.md`](../../.claude/knowledge/device-testing.md), or a new
  one added there. **An instrumented test is not a substitute** for anything the platform will not let a
  test stage.
- **Expected observation:** the exact thing to look for, and the oracle that decides it. A recipe whose
  oracle is a log line the buggy code also prints is not a test.
- **Phone state to restore afterwards:** every setting the run changes.

### 11.2 Other obligations

| Test | Class | Proves | Revert that turns it red |
|---|---|---|---|

## 12. Blast radius & rollback

- Modules touched, and modules deliberately NOT touched. The negative space matters.
- Exact revert steps.

## 13. Ship criteria specific to THIS change

The shared completion contract is owned by `.claude/rules/workflow-process.md` RULE: definition-of-done,
and the per-lane evidence by FACT: lanes in the same file. Do not restate either here. A copied checklist
is a second answer to a question that already has one, and it is the copy that goes stale.

List only what is true of this change and nothing else:

- [ ] <the observable result, in the words a user would use>
- [ ] <the specific surface, device, or app it was confirmed in>

## 14. Open questions

## 15. Related

Issues, plans, `PAR-###` rows, catalog features.

---

## Checklist for the plan author

- [ ] Gate 0 prior context posted before this file was written
- [ ] User Rubric answered against named personas, or N/A with a specific reason
- [ ] §2.5 grounded in real code before §3 was written, never the reverse
- [ ] §4-9 answered, briefly or in full, none struck through
- [ ] Lane declared and matching detection
- [ ] Self-reviewed to all-clear before any reviewer saw it

## Checklist for the reviewer

- [ ] Is the direction right, before anything else?
- [ ] Is any load-bearing negative claim unevidenced?
- [ ] Does any §5 row describe a topic instead of enumerating a population?
- [ ] Does the design answer a question the rules already answered?
- [ ] Would any test here pass with its behaviour removed?
