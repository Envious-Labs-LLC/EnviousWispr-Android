# Design brief: the EnviousWispr Android floating bubble, v2

You are the designer. Produce an HTML mock, not Android code. Write ONLY inside
`docs/mockups/android-bubble-v2/`. Do not touch any other path.

## What the bubble is

EnviousWispr is on-device voice-to-text on Android. While a text box in any app is focused, a 56 dp
floating bubble sits above the keyboard, docked to the left or right screen edge. Tap it to start a
dictation; hold it to talk while held; drag it to move it. While the app is starting or finishing a
take, the bubble stays and its lips roll their rainbow (the "working" state). It is the product's
primary control and its logo at once.

## What the founder said (2026-09-14)

The current bubble is a dark circle with a 1 dp violet ring and the rainbow lips inside
(`reference/founder-phone-both-bubbles.png`, left). Wispr Flow's bubble is on the right of the same
image: a translucent light rounded square, no border, a small dark glyph. The founder: "You see how
the wisprflow icon is transparent. Rounded square. No border. I'm thinking we do something more like
that."

A first attempt, a frosted light-grey rounded square at 80 percent with the rainbow lips inside
(`reference/rejected-frosted-square.png`, light page on the left, dark on the right), was rejected:
"That looks bad." Do not repeat it. Probable reasons, for you to judge: the grey fill fights the
rainbow, the lips look pasted onto a tile, and it reads as neither Wispr Flow's lightness nor our
brand.

## Fixed inputs

- The lips are the brand mark and must stay: two rows of nine rounded bars on a 256-unit grid, exact
  geometry and colours in `../android-lips-bubble-v1/index.html` (search for the SVG the bubble
  uses) and in the palette below. Do not redraw the lips; reuse that SVG.
- Palette: rainbow `#FF2A40 #FF8C00 #FFD700 #ADFF2F #00FA9A #00FFFF #1E90FF #4169E1 #8A2BE2`;
  brand violet `#8A2BE2`; accent `#7C3AED`; recorder ground `#131019`; text `#ECE9F4`.
- Bubble is 56 dp square touch target. The visible shape may be smaller than the touch target.
- No new permission, no blur of the app underneath (Android accessibility overlays cannot blur).
  Translucency is a flat alpha fill only.
- It must read on a white page (Gmail, Chrome), on a dark page (Messages dark, this chat app),
  and over a photo. It must still look like something you can tap.
- Reduced motion: the working state must have a still fallback.

## Deliverable

`index.html`, self-contained (inline CSS and SVG, no network), opening to a gallery:

1. Four to six DISTINCT candidates, each shown at 3x device scale on three grounds side by side:
   white page with body text, dark page with body text, a photo-like gradient. Show the idle state
   and the working state (rolling rainbow, CSS animation, with `prefers-reduced-motion` fallback).
   Candidates to include at minimum: (a) lips alone with only a shadow or glow; (b) a near-clear
   rounded square, alpha 20 to 35 percent; (c) a dark translucent rounded square, no border;
   (d) one idea of your own that is neither.
2. Under each candidate, two lines: what it is, and the one thing that could go wrong with it.
3. At the top, your RECOMMENDATION: one candidate, and why in three sentences, judged against: does
   it look tappable, does it stay readable on all three grounds, does it feel as light as Wispr
   Flow's while still being ours.
4. `README.md` with the exact CSS values of the recommended candidate (fill colour and alpha, corner
   radius, shadow, lips size inside the 56 dp) so an engineer can port it to Android.

Stop after writing `index.html` and `README.md`. Do not review, do not summarise, do not touch code.
