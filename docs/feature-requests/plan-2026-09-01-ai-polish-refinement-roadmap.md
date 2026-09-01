# AI Polish refinement roadmap — 2026-09-01

Status: DRAFT, revised after one Codex architecture consult (scratchpad `roadmap-consult-output.txt.last`,
session `01a05e59`). Founder framing (2026-09-01): "treat this as refactor-style work, not just a visual
improvement ... fixing the architecture, fixing the experience, fixing the UI." This document is the
program. Each phase below becomes one issue, one plan under `docs/feature-requests/`, and one pull request,
run through the ten-step process in `.claude/rules/workflow-process.md`.

**Lane:** Docs/dev-tooling

User Rubric: N/A — a roadmap document; every phase carries its own rubric in its own plan.

**Consolidation:** the dominant root across every phase is "what polish is running and what it did", which
today has three homes that disagree: the preference file the app writes, the copy of it a live `:polish`
process loaded at its own start, and the screen's own draft. The program collapses them to one owner: the
main process latches the policy per session and sends it with every request; the engine holds no copy of
settings at all; one typed outcome comes back; the screen renders saved state only.

## 0. What the audit found

Sources: two read-only audits of the engine and the cloud path (scratchpad `polish-runtime-audit.md`,
`cloud-polish-audit.md`, every claim below re-checked against the file and line named), the cross-platform
catalog, the macOS knowledge corpus, the open issues, two measurements on the S26 Ultra today, and the
Codex consult.

### Measured on the phone

1. **A polish setting change does not reach a running engine.** With the saved mode `OFF`, a session was
   started and cancelled; the `:polish` process (pid 27263) stayed alive. The mode was switched to `This
   phone` (the preference file read `OFFLINE_S1`). A second session bound to the same pid and `warmUp()`
   did not load S1-mini: memory stayed near 90 MB and no `S1-mini loaded` line appeared. Control: after
   `am kill com.envi.wispr`, a fresh process (pid 29843) logged `S1-mini loaded: GenieX 0.4.0 llama.cpp on
   gpu; Ready on GPU in 1843ms`. Cause: `PolishService.kt:68` reads the mode through a `SharedPreferences`
   instance created once per process (`ProviderConfigurationRepository.kt:22-25`), and Android's
   `SharedPreferences` does not reload across processes. The cleanup options already travel the right way,
   as booleans on every `polish()` call (`DictationSessionService.kt:519-524`).
2. **S1-mini loads twice-triggered and dies with the binding.** The first load decision is in
   `PolishService.onCreate` (`PolishService.kt:141-145`), before any binder command; `warmUp()` on connect is
   the second trigger (`DictationSessionService.kt:192-196`). The engine process lives only as long as the
   session's binding (`:351-353`, `:924-935`) and `onDestroy` closes the runtime (`PolishService.kt:152`), so
   every `This phone` dictation pays the load, measured at 1843 ms on the GPU path. Both heavy models are
   resident together for the whole session, which `.claude/rules/architecture-rules.md:82-84` names as not
   a supported state.

### Read in the code

3. **A local polish has no deadline and cannot be cancelled.** `PolishService.polishWithS1`
   (`PolishService.kt:191-210`) calls `S1GenieXRuntime.generate` (`S1GenieXRuntime.kt:65`) with no budget
   and no cancellation token. The session owner has no watchdog on the callback
   (`DictationSessionService.kt:503-546`). A wedged generation leaves a dictation at "processing" forever.
   `architecture-rules.md:79-80`: every limb has a deadline. There is no binder method to cancel a polish
   (`IPolishService.aidl:5-15`); destroying the service is the only route.
4. **A polish failure is invisible, and the fallback has two owners.** `ProviderPolishResult.Failure`
   carries a status code (`ProviderPolishClient.kt:59-62`); `PolishService.kt:84` keeps only the kind, `:92`
   logs it, and the engine label becomes `DETERMINISTIC` (`:100`), which History renders as "Cleaned up on
   this phone". A revoked key and a healthy offline run look the same. The session owner's own fallback
   runs `DeterministicCleanup` plus `RegexPolisher` (`DictationSessionService.kt:547-554`) while the engine
   runs `PolishPipeline` (`PolishService.kt:71-89`); nothing keeps those two results the same.
   `IPolishCallback.onError` has no producer. The callback carries no request identity, so a late result
   from an older request cannot be told apart from the current one.
5. **Engine housekeeping defects.** A second `polish()` overwrites the in-flight call's cancellation token
   (`PolishService.kt:62-63`); a throwing `onResult` is retried in the catch (`:105-112`);
   `selectedProvider!!` (`:97`); the caught exception is dropped from the log (`:107`).
