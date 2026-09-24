# Issues #308 and #310: one live provider disclosure table, and setup tiles chosen by capability (2026-09-24)

GitHub issues: `#308` (REF-05) and `#310` (REF-07) of `docs/audits/2026-09-24-senior-audit.json`. Tier: SMALL in code (a dead table deleted, one list filter), with a rule consequence: the privacy guidance that names the dead table moves to the live one. Status: revised after the combined coverage and grounded round (`308-cov`), all three findings adopted; round 2 (`308-g2`), the store-listing row added.

## Preface: Lane + Hardware UAT declaration

**Lane:** Code

**PAR rows closed:** none.

**Hardware UAT:** Y, light. Emulator: the AI Polish tab's cloud rung still shows OpenAI, Gemini and Claude tiles (no self-hosted tile), and a provider's disclosure line still reads as before.

## Preface — User Rubric

User Rubric: persona, the founder setting up cloud polish on his S26. Nothing he sees changes: the same three provider tiles and the same sentence about where his text goes. What changes is that the sentence he sees is now the one the privacy rules and tests protect; today they protect a second table no screen shows.

---

## 0. TL;DR

- #308: `privacy/PrivacyDisclosure.kt` holds two things: the Privacy page's shared sentences (`POLICY_URL`, `ON_DEVICE_SUMMARY`, `TELEMETRY_SUMMARY`, `TELEMETRY_VENDORS`), which the page shows, and a per-provider table (`PolishProvider` (removed), `PrivacyDisclosure`, `PrivacyDisclosures.forProvider` (removed)) with no production caller. The disclosure a user actually reads is `providers/Provider.disclosure()`, shown by `PolishCloudRungs.kt:336`. Delete the dead table and its test; keep the page sentences; make `Provider.disclosure()` the one per-provider table (its `when (this)` is already exhaustive with no `else`), and bind it with a test against the production disclosure.
- #310: `PolishLadder.CloudProviders = Provider.entries - Provider.SELF_HOSTED_POLISH` picks setup tiles by identity. Add a named capability, `ProviderCapabilities.offeredAsSetupTile`, set per provider in the exhaustive `capabilities()` (true for OpenAI, Gemini and Claude; false for self-hosted, the catalog decision of 2026-09-01), and filter on it. Key-portal URLs stay in their exhaustive `when`.

Consolidation: one per-provider disclosure table (`Provider.disclosure()`); the privacy guidance points at it.

Prior context: #81 (the ladder), the 2026-09-01 catalog decision excluding self-hosted from fresh setup, #189 (provider adapters), CLAUDE.md § Privacy (three enforcers, never weakened).

## 1. Grounding (main ae4c49b)

- `git grep -n "PolishProvider\b\|forProvider(\|PrivacyDisclosure(" -- app/src`: the dead table's only users are `privacy/PrivacyDisclosure.kt` itself and `PrivacyDisclosureTest` (removed) (two rows). (`ModelNotes.forProvider` is an unrelated private function.)
- `Provider.disclosure()` (`providers/Provider.kt:35`) is used by `PolishCloudRungs.kt:336` and `ProviderConfigurationValidatorTest.kt:118`.
- `CloudProviders` (`ui/PolishLadder.kt:48`) is read by `PolishCloudRungs.kt:83`, `PolishLadder.kt:112,137` and `PolishScreen.kt:84`.
- Guidance naming the dead table (gitignored, primary checkout): `CLAUDE.md:49` (the first of three privacy enforcers), `.claude/rules/keystore-security.md` RULE: the-privacy-disclosure-is-part-of-the-security-surface, `.claude/rules/testing-philosophy.md` RULE: a-public-product-promise-needs-a-binding-test (`PolishProvider` (removed)), `.claude/rules/workflow-process.md` RULE: author-enumerates-reviewer-adjudicates (`PolishProvider` (removed) as an example population), `.claude/knowledge/polish-engines.md` FACT: cloud-polish-providers, `.claude/knowledge/play-store-readiness.md` RULE: the-listing-and-the-data-safety-form-must-match-the-code. `telemetry.md:101` names the page constants, which stay. `session-log.md` entries are dated records and stay.

