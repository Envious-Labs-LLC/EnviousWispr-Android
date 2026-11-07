# Issue #521: Google Play install referrals, 2026-11-07

GitHub issue: https://github.com/Envious-Labs-LLC/EnviousWispr-Android/issues/521
Tier: LARGE because the optional collector is wired at app startup. Status: IMPLEMENTED, delivery pending. Founder explicitly requested research, plan and execution, then approved a one-time backfill and a proportionate investment in acquisition insights. The higher tier changes no product scope; required planning and code reviews were completed.

**Lane:** Code
mixed_pr: true
Additional lane: Docs/dev-tooling.
**PAR rows closed:** none. PAR-093's existing metadata privacy boundary remains binding.
**Hardware UAT:** N for speech hardware: no capture, speech, polish, insertion or model change. Run application/SDK integration on a disposable Play-enabled emulator. Genuine tagged Play installation remains a separately named acceptance outcome, not something a sideload can prove. Personal S26 is prohibited for this unrelated task.
**User Rubric:** N/A: founder-facing acquisition analytics, no dictation UI or behavior. In-app privacy text will disclose campaign metadata.

## 0. TL;DR
Use Google's official Install Referrer 2.2 library. From existing main-process analytics startup, launch a separate optional background lookup. Capture at most one successful referral snapshot per app installation, including a one-time backfill on existing installations. Parse only source, medium, campaign and content campaign labels. Never store/send the raw referral string, ad identifiers, search terms or arbitrary URL fields. Reuse PostHog, its existing installation identity, queue, schema and sanitizer. No worker, new backend, dashboard framework, polling or Discord work.

## 1. Problem and prior context
Founder wants to understand how people learn about Android EnviousWispr. Google Play is the only offered store. The source sweep for install-referrer/referrerclient/referrerdetails in app/src/main, Gradle files and current knowledge had no implementation hits. Existing AppLaunchFacts only reads current installer, which cannot answer campaign origin. Telemetry owner was read in full. Session-log matching install/referral search and latest checkpoint 3242-3321 show no earlier referral implementation. Catalog privacy-safe-telemetry row is shipped on Android. No new parity row is claimed.

## 2. Goals and non-goals
Goals: safe acquisition labels in PostHog for new and existing Play installations; successful lookup remembered; transient unavailable service retried next main-process run; ready-to-use correctly encoded Play links and a simple reporting query.
Non-goals: notifications, custom tracking service, third-party attribution vendor, advertising ID, historical identity matching, website changes, paid tests, broader release improvements.

## 2.5. Grounding brief
Producer: Play Store referrer service provides the original install referral and timestamps for up to 90 days, unchanged except reinstall. Official guide: https://developer.android.com/google/play/installreferrer/library . Dependency/API signatures verified from Google's actual 2.2 AAR using javap.
Existing trace: models/ModelBootstrapApplication.kt onCreate calls Telemetry.bootstrap before its process gate. telemetry/Telemetry.kt bootstrap starts PostHog only in main when identity and key work; startMainProcessWork launches existing IO work. Telemetry.capture queues AnalyticsEvent onto vendorCalls, PostHogBootstrap.processProperties stamps app/environment/version and passes TelemetryVolumePolicy then PayloadSanitizer and PostHogSchema. InstallIdentity can be minted by a helper before main; its freshness cannot define backfill eligibility.
Evidence commands: rg for Telemetry.bootstrap/onCreate and install/referrer/utm terms; read all named owners. Problem-only Codex consult completed at artifact:enviouswispr-android/20261107-021917-play-install-referrer-56e2/problem-review.txt.last; confirms main-only ownership, helper-first identity trap, raw metadata filtering, and best-effort telemetry queue.
Current authority: Telemetry owns vendor enablement and process scope; InstallSource owns Play installer classification; Android SharedPreferences holds one main-only completion flag, no referral payload. Existing launchScope (SupervisorJob + IO) outlives activities. This is a new narrow analytics collector, not a new coordinator.
Boundaries: only main and enabled analytics; only installer PLAY. Never await referral in application setup, launch facts, journal recovery or dictation. Source/medium/campaign/content are publisher-supplied labels, not independently verified traffic identity. No raw referrer data in logs, files, exceptions or events.
Risk premises proved: official library and API available; source gap and mutable installer contract verified; no older-install exclusion, per explicit founder backfill approval. Analytics capture is best-effort, without a vendor acknowledgement, as documented in telemetry owner. Do not claim exactly-once vendor delivery.

