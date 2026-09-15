# Setup demo: five animated scenes that teach the floating lips

Design brief for Codex, 2026-09-14. Founder direction from his phone pass of build 122: when setup
reaches "try it", play a short demo first: EnviousWispr works in any text box (show BREADTH, not three
apps); you'll notice this icon floating on your screen (the lips, blinking); tap or hold it; tapping is
toggle mode and the check inputs your recording; holding is push to talk, release to see your words;
"Feel free to try yourself!" Then the real practice box with the real lips.

Make it the way the EnviousWispr feature GIFs were made (the founder made those with you and likes
them): one HTML page per scene with a `window.frame(t)` function that lays out the picture for time `t`,
rendered frame by frame by Playwright, packed into one clip per scene. Each scene is a separate piece so
one can be swapped later without touching the others.

Write ONLY inside `docs/mockups/android-onboarding-demo-v1/`. Do not touch app code.

## Read these, and nothing else (write UNCHECKED if you want another file)

- `docs/mockups/android-bubble-v2/themes.html` and `docs/mockups/android-bubble-v2/README.md`: the
  exact CSS of the lips bubble, the tap pill (clock, rail, X, check) and the hold pill (rail alone),
  per look. Use the SMOKE look. These are the shapes the app really draws; copy them exactly.
- `docs/mockups/android-onboarding-v2/index.html`: the practice screen the demo leads into (its dark
  ground `#251F37`, light ground `#FFFFFF`, Plus Jakarta Sans from `bold.ttf` / `regular.ttf` in that
  folder, the phone frame at 3x).
- `/Users/m4pro_sv/Downloads/EnviousWispr Feature GIFs/Sources/enviouswispr-s1-mini-gif/render.cjs`:
  the render recipe you used before (page per scene, `window.frame(t)`, Playwright frames). Reuse the
  shape: `render.cjs` per scene writing `frames/NNNN.png` at 15 fps, 720x1280 portrait this time.
- `/Users/m4pro_sv/Downloads/EnviousWispr Feature GIFs/Sources/enviouswispr-globe-fn-gif/sprite-sheet.png`:
  the clay character style the brand uses, as a 3x3 sprite sheet from the image model, and its
  `animation.ffconcat` for how frames were sequenced.

## The five scenes (each 3 to 4 seconds, 720x1280, dark AND light variants via a `?theme=light` query)

1. **Works in every app.** Show BREADTH. The founder: "we need to show 99+ apps ... just showing three
   risks people thinking we don't support more." A dense wall of app tiles (generic, coloured, varied
   glyphs, NO real logos) flows past, dozens visible at once, more arriving; a text box slides into
   the middle and the lips pop in beside it. Caption: "Works in every app with a text box." In the app
   this scene will be LIVE, built from the user's own installed app icons, so design it as a wall of
   ~64 dp rounded tiles that could be real icons.
2. **Meet the lips.** One text box with the keyboard below it, the lips docked at the right edge just
   above the keyboard, pulsing three times (scale and a soft glow). Caption: "This is your button. It
   floats beside any text box."
3. **Tap to dictate.** A hand taps the lips; the tap pill slides out from the edge (clock counting, rail
   bars dancing); the hand taps the check; the pill folds back to the lips; words type into the box.
   Caption: "Tap to start. Tap the check to finish."
4. **Hold to talk.** The hand presses and holds; the hold pill (rail alone) slides out, bars dance; the
   hand lifts; the pill folds back; words type into the box. Caption: "Or hold while you talk. Let go
   to finish."
5. **Your turn.** The demo phone shrinks toward the real practice box (a still of the v2 practice
   screen is fine as the target); caption: "Your turn. Tap or hold the lips."

The HAND: the founder likes the brand's clay characters. Build the scenes with a simple CSS-drawn
pointer FIRST so they run today, and write `sprite-prompt.md`: the exact prompt for the image model
(gpt-image-2.5 on Azure) to produce a 3x3 sprite sheet of a clay-style hand, transparent background,
poses: resting, approaching, tap down, tap up, press and hold (three frames of slight pressure), lift,
resting. Describe how `render.cjs` will swap the CSS pointer for the sprite frames once I generate them.

## What to produce

1. `scene-1-apps/`, `scene-2-lips/`, `scene-3-tap/`, `scene-4-hold/`, `scene-5-yours/`, each with
   `index.html` (self-contained, `window.frame(t)`, fonts referenced from
   `../../android-onboarding-v2/`) and `render.cjs`.
2. `preview.html`: plays the five scenes in sequence in one phone frame with a scene selector and a
   theme toggle, for the founder's review in a browser.
3. `sprite-prompt.md` as above.
4. `README.md`: the five captions verbatim; timing per scene; how to render (`node render.cjs`, then the
   ffmpeg line that packs frames into an animated WebP, 15 fps, and the same into an MP4); how to swap
   one scene; the exact CSS values reused from the bubble themes; what is simulated.

Stop after writing those files. Do not review, do not summarise, do not suggest further work.
