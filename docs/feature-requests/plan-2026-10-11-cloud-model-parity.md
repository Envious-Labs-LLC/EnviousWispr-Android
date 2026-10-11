# Android cloud model and speed parity, 2026-10-11

Tier: MEDIUM. Status: IMPLEMENTED. Local review, unit tests, APK build and bounded provider checks passed; phone acceptance is pending. Scope authorized by the founder's request to port the recent Mac model and speed improvements. Founder approved up to $1 for real-provider validation on 2026-10-11. Direct-provider calls are required to verify the BYOK endpoints, not Azure/Bedrock substitutes. Bound output and disable retries in the validation harness; ordinary unit tests spend nothing.

**Lane:** Code
mixed_pr: true
Additional lane: Docs/dev-tooling for this plan.
**Hardware UAT:** Y. Select each provider, dictate two sentences into a third-party editor, and check both arrive once. The S26 is disconnected at session start; this remains unverified until it can run.
**PAR rows closed:** none. This improves PAR-061, PAR-063 and PAR-066 without claiming all their acceptance criteria.

## User Rubric
Priya Ramachandran is answering a colleague in Slack and wants her chosen cloud model to finish promptly. She selects Cloud in AI Polish and then dictates through the normal floating button. Her other target apps are GitHub and Notion. Natural inputs: "send the deck to finance today", "the release is ready for review", "please use the staging endpoint", "I will check that tomorrow", "the build finished successfully". Success is complete clean text arriving promptly; wrong-but-working is a request consuming needless thinking or silently using another model. A power user would change models to escape a slow default. Keep the existing provider/model/off/local controls. Marcus wants minimal edits; Diana and Aaron want uninterrupted delivery; Elena can keep local polish; Meera and Frank need no new controls. No persona needs a new thinking toggle.

## 0. TLDR and 1. Problem
Mac PR #3429 added per-model minimum thinking settings for Gemini 3.8, GPT-6 and modern Claude. Android discovers their ids already, but its polish bodies do not request those settings. Its discovery retries send minimal effort to all OpenAI ids and budget zero to all Gemini ids, including models that reject those values.

## 2. Goals and boundaries
Port Mac's verified request decisions into one provider-domain authority used by both polish and probes. Preserve selected ids, user keys, endpoints, output caps, privacy, cancellation and fallback. Do not change recommendations, invent speed/quality ratings, substitute models, alter prompt quality or redesign the transport. Paid live checks require separate spend approval.

## 2.5 Grounding
Catalog cloud-polish records direct user-to-provider text under BYOK. Its 2026-10-03/04 decisions request the lowest accepted thinking and forbid substitution. Mac authority is Sources/EnviousWisprLLM/LLMModelCapabilities.swift at commit 8ccff73f7, read from the current Mac checkout. Its Gemini table covers exact ids; OpenAI none is allowed for nine ids and valid trailing ISO snapshots, with low for other reasoning models; bounded GPT-6 matching excludes gpt-60. Claude Sonnet 5.5 uses between_tools, Opus 5.5 and Fable 5/5.1 use output_config effort low, others disable thinking. Modern Claude omits temperature.

Android source read: ProviderPolishClient.polish -> ProviderAdapters.of -> polishPlan -> ProviderTransport.run -> ProviderReplyFormat -> the existing polish outcome. Discovery uses the same adapter's probePlan through ProviderModelDiscoveryClient.probeOnce and keeps one deadline over both attempts. ProviderAdapter is the protocol authority; no provider branch belongs in the clients. The live list filters model modalities, not generations. ModelNotes decorates ids and is not the availability authority. Existing issue #103 and its complete comments record snapshot aliases and inconclusive token-capped probes; #106 already fixed the recommendation and key issues. The session log's earlier cloud setup/refactor receipts were inspected.

Official API docs describe model-specific reasoning effort and Gemini level versus budget controls. Mac's live-probed model table supplies exact newer-id settings. OpenAI Android uses Responses rather than Chat Completions, so reasoning must be a nested effort object, and Responses-only models stay eligible. No new temperature field is needed because Android already omits it.

## 3. Design and ownership
Add ProviderModelCapabilities beside the adapters, a pure stateless request-settings authority. It returns JSON field fragments with no user content. Each adapter adds those fragments to polish and both probe styles. Known settings apply on the first probe; the second probe only raises the existing output cap. Unlisted Gemini and non-reasoning OpenAI ids omit controls; unlisted OpenAI reasoning ids use the documented fallback policy; unlisted Claude ids use thinking disabled. Future compatibility remains unverified. For OpenAI, lowercase only the lookup id and resolve valid trailing ASCII ISO snapshots using the separate OpenAI date rule. Apply exact overrides first: the nine Mac none entries; gpt-5-pro high; gpt-5.2-pro, gpt-5.4-pro and gpt-5.5-pro medium. Otherwise, ids containing -pro or codex omit effort; remaining Mac reasoning families use low; other ids omit effort. Preserve the original request id. Unlisted Gemini ids omit controls and unlisted Claude ids use disabled. Future compatibility remains unverified. Claude's exact table is ported, with valid dated aliases resolved using the existing snapshot parsing behavior moved from ModelNotes into a provider-domain ModelId. Update ModelNotes and its callers/tests to use that same authority, with no compatibility shim. Gemini remains exact-id, as Mac does.