## 3. Design and ownership
Add a small Play adapter in telemetry, plus a pure referral parser and a small reporter whose injected operations are reading/writing one completion flag, reading the provider and capturing the typed event. Production uses existing launchScope, a SharedPreferences completion boolean and the official client. No second job scheduler or app-wide singleton.
The adapter suspends for the asynchronous setup callback with a 5-second connection deadline, reads details on IO and always closes the client. Finite outcomes: received, unsupported, retry-later. Service disconnect, setup error, read exception or timeout retries only on a later process start; unsupported is remembered. No continuous reconnect or background polling. Cancellation propagates and closes resources.
On received data, parse query pairs with Java URI/URLDecoder; do not infer domains or traffic from device/IP. Admit only utm_source, utm_medium, utm_campaign, utm_content. Preserve publisher spelling. Values must be 1-64 ASCII label characters (letters/digits/dot/underscore/hyphen), beginning alphanumeric, and survive existing sensitive-pattern rejection. Malformed encoding, duplicate selected keys or unsafe selected values yields invalid with no labels, never first/last-wins guessing. Empty/no selected labels yields unattributed, not organic. Bound raw input to 4096 characters before parsing. Ignore unknown keys (including utm_term, gclid and arbitrary names), and never persist them.
New typed event install_referrer.received: referral_status (tagged/unattributed/invalid), optional referral_source, referral_medium, referral_campaign, referral_content; optional positive server-provided referral_click_s/referral_install_s. Prefix fields to avoid shared-project ambiguity. Use no client-clock fallback. All pass the existing allowlist/schema/pattern filter. Stamps/installation ID retain current meaning. This event is a referral snapshot, not an install/first-open count. Backfill does not emit app.launched or change is_fresh_install.
After requesting capture, synchronously commit the handled flag on IO. Process death can lose a queue entry or replay after capture-before-commit, consistent with existing best-effort metadata delivery. All analysis counts distinct installation IDs, never raw referral event counts. No new durable outbox solely for marketing analytics.

## 3b. Placement
This lives in telemetry/main because it enriches existing metadata analytics. Putting it in onboarding would miss service-only starts and upgrades; a separate WorkManager protocol would add machinery for a noncritical once-per-install fact. Failure is invisible to the customer and cannot delay getting words.

## 4. Contract deltas
AnalyticsEvent adds one declared name with typed safe properties. PayloadSanitizer/PostHogSchema admit only the named fields/shapes; no generic URL or free-text allowance. TelemetryVolumePolicy advances its policy version to 2 to identify the expanded metadata contract. Reporter completion means lookup handled and capture requested, not proof of vendor delivery. PrivacyDisclosures describes limited install campaign labels. Existing events, installer classification, person identity and first-launch semantics stay intact.

## 5. State/lifecycle audit
Async population: official setup callback and synchronous referrer read; capture enqueue and completion commit.
Interrupted: coroutine cancellation closes client; kill before handled retries next main start. Deleted: uninstall clears flag and installation ID; reinstall is another installation. Mutated: updates preserve flag, no successful re-query; Play can update while connecting, retry on later launch. Concurrent: only main bootstrap creates this collector once per process; helper processes cannot enter; callback completion accepts at most one answer. Absent: empty key/identity skips collection, non-Play skips, unsupported remembers; missing safe tags reported unattributed. Stale: late/duplicate callbacks cannot publish twice; received snapshots refer to installation timestamps, not the time the feature was added. Existing installs backfill once, without generating a new-install event.

## 6. Consumer matrix
| Delta | Consumer | Required behavior | Verification |
|---|---|---|---|
| New event/properties | Existing PostHog pipeline | Keep approved tags only and current stamps | Existing schema/contract fixtures extended |
| Snapshot timestamp/backfill | Founder acquisition analysis | Group by source/campaign, distinct installation IDs; apply existing full-history emulator/TestLab/review/internal exclusions; snapshots are not new users | Reporting instructions + local tests |
| New dependency | Build/notices/merged manifest | Official pinned 2.2, generated licence entry, no ad ID permission | Gradle, notices generator, merged manifest |
| Privacy text | App privacy screen and submitted-answer record | State campaign metadata; existing processor/analytics purpose | Existing privacy fixture + docs audit |
| Future alerts | Separate subsequent work | Join by installation; no notification in this change | Scope statement |

