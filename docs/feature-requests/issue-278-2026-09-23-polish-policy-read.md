# Issue #278: a failed polish-policy read is not Off (2026-09-23)

GitHub issue: `#278`. Tier: MEDIUM (polish path, a new failure reason, a new defect). Status: APPROVED after the coverage round (`278-cov`: four findings adopted; the last-read cache moved from the one-take owner to the process, see section 2).

## Preface: Lane + Hardware UAT declaration

**Lane:** Code

**PAR rows closed:** none.

**Hardware UAT:** Y. On the emulator: a normal Gmail take still polishes by the stored policy (an unchanged read). The failed read cannot be staged on a device without a test seam; its rows are JVM rows through `DictationSessionRig`.

## Preface — User Rubric

User Rubric: persona, the founder dictating on his S26. Today, if the phone cannot read his polish setting at the start of a take, the take silently runs with polish Off, so he gets unpolished text with no notice and nobody learns of it. After this change the take uses the setting it last read successfully, or, on the first take after a start, publishes his words cleaned up deterministically with the polish notice; either way one defect is raised so it gets fixed.

---

## 0. TL;DR

REF-02 of `docs/audits/2026-09-23c-senior-audit.json`. `ProviderConfigurationRepository.readPolicy` turns any failure to read the store into `PolishPolicy.Off`, the value a user picks deliberately. Founder decision 2026-09-23 (#238): polish is core, and a polish failure publishes the deterministic text with the polish notice and raises one defect. Make the read return a typed result, and give the one heart caller (the session owner) the failure path the decision names.

Consolidation: none. One producer gains a typed result; its four callers each handle the failed case.

Prior context: #234 (polish is a limb: bind refused, never connected or died publishes the deterministic text with the notice and one defect, `TakePolishController.recordLoss`); #193 (the settings readers are limbs with a fallback token, `takeFacts.settingsFallback`); #238 (polish is core).

## 1. The producer and its callers (grounded 2026-09-23 against main 4e1395c)

Producer: `ProviderConfigurationRepository.readPolicy(readSnapshot)` = `runCatching { decodePolicy(readSnapshot()) }.getOrDefault(PolishPolicy.Off)`; `loadPolicy()` wraps it over `preferences.all`. `decodePolicy` itself never throws on bad values (unknown mode reads as the shipped default `OFFLINE_S1`), so the failure is the store read.

Callers of `loadPolicy` in `app/src/main` (`git grep -n loadPolicy`), each classified:

| Caller | Today on a failed read | After |
|---|---|---|
| `DictationSessionCoordinator.beginSession` (via the `loadPolicy` constructor seam, wired in `DictationSessionService`) | the take polishes as Off, silently | section 2 |
| `EngineWarmUp` | warms nothing (Off) | skips the warm-up and logs; a warm-up is best effort |
| `PolishSettingsViewModel` (telemetry context token) | encodes `off` | encodes `AppLaunchFacts.UNKNOWN` |
| `AppLaunchFacts` | encodes `off` | encodes `UNKNOWN` |
| `DictationSessionService` (wiring only) | passes the bare policy | passes the `PolicyRead` |
| androidTest `ProviderConfigurationRepositoryTest` (assertions on `loadPolicy()`) | expects a bare policy | expects `PolicyRead.Fresh(...)` |

The polish process side: `PolishService` maps a null AIDL `policy` to `PolishPolicy.Off` (`effectivePolicy = policy ?: PolishPolicy.Off`). Our own client never sends null (`TakePolishController.prepare` passes the frozen non-null policy), so null is a protocol violation: answer the deterministic text with `PolishReason.UNEXPECTED` (a DEFECT-channel reason, `AppDefect.PolishUnexpected`) instead of running as Off.

## 2. Design

1. `ProviderConfigurationRepository`: `sealed interface PolicyRead { data class Fresh(val policy: PolishPolicy); data class Failed(val lastRead: PolishPolicy?) }`. `readPolicy(readSnapshot, lastRead)` returns `Fresh(decodePolicy(...))` or `Failed(lastRead)`; The last read lives in a small `PolicyReader` (`@Volatile lastRead`, `read(snapshot): PolicyRead`, written on every `Fresh`) with one process instance in the companion; `loadPolicy(): PolicyRead` calls it over `preferences.all`, and a test builds its own reader. The cache lives in the process, not in the session owner: `DictationSessionService` stops itself when the owner returns to IDLE (`host.stopSelfNow()`), so an owner, like its service, serves one take (coverage finding B). `DECLARED_DEFAULT_POLICY = decodePolicy(emptyMap())` (today `LocalS1` with the default control), named once.
2. `DictationSessionCoordinator`, at take start:
   - `Fresh` -> the take uses it, as today.
   - `Failed(lastRead)` with a last read -> the take uses it and polishes normally; `polish.policyReadFailed(usedLastRead = true)` raises one `AppDefect.PolishPolicyUnreadable` and nothing is shown. A last read of `Off` is the user's own choice: the take runs Off, no notice, and still the one defect.
   - `Failed(null)` -> the take freezes `DECLARED_DEFAULT_POLICY` (so the watchdog budget and the notice context are the shipped default's; freezing `Off` would suppress the notice, because `PolishFailure.from` returns null for an Off context) and `polish.policyReadFailed(usedLastRead = false)` latches the loss with the new `PolishReason.SETTINGS_UNREADABLE` and raises the same defect once; `prepare` then publishes the deterministic text, exactly as for a refused bind. `connected()` checks the latch before the warm-up, `prepare()` before any request or watchdog, and `recordLoss` keeps the first reason, so a later service loss cannot replace it.
   - `takeFacts.policyRead` records `fresh`, `last` or `failed` (content-free token, allowlisted in `PayloadSanitizer` and `DictationTerminal` like `settings_fallback`).
3. `PolishReason.SETTINGS_UNREADABLE` (session-owner side only). The compiler enumerates every exhaustive `when` over `PolishReason`; known today: `PolishFailure.from` (-> `UNEXPECTED`, so the notice reads "an unexpected error stopped it. Your original text was pasted unchanged."), `TelemetryChannels.of` (BREADCRUMB, like `SERVICE_UNAVAILABLE`: the controller raises the defect itself), `TelemetryChannels.defectOf` (null).
4. `AppDefect.PolishPolicyUnreadable` in `DefectIdentity.all()`, both `SentrySchema` defect lists, and `DefectListCompletenessTest`. Test tables that enumerate reasons: `PolishFailureTest` (the complete reason table), `TelemetryContractsTest` (channel expectations), `PolishReasonTest` (its `takeLast(6)` assertion).
5. The four other callers as in the table; `PolishService`'s null policy as in section 1.

## 3. Tests

1. `PolishPolicyTest` (existing, the JVM home of `readPolicy`): a throwing snapshot reads `Failed(lastRead)`, never `Fresh(Off)`; a stored Off reads `Fresh(Off)`. MUTATION m1: the failure branch back to `Fresh(PolishPolicy.Off)`.
2. `DictationSessionRig` rows (the rig already stubs `loadPolicy`; it records the policy each request sends to `FakePolish`):
   - failed read with no last read -> polish connects, the published text is the deterministic text, the terminal carries `SETTINGS_UNREADABLE`, the notice shows, one `PolishPolicyUnreadable` defect, no polish request sent. MUTATION m2: the `Failed(null)` branch freezes `PolishPolicy.Off` without latching the loss.
   - failed read carrying a last read A, chosen different from the declared default (a `Cloud` policy) -> the request is sent with exactly A, one defect, no notice. MUTATION m3: the owner ignores `lastRead` and takes the `Failed(null)` path.
3. The process cache: a `PolishPolicyTest` row reads `Fresh(A)` then a failing snapshot through one `PolicyReader`, and the second answer is `Failed(A)`. MUTATION m4: the `Fresh` branch does not write the cache.
   Wiring (grounded rounds 1 and 2): `loadPolicy()` is one line, `loadPolicyWith { preferences.all }`, and the companion's `loadPolicyWith(snapshot)` reads through the one process reader. A JVM row (no Context needed) resets that reader, calls `loadPolicyWith` with a readable snapshot (`Fresh(A)`), then with a throwing one, and asserts `Failed(A)`. A source-shape row pins `loadPolicy()` to `loadPolicyWith { preferences.all }`. MUTATION m5: `loadPolicyWith` builds a new reader per call, so the second call returns `Failed(null)`.
4. `PolishService` null policy (a JVM row is unproven: the service needs its process): a source-shape row asserting the null guard answers `fallbackText(raw, options)` with `PolishReason.UNEXPECTED` before request registration or pipeline work, and that `policy ?: PolishPolicy.Off` is absent. MUTATION m6: the guard restored to `policy ?: PolishPolicy.Off`.
5. Existing `DefectListCompletenessTest` and telemetry allowlist tests updated for the new defect and token.

## 4. Blast radius

The heart: none on a normal day (a readable store gives `Fresh` and every path is unchanged). On a failed read, the take now polishes with the last read policy or publishes the deterministic text with a notice, instead of silently skipping polish. Telemetry: one new defect and one new take token. Rollback: revert the squash commit.

## 5. Ship criteria

- [ ] Rows green, m1 to m6 RED; the suite green; the app builds.
- [ ] Emulator: a normal take polishes and lands.
- [ ] Codex code review ALL-CLEAR.

## 6. Related

#234, #193, #238; REF-02 of the third 2026-09-23 audit.