## 4. Contract deltas
Requests carry Mac's chosen thinking settings, with exceptions required by Android's Responses endpoint; these are not necessarily every model's lowest supported value. ProbeStyle.DEFAULT now means the first capped ask using production's settings; NO_REASONING means a larger-cap retry with those same settings. Rename that enum member to MORE_OUTPUT to make the contract explicit everywhere. Null remains the unsupported-probe answer for Claude and self-hosted. No key or transcript is stored in capabilities.

## 5. State and lifecycle
Enumerated four providers: OPENAI, GEMINI, CLAUDE use model settings; SELF_HOSTED_POLISH remains unchanged. Settings are pure values applied to each frozen request. Interrupted: existing cancellation wins; Deleted: removed key still follows existing validation; Mutated: next session reads changed settings; Concurrent: no new mutable state; Absent: unlisted Gemini omits controls, unlisted Claude uses disabled, unlisted OpenAI -pro/codex variants omit effort; other unlisted Mac reasoning-family ids use low; Stale: new discovery uses settings instead of older cached access until refreshed. Provider outages and lost polish processes retain the existing last-good-text fallback. No new service, timer, retry loop or process owner.

## 6. Consumers
Polish adapters and discovery adapters consume the same settings. ModelNotes and recommendation presentation use the moved snapshot parser with identical behavior. Tests calling the old parser move to its provider-domain owner. UI, history, telemetry, model lists and selected-model storage receive the same result contracts.

## 7. Failure modes
Provider refusal, quota, invalid key, rate limit, deadline and cancellation remain classified by existing clients and adapters. Failed polish returns to the existing deterministic text; a failed discovery probe never changes the chosen model. Unknown models are still offered when listed and filtered as text-capable, without claims of guaranteed future compatibility.

## 8. Signals
ProviderPolishResult, ProbeOutcome, ModelAccess and selected model id keep their meanings. No success stamp on a failed request, no log of prompt/key/body, no new network route. The first probe may now succeed without spending a retry.

## 9. Fallback
Existing deterministic last-successful text remains authoritative on every polish failure. No fallback model. Discovery's inconclusive first answer survives a failed second ask. Caps and deadline still bound both probes.

## 10. Files
ProviderModelCapabilities, ModelId, three cloud adapters, ProviderAdapter's probe-style name, discovery call site, ModelNotes snapshot consumers, and existing provider/model-note tests.

## 11. Validation
Product Outcome tests assert literal nested fields on actual adapter-generated polish/probe bodies across the Mac table and provider filters. Real HTTP fixture rows check newer models remain available and both request paths use the same setting. Normalize lookup case, preserve the original request id. Keep UI snapshot years 2000 through 2099 unchanged; OpenAI effort accepts only valid ASCII ISO dates with years at least 2000, matching Mac. Snapshot tests cover compact and mixed formats, calendar validity, version-like numbers, uppercase ids and year 2100 independently. Assert all four Pro overrides and valid ISO snapshots on actual polish, DEFAULT and MORE_OUTPUT bodies. Assert omitted effort for unlisted -pro/codex variants, including dated variants. These Android exceptions are tested separately from Mac drift comparison. Literal wire expectations are pinned to the Mac revision; they guard this port, not automatic detection of future Mac edits. Removing capabilities must turn new wire tests red. Full uncached unit run, build, review and confirming review follow. Real-provider and phone results remain separate; no performance percentage or universal quality claim from unit tests. Phone settings are unchanged until a reachable device and review-clean build exist.

## 12. Blast radius and rollback
Only provider request fields, probe-style naming and shared snapshot parsing are touched. Revert the change commit to restore prior requests; no stored-data migration.

## 13. Ship criteria and 14. Open questions
Actual polish and supported discovery requests apply the same Mac-selected policy and documented Android exceptions, including deliberate omission for unlisted -pro/codex variants; newer provider-listed text models remain eligible. Real cloud checks need spend approval, phone UAT needs a reachable S26. These receipts cannot be replaced by build success.

## 15. Related
Mac #3425/#3429; Android #103/#106/#189; PAR-061/PAR-063/PAR-066.

## Validation checkpoint, 2026-10-11

- Uncached full suite: 211 suites, 1702 tests, zero failures/errors/skips. The new OpenAI wire test failed when its model settings were removed; restoration returned the suite to green.
- Android debug APK assembled successfully. Independent code review and confirming review approved the implementation.
- Ten direct-provider polish calls through compiled Android client/transport/parser code succeeded: four GPT-6 models, Gemini 3.8 Flash and Claude Haiku 5.5, Sonnet 5.5, Opus 5.5, Fable 5 and Fable 5.1. Validation alone imposed a 512-token output cap, no retries; production caps are unchanged. This proves bounded host-side API compatibility, not on-phone timing or broad cleanup quality.
- Real GPT-5 mini/nano discovery probes both returned 200: the first 16-token ask was inconclusive and the 64-token ask returned text under low effort. No older-model request-policy regression was observed on the fixed Hi probe.
- Conservative total cost reservation: $0.5748282 for these 14 requests, under the founder-approved $1. This is an upper estimate, not an invoice.
- Another Android chat owns the S26 and Play candidate 270 for its final v1 pass. No phone action or publication by this chat until explicit handoff.
