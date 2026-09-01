# AI Polish refinement roadmap — 2026-09-01

Status: DRAFT. Founder framing (2026-09-01): "treat this as refactor-style work, not just a visual
improvement ... fixing the architecture, fixing the experience, fixing the UI." This document is the
program. Each phase below becomes one issue, one plan under `docs/feature-requests/`, and one pull request,
run through the ten-step process in `.claude/rules/workflow-process.md`.

**Lane:** Docs/dev-tooling

User Rubric: N/A — a roadmap document; every phase carries its own rubric in its own plan.

**Consolidation:** the dominant root across every phase is "what polish is running and what it did", which
today has three homes that disagree: the preference file the app writes, the copy of it a live `:polish`
process loaded at its own start, and the screen's own draft. The program collapses them to one owner (the
main process pushes policy into the engine, the screen renders saved state only, the engine reports one
typed outcome).

## 0. What the audit found

Sources: two read-only audits of the engine and the cloud path (scratchpad `polish-runtime-audit.md`,
`cloud-polish-audit.md`, every claim below re-checked against the file and line named), the cross-platform
catalog, the macOS knowledge corpus, the open issues, and two measurements on the S26 Ultra today.

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
2. **S1-mini is loaded at bind and destroyed a few seconds after unbind.** The engine process lives only as
   long as the session's binding (`DictationSessionService.kt:351-353`, `:924-935`); `onDestroy` closes the
   runtime (`PolishService.kt:152`). Every `This phone` dictation pays the model load, measured at 1843 ms
   on the GPU path today. Both heavy models are resident together for the whole session, which
   `.claude/rules/architecture-rules.md:82-84` names as not a supported state.

### Read in the code

3. **A local polish has no deadline and cannot be cancelled.** `PolishService.polishWithS1`
   (`PolishService.kt:191-210`) calls `S1GenieXRuntime.generate` (`S1GenieXRuntime.kt:65`) with no budget
   and no cancellation token. The session owner has no watchdog on the callback either
   (`DictationSessionService.kt:503-546`). A wedged generation leaves a dictation at "processing" forever.
   `architecture-rules.md:79-80`: every limb has a deadline.
4. **A polish failure is invisible.** `ProviderPolishResult.Failure` carries a status code
   (`ProviderPolishClient.kt:59-62`); `PolishService.kt:84` keeps only the kind and `:92` logs it; the
   engine label becomes `DETERMINISTIC` (`:100`), which History renders as "Cleaned up on this phone". A
   revoked key and a healthy offline run look the same. Android has none of the sixteen macOS completion
   warnings, and not the locked sentence "Polish failed. Using raw text." (catalog decision 2026-07-15).
   `IPolishCallback.onError` has no producer.
5. **Engine housekeeping defects.** A second `polish()` overwrites the in-flight call's cancellation token
   (`PolishService.kt:62-63`); a throwing `onResult` is retried in the catch (`:105-112`); `selectedProvider!!`
   (`:97`); the caught exception is dropped from the log (`:107`).
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

Order is by dependency and by what the founder feels first. Each phase leaves the app usable on the phone.

| # | Phase | Closes | Tier | Depends on |
|---|---|---|---|---|
| 1 | **Engine truth.** The main process owns polish policy and pushes it into the engine on every bind. | new issue (measured defect 1) | REFACTOR (AIDL append) | none |
| 2 | **Engine safety.** Local polish deadline and cancellation; client-side watchdog; callback delivered at most once; per-call cancellation; `onError` produced or deleted; exception logged. | new issue (findings 3, 5) | LARGE (heart) | 1 |
| 3 | **Polish outcome reaches the user.** One typed outcome crosses the binder (engine, failure reason, status); History names the reason; the completion surface shows the locked sentence and the macOS reason set where Android can tell them apart. Cloud client fixes from finding 6. | #18 (polish rows), new issue (finding 4, 6) | LARGE | 1, 2 |
| 4 | **The AI Polish screen.** Master switch, two engine cards, picker sheet, setup page, remember-last-mode; the tab renders saved state only; semantic model health. | #67, #53, #64 | MEDIUM | 1, 3 |
| 5 | **Live key check.** Check calls the provider's model-list endpoint; status-aware reasons shared with phase 3. Optional next step: live model list (`ai-model-discovery`). | #61 | MEDIUM | 3, 4 |
| 6 | **Model residency.** One heavy model at a time, or a measured decision to keep both on this phone; an unload schedule; the development model visible and removable. | #37, #21, finding 2 | LARGE | 2 |
| 7 | **Polish quality parity.** Short-transcript guard, retry policy, non-English handling, the broader hallucination guard set, app-name and custom-word context for cloud prompts. | #2, #4, #3 | MEDIUM each | 3 |

