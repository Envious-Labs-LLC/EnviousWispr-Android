# Android release and maintenance

## Release status

On October 12, 2026, version 0.1.0, build 277 was submitted for public Google Play review worldwide
(177 countries/regions plus rest of world). It is not confirmed publicly available. Managed publishing
is off, so approved changes publish automatically. Verify Console and the public listing before
changing this status to live. Application ID: com.envi.wispr.

The reviewed application fixes duplicate spoken punctuation, landscape permission rows, accessibility
telemetry disclosure and Android 13/14 number-cleanup compatibility (PRs439,443,445). Recording sounds
are included (PR425). Main app-code snapshot fd406273, tested277 and internal278 have identical app
source. Internal publication is not Google approval or installation proof.

## Ongoing priorities

Protect dictation delivery, first-run setup, upgrades, user data and privacy on supported phones.
Investigate actual customer crashes, failed downloads and lost text before optional parity or tuning.
Use release/build-specific evidence; do not treat emulator output as broad-device or acoustic proof.
Keep the accepted device sample and limitations explicit.

| Work | Owner |
|---|---|
| Signed tester updates and receipts | [Release procedure](releasing.md) |
| Nominal support and actual store filter | [Device support](device-support-decision.md) |
| Submitted listing and claims | [Listing](play-listing/store-listing.md) |
| Data Safety and declarations | [Data Safety](play-data-safety-answers.md), [declarations](play-listing/console-declarations.md) |
| Optional test repeatability and API lint | Issue440 |
| Completed-setup navigation | Issue441 |
| Publisher/public-review separation | Issue447 |
| More device qualification and speed work | Issues418 and433 |
| Model replacement and interruption recovery | Issues108 and43 |

## Safe update workflow

Use an isolated worktree, relevant validation, explicit review, PR/required checks, merge and main
synchronization. Application changes reach the founder through the existing signed Play pipeline;
never overwrite the Play app with a debug APK or wipe installed data. Verify source, publication,
installation and actual behavior separately. Documentation-only changes need documentation checks,
not a new tester release.

Before publishing a tester update while production changes exist, inspect Console: the current
publisher commit can submit other pending changes and cancel/resubmit an existing review. Issue447
owns correcting that boundary. Do not assume an internal-track update leaves the review untouched.

For later production updates, promote the tested signed bundle, inspect Play validation, confirm
the intended countries and release behavior, then submit the reviewed candidate. Preserve settings,
history, vocabulary, keys and models across upgrades. Qualify an intentional required-model replacement
before changing its manifest; v1 has one speech model, so the future replacement issue was not a
first-release blocker.

## Historical material

September launch checklists and proposals are dated history, not current instructions. See
[the archived launch checklist](play-listing/archive/launch-checklist-2026-09-17.md) for that record.
Do not copy old privacy claims, CPU-only assumptions, US-only recommendations or old review videos
into the next submission. Current declaration and listing owners above supersede those drafts.
