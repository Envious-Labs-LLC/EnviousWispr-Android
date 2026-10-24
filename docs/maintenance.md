# Android release and maintenance

## Release status

Build 277 (version 0.1.0) is live worldwide on Google Play: Google approved it and Console reads "Last
published on October 18, 2026" (read 2026-10-19). Managed publishing is off, so an approved update
publishes on its own. Refresh Console before calling any later build live. Internal publication is not Google approval or installation proof. Application
ID: com.envi.wispr.

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
| More device qualification and speed work | Issues418 and433 |
| Model replacement and interruption recovery | Issues108 and43 |

## Safe update workflow

Use an isolated worktree, relevant validation, explicit review, PR/required checks, merge and main
synchronization. Application changes reach the founder through the existing signed Play pipeline;
never overwrite the Play app with a debug APK or wipe installed data. Verify source, publication,
installation and actual behavior separately. Documentation-only changes need documentation checks,
not a new tester release.

A tester publish never cancels a production review (#447): the publisher commits with
`changesInReviewBehavior=ERROR_IF_IN_REVIEW`, so while a review is in progress the run fails with
Google's reason; push again after the review clears. It also refuses to run while another track holds
a draft release or a release saved ready but not sent for review, either of which the commit could
send for review. Store-listing and declaration changes are not visible to those checks, so inspect
Console before a tester publish while such changes are saved but not submitted.

For later production updates, promote the tested signed bundle, inspect Play validation, confirm
the intended countries and release behavior, then submit the reviewed candidate. Preserve settings,
history, vocabulary, keys and models across upgrades. Qualify an intentional required-model replacement
before changing its manifest. The Parakeet Ultra swap (#458) accepted that an upgraded phone cannot
dictate until the new model lands over Wi-Fi (founder-approved, 2026-10-20); Issue108 owns removing
that gap before the next replacement.

## Historical material

September launch checklists and proposals are dated history, not current instructions. See
[the archived launch checklist](play-listing/archive/launch-checklist-2026-09-17.md) for that record.
Do not copy old privacy claims, CPU-only assumptions, US-only recommendations or old review videos
into the next submission. Current declaration and listing owners above supersede those drafts.
