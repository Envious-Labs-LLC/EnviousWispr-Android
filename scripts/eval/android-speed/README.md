# Android native speed research (#433)

This isolated research app measures the shipped native engines on a physical Android phone. It does not measure capture, process handoff, cleanup, insertion, or stop-to-editor time. A passing native target is never proof that the production app is faster.

The decision targets, corpus requirements and release comparison are owned by [the frozen plan](../../../docs/feature-requests/issue-433-2026-10-07-proven-speed.md). Initial work is development screening. The model files, private text, audio, generated projects and raw results belong under ignored `docs/internal/433-speed/`.

## Safety and identity

The generated package is `com.envi.wispr.speedbench433`, separate from the production package. The builder snapshots original and generated sources, native logger and front-end asset hashes. Staging checks the actual merged APK for forbidden Internet, microphone and audio-settings permissions, and verifies installed APK and transferred file hashes. Every execution checks the production installation identity and test APK again.

The native bench consumes staged PCM files directly. It does not play or record audio. Full production audio tests must use the project's silent physical-phone helper. Never install a production APK from this bench; production updates go through Google Play.

`run_bench.py` uses Wispr Eyes preflight and restoration. It refuses a locked phone, an existing recorder window or a live production dictation session. Only a terminal signal carrying the exact run, arm and corpus identities permits analysis. Timeouts and failed native calls retain failure evidence and stop the isolated package. Failed or hot records remain excluded; never relabel them as successful runs.

## Candidate arms

| Arm | Difference from its baseline | Scope |
| --- | --- | --- |
| A0 | Original Parakeet CPU4, 15-second minimum input | Baseline |
| A1 | Disable per-session intra-operation spinning | Nonsemantic candidate |
| A3 | 12-second minimum input padding | Screening only |
| A4 | 8-second minimum input padding | Screening only |
| A5 | Real length plus one second, capped at 15 seconds | Screening only |
| B0 | Original S1 GPU, standard Q4_K_M weights, batch/ubatch 512 | Baseline |
| B1 | Batch/ubatch 256 | Nonsemantic candidate |
| B2 | Batch/ubatch 128 | Nonsemantic candidate |

A2 global thread pools are excluded because the pinned runtime's environment cannot be reconfigured after creation without changing model/process lifetime assumptions. Padding candidates cannot authorize a default change from English read-speech evidence alone.

## Development execution

Run from the task worktree. Build a fresh immutable project with `build_bench.py --root <new-private-bench-directory> --logger <verified-native-logger>`, then build the generated Gradle project. `run_bench.py stage --help` describes isolated APK/model/corpus staging. Model pins and the corpus selection must be verified before staging.

Use `paired_campaign.py` for the revised deliverable campaign. Earlier continuous and multi-arm micro campaigns retain their original rejected evidence and must not be restarted or imported. Each comparison pairs a candidate with its own adjacent baseline, counterbalances order, and covers the complete source population in three repetitions. Do not pool the S1 candidates' separate baseline observations. Eight-case pairs use one excluded warmup per native load for both arms. Parakeet's deliverable candidate is A1; input-padding experiments remain deferred because the language reference pack required for shipment is unavailable. S1 compares B0/B1 and B0/B2 separately.

```sh
python3 scripts/eval/android-speed/paired_campaign.py freeze \
  --root <existing-empty-private-directory> \
  --corpus <development-manifest.jsonl> --family asr
python3 -u scripts/eval/android-speed/paired_campaign.py execute \
  --serial <verified-physical-phone-serial> \
  --bench <staged-private-bench-directory> \
  --manifest <private-directory>/paired-campaign.json
```

The runner begins immediately when Android reports normal thermal status. It polls at five-second intervals only when waiting is necessary. The additional half-degree start matching from the earlier multi-arm design is not used. The entire adjacent pair still must satisfy the original two-degree Celsius temperature range, unchanged charging, launch status0, ending status at most1 and valid per-call thermal samples. A violation rejects the whole pair, with at most two reruns. Cooling retains the 900-second deadline. The complete seeded schedule is checked before device actions; an existing attempt index refuses automatic replay. Never delete an index to restart a failed campaign.

Only complete accepted repetitions reach aggregation. The reported Parakeet analysis uses speaker clusters; the S1 development analysis uses input-hash clusters, which do not establish originating-author or template-family independence. The analysis reports bootstrap uncertainty and tails from actual calls, including repeats. Raw output equality is compatibility evidence, not independent recognition or meaning accuracy. Independent-reference and critical-meaning scoring remain requirements for any future acceptance study. Development summaries keep campaign acceptance and app-speed proof false. These duty-cycled runs establish no sustained-performance or battery claim.

## Harness verification

```sh
python3 -m unittest discover -s scripts/eval/android-speed -p 'test_*.py' -v
```

These are Harness Contract checks for false acceptance, scheduling and transport. They are not product-outcome coverage. A native winner still needs held-out quality acceptance and independently observed literal text in a real editor across baseline, candidate and baseline Play releases.
