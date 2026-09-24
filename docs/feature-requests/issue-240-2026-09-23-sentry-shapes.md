# Issue #240 — Restrict Sentry text to declared diagnostic shapes — 2026-09-23

GitHub issue: `#240`. Tier: MEDIUM (the last privacy seam before crash reports leave the phone). Status: APPROVED, grounded round 4 PROCEED-AS-PLANNED (coverage round: six findings adopted; grounded round 1: five findings adopted, finding 5 with a reflection inventory; grounded round 2: three findings adopted; grounded round 3: two findings adopted).

## Preface — Lane + Hardware UAT declaration

**Lane:** Code

**PAR rows closed:** none.

**Hardware UAT:** N. The change is the pure rewrite of a Sentry event and breadcrumb at the final seam, driven on the JVM with the SDK's own types (`SentryPayloadTest`). A person sees nothing. The emulator smoke is one dictation by COMMIT, to show the app still runs with the new seam.

## Preface — User Rubric

User Rubric: what a person says never leaves the phone in a crash report, however short (`kotlin-patterns.md` RULE: no-content-in-diagnostics).

---

## 0. TL;DR

`SentryBootstrap.sanitize` rewrites every event and breadcrumb, but the rule it applies to text, `PayloadSanitizer.sanitizeFreeText`, keeps any string of 100 characters or fewer that matches no deny pattern. "Meet me at six" passes on the event message, tags, extras, breadcrumb messages, breadcrumb data and map contexts (REF-07). Replace acceptance-by-absence with a declared schema: a Sentry key-to-shape allowlist for tags, extras and breadcrumb data, closed token sets for event and breadcrumb messages, and dedicated validators for exception types and frame locations. Everything else is redacted.

## 1. Problem

Grounded by Codex (`240-g0`), re-read by Claude:

