# Onboarding v2: teach the floating lips button

Design brief for Codex, 2026-09-14. Founder direction: "update onboarding now that we have this
overlay." The overlay is the floating lips button (the bubble) that sits beside any focused text
field, shipped on the founder's phone 2026-09-12 and refined 2026-09-13/14 (three looks, a compact
hold pill, a slim mirrored tap pill). Today's onboarding was designed BEFORE the bubble existed and
never shows it, never names it, and practises dictation with an in-app "Start dictation" button the
user will never see again after setup.

Write ONLY inside `docs/mockups/android-onboarding-v2/`. Do not touch app code.

## Facts you may rely on (verified in the working tree, do NOT re-derive)

- The bubble is drawn by the accessibility service as an accessibility overlay
  (`app/src/main/java/com/envi/wispr/paste/RecordingAccessibilityOverlay.kt:71,83`,
  `TYPE_ACCESSIBILITY_OVERLAY`). No "Appear on top" / draw-over-apps permission exists or is needed.
  Wispr Flow needs Appear on top AND Accessibility because its bubble is an application overlay;
  ours needs Accessibility alone. Do not add an Appear on top screen.
- The permissions today are exactly three: Microphone (required), Accessibility (required, it is
  what shows the bubble and pastes the words), Notifications (optional).
  `app/src/main/java/com/envi/wispr/ui/OnboardingScreen.kt` lines 95-101 (cards) and 140-143 (buttons).
- The Accessibility card copy today: "To find your text field and paste your words." The in-app
  disclosure headline: "Let EnviousWispr paste for you"
  (`app/src/main/java/com/envi/wispr/ui/AccessibilityGuideActivity.kt` lines 90-101). Neither mentions
  the floating button. The Android service description already does: "detect the text field you are
  typing in, show its floating dictation button beside it, and paste transcribed text into that
  field after you start a dictation" (`app/src/main/res/values/strings.xml` line 4). The disclosure
  and card must say the same thing as the service description.
- The bubble NEVER appears inside EnviousWispr's own screens today: the service ignores focus events
  from its own package (`app/src/main/java/com/envi/wispr/paste/PasteAccessibilityService.kt`
  lines 604-606, `rememberEditableTarget`). So the practice box in onboarding cannot show the bubble
  without an app change. Treat that app change as in scope for the mechanism note (below), and mock
  the practice as if the bubble is there.
- Practice today: `OnboardingScreen.kt` lines 102-125 and 144-147 (a text box, Start dictation / Stop / Cancel
  text buttons, "Finish setup" enabled after one successful take, "Skip practice").
  `OnboardingViewModel.startPractice` sends a token and a ResultReceiver to the session service
  (`app/src/main/java/com/envi/wispr/ui/PracticeDelivery.kt`); the finished text is merged into the
  Compose text box by the view model, not inserted through accessibility.
- Bubble gestures on the phone today (`RecordingAccessibilityOverlay.kt` lines 418-445): TAP starts a
  take and the bubble becomes the tap pill (clock, short level rail, X, check; 232 dp; mirrored when
  docked left); tap the check or the bubble again to finish. HOLD (past the long-press timeout)
  starts a take and shows the hold pill (the level rail alone, 168 dp); letting go finishes. Drag
  docks the bubble to either edge; drop on "Drop to hide" hides it until the next field. While the
  words are being worked on, the lips roll their rainbow. The bubble appears ONLY when a text field
  in another app is focused, and it sits just above the keyboard at the docked edge.
- Three looks exist, picked in Settings > Appearance > Floating button: Bare, Clear, Smoke
  (default). Exact CSS per look: `docs/mockups/android-bubble-v2/README.md` and `themes.html`.
  Draw the bubble in the mock in the SMOKE look.
- The current interactive onboarding mock is `docs/mockups/android-onboarding-v1/index.html` with
  `accessibility.js` / `accessibility.css` and the bundled fonts `regular.ttf` / `bold.ttf`. Its
  welcome-story videos are NOT in this checkout; the v1 page references `artwork/azure/*.mp4` that
  do not exist here. That is expected.

## Wispr Flow reference (competitor captures, `reference/`, read them as images)

- `wispr-flow-02-recording-bubble.png` "How it works": introduces the bubble BEFORE asking the
  user to use it.
