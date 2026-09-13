# Google Play internal testing

Push reviewed code to the `internal-testing` branch to publish a tester update. Other branches do not publish. The GitHub workflow builds the pushed commit, runs release unit tests, signs the resulting bundle and publishes only to the existing internal testing track. It never targets production.

## Current setup status

Use the latest successful publishing run and its Play read-back as the current delivery receipt. Initial app registration, Play App Signing and tester enrollment already exist. A build-only success is not publication.

## Release procedure

1. Start from the latest internal-testing history, including other contributors' released features. Complete code review and relevant local/emulator validation. Record any physical-device tests that remain for internal testers; a test release is not public-release approval.
2. Update `internal-testing` to the reviewed commit and push it. Preserve other contributors' commits; do not force-push.
3. Open **Actions > Google Play internal testing**. A successful build alone does not publish: the publishing job must finish and its summary must name the confirmed Play version and source commit.
4. Download `published-play-bundle` for the signed bundle and publication receipt. The separate test-results artifact contains the actual test reports.
5. Confirm the new version is available on Play's internal testing track. Testers update through Google Play; allow for distribution delay. Phone installation and successful onboarding are separate checks.

## What the workflow uses

- Standard GitHub-hosted Linux runner, Java 21 and pinned Android build tools.
- The pinned llama.cpp submodule and upstream sherpa-onnx AAR with an exact SHA-256 check. No laptop-only file is required.
- Two jobs: an uncredentialed build followed by signing/publication. The artifact is tied to the current run and commit.
- Keyless Google authentication restricted to this repository and the internal testing branch. The existing upload key remains in Google Secret Manager. No service-account JSON key is stored in GitHub.
- GitHub environment `play-internal`, with variables `PLAY_WORKLOAD_IDENTITY_PROVIDER` and `PLAY_SERVICE_ACCOUNT`. The associated service account needs access to the one signing secret and Play Console rights for this app's testing releases.
- Version code equals GitHub's workflow run number plus 100. Local builds retain their normal version code. The workflow serializes releases and rejects codes that are not newer than Play's current tracks.

Use this workflow for tester releases across Claude Code and Codex. Coordinate any emergency manual Play upload first, and bring its source changes into `internal-testing` before the next automated release; a higher version number alone does not guarantee newer app features.

## Failures and retries

A failed build cannot publish. Read the failing job before starting another run. If upload/commit has an uncertain result, check Play first: the publisher reads back the track before declaring success and never blindly retries a commit.

A rerun after successful publication is rejected as a reused version. Push a new reviewed commit for a new release. Do not reset workflow numbering or reuse a version. If numbering ever resets, choose and review a new version offset greater than all previously uploaded codes before enabling publication again; update both build and publisher validation together.

Expired/missing federation or Play permission should be fixed through the account configuration. Do not add broad owner rights, print credentials or bypass checks. A newer code uploaded only as a draft may not appear on current tracks; Google will still reject reuse, so inspect bundle history when diagnosing such an error.

Local Claude Code operators should read `.claude/knowledge/play-console-operations.md` for the specific organization, app, tester link and setup receipts. That file is intentionally local; this public document is the reproducible workflow contract.
