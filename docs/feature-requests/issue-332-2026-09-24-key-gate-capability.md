# Issue #332: the key gates read the provider's capability (REF-08, regrade 6) (2026-09-24)

GitHub issue: `#332`. Tier: SMALL. Status: built 2026-09-24.

## Preface: Lane + Hardware UAT declaration

**Lane:** Code

**PAR rows closed:** none.

**Hardware UAT:** none. Pure functions whose answers do not change for any provider that exists today.

## Preface: User Rubric

Persona: the founder. No visible change today. A future provider that needs no key must not show a red "no key" badge or be refused activation just because its name is not in a list.

## 0. TL;DR

`architecture-rules.md` RULE: gate-on-capability-not-identity-literal. The sweep found two identity-based missing-key gates, both changed here. Remaining identity branches select provider-specific text, links, artwork, models, adapters, disclosures, or match state to its provider; they do not decide whether a key is required (code review round 1). The gates and the closest identity uses:

- Gate: `polishStatusChip`, where the red dot for a missing key named OpenAI, Gemini and Claude. It now reads `capabilities().requiresApiKey`. The label stays an exhaustive identity `when` (`labelFor`), because what the badge names IS identity.
- Gate: `PolishLadder.cloudTap`, which activated Cloud for `SELF_HOSTED_POLISH` by name. It now activates a provider whose capability needs no key.
- Legitimate: the self-hosted card in `PolishCloudRungs` (its endpoint and Remove button are about that provider), the Ollama protocol flag in `PolishContext`, and discovery state matched to the displayed tile.

## 1. Tests

The existing rows pin every answer: `PolishStatusChipTest` (the keyed red and green rows, the self-hosted green row) and `PolishLadderTest` (`cloudTap` for a key, self-hosted, no key, nothing). MUTATION m1: the chip drops `requiresApiKey &&`, so the self-hosted row, which has no stored key, goes red. MUTATION m2: `cloudTap` drops the capability line, so self-hosted with no key reads SETUP.

## Results (2026-09-24)

- MUTATION m1 RED (`selfHostedConfiguredStillShowsAGreenBadgeEvenThoughItIsHiddenFromTheScreen`); m2 RED (`cloudActivatesOnlyAConfiguredProviderWithAKey`, `theSelectedProvidersKeyIsReadOutOfTheSameSetTheTilesUse`); `332-mut.py`.
- Suite 1377, 0 failures (no new rows: the existing rows pin every answer); visibility and citation checks clean.