6. **Cloud client defects.** `readResponse` can dereference a null error stream and report an empty-bodied
   401 as `NETWORK` (`ProviderPolishClient.kt:320-322`); a key survives a provider switch with no surface to
   remove it (`ProviderConfigurationRepository.kt:74-82`, `:100-104`); `UNSUPPORTED_PROVIDER` has no
   producer; `defaultModel` has no caller (`PolishModelCatalog.kt:93`); any three backticks in an output fail
   it (`ProviderPolishPrompt.kt:24`); Claude `max_tokens` is a fixed 1024 (`ProviderPolishClient.kt:267`); no
   retries (#4).
7. **The screen.** `ui/PolishScreen.kt` mirrors persisted state locally and reconciles it (#62's 23 rounds);
   the S1-mini card renders in every mode; three uneven pills; inline provider setup; `settings.error` is
   never displayed. The key Check is a format check only (#61). Model health is classified by display
   string (#64).
8. **Parity rows.** Catalog `ai-polish` domain, Android versus macOS: `ai-model-discovery` absent,
   `hallucination-protection` partial, `context-aware-polish` absent, `writing-style-presets` absent by
   decision (not coming back), `apple-intelligence-*` and `eg1-polish` platform-inapplicable.

### What is clean

Prompt and logging privacy on both paths (no custom words, app name, surrounding text or transcript in any
log; vendor logging silenced before SDK init); key storage (never blank, never in preferences, redacted
`toString` everywhere); cloud timeouts and size limits; self-hosted endpoint validation.

## 1. The phases

Order is by dependency, per the consult: contract first, then structural safety, then the residency
decision because it changes the latency population every budget is measured against, then the measured
watchdog, then what the user sees, then the screen. Each phase leaves the app usable on the phone.

| # | Phase | Closes | Tier | Depends on |
|---|---|---|---|---|
| 1 | **Engine contract.** Policy travels with every request; one typed outcome with request identity comes back; a cancel method exists; the engine holds no settings. | new issue (findings 1, 4 contract half, 5) | REFACTOR (AIDL append) | none |
| 2 | **Residency decision.** Measure serial versus overlapping residency end to end; record the supported state in the architecture rule; implement serial loading if the measurement says so. | finding 2, #37 | LARGE | 1 |
| 3 | **Engine safety, measured.** Local deadline that abandons a wedged generation and restarts the engine process; client watchdog with a budget measured after phase 2; user cancel reaches the engine. | new issue (finding 3) | LARGE, heart | 1, 2 |
| 4 | **Outcome to the user.** History names the reason; the completion surface shows the locked sentence and the reason set; one fallback owner. | #18 (polish rows), new issue (finding 4 user half) | MEDIUM | 1 |
| 5 | **Cloud client cleanups.** Null error stream, orphaned key, dead enum member, dead `defaultModel`, fence check, Claude `max_tokens`, self-hosted body. Each its own SMALL pull request. | new issue (finding 6) | SMALL each | 1 |
| 6 | **The AI Polish screen.** Master switch, two engine cards, picker sheet, setup page, remember-last-mode; the tab renders saved state only; semantic model health. | #67, #53, #64 | MEDIUM | 1, 4 |
| 7 | **Live key check.** Check calls the provider's model-list endpoint; status-aware reasons shared with phase 4. Optional next: live model list. | #61 | MEDIUM | 4, 6 |
| 8 | **Polish quality parity.** Short-transcript guard, retry policy, non-English handling, broader hallucination guards, app-name and custom-word context for cloud prompts. | #2, #4, #3 | MEDIUM each | 4 |

### Phase 1 — Engine contract

**Policy contract.** The main process latches the polish policy (mode, provider, model, endpoint,
protocol) into the session at bind time, beside `sessionPreferences` (`DictationSessionService.kt:511-524`
is the precedent), and sends it with every polish request and with warm-up. A settings change during a
session applies from the next session; that is the stated contract, not an accident. The engine never
reads the preference file: `PolishService.onCreate` stops calling `ensureModelLoaded` from a preference
read, and `ProviderConfigurationRepository` leaves the engine except as the Keystore accessor for the key,
which is process-safe. Each request carries one immutable policy snapshot captured when it enters; there
is no shared mutable policy field.

**Binder surface (append-only, `architecture-rules.md` RULE: aidl-is-append-only).** Append to
`IPolishService`: a polish method that takes the cleanup flags, the policy fields, a request id and the
callback; a warm-up that takes the mode; `cancel(requestId)`. Append to `IPolishCallback`: `onOutcome` (proposed)
carrying request id, final text, engine label, a reason code, an HTTP status when present, and latency.
The old `polish`, `warmUp`, `onResult` and `onError` stay declared. Both ends ship in one APK, so once the
session owner moves to the new methods nothing binds the old ones; whether they are deleted in the same
change (`GR-MIGRATION-COMPLETE`, no shims) or in the next (`aidl-is-append-only`, keep until nothing binds)
is a rules tension the phase plan puts to Codex with this reading attached: delete in the same change,
because no installed binary can ever hold the other side of this interface.

**Delivery.** At most one callback per request, guarded on the engine side; per-request cancellation
tokens, all cancelled in `onDestroy`; the caught exception passed to the log; `selectedProvider!!` removed
by returning the provider from the `when`. The session owner discards any outcome whose request id is not
the current one. `onError` is retired by the same migration rather than given a producer.

**One fallback owner.** The session owner's fallback (`DictationSessionService.kt:547-554`) calls the same
`PolishPipeline.run` the engine calls, with the same options, so a fallback result cannot drift from the
engine's deterministic result. Both are in the shared `cleanup/` package already.

Hardware oracle: the measurement in finding 1 repeated, expecting `S1-mini loaded` on the same pid after a
mode switch, plus a dictation in each mode with the History row naming the engine.

### Phase 2 — Residency decision

The written rules do not conflict: stage 1 optimises the felt experience and does not waive the more
specific architecture rule, and "by default" leaves room for a supported, capability-based exception that
does not exist merely because both models fit in one observed session. This is an architecture decision,
not a founder decision. The measurement that settles it, on the S26 Ultra with ordinary apps open: repeated
end-to-end sessions in both shapes (serial: S1 loads when ASR delivers; overlapping: today), recording time
to inserted text, p50 and p95 model-load and polish latency, peak and steady process memory, available
memory margin, low-memory kills, thermal state, and recovery. If overlapping is safe, the rule records the
S26 exception with the numbers; if not, `warmUp` is deferred until ASR delivers. The unload schedule (#37)
and the development-model surface (#21) follow in the same phase.

### Phase 3 — Engine safety, measured

A local deadline whose expiry is honest: a wedged native generation cannot be interrupted
(`S1GenieXRuntime.kt:92-98` checks nothing while waiting for the next token), so on expiry the engine
reports a timeout outcome, marks its runtime poisoned, and asks the OS to end its own process after the
outcome is delivered, so the next session starts clean. The session owner's watchdog publishes the
deterministic fallback with a timeout reason and calls `cancel(requestId)`; `publicationStarted` guards the
publication, the cancel call guards the engine. User cancel during processing calls `cancel(requestId)`.
Budgets are measured after phase 2, never guessed (`validation-discipline.md` RULE:
measure-with-the-real-tool-never-a-simulation).

### Phase 4 — Outcome to the user

`PolishEngineLabels` grows the reason vocabulary History renders. The completion surface shows "Polish
failed. Using raw text." (catalog decision 2026-07-15, product-wide; "raw" there means not AI-polished, the
same deterministic pre-polish text macOS keeps) followed by a reason line from the macOS set. Where Android
cannot tell two macOS cases apart, the plan maps to the nearest honest sentence and says so. The reason
enum (rejected key, denied, rate limited, out of credits, outage, model unavailable, blocked, truncated,
too long, unreachable, timed out, no output, configuration, local not ready, cancelled, unexpected) is
derived from `ProviderFailureKind` plus status in one place.

### Phase 5 — Cloud client cleanups

One SMALL pull request each, in any order after phase 1, each with the test that turns red on revert.

### Phase 6 — The AI Polish screen

Already planned: `docs/feature-requests/issue-67-2026-09-01-ai-polish-switch-and-cards.md`. Before its
grounded review it is updated to depend on phases 1 and 4 and to carry, explicitly, the coverage-round
findings: snackbar replay after recreation, migration when `last_on_mode` (proposed) is absent, restoring a provider
whose key is missing, form loss on storage failure, and showing one provider's setup while another is
active. Semantic model health (#64) lands here because the card state reads it.

### Phase 7 — Live key check

`GET /v1/models` (OpenAI), `GET /v1beta/models` (Gemini), `GET /v1/models` (Claude) with the auth headers
the client already sets; no user content. 200 valid, 401 rejected, 403 denied, 429 not a verdict, network
or timeout unknown. Needs a method field on the request plan and a probe response format. The model list
those endpoints return is the natural next step for `ai-model-discovery`, not part of #61.

### Phase 8 — Polish quality parity

Each item is its own SMALL or MEDIUM plan against the macOS knowledge corpus
(`~/Developer/EnviousLabs/EnviousWispr/.claude/knowledge/llm-contract.md`, `polish-prompt-architecture.md`).

## 2. Founder decisions needed

None. The rules, the catalog decisions and the consult answer every fork in phases 1 through 8; where two
rules pull apart (append-only versus no shims, in phase 1) the phase plan puts it to Codex with a reading
attached.

## 3. Not in this program

Telemetry, first-run model download, reinstall recovery (stage 2 per `CLAUDE.md`). Writing-style presets
(decided out, product-wide). Apple Intelligence and EG-1 (platform-inapplicable). Self-hosted as a fresh
selection (catalog decision 2026-09-01).

## 4. How progress is reported

One task label for the program in every message: `AI Polish Refinement`. Each phase reports its own
ten-step gates by name. A phase is done when its pull request is merged and its hardware oracle ran.
