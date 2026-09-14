# Android setup demo v1

## Approved 2026-09-14, and what the app draws

The founder reviewed one scene at a time and approved these five, in this order. The app does not play
these files: `app/src/main/java/com/envi/wispr/ui/OnboardingDemo.kt` draws the same five scenes live,
at the overlay's real sizes, in the user's theme, with the phone's own app icons, and hands over to the
real practice screen. `OnboardingDemoScript.kt` carries the clock and the captions. The table and notes
below the line describe the first cut and stay as the render recipe; where they differ from this
section, this section is what shipped.

| Scene | Caption | Seconds |
| --- | --- | --- |
| `scene-1-apps` | Works in every app with a text box. (138 real Play Store icons in `icons/`; the app uses the phone's own) | 4 |
| `scene-2-lips` | This is your recording bubble. / It lives on the left or the right side of your screen. / It comes in three looks. | 7 |
| `scene-3-tap` | Tap the bubble to start. Tap the check to finish. | 5 |
| `scene-4-hold` | Or hold the bubble and talk. Let go to finish. | 5 |
| `scene-5-yours` | Now it's your turn. Tap or hold the bubble. | 3 |

Founder decisions carried into the app:
- The button is "the bubble" (his phrase: "your recording bubble"), in every setup string.
- Scene 2 shows the bubble ALONE at real size (56 dp target, 12 dp from the edge), never inside a fake app; it slides to the left edge and back; then Smoke, Clear, Bare, the three looks in `BubbleLook.kt`.
- Scenes 3 and 4 play inside a drawn Gmail compose screen after his S26 screenshot (`reference/gmail-compose-s26-dark.jpg`): a real email draft to Priya Shah, the body empty with only the cursor, no "Compose email" hint; the whole formatted email lands AT ONCE after the pill folds, never letter by letter, because that is how the app inserts.
- Scene 5 is an end card only: the Gmail screen fades, the caption and a real-size bubble remain, and the real practice screen follows. No mock of the practice screen.
- No approvals in the demo; the demo only teaches that the bubble starts a recording.
- Progress dashes stay at the bottom left; the brand header (lips mark and wordmark, website spec) at the top.

Render output (`frames/`, `frames-light/`, `*.mp4`) is ignored by git; run `render.cjs` per scene to regenerate.

---

Five independent HTML scenes, 720 × 1280 portrait, 15 fps. Dark by default; append `?theme=light` for light. Open `preview.html` for the five-scene sequence, scene selector, theme toggle, pause, replay, and scrubbing. Playback stops on the practice-screen illustration. With reduced motion enabled, the preview starts paused.

Each page contains its own styles, SVGs, timeline, and `window.frame(t)`. Time is in seconds and clamps at the scene end. A scene opened directly loops; `?capture=1` disables its autonomous playback. Capture and preview drive the same function. Font files are referenced from `../../android-onboarding-v2/regular.ttf` and `bold.ttf`; the source's embedded semibold is retained for the exact clock typography. No CDN or runtime image request is needed.

## Captions and timings

| Scene | Caption, verbatim | Duration | Frames |
| --- | --- | --- | --- |
| `scene-1-apps` | Works in every app with a text box. | 4.0 s | 60 |
| `scene-2-lips` | This is your button. It floats beside any text box. | 3.6 s | 54 |
| `scene-3-tap` | Tap to start. Tap the check to finish. | 4.0 s | 60 |
| `scene-4-hold` | Or hold while you talk. Let go to finish. | 4.0 s | 60 |
| `scene-5-yours` | Your turn. Tap or hold the lips. | 3.6 s | 54 |

Total: 19.2 seconds per theme.

1. Apps: a 180-tile wall flows throughout. A field slides in at 0.85–1.50 s; lips appear at 1.55–1.95 s. Ten columns use 64-unit rounded tiles and 12-unit gaps, shown in a zoomed-out wall at 1.2 output pixels per unit. This deliberately shows dozens at once. The recorder remains at 3× device scale. Generic varied SVG glyphs and colors replace installed icons, with “99+ apps. One button.” communicating breadth.
2. Lips: the same field, keyboard, and right-edge lips. Three teaching pulses begin at 0.45, 1.40, and 2.35 s, each lasting 0.60 s. Scale peaks at 1.12 with a separate soft glow.
3. Tap: touch at 0.52 s, pill opens through 0.78 s. The hand lifts away while recording continues; outlined clock advances from 0:00 and rails move. The hand returns to the check at 2.62 s; fold finishes at 2.88 s. “Let’s take the long way home.” types from 2.95–3.62 s.
4. Hold: contact begins at 0.42 s, hold pill opens at 0.82–1.06 s. Finger stays down with three pressure poses. Release is at 2.55 s; fold ends at 2.82 s. “See you by the water.” types from 2.91–3.62 s. No clock, X, or check appears in this mode.
5. Your turn: “Feel free to try yourself!” appears initially. The demo phone shrinks toward the practice field at 0.45–1.95 s and fades away at 1.55–2.15 s. The v2-based practice illustration is revealed behind it, then its lips receive a gentle cue. The practice target uses the supplied palette, 390 × 844 frame geometry, text box, keyboard, and SMOKE lips. Its instructional copy is simplified for the final still.

## Render a scene

Requires Node, Playwright with Chromium installed, and ffmpeg. The renderer first resolves `playwright` normally and then falls back to the same local Playwright module path used by the supplied feature-GIF recipe. Dependency availability: **UNCHECKED**. No renderer or encoder was run as part of this write-only task.

Run from the chosen scene directory:

```sh
node render.cjs
node render.cjs --theme=light
```

Dark frames go to `frames/0000.png` onward; light frames go to `frames-light/0000.png` onward. The renderer recreates only the numbered PNG sequence for that theme, removing old numbered frames before rendering. It leaves the other theme and unrelated files alone. Viewport and output are both exactly 720 × 1280, device scale factor 1. It waits for fonts, calls `window.frame(i / 15)`, then captures each PNG.

Pack dark frames into animated WebP and MP4:

```sh
ffmpeg -y -framerate 15 -start_number 0 -i frames/%04d.png -an -c:v libwebp_anim -lossless 1 -loop 0 scene-dark.webp
ffmpeg -y -framerate 15 -start_number 0 -i frames/%04d.png -an -c:v libx264 -crf 18 -preset slow -pix_fmt yuv420p -movflags +faststart scene-dark.mp4
```

Pack light frames:

```sh
ffmpeg -y -framerate 15 -start_number 0 -i frames-light/%04d.png -an -c:v libwebp_anim -lossless 1 -loop 0 scene-light.webp
ffmpeg -y -framerate 15 -start_number 0 -i frames-light/%04d.png -an -c:v libx264 -crf 18 -preset slow -pix_fmt yuv420p -movflags +faststart scene-light.mp4
```

Each command emits one clip for that scene and theme. WebP loops; MP4 contains one pass. There is no audio. `sprite-prompt.md` contains the Azure image prompt and the optional `--sprite=../hand-sprite.png` renderer switch for scenes 3 and 4.

## Replace one scene

Edit only that folder's `index.html` and rerender its two themes with its `render.cjs`. Preserve `window.frame(t)`, `window.ready`, and the small `ew-frame` / `ew-theme` message interface if it remains in this preview. If duration changes, update `window.sceneDuration`, the renderer duration, and that scene's entry in `preview.html`. The other scenes need no edits or new captures. The HTML files deliberately duplicate the required recorder styles so each is independently replaceable.

## Exact reused SMOKE values

From `../android-bubble-v2/themes.html` and its README. Values are device units, one CSS px per dp, before the tutorial dock's 3× transform. Both page themes keep this same recorder palette.

| Element | Exact values |
| --- | --- |
| Hit target | 56 × 56; transparent; border 0; padding 0; grid centered |
| SMOKE backing | 48 × 48; radius 14; `rgba(19,16,25,.64)`; shadow `0 2px 5px rgba(19,16,25,.20)` |
| Lips | 38 × 38; original `viewBox="0 0 256 256"`; overflow visible; no filter; all 18 source rectangles copied verbatim |
| Both pills | Height 60; radius 30; border 0; same SMOKE fill and shadow; fully opaque children |
| Tap | Width 232; horizontal padding 10; gap 6; clock + rail + X + check |
| Hold | Width 168; centered rail only; horizontal inset 17 |
| Clock | 48 × 28 viewport; text x=0, y=19; Jakarta semibold 600, size 15; fill `#ece9f4`; stroke `#131019`, width 1.5; round joins; `paint-order:stroke fill` |
| Tap rail | 66 × 22; 11 bars |
| Hold rail | 134 × 28; 22 bars |
| Rail bars | Width 2; radius 1; x=`index × (width−2)/(count−1)`; height=`max(.14,level) × railHeight`; vertically centered |
| Rail readability | Stroke `#131019`, width .65; `paint-order:stroke fill`; `drop-shadow(0 0 .3px rgba(236,233,244,.6))` |
| Rail fill | Silent `#3A3547`; live nine-stop gradient at 0%, 12.5%, 25%, 37.5%, 50%, 62.5%, 75%, 87.5%, 100% |
| Palette | `#FF2A40`, `#FF8C00`, `#FFD700`, `#ADFF2F`, `#00FA9A`, `#00FFFF`, `#1E90FF`, `#4169E1`, `#8A2BE2` |
| X and check | 40 × 40; radius 20; X `#2a2733`; check `#7c3aed` |
| Control SVG | 20 × 20; no fill; white stroke width 2; round joins and caps |
| Paths | X `M5 5L15 15M15 5L5 15`; check `M4 10L8 14L16 6` |

Docking preserves the source's 4 dp gap over the keyboard. The source used `bottom:17px` above a 13 px keyboard strip; these scenes position a 64 dp dock above a full illustrative keyboard and use `bottom:0` inside the dock. Bubble and pill geometry remains the same. In the main scenes the 232 dp tap pill occupies 696 output pixels. The apps wall is deliberately zoomed out, and the final phone uses a scaled composition so the complete practice destination fits.

The three scale/glow pulses are teaching emphasis requested for this demo, not an idle or working-bubble specification. The source's working color cycle is not used as a recording state. Rail samples are copied from the supplied theme sequence and advance deterministically every 100 ms. The source's clock glyph style is preserved while its fixed comparison value 0:07 becomes elapsed scene recording time.

## What is simulated

All five scenes are illustrations. The app wall has generic tiles, not installed app data. Text boxes, keyboard, touches, clock, voice levels, transcription, and practice destination are simulated. The last screen does not request microphone access or accept a real practice take. Its native handoff is outside this folder. CSS hands are the initial art; no image generation was invoked. No app code was read or changed. No rendered clips, browser review, or live-phone validation were performed.