- `SentryBootstrap.sanitize(event)`: message through `sanitizeFreeText` (removed); exception value dropped; frames' `absPath`, `filename`, `package` through `sanitizeFreeText` (removed); tags, extras and map contexts through `sanitizeFreeText` (removed) / `sanitizeFreeMap` (removed); `request` and `serverName` cleared; `user` untouched; fingerprints untouched.
- `SentryBootstrap.sanitize(crumb)`: message and data through the same rule; category untouched.
- `PayloadSanitizer.sanitizeFreeText`: `[REDACTED]` only over 100 characters or on a deny pattern; paths become `[PATH]`.
- The PostHog side already has the right model (`allowedKeys`, `boundedStringKeys` (removed), `sanitizeValue`, `TOKEN` (removed)): a string passes only as a closed token or a shaped bounded string.
- Senders: `Telemetry.defect` (message = `AppDefect.semanticId`, tag `error.identity`, extras from the call's map), `Telemetry.breadcrumb` (literal category and message, a data map), `convertPendingDefects` (message = semantic id, tags, extras `take_id` and `detail`), the bootstrap's option tags, and SDK automatic crash and ANR events.

## 2. Goals & non-goals

### 2.1 Goals
1. One owner, `SentrySchema` in `telemetry/`, declares every Sentry key and its exact value shape (a key maps to one shape: a UUID, a number, a boolean, a closed token set, a token, a package, a label), for tags, extras and breadcrumb data, reusing `PayloadSanitizer`'s shapes. It declares the exact tags `app.process`, `app.build_type`, `analytics.distinct_id`, `dictation.take_id`, `error.identity` and `pending_defect.build`, and every defect and breadcrumb key in `240-g0` §B plus `history/delivery_unknown_recovered/count` (coverage finding 1).
2. Key AND value shape must match (coverage finding 2): a number under `take_id` fails like prose does. The schema returns one of Keep, Redact or Drop (grounded round 1, finding 2): an undeclared key is DROPPED; a declared key with a bad value keeps the key with `[REDACTED]`. Where the producer is finite the shape is the finite set of its values (reasons, results, outcomes, routes, sources, shapes, `detail`), not the generic token rule, so a single ordinary word ("hello") fails; a dynamic identifier (a take id, an install id, a package, a build number, an error type) names its trusted producer and its validator (grounded round 1, finding 3). The explicit `take_id` extra is validated as a UUID before `beforeSend` uses it as the event tag; otherwise the valid live take id, or no tag.
3. Event messages pass only from the closed set of every `AppDefect.semanticId`, built from the sealed hierarchy with an exhaustive `when (defect: AppDefect)` that uses `is` branches for the parameterised subclasses (`VadCallWedged`, `AsrDecodeFailed`, `HistoryContractViolation`), so a new defect is a compile error until it is declared; the hand-written `AppDefect.all()` is not the proof (grounded round 1, finding 1). Breadcrumbs pass only as declared category/message pairs, including the internal `breadcrumb("history", "delivery_unknown_recovered", …)` call in `Telemetry.kt`. Pending `detail` is the finite set `capture_release`, `start`, `processBlock`, `finish` (coverage finding 3).
4. Exception types pass a JVM class-name validator. Frames are validated per language (coverage finding 5): JVM class, method, constructor (`<init>`, `<clinit>`), `$`-nested and lambda names; native library paths (after the path rules) and native symbols; source file names. An unsupported symbol becomes `[REDACTED]` while the safe filename, library, line and address fields stay. The existing removals stay: request, server name, exception message, mechanism data, source context, local variables.
5. `event.user` is cleared. Fingerprints: an absent fingerprint stays absent; an approved one (a defect's) stays; an unapproved one is removed, never replaced with one shared `[REDACTED]` fingerprint (coverage finding 4). Map-valued contexts walk the same key-and-shape rule.
6. `sanitizeFreeText` (removed) is deleted (GR-MIGRATION-COMPLETE) once nothing calls it.

### 2.2 Non-goals
- No PostHog change. No change to which events are sent.

## 3. Design

- `SentrySchema.judge(surface, key, value): Verdict` (`Keep`, `Redact`, `Drop`); `message(text)`; `exceptionType(text)`; `frameField(field, text)`.
- The final-seam inventory (grounded round 1, finding 5; round 2, finding 2): a JVM test against the pinned `sentry-android` 8.57.0 walks the serializable instance fields of `SentryEvent`, `SentryException`, `Mechanism`, `SentryThread`, `SentryStackTrace`, `SentryStackFrame`, `Message`, `Breadcrumb`, `User`, `Request` and every typed context class discovered from `Contexts` accessors, through superclasses, arrays, collections, maps and nested SDK value types, and fails naming any text-bearing path that is not in the seam's declared handling table (validated, cleared, or documented content-free with its producer). An SDK upgrade that adds a text field turns it red. Thread names, exception module and mechanism description and type are in that table.
- Typed contexts (round 2, finding 1): only approved context objects stay (`Device`, `OperatingSystem`, `App`, `SentryRuntime`, `Gpu`), each with its approved fields validated; every other context object (`Feedback` (external), `Response` (external), `Spring` (external), `Browser` (external), `ArtContext` (external), `FeatureFlags` (external), `SpanContext` (external), `ProfileContext` (external), and any new one) is removed. They are no longer passed unchanged.
- The declared breadcrumb pairs are a set in `SentrySchema`; a JVM test reads every qualified and internal `breadcrumb(` call in `app/src/main/java`, including multiline calls, and fails naming any category/message pair not declared (the test-time failure for a new crumb). A second test reads every key literal passed to `Telemetry.defect`, `defectSink`, `breadcrumb` maps and the pending-defect `setTag`/`setExtra` calls, and fails naming any key not in the schema (coverage finding 1).
- Automatic events (coverage finding 4; grounded round 1, finding 4): JVM fixtures of a Java crash, an ANR and a native event are sanitizer tests, not grouping proof. Before claiming grouping is unchanged, inspect the pinned 8.57.0 bytecode (`javap -c` on the cached JAR and AAR classes; no source JAR is cached) of the ANR and native event builders for the message, fingerprint and tag fields they set. The seam changes only messages, tags, extras, fingerprints, contexts and frame text, so the claim is limited to what those builders put in these fields. Until that inspection is recorded in the PR, grouping for ANR and native events is labelled NOT VERIFIED (round 2, finding 3).

## 4. Contract deltas

Sentry payload only: free text that passed before is now `[REDACTED]`.

## 5-9. State, consumers, failure modes

| Delta | Consumer | Change |
|---|---|---|
| `sanitizeFreeText` (removed) removed | `SentryBootstrap`, tests | schema calls |
| Messages closed | Sentry grouping | declared `AppDefect` fingerprints remain unchanged; ANR and native grouping is NOT VERIFIED until the pinned builder inspection is recorded |
| Undeclared key | a future call site | dropped from Sentry and a red source test |

Risks: a needed diagnostic string redacted (the source tests name every key we send, so a gap is found in CI, not in production); a frame validator that is too narrow loses a native library location (the validator keeps the path-ruled library path).

## 10. Files

`telemetry/SentrySchema.kt` (new), `SentryBootstrap.kt`, `PayloadSanitizer.kt`; tests `SentryPayloadTest`, `PayloadSanitizerTest`, new `SentrySchemaTest`.

## 11. Testing

1. Product row: "Meet me at six" placed in the event message, exception value, a tag, an extra, a map context, the user, the request, a breadcrumb message, category and data, and each frame text field; none of it survives. Mutation: restore free-text acceptance for any one surface (one mutation per surface family: message, tags, extras, crumb message, crumb data, contexts, frames, user).
2. Shape rows: a defect event keeps its semantic id message, `error.identity`, take id tag, numeric and token extras; a declared breadcrumb keeps its message, category and token data; an exception keeps its type and code-location frames. Mutations: loosen the UUID shape, accept an undeclared key.
3. Source rows: every breadcrumb message and category, and every telemetry map key in `app/src/main/java`, is declared. Mutation: remove one declaration. Inventory row: every text-bearing SDK field is in the handling table. Mutation: remove one table entry.
7. Typed contexts on the final event: ordinary prose in an approved `Device` field is redacted, an approved device value is kept, and a `Feedback` (external) context is removed. Mutations: pass typed contexts unchanged; skip the field validation; keep an unapproved context.
6. A single ordinary word ("hello") under every finite-set key and as a message is redacted. Mutation: accept the generic token rule for one finite key.
4. Existing rows that expect free text to survive are rewritten to the new rule (coverage finding 6): `PayloadSanitizerTest` (short text, a bare URL and 100 arbitrary characters passing; prose around `[PATH]`; unknown nested keys retained) and `SentryPayloadTest` (a free-form breadcrumb message and `path` datum) now expect `[REDACTED]` for a message, a removed unknown key, and a rejected bare URL. Path-rule tests stay for approved frame fields and for PostHog.
5. Automatic-event rows: a Java crash, an ANR and a native event keep their exception type, frames, fingerprints (absent stays absent) and option tags.

## 12. Blast radius

What Sentry receives. Rollback: revert the squash commit.

## 13. Ship criteria
- [ ] Product row green, each mutation red.
- [ ] Shape and source rows green.
- [ ] Emulator dictation by COMMIT.

## 14. Open questions
None.

## 15. Related
#176 (the sanitizer), audit REF-07.

## 16. As built

- `telemetry/SentrySchema.kt` (new): the key-to-shape table (finite sets from the producers' own enums and constants; UUID, package, Throwable name, settings-fallback and build-number validators for the dynamic identifiers), the defect list with an exhaustive `when`, the breadcrumb pairs, and the code-location and context-label validators. `judge` returns Keep, Redact or Drop.
- `SentryBootstrap.sanitize`: every field in `HANDLED_FIELDS` is judged, validated or cleared; only `Device`, `OperatingSystem`, `App`, `SentryRuntime` and `Gpu` contexts stay (map contexts are removed too, stricter than §2.1.5's walk, since no code sets one); `Device.name` is cleared (it is the user-set device name). `PayloadSanitizer.sanitizeFreeText` and `sanitizeFreeMap` are deleted; `redactPatterns` stays as the pattern pass under shapes and for PostHog.
- ANR grouping, from the pinned `sentry-android-core` 8.57.0 bytecode (`javap -c`): `ApplicationExitInfoEventProcessor.AnrHintEnricher` (external) sets fingerprints from the fixed words `{{ default }}`, `system-frames-only-anr`, `background-anr`, `foreground-anr`; they are allowed, so SDK ANR grouping is unchanged. `AnrV2Integration` (external) can set an SDK-written message (a thread-dump parse failure sentence); it becomes `[REDACTED]`, which does not change grouping (grouping is by fingerprint and exception). The `sentry-android-ndk` classes set no message, fingerprint, tag or extra on events (only `NdkScopeObserver.setTag` (external) into the native scope, which reaches the event as tags that are judged). Native event grouping by exception and frames is unchanged.
- Tests: `SentrySchemaTest` (16 rows, four added by code review round 1: paths outside the code roots, native signatures, base Throwable names, an invalid live take id; the inventory now walks nested types from the event and the approved contexts), `SentryPayloadTest` breadcrumb row rewritten, `PayloadSanitizerTest` pattern rows retargeted to `redactPatterns`; receipts 28 of 28 RED. Code review round 2 found two validators that admitted prose; the class was then enumerated (THE LINE comment in `SentrySchema`: no validator admits whitespace-separated prose, and identifier-shaped strings pass only in fields code, the OS loader or a build property fills), every validator was checked against it, the plain C signature rule was removed, the C++ rule checks where its spaces are, a full path needs a code or library file name, and `noValidatorAdmitsProse` runs prose through every validator and key.
- Code review round 3 refuted the C++ signature rule twice (`std::x(long ago)` passed; `MyAllocator::operator new(unsigned long)` was lost): C++ type syntax cannot be told from prose by shape, so the pipe fix removes the space-allowing branch instead of patching it. A native function now passes only with no whitespace (mangled, a bare C name, a symbol and offset); demangled function text is `[REDACTED]`; the frame keeps its instruction address and library, and native server symbolication is NOT VERIFIED until a pinned native crash payload confirms the surviving address and module information (code review round 4). A device's model, maker and brand keep their spaces only when they equal this phone's own `Build.MODEL`, `MANUFACTURER` or `BRAND` (`SentrySchema.buildLabel`); any other text there is a label or `[REDACTED]`. Receipts 28 of 28 RED.