## 7. Failure-mode table
| Failure | Customer effect | Persisted state | Retry |
|---|---|---|---|
| Play connection/service/read fails or times out | None; ordinary app continues | Unhandled | Next process start |
| Unsupported service | None | Handled | No |
| Invalid/unattributed payload | None; analytics says invalid/unattributed | Handled after capture request | No |
| Storage commit fails | None | Unhandled | Next launch; possible duplicate snapshot |
| Analytics unavailable | None | Unchanged | Later enabled main start |
| Process dies across capture/commit | Existing best-effort analytics limitation | Depends on commit | Deduplicate analysis by installation |

## 8. Signals audit
referral_status distinguishes tagged, unattributed and rejected input. Absent label never means direct/organic. Positive server timestamps are seconds since epoch; absence means unavailable, never zero. Handled flag is not a customer acquisition signal. Anonymous distinct_id is installation identity. Existing is_fresh_install is never read or changed for eligibility.

## 9. Fallback authority
No invented attribution fallback. The only attribution authority is Play's returned string, parsed into bounded approved labels. Unsupported/failed lookup leaves attribution unknown. Existing app/installer/device stamps remain independent facts. No use of current browser referrer or IP to fabricate source.

## 10. Files
New telemetry parser, reporter and official client adapter; wire Telemetry; extend AnalyticsEvent, PayloadSanitizer and PostHogSchema; pin official dependency; regenerate root THIRD-PARTY-NOTICES.txt; update privacy summary, existing telemetry/privacy contract fixtures and focused pure reporter/parser tests; add docs/install-referrals.md with link examples and reporting instructions; update owning telemetry knowledge after implementation.

## 11. Validation proportionality and edge-case justification
Customer-visible harm to avoid: setup/dictation blocked by optional acquisition work, or personal input leaking in raw referrer metadata. Business silent harm: wrong campaign or replayed snapshots mistaken for new customers. Reproducible supported inputs: tagged/untagged Play install, existing-install update, transient Play update/disconnect, reinstall. Hypothetical unsafe or duplicate callback/referrer inputs justify only small cases because otherwise attribution or privacy fails silently. Owning rules: testing-philosophy dont-test-what-cannot-happen and do-not-multiply-independent-axes; code-design parse-structured-input and async-edge-case-enumeration. Existing mechanisms: schema/pattern sanitizer and coroutine cancellation/deadline, plus existing telemetry test fixtures. No new test framework.
Parser rows: literal encoded tags, untagged, duplicate key, malformed percent, unsafe value, ignored secret/ad/search keys, length boundary. Existing schema fixture verifies real typed producer survives, raw/referrer URL cannot leave. Small fake-backed reporter/adapter tests exercise success skip, retry/unsupported, cancellation/cleanup and duplicate callback without sleeping or multiplying input axes. Class: observability contract; privacy cases product outcome, as a leak reaches a vendor. Removing parser rejection/event production must make focused tests red; mutation-check in a private source copy after checkpoint.
Run full unit suite and measure actual XML; required checks/visibility/notices; reviewed Debug build; disposable-emulator startup and actual SDK connection/cleanup behavior. A sideloaded emulator cannot prove tagged Play install conversion. Do not build a fake Play Store or add production testing bypasses. Report genuine Play-install gap plainly and use a normal tagged-link install on a separate enrolled test device when available.

## 12. Blast radius and rollback
Only app telemetry, dependency, privacy description, tests and documentation. No recording, editor, provider, model or schema migration. Revert the feature commit to stop collecting; existing private handled boolean is inert afterward. No new cloud service or spend.

## 13. Ship criteria
Safe labels preserved through actual existing analytics sanitizer; malformed/raw data excluded. Successful lookup/backfill is remembered without repeated Play calls. Failure leaves app responsive. Source/campaign instructions are usable without custom infrastructure. Real Play result and release status are reported separately from local tests and GitHub merge.

## 14. Open questions / limits
Genuine tagged fresh-Play-install receipt needs Play-distributed candidate and separate test installation. New release must preserve production312's current review; no cancellation/restart for this feature. Current public policy explicitly covers referring URLs and analytics, with app summary updated to spell out campaign labels. No change to advertising purpose or advertising ID; verify no new collected category beyond existing metadata/analytics before publication.

## 15. Reviews
Problem-only consultation complete. Coverage review completed: adopted existing full-history test exclusion requirement for the reporting query. Reviewer agreed the official SDK, small completion flag and existing analytics paths suffice; no outbox/scheduler/framework. Grounded plan review returned PROCEED-AS-PLANNED with no blocking findings. Founder intent and execution authorization already provided in this conversation; no new product/spend decision is open. Reports use the saved private internal-ID exclusions; the public exploratory query explicitly disclaims customer counts until those private exclusions are applied.
