# Android configuration speed screening, issue #433

No tested configuration qualified for integration. Production remains unchanged. These results do not establish that the app is faster or that further improvements are exhausted.

| Candidate | Typical native change | 97.5% interval for speed reduction | Exact output equality | Decision |
| --- | --- | --- | --- | --- |
| Parakeet A1, disable intra-operation spinning | 6.63% slower; median 35.87ms slower | -7.67% to -5.76% | Yes, including controls and repeats | Reject |
| S1 B1, batch/ubatch 256 instead of 512 | 0.13% faster; median 0.65ms faster | -0.22% to +0.42% | Yes, including controls and repeats | Reject |
| S1 B2, batch/ubatch 128 instead of 512 | 0.20% slower; median 0.87ms slower | -0.53% to +0.16% | Yes, including controls and repeats | Reject |

Positive reduction means faster. Typical change is the median paired fractional reduction of each input's three-repeat median. Absolute change uses the median paired difference and is a separate estimator. All candidates failed the preregistered 20% median reduction and 10% lower confidence-bound requirement. Tail checks cannot rescue a candidate that fails the median target.

## Measurement and provenance

Physical Samsung Galaxy S26 Ultra, Android16, arm64, charging state matched within each pair. Production baseline was Play270. The separately installed debug test package copied the unchanged baseline engine sources and changed only the named candidate configuration. ONNX Runtime1.30.0 and GenieX0.4.0; standard S1 Q4_K_M weights. The generated APK has no Internet, recording or audio-settings permission. PCM inputs were decoded directly from files, without speaker or microphone use. Production installation identity was checked on every run.

Each candidate had its own adjacent baseline, with counterbalanced order, eight cases maximum per native load, exactly one excluded warmup/load and three complete seeded repetitions. S1 candidates did not share baseline observations. Every launch required normal thermal status0; native per-call and ending status had to remain at most1. Pair temperature span had to remain within2C, with the same charging state. Failed environments rejected the whole pair, with at most two reruns; rejected records remain retained outside analysis.

Parakeet completed42 accepted pairs,112cases per arm per repeat,672accepted native calls; two attempted pairs were environmentally rejected. Timing used108noncontrol inputs in21clusters. S1 completed102accepted pairs,130cases per arm per repeat,1560accepted native calls; one attempted pair was environmentally rejected. Each S1 candidate timing comparison used112noncontrol inputs in111input-hash clusters. Those clusters do not establish independence of originating author or template families. Three repeats never substitute for independent input count.

Source/model/APK identities and aggregate decisions are in the [machine-readable receipt](2026-10-08-issue-433-config-speed.json). Private inputs, transcripts, PCM, source snapshots and rejected attempts remain in ignored local research storage. The [plan](../feature-requests/issue-433-2026-10-07-proven-speed.md) records the prospective switch from infeasible multi-arm groups to adjacent pairs before opening a complete timing summary. No earlier continuous or microblock observations were imported.

## Scope and remaining limits

These are development results for warmed resident native inference in the isolated debug bench. They do not measure production process startup, recording, cleanup, editor insertion, sustained workloads, exact clock throttling or battery efficiency. Identical output establishes configuration equivalence on this corpus; it does not prove the baseline words are correct.

S1 development inputs retain semantic category tags rather than the required short/medium/long acceptance strata. Therefore no duration-stratified acceptance claim is made. All candidates already fail the primary speed requirement; no new bins were chosen after outcomes. A future study must freeze those timing classes and independently referenced meaning/list/control guards before running acceptance.

The held-out corpora were not run. No candidate was frozen as a winner, no production code was edited, no release was published, and no end-to-end speed claim is made. Padding changes remain deferred because the required independently referenced multilingual pack is unavailable. Additional accelerators and model choices remain separate issues #430/#431/#418.

The maintained [research tools](../../scripts/eval/android-speed/README.md) reproduce identity checks, scheduling, strict result verification and analysis. Their unit checks protect the harness and are not product-outcome coverage. This completed campaign records rejected hypotheses, not a faster app.