## 2. Design

1. Delete `PolishProvider` (removed), `PrivacyDisclosure` (the data class) and `PrivacyDisclosures.forProvider` (removed); `PrivacyDisclosures` keeps the four page constants. The file name stays (the page sentences are what CLAUDE.md's "telemetry sentences the Privacy page shows" already points at).
2. Delete `PrivacyDisclosureTest` (removed). New `ProviderDisclosureTest`: for EVERY `Provider` entry, text egress is asserted directly, `capabilities().sendsTextOffDevice` is true alongside `disclosure().networkRequired` (finding 2; `networkRequired` alone says a connection is needed, not that text leaves), the key-storage flag equals `capabilities().requiresApiKey`, and each destination sentence `PolishCloudRungs` shows is pinned as a literal (so a reworded privacy sentence is a deliberate test change). The offline promise stays bound (finding 1): `Provider` has no offline member and none is invented; a `PrivacyPageSentencesTest` row pins `ON_DEVICE_SUMMARY` literally (audio never leaves; words stay unless the user connects their own provider), the page sentence that replaces the dead table's OFFLINE row.
3. `ProviderCapabilities` gains `offeredAsSetupTile: Boolean`, set in the exhaustive `capabilities()`; `CloudProviders = Provider.entries.filter { it.capabilities().offeredAsSetupTile }`. A test pins the list to OpenAI, Gemini and Claude in that order.
4. In this PR, the current Play documents (finding 3) cite `Provider.disclosure()` for cloud text and `PrivacyDisclosure.kt` for the Privacy page and telemetry copy: `docs/play-data-safety-answers.md:5,57`, `docs/play-listing/store-listing.md:4,84`, `docs/play-listing/privacy-policy-android-addendum.md:28,32`. Also update `store-listing.md:87` to cite `Provider.capabilities().offeredAsSetupTile` and `CloudProviders` for the three fresh setup choices (round 2); the row's self-hosted explanation stays. Dated audit records stay.
5. After the merge, in the primary checkout: CLAUDE.md's first enforcer becomes `providers/Provider.kt` (`disclosure()`, the exhaustive `when` over providers) together with `privacy/PrivacyDisclosure.kt` (the Privacy page's sentences); the five guidance files above point at `Provider.disclosure()` and `Provider` instead of `PrivacyDisclosures.forProvider` (removed) and `PolishProvider` (removed), each keeping its rule. The offline "nothing leaves" promise is `ON_DEVICE_SUMMARY`, unchanged.

## 3. Tests

1. `ProviderDisclosureTest` (above). MUTATION m1: one provider's summary reworded (RED on the literal). MUTATION m2: `networkRequired = false` (RED). MUTATION m6: one provider's `sendsTextOffDevice` false (RED). `PrivacyPageSentencesTest`: MUTATION m7: `ON_DEVICE_SUMMARY` loses "Audio never leaves it" (RED).
2. The setup-tile row. MUTATION m3: self-hosted's `offeredAsSetupTile` set true (RED). MUTATION m4: the filter reverts to all entries (RED).
3. The compiler keeps both `when (this)` exhaustive; no `else` is added (a source row asserts neither function has `else ->`). MUTATION m5: an `else` branch added to `disclosure()` (RED).

## 4. Blast radius

No user-visible change. Guidance: CLAUDE.md, five rule or knowledge files and three Play documents change their pointers; no rule is removed or weakened. Rollback: revert the squash commit and the guidance edits.

## 5. Ship criteria

- [ ] Rows green, m1 to m7 RED; the suite green; app and androidTest build; checks clean.
- [ ] Emulator: the cloud rung shows the three tiles and a disclosure line.
- [ ] Codex code review ALL-CLEAR with a confirming round, including the guidance text.
