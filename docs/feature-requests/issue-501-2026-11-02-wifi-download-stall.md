# Issue #501 — Wi-Fi-only model downloads stop after 1 s and crawl — 2026-11-02

## Preface — Lane + Hardware UAT declaration

**Lane:** Code

**PAR rows closed:** none.

**Hardware UAT:** Y. A first-run user on home Wi-Fi with the default "Download models: Wi-Fi only" setting taps
Get started, and both models download in one continuous run (no "Queued" flips, no `cancelled` terminal events at
about 1 s). Turning Wi-Fi off mid-download makes the card wait for Wi-Fi and no bytes move over mobile data;
turning Wi-Fi back on resumes it.

## Preface — User Rubric

Persona: the first-time Android user from the brand guide who installs from Play at home and expects setup to just
work (here: the founder's mother, Samsung phone, 1 Gbps Wi-Fi).

1. What does this persona notice? Setup's downloads finish in about a minute instead of crawling for most of an hour
   with a card that keeps saying "Queued".
2. What could make it worse for them? Nothing user-visible changes except speed: the Wi-Fi-only rule, its metered
   hotspot eligibility and the card states stay exactly as #108 shipped them.
3. Word of mouth: a stalled first-run download is the first thing a new user sees; it is the cheapest uninstall.

### Cross-persona check
Every persona on Wi-Fi-only gets the fix. Users on "Wi-Fi or mobile data" use `NetworkType.CONNECTED` and never hit
the bug; the library upgrade applies to them too, without behaviour change.

Consolidation: none. A dependency bump; no owner moves.

## 0. TL;DR
WorkManager 2.10.1 reports a `NetworkRequest` constraint as not met when a second worker registers an identical
request, and stops that worker about one second after it starts. Our two setup downloads (speech and polish) are
enqueued together with the identical Wi-Fi request, so they knock each other off, re-queue, and restart minutes
later. Google fixed exactly this in 2.10.2 and 2.10.3. Upgrade `androidx.work:work-runtime-ktx` from 2.10.1 to
2.10.5 (latest 2.10 patch, bug fixes only) and keep everything else as is.

## 1. Problem
See #501. Evidence: PostHog `model_delivery.terminal` cancels at `duration_s≈1.1` on every device class including
the emulator, in speech/polish pairs; the 2.10.1 bytecode's `NetworkRequestConstraintController` sends
`ConstraintsNotMet` after a 1000 ms wait for a callback; the reporter's download finished at once on the
`CONNECTED` path while still on Wi-Fi.

Upstream fixes (release notes, https://developer.android.com/jetpack/androidx/releases/work, read 2026-11-02):
- 2.10.2: "Fix a bug that would cause workers with network constraints to not execute promptly due to constraints not
  met even though the network and capabilities were available" (b/423403088); and persisted network requests with
  default capabilities re-adding removed capabilities (b/409716532).
- 2.10.3: "Fix an issue where workers with identical network constraints as a previous worker would report their
  constraints not being met" (b/427115602).
- 2.10.4 (RemoteCoroutineWorker unbind) and 2.10.5 ("Fix foreground service stopping when there are pending
  commands", b/432069314) are bug fixes; our download worker runs as a foreground worker.

## 2. Goals & non-goals
### 2.1 Goals
- Wi-Fi-only downloads run to completion without WorkManager stopping them.
- The #108 Wi-Fi promise, eligibility and UI are unchanged.
### 2.2 Non-goals
- Replacing the `NetworkRequest` constraint (the first draft of this plan; Codex pointed to the upstream fix, so it
  is dropped).
- Moving to WorkManager 2.11 or 2.12 (minor-version changes beyond this fix; separate decision).
- Hardening the worker against a non-Wi-Fi assigned network: with the request constraint retained, WorkManager
  assigns a network satisfying the Wi-Fi request.

## 2.5 Grounding brief
### 1. Producer → owner → consumer
`ModelDownloadPolicy.constraints` builds the Wi-Fi `NetworkRequest`; `ModelDeliveryWorker.request` attaches it;
WorkManager's in-process `WorkConstraintsTracker` (`NetworkRequestConstraintController`) and JobScheduler own
eligibility; the worker, `ModelCards.workUiState` and `reapplyNetwork` consume it. None change.
### 2. Existing authority
The pinned dependency line `app/build.gradle.kts` `implementation("androidx.work:work-runtime-ktx:2.10.1")`.
### 3. Prior attempts
#108 / PR #494 introduced the request constraint on 2026-10-25; the cancel storm in PostHog starts with builds
carrying it.
### 4. Boundaries
- Already-enqueued WorkSpecs keep their stored constraints; the fix is in the tracker, so they benefit without
  migration.
- 2.10.5 requires core 1.12.0, lifecycle 2.6.2, room 2.6.1, coroutines 1.7.3 (Gradle module metadata); our build is
  compileSdk 36, minSdk 33, Kotlin 2.0.21.
### 5. High-risk premises
- P1: the identical-request bug is what stops our worker. Chunk 1 reproduces on the emulator with 2.10.1 (both
  setup downloads, Wi-Fi only, look for ~1 s stops and WorkManager's `didn't receive neither
  onCapabilitiesChanged/onLost callback` debug line), then repeats on 2.10.5 and expects neither. Same emulator,
  equivalent starting download state, stable Wi-Fi; correlate each timeout and stop with its WorkSpec id. The
  decisive window is both workers transferring past the first seconds; a full completion validates the user outcome.
- P2: 2.10.5 resolves without forcing other library versions upward in a way that changes behaviour. Checked with
  `./gradlew :app:dependencies --configuration releaseRuntimeClasspath` diff before/after.

## 3. Design
1. `app/build.gradle.kts`: `work-runtime-ktx` 2.10.1 → 2.10.5.
2. Drift guard in `ModelDownloadPolicyTest`: the build file pins WorkManager at 2.10.3 or later, with the reason
   (b/427115602) in the assertion message, so a downgrade cannot silently bring the stall back. It parses numeric
   version components (never string order, which misorders 2.10.10) and rejects prereleases.
3. `.claude/knowledge/model-delivery.md`: one FACT recording the bug, the fixed version and the evidence.

## 4. Contract deltas
None in our code. Library behaviour delta: network-request constraints are reported met when the network is.

## 5. State and lifecycle audit
No state machine change. Expected observable delta: no `cancelled` terminal at ~1 s on Wi-Fi.

## 6. Downstream consumer matrix
| Consumer | Change |
|---|---|
| Other WorkManager users (`FeedbackDeliveryWorker`, adoption/repair work) | same library fixes; no code change |
| Room / lifecycle transitive versions | checked by P2 dependency diff |

## 7. Failure-mode × caller table
Unchanged from #108 except the 1 s stop is gone.

## 8. Caller-visible signals
`model_delivery.terminal` loses the ~1.1 s `cancelled` storm.

## 9. Fallback source-of-truth
Unchanged.

## 10. File-by-file changes
- `app/build.gradle.kts` (one line).
- `app/src/test/java/com/envi/wispr/models/ModelDownloadPolicyTest.kt` (version drift guard).
- `.claude/knowledge/model-delivery.md` (FACT).

## 11. Testing
Unit gate with count. Emulator before/after reproduction (P1). Dependency diff (P2). Physical: the founder's Play
build, setup-style download on Wi-Fi only.

## 12. Blast radius & rollback
Every WorkManager job in the app moves to a patch release. Rollback is reverting the line.

## 13. Ship criteria
Unit gate green with count; P1 before/after captured; P2 diff reviewed; Codex all-clear with confirming rerun; Play
internal delivery to the founder's phone; PostHog shows no ~1 s `cancelled` storm from the new build.

## 14. Open questions
None.

## 15. Related
#108, #494, b/423403088, b/427115602, b/409716532, b/432069314.