- `wispr-flow-04-…`, `05-…`, `06-…`: practice is "Tap the flow bubble" in a real focused field
  inside their app; the mic permission is asked at the tap; "Nice, that worked!" on success.
- `wispr-flow-07-push-to-talk-tutorial.png`: AFTER the first success, a second practice teaches
  press and hold. Progressive, one gesture at a time.
- `wispr-flow-09-overlay-permission.png`: their Appear on top ask, with a picture of the destination
  toggle. We do NOT need this permission; it is here only for the "show the destination" idea.
- `wispr-flow-12-accessibility-instructions.png`: outcome-first explanation, three pictured steps.
- `wispr-flow-19-final-screen.png`: sets the expectation that the bubble is not on screen until a
  text box is active. Our founder decision keeps "Finish setup opens the app directly, no extra
  completion screen", so fold this expectation into the practice screen's success state, not a
  new screen.
- `founder-phone-both-bubbles.png`: our bubble (left) beside Wispr Flow's (right) on the phone.

## What to change in the flow (founder decisions already made; keep everything else from v1)

Sequence stays: Welcome → Downloads → Permissions → Practice → Finish setup opens the app.

1. **Introduce the bubble once, before the user needs it.** Put a short "How it works" moment at
   the top of the Permissions screen or as the transition into it: the lips beside a text field,
   "Tap the lips to dictate. Hold them to talk. They appear beside any text box." Decide where it
   sits; keep the screen count unchanged if you can, and say why.
2. **Accessibility card and disclosure name the floating button.** Card copy and disclosure copy must
   match the service description sentence quoted above, in plain English, outcome first: this is
   what puts the lips beside your text box and pastes your words.
3. **Practice uses the real bubble, progressively.** The practice box is a focused text field with
   the keyboard up and the SMOKE bubble docked at the right edge just above the keyboard, exactly as
   it will look in Gmail. Step A: "Tap the lips and say…" → tap pill with a live rail and the clock →
   tap the check → the words land in the box → "Nice, that worked!" Step B: "Now hold the lips and
   talk; let go when you are done" → hold pill (rail alone) → release → words land → success. The
   Start dictation / Stop / Cancel text buttons are gone. "Finish setup" enables after step A
   (step B is encouraged, skippable). Show the no-speech and interrupted states.
4. **Set the expectation in the success state**: one line that the lips show up whenever a text box
   is active, in any app, and where they will sit.

Keep the persistent lips at the top of every screen as in v1. Keep the v1 Accessibility disclosure +
Samsung Settings guide interaction (`accessibility.js`); only its words change.

## What to produce

1. `index.html`, self-contained (inline CSS and JS, fonts referenced from `../android-onboarding-v1/`
   with a system fallback), phone frame at 3x like v1, dark and light, reduced-motion respected.
   Screens: Permissions (with the how-it-works moment), the Accessibility disclosure with the new
   words, Practice step A and B with every state (idle prompt, recording tap pill, processing lips
   rolling, success, no speech, interrupted), and the Skip path. Welcome and Downloads may be
   static placeholders that say "unchanged from v1". Provide the same external screen and state
   selector v1 has.
2. `README.md`: the flow in ten lines; every copy string as the user will read it, in one table
   (screen, element, exact text); the decisions you made where the brief left a choice and why;
   what is simulated.
3. `mechanism-adjudication.md`, at most 60 lines: how practice can use the REAL bubble inside our
   own app. Read only the files named above (`PasteAccessibilityService.kt` lines 370-400 and
   600-640, `RecordingAccessibilityOverlay.kt` lines 120-160 and 400-510, `OnboardingViewModel.kt`,
   `PracticeDelivery.kt`, `OnboardingScreen.kt`). Never run grep -r or rg without a file argument;
   if you want another file, write UNCHECKED and move on. Compare exactly two options:
   (a) lift the own-package exclusion for the onboarding practice field only, so the service pins
   our Compose text box like any other app's field and the normal insertion path pastes into it;
   (b) keep the practice ResultReceiver path and make the bubble's tap and hold, when the focused
   field is the practice box, send the practice token instead of minting a bubble request.
   For each: what changes, what it proves about the real path, the one risk. Then one
   recommendation.

Stop after writing `index.html`, `README.md` and `mechanism-adjudication.md`. Do not review, do not
summarise, do not suggest further work.
