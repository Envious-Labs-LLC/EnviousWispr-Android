# Store screenshots, v2 (2026-10-19)

Eight 1080x1920 Play phone screenshots: the real app in a phone frame, a headline, one line of
explanation, and callout tags in a column beside the screen, never over it. Founder approved
2026-10-19. Not yet on the listing until the Console change is saved and Google approves it;
[store-listing.md](../../store-listing.md) records what is submitted.

- Content, targets and copy: `shots.json`. Layout: `frame.html`. Render: `node docs/play-listing/screenshots/v2/render.mjs`.
- Screens 03 to 08 are captures of the debug build on the `EnviousWispr_Android_16_Play` emulator with
  sample History and Dictionary rows written to that emulator's database (no founder data), status bar in
  demo mode.
- Screens 01 and 02 are the app's own setup-demo screens (`docs/mockups/android-onboarding-demo-v1`
  scene 3 at t=3.9 s and t=1.7 s, the screens `OnboardingDemo.kt` draws). Android excludes the
  accessibility overlay (bubble and pill) from screencap, so a capture cannot show them.
- Claim sources: the History Copy action (`ui/HistoryScreen.kt`), Tone, Structure and Context
  (`ui/PolishLadder.kt` `S1ControlCopy`), the cleanup switches and English spelling
  (`ui/TranscriptionScreen.kt`), the privacy sentences (the app's Privacy page).
