# Portable sampler experiment: local gate stopped

Related: #510. This is an incomplete experiment, with no speed result and no production change.

## Outcome

The preregistered local check stopped before timing. The baseline and greedy candidate returned identical complete text, tokens and ending for the list case, but both failed the required list-formatting oracle:

```text
Input: First bring the blue folder, second print three copies, third call Jordan.
Both outputs: First, bring the blue folder. Second, print three copies. Third, call Jordan.
```

The frozen case requested lists and required a bullet or numbered-list marker. Neither output contained one. The oracle and corpus were not changed after observing the result. This establishes neither a candidate regression nor a speed gain. No physical pilot or confirmation was submitted because the same-APK local gate failed.

## What ran

- Source checkpoint: 4cc7dd04a62d5c01d363a976f4af149f431e8a89.
- Local Android 16/API36 ARM64 emulator: Google/sdk_gphone64_arm64, portable CPU backend android_armv8.2_2.
- One selected instrumentation test, 16.659 seconds, one failure, zero timing rows. Retained 45 result rows: 37 lifecycle rows and eight quality rows.
- Native finite/tied/masked/invalid-logit, controlled suppression and history controls completed. The model reported no suppression metadata; that absence was recorded separately.
- Ordinary/debug lifecycle controls completed through reload. Names/numbers, negation and accented-name cases matched across arms and passed their independent checks. The list pair matched but failed formatting.
- Timer-turnover, remaining quality cases, warmups, performance trials and physical-device qualification did not run.

The emulator is functional evidence only. No emulator timing is used as phone performance evidence.

## Code and package checks

The confirming independent review cleared code only. Fresh JVM tests: 1879 tests, 232 suites, zero failures/errors/skips. Host result-analysis controls: 13 passed. Debug app/test APKs and the release native library built. The debug library had eight experiment exports; the release library had none and retained the seven original JNI exports. The experiment class was absent from the release library classes and generated release DEX files.

Full release APK assembly was blocked by the lint analysis crash recorded in #513. No detector was disabled. This is a separate build-tool blocker, not evidence of an app crash.

## Delivery

Only this report and the approved plan are delivered. The experimental runtime, test API and study driver were withdrawn from the final change. Their checkpoint, matching APKs, complete partial results and review receipts remain preserved in the artifact library, including a verified Git bundle. The normal sampler stays unchanged. #510 remains open. All release uploads remain held for the combined enhancement batch.

Evidence: artifact:enviouswispr-android/20261105-150355-mobile-recovery-preview-ea8c/ (study-emulator-results.json, study-emulator-pilot.txt, study-stop-receipt.json, study-code-review-r2.out, study-unit-tests-r2-final.txt, study-analysis-controls-r2.log, study-package-identities.json, study-release-dex-exclusion.json, sampler-study-checkpoint.bundle).
