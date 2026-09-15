# Launch readiness, 2026-09-15

Founder question: "Anything blocking us from launching on Android? I feel the app is viable." This is the
grounded answer after an overnight pass: a full stranger-phone setup driven on a clean emulator, the
back-to-back insertion case exercised, the Play blockers checked against the code, and two founder
decisions prepared. It supersedes nothing; it feeds `play-store-readiness.md`.

## The headline
The app IS viable. The core loop works end to end on a phone nobody has touched: install, download both
models, grant permissions, dictate, and the finished text lands in the target app. Nothing found is a
rewrite. What stands between here and a public Google Play release is a short list of Play paperwork, two
founder decisions, and one reliability question to measure on the real S26.

## What I proved works (clean emulator, fresh install, 2026-09-15)
A brand-new phone with nothing set up went all the way through, unassisted:
1. Welcome screen, then both models downloaded from scratch (speech 670 MB, polish 484 MB) and passed
   their integrity check. This is the stage-2 "stranger setup" path that had never been run before.
2. Permission setup: microphone, then the accessibility disclosure screen with Agree / Not now (I tested
   Not now too, it backs out cleanly), then the Android settings hand-off, then notifications.
3. Dictation into a real Gmail draft: the words landed in the field, verified, first try.
4. Five back-to-back dictations into the same Gmail field each inserted exactly once, in order, no false
   "could not verify" warning (issue #132). The only failures were an emulator microphone artifact, not
   the app; the physical-phone back-to-back run on the S26 is still the receipt that closes #132.

## The one thing to watch: does auto-paste stay connected
On the emulator, after granting accessibility the app sometimes showed "Auto-paste is not connected" even
though dictation worked. I traced it end to end with temporary logging: the app's connected/not-connected
display is CORRECT wiring, it faithfully reports whether the accessibility service is actually running,
not just switched on (this is the fix from issue #16). The "not connected" showed because the service
genuinely was not bound at those moments. So the real question is not the UI, it is whether Android keeps
the accessibility service reliably bound after a fresh grant. The emulator is a poor judge of this. This
needs one clean measurement on your S26. It is issue-#16-adjacent and already flagged as unmeasured.

## The Play submission checklist (paperwork, not code)
1. **Privacy policy + Data Safety form.** Answers are written and grounded in the code
   (`play-data-safety-answers.md`): audio never leaves the phone, transcript text goes to a cloud provider
   only under the user's own key, no telemetry, no server. The macOS privacy policy exists and needs an
   Android-specific adaptation.
2. **Accessibility declaration + demo video.** The disclosure screen is built and compliant. I added the
   explicit `isAccessibilityTool="false"` declaration Play reviewers look for (the #1 rejection risk) and
   a test that pins it. Still owed: the short review video showing disclosure, Agree, settings, and
   cross-app insertion.
3. **Foreground-service declarations** for microphone and data-sync in the Console.
4. **Store listing**: icon, screenshots, feature graphic, description. Not started (issue #10).
5. **Signed release AAB**: already built and published to internal testing by the pipeline. Still owed:
   testing the release-flavour polish path as itself, and a 16 KB check on the bundle.

## Two decisions that are yours
1. **Which phones does version one support?** (`device-support-decision.md`.) The shipped path is CPU-only,
   so it is NOT Snapdragon-locked. My recommendation: launch on 6 GB-RAM-and-up phones, Android Go
   excluded, any chipset, then relax to 4 GB after real testing. This protects the first store reviews from
   out-of-memory crashes.
2. **Closed beta before public, or straight to public?** My recommendation: internal testers you have now,
   then a closed beta on exactly the supported phones, fix what the Play pre-launch report finds, then a
   staged public rollout.

## What I changed this pass
- Added the `isAccessibilityTool="false"` declaration and a test that keeps it from drifting.
- Wrote the Data Safety answers, the device-support decision, and this brief.
- Posted the emulator back-to-back receipt on issue #132.
- Updated `play-store-readiness.md` to reflect that the disclosure screen and signed AAB already exist.

Nothing here is merged: on Android nothing ships to your phone or to Play without your phone pass first.
