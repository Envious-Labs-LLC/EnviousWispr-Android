# Issue #510: local polish timing evidence, 2026-11-05

GitHub issue: [#510](https://github.com/Envious-Labs-LLC/EnviousWispr-Android/issues/510). Tier: REFACTOR (conservative: extends a cross-process outcome parcel). Status: APPROVED for implementation by the founder's Nov5 follow-up specifying the laptop emulator and free online Android devices. This is the first measurement chunk of the speed work, not a speed fix.

## Preface: lane and acceptance

**Lane:** Code
mixed_pr: true
Additional lane: Docs/dev-tooling for this plan and owning knowledge update.
**PAR rows closed:** none. Protect PAR-066 and PAR-093.
**Hardware UAT:** Y, on a remote physical test device. Two successive real-model requests must return separate matching timing observations; a staged local timeout must preserve deterministic text and permit a subsequent request on a fresh process. Full dictation-to-editor and fallback propagation are checked separately on the laptop emulator, without calling its timings phone-speed evidence. The founder's personal phone is excluded by the current device-testing owner and his Nov5 test-environment direction.
**User Rubric: N/A:** this chunk changes internal, content-free performance observations only. It changes no result, setting, notice, model, routing or waiting budget. User benefit comes from the subsequent measured speed fix. No performance improvement is claimed for this chunk.

Gate 0: issue #510 read in full with labels and comments (none); #465 including its diagnosis comment; current polish/telemetry owners; session-log entries dated Nov4 and Oct24-25; Android catalog rows ai-polish and s1-mini-polish. Prior context posted in chat before this plan.
Gate 1: founder accepted speed first on Nov5. Gate 2: after the executive summary and reviewed-plan request, founder replied “Understood” and authorized the laptop emulator or free online Android devices. Proceed within those environments, with no new paid cloud testing.

## 0. TL;DR

Separate the time a local-polish request spends waiting for the service worker, executing the runtime, and waiting for an accepted answer on the main side. Attach the matching resident model's last successful load duration, age and existing major-fault observation, plus the selected runtime and the actual device tier. Carry snapshots through the existing outcome and take telemetry route. Preserve every timeout, first-answer rule, fallback, privacy rule and model-residency choice. Prove numeric observations cross the real process boundary and both privacy schemas before release.

## 1. Problem

Issue #510 reports local cleanup returning deterministic text after roughly 10 to 12 seconds on several phones. #465 observed one approximately 10-second load, but its later 120-take diagnosis did not reproduce that stall. PR491 already logs load duration and major faults locally.

Read-only field query on Nov5 found LOCAL_TIMEOUT takes with short raw speech transcripts as well as long ones. That rules out assuming this only concerns long dictation, but raw transcript length is not the exact cleaned prompt length. Known tests and quarantined installations were excluded; remaining installations are candidates, not verified people. Query/results are retained privately in artifact:enviouswispr-android/20261105-112104-android-polish-speed-5770/field-query.sql and field-result.json.

The problem-only consult confirmed the key distinction: existing polish_ms starts on the worker after queued loading. A queued load does not directly consume that metric or its hard timer. Native generation can still fault pages after loading, so the cause is NOT VERIFIED. Owner-created fallbacks currently pass zero latency rather than measured wait. Do not reinterpret historical polish_ms.

## 2. Goals and non-goals

### 2.1 Goals

- Identify worker queue wait versus runtime execution on accepted local outcomes, including cooperative and hard expiry.
- Distinguish the owner's actual request wait from the existing worker-latency metric.
- Report the resident model's last successful load as historical context, explicitly aged, never as an additive per-request stage.
- Preserve missing measurements as absent. Carry only numeric values and finite runtime/tier tokens.
- Use the same take/request identity and first-winner rules as text publication.

### 2.2 Non-goals

No deadline extension, earlier warm-up, residency change, native tuning, new model, automatic cloud switch, new notice, broad-device promise, crash-recovery implementation or Sentry morning-review change. Do not close #510 or #465 from this measurement chunk. No paid device farm or cloud inference.

## 2.5 Grounding brief

### Producer, owner and consumers

Discovery commands used: rg -n 'latencyMs|LOCAL_TIMEOUT|warmUp' on the explicit polish, telemetry and controller source roots; rg -n 'polishDone|publishPrepared|publishResult' on the coordinator; targeted numbered reads at each hop.

- The binder accept path queues work at PolishService.kt:194-204; work samples its start and arms its timer at :261-269.
- Generation is called at :693-698; completion is interpreted at :699-729. The hard/cooperative expiry converges at :287-290 and :316-339.
- The separate load timer starts at :591; load duration and major faults are read at :607-623.
- Existing resident identity/reuse is S1LoadedConfiguration.kt:25-50. A replacement clears current before loading; matching reuse copies its existing record.
- The outcome is positional Parcelable data in PolishOutcome.kt:11-58, including a deliberately retained six-argument constructor.
- TakePolishController.kt:321-359 rejects mismatched/stale outcomes before constructing PreparedText. Its watchdog is armed before the service request at :238-247.
- DictationSessionCoordinator.kt:1022-1052 sends accepted facts through TakeOutcomeRecorder.kt:107-110 into TakeFacts.kt:78-119.
- AnalyticsEvent.kt:103-133 projects the terminal properties. TakeJournalWriter.kt:81-84 commits the ending before capture; timing properties are not Room columns.
- PayloadSanitizer.kt:30-62 owns allowed keys; PostHogSchema.kt:81-143 owns finite string rules. SentrySchema.kt:126-167 independently shapes breadcrumbs/extras/tags.
- S1RuntimeKind.kt:19-26 is the selected runtime authority, not a guessed chip-to-runtime map. DeviceTier.kt:22-32 owns the actual process tier; STANDARD includes unknown reports and must not be relabelled recommended.

### Existing authorities and prior direction

Use the existing service worker, resident record, request registry, outcome, controller decision and take facts. No new manager, worker, polling loop, persistence queue or alternate event is proposed. Catalog rows establish the existing optional polish/fallback contract, not Android timing mechanics. Prompt/model output behavior is unchanged, so macOS prompt source is not needed for this Android-only observation change.

The problem-only consult is in artifact:enviouswispr-android/20261105-112104-android-polish-speed-5770/problem-consult.out.last. Its native mmap discussion was NOT VERIFIED and is not a premise or a proposed fix here.

### Boundaries and high-risk premises

The timeout thread cannot depend on a native call finishing, take a lock held during generation, do new file I/O, or wait for diagnostics. Only the outcome accepted for the open request may enter take facts. Model-load observations must follow the resident runtime through replacement, reuse and qualification invalidation. App and engine run the same installed app parcel classes; older separately installed test clients may retain constructor references, which must remain linkable. Before implementation, inspect every outcome construction, copy and parcel consumer; any independently packaged old decoder blocks a simple parcel append and requires an explicitly versioned transport design reviewed again.

## 3. Design

### Observations and exact meanings

Introduce an internal immutable LocalPolishDiagnostics value (proposed) carried as an optional outcome field. It contains only numeric measurements and a finite runtime token. Main-side wait is added to the accepted PreparedText for both its Polished and Fallback variants, separate from the engine's diagnostics.

New event keys, all proposed:

| Key | Meaning |
|---|---|
| polish_queue_ms | Binder acceptance to worker start for this request, from the same monotonic clock. Absent when the request never starts. |
| polish_generation_ms | Time within the one runtime generate call, including its internal setup/prompt processing. Not token-only decode time. Ends when the call returns or throws. At expiry, use the completed duration if available, otherwise elapsed time only while the call remains in flight. Absent if generate was never entered. |
| polish_last_load_ms | Duration of the last successful load for the matching currently resident native instance. Not part of polish_ms and not an additive request stage. |
| polish_last_load_age_ms | Time from that load's completion to this request's diagnostic snapshot. |
| polish_last_load_major_faults | Existing before/after load fault delta; absent if either read failed. It does not measure generation faults or prove paging caused a timeout. |
| polish_runtime | Selected S1RuntimeKind.identity from the engine process. Enumerated values only. |
| polish_wait_ms | Main controller's request-open time to the winning callback/fallback claim. Includes queue/binder wait, excludes later vocabulary restoration/History/insertion. Absent for fallback before a request opened. |
| device_tier | Existing DeviceTier.current.name. Finite values only; report it with local diagnostics. |

Do not change polish_ms, any old zero convention, or the meaning of an existing field.

### Sampling and ownership

- The binder samples acceptance time immediately before enqueue; the worker records queue duration once.
- The actual resident record owns an immutable successful-load observation. A matching reuse retains it; clear/replacement/qualification invalidation removes it. A failed or stalled load must not reuse a prior successful observation.
- Copy the matching load observation and runtime into request-local diagnostics before arming its hard deadline. Never inspect mutable resident state from the expiry thread.
- Per-request generation phase observation is atomic and immutable at every publication. Mark entry immediately before generate; record its ending in a finally block, including a throw. At expiry use completed duration when available, otherwise snapshot time minus entry time only while the call remains in flight. Expiry reads this snapshot without blocking. Completion after expiry cannot mutate an already-published diagnostic value.
- Freeze the snapshot for whichever existing deliverOnce branch wins, including timeout and unexpected failure. Diagnostic capture failure leaves diagnostics absent and never changes delivery.
- Controller request-open time is per take/request. The existing successful claim captures elapsed wait on callback, watchdog, request-call error and service loss. Cancellation that publishes nothing also emits nothing. Late outcomes cannot replace a winning snapshot.
- Carry the value through PolishOutcome, PreparedText, publishResult, TakeOutcomeRecorder and TakeFacts to the existing dictation terminal row. Add the shaped measurements to the existing polish_done breadcrumb before its defect. For local timeout, copy the same numeric/token facts into the existing defect extras. Set the shaped device.tier Sentry tag from the existing device-tier owner without changing the defect fingerprint.
- Retain existing six- and seven-argument JVM outcome construction signatures, leading parcel fields and old callers. Append a nullable diagnostic payload using an explicit presence marker; current writer and reader must agree and consume the entire object. Do not use Parcel.dataAvail to guess an embedded object's version. Do not claim interoperability with independent old parcel implementations without proof.
- No Room schema change: terminal timing properties are projected from take facts and captured after the existing journal commit.

### Alternatives

Changing a timeout now can increase waiting without addressing the cause. A standalone extra event adds a second take-outcome route. Uploading detailed logs would violate the content-free boundary. Polling or remote sampling is unnecessary because the existing request has all sampling points.

## 3b. Ownership justification

Engine measurements live with the engine/request that can observe them. Successful-load observations live on the matching resident record. Main-side wait lives on the existing one-request controller. Existing telemetry types project the frozen result. Putting clocks in the global coordinator or reading local logs would add unrelated state or cross the wrong boundary.

## 4. Contract deltas

PolishOutcome gains optional content-free diagnostics, never text ownership or decision authority. PreparedText gains optional local wait/diagnostics on both accepted engine and fallback routes. TakeFacts and AnalyticsEvent gain optional properties without changing ending semantics. Schema key sets admit exactly these keys and runtime/tier values. Constructors used by older test APKs remain callable; parcel read/write tests cover null and populated diagnostics. No AIDL method is removed, reordered or added.

## 5. State and lifecycle audit

| Population | Required disposition |
|---|---|
| Local request: queued, running cleanup, generating, finished | Queue measured only after worker entry; generation absent before entry; completed or elapsed-at-expiry after entry. |
| Worker outcome exits: cancelled, poisoned, normal, cooperative expiry, hard expiry, unexpected exception | Preserve text/reason and first winner; emit only observed local fields, never stale generation state. |
| Owner fallback exits: bind unavailable, disconnect, call failure, watchdog, malformed outcome | Wait absent before request-open; otherwise freeze at winning claim. Engine-only fields absent without a valid outcome. |
| Resident transitions: successful load, matching reuse, failed replacement, clear, qualification invalidation, process death | Reuse retains matching observation; every invalidation clears it; death leaves no invented durations. |
| Interrupted, deleted, mutated, concurrent, absent, stale | Existing publication gates decide whether anything leaves. Settings remain frozen; deleted/cancelled takes get no late mutation; atomic snapshots resolve expiry/completion; absent stays absent; stale request IDs remain rejected. |
| Cloud and Off paths | New local fields absent. Old metrics and output unchanged. |

## 6. Downstream consumers

| Consumer | Current behavior | Required behavior |
|---|---|---|
| Outcome parcel and installed test callers | Positional fields and retained constructor | Optional diagnostics round-trip; old constructor references link. |
| Controller/coordinator | Claim one outcome then publish | Pass a frozen snapshot only from that winner. |
| History and insertion | Use text/label/existing latency | No new dependency or decision on diagnostic values. |
| Journal/event capture | Commit ending before sending | Same single terminal route, optional added properties. |
| PostHog/Sentry schemas | Drop undeclared properties and invalid strings | Admit declared numbers and finite runtime/tier tokens; reject words/paths/keys. |
| Existing reports and older app builds | Read polish_ms | Keep its meaning; new fields absent on older builds. |

## 7. Failure modes

| Failure | Origin/caller | User sees | Diagnostics/retry |
|---|---|---|---|
| Model load slow or failed | Warm-up/service | Existing fallback/wait policy | Last successful matching load only; never fabricate failed-load duration. No new retry. |
| Cooperative/hard expiry | Runtime/deadline | Existing deterministic fallback once | Freeze elapsed generation if started; preserve process exit. |
| Process death/watchdog | Controller | Existing fallback once | Owner wait if request opened; engine metrics absent if never received. |
| Diagnostic getter/capture failure | Engine/controller | Existing text/outcome | Omit optional diagnostic data; no retry, network call or blocking wait. |
| Late answer/cancel/delete | Existing request/session gates | Existing behavior | No new publication or stale facts. |

## 8. Caller-visible signals

Null means not observed; a measured zero remains a valid duration. Runtime is selected engine identity, not a chip guess or a backend performance promise. Device tier is the existing classification, not a certification. Last-load age explicitly prevents interpreting historical load cost as current request wait. Elapsed generation on expiry is the observed span until the snapshot, not proof that all that time was token generation. Existing polish_ms and failure reasons retain their historical meaning.

## 9. Fallback audit

Every text fallback remains owned by the existing service/controller path. Diagnostics never select, replace, clean or persist words. Missing engine diagnostics yield absent properties; a winning owner fallback can still supply its own wait. No value is reconstructed after process death and no local detailed log is uploaded.

## 10. Changes by file group

- polish: new internal diagnostics value, PolishOutcome parcel/constructors, PolishService sampling, S1LoadedConfiguration resident load observation.
- ui: TakePolishController/PreparedText and DictationSessionCoordinator carry accepted snapshots, without new decisions.
- telemetry: TakeOutcomeRecorder, TakeFacts, AnalyticsEvent, PayloadSanitizer, PostHogSchema, SentrySchema and the existing Sentry tag assembly.
- Existing tests at those boundaries, plus a small clock-controlled diagnostic contract fixture if the existing fixtures cannot isolate the phase snapshot.
- Owning telemetry/polish knowledge documents in place after implementation.

## 11. Testing

Class: new numeric/identity cases are Observability Contract tests. Existing fallback/insertion checks remain Product Outcome tests. Parcel transport checks are Harness Contract. This chunk claims no speed or quality improvement.

Use literal clock values and callback/latch signals, not sleeps. Required mutations: remove each new hop or swap queue/generation clocks and observe the appropriate contract fail; remove a schema key/rule and observe admission fail; let a stale callback overwrite and observe winner assertion fail. Do these before claiming validation.

| Test boundary | Proves |
|---|---|
| Phase snapshots with literal clocks | Queue, generation not entered, finished, hard expiry racing completion; timeout remains nonblocking. Cover generation returning and throwing before hard expiry wins during subsequent request work. Its duration excludes that later work; late completion cannot mutate the published snapshot. |
| Resident record reuse and invalidation | No old load metrics after failed replacement/clear/qualification invalidation. |
| Existing controller rig | Polished/fallback wait reaches accepted output; late answers cannot replace it; pre-open fallback has no wait. |
| TakeFacts/recorder and both schemas | Exact keyed values survive; arbitrary prose, filenames, credentials and undeclared tokens do not. |
| PolishPolicyParcelTest existing outcome fixture | Null/populated diagnostics round-trip with full parcel consumption; retained constructor signatures. |
| Real PolishService binder rig | Numeric observations originate in :polish and reach main for a real local request. |

Hardware UAT: use a supported remote physical Android device for the real PolishService binder case, back-to-back observations and staged-timeout/recovery. Check available free headroom before submission and retain exact device/API/build identities. Separately use the laptop emulator for full pipeline/editor propagation and the fallback winner, via the existing harness. Follow the remote-device owner and restore emulator settings. This proves the named service/runtime and transport cases, not physical microphone capture, Samsung shortcuts or broad device speed. All device acceptance is NOT RUN until receipts exist. Do not connect to or test on the founder's personal phone.

Not tested in this chunk: performance gain, every phone, real-customer identities, native paging root cause, microphone/Bluetooth quality or a new long-dictation limit. No changed behavior supports those claims.

## 12. Blast radius and rollback

One app module, its cross-process polish outcome and terminal telemetry route. No native source, model assets, database schema, audio callback, decoder, provider secret handling, capture/insertion algorithm or UI copy changes. Revert the entire implementation commit/PR and ship through the existing signed Play route. Keep old event fields interpretable.

## 13. Ship criteria

- One accepted local take contains correctly attributable queue, runtime-call and owner-wait observations; historical load metrics are explicitly aged and match its resident instance.
- A hard timeout still returns the last successful text once without waiting for the native worker or diagnostic I/O.
- Device evidence shows independent back-to-back takes, no text duplication, and all optional diagnostic fields filtered through both vendor schemas.
- No claim of a speed gain or resolved #510 until a later measured behavior change establishes it.

## 14. Open questions

Independent old parcel decoder inventory must be verified before the transport edit; if found, revise and re-review that design. Remote physical-device acceptance depends on verified free headroom. No paid testing is authorized. The acceptance-environment update must receive a confirming plan review before runtime edits.

## 15. Related

Issues #510, #465, #433; PAR-066, PAR-093. The ordered longer-term work is speed, recovery, then live dictation. Only the first speed measurement chunk is specified here.