### Phase 1 — Engine truth

Append `applyPolicy(String mode, String provider, String model, String endpoint, String protocol)` to
`IPolishService` (append-only per `architecture-rules.md` RULE: aidl-is-append-only). `DictationSessionService`
calls it in `onServiceConnected` before `warmUp()`, reading `ProviderConfigurationRepository` in the main
process. `PolishService` keeps the last applied policy in a volatile field and reads it in `polish`,
`isReady`, `getStatus` and `warmUp`; the API key is still fetched from the Keystore by provider name at
polish time, which is process-safe. At process start the field is seeded from the preference file, which is
correct at that instant, so a caller that never applies a policy behaves exactly as today. Hardware oracle:
the measurement in finding 1 repeated, expecting `S1-mini loaded` on the same pid after a mode switch.

### Phase 2 — Engine safety

`polishWithS1` takes the call's `ProviderCancellation` and a wall-clock budget; `S1GenieXRuntime.generate`
checks the token between streamed tokens. `DictationSessionService.polishAndPublish` posts a delayed
deterministic publish that the existing `publicationStarted` guard discards if the real callback lands
first. `PolishService` delivers the callback at most once, keys cancellation per call, cancels every
outstanding token in `onDestroy`, and either produces `onError` or removes it from the interface. Budget
values are measured on the phone, never guessed (`validation-discipline.md` RULE:
measure-with-the-real-tool-never-a-simulation).

### Phase 3 — Polish outcome reaches the user

A `PolishOutcome` crosses the binder: engine label, a reason enum (mapped from `ProviderFailureKind` plus
status: rejected key, denied, rate limited, out of credits, outage, model unavailable, blocked, truncated,
too long, unreachable, timed out, no output, configuration, local not ready, unexpected), and latency.
`PolishEngineLabels` grows the reason vocabulary History renders. The completion surface shows "Polish
failed. Using raw text." (locked sentence) with the reason line from the macOS set. Where Android cannot
tell two macOS cases apart, the plan says so and maps to the nearest honest sentence rather than inventing
one. Also in this phase: the null error stream, the orphaned key on provider switch, the dead enum member,
the dead `defaultModel`, the code-fence check, Claude `max_tokens`.

### Phase 4 — The AI Polish screen

Already planned: `docs/feature-requests/issue-67-2026-09-01-ai-polish-switch-and-cards.md`. It is updated
to depend on phases 1 and 3 (the card status and the badge read the typed outcome and semantic model
health from #64 instead of display strings) and to fold the coverage-round findings.

### Phase 5 — Live key check

`GET /v1/models` (OpenAI), `GET /v1beta/models` (Gemini), `GET /v1/models` (Claude) with the same auth
headers the client already sets; no user content. 200 valid, 401 rejected, 403 denied, 429 not a verdict,
network or timeout unknown. Needs a method field on the request plan and a probe response format. The model
list those endpoints return is the natural next step for `ai-model-discovery`, not part of #61.

### Phase 6 — Model residency

Decision to ground with Codex before planning: defer S1 warm-up until ASR delivers (serial loads add the
measured 1843 ms to every dictation) versus keep both resident on this phone by a written decision with the
measured memory (`:asr` 917 MB, `:polish` 393 MB, issue #37). The rule says one; the felt experience on
the founder's phone says both fit. Then the unload schedule (#37) and the development model surface (#21).

### Phase 7 — Polish quality parity

Each item is its own SMALL or MEDIUM plan against the macOS knowledge corpus
(`~/Developer/EnviousWispr/.claude/knowledge/llm-contract.md`, `polish-prompt-architecture.md`).

## 2. Founder decisions needed

None to start phases 1 through 5; the rules and the catalog decisions answer them. Phase 6 carries one
product trade (latency versus memory) that is put to Codex first and to the founder only if Codex reads the
rules as conflicting.

## 3. Not in this program

Telemetry, first-run model download, reinstall recovery (stage 2 per `CLAUDE.md`). Writing-style presets
(decided out, product-wide). Apple Intelligence and EG-1 (platform-inapplicable). Self-hosted as a fresh
selection (catalog decision 2026-09-01).

## 4. How progress is reported

One task label for the program in every message: `AI Polish Refinement`. Each phase reports its own
ten-step gates by name. A phase is done when its pull request is merged and its hardware oracle ran.
