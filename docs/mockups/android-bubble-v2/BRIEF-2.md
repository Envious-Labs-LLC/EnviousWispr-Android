# Round 2: three themes, each with its bubble, tap pill and hold pill

The founder saw your gallery (2026-09-14) and decided: ship THREE looks the user picks from in
Settings > Appearance, and drop the cushion. The three are your **Bare lips**, **Clear square** and
**Smoke square**. Each look is a THEME: it styles the idle bubble AND the two recorder pills so the
three surfaces read as one family.

Write ONLY inside `docs/mockups/android-bubble-v2/`. Keep `index.html` from round 1 as is; write a
NEW page `themes.html` and update `README.md` (add a section per theme; keep the round 1 content).

## The two pills as they exist today on the phone (build 117)

Both pills are 60 dp tall, fully rounded, anchored to the same screen edge the bubble is docked at,
with a 1 dp violet `#8A2BE2` outline and a violet-tinted shadow on a `#131019` ground. Both hold a
level rail: thin rounded vertical bars painted with the rainbow gradient, silent bars in `#3A3547`,
14 percent of the rail height at silence and up to 100 percent at full level, symmetric about the
centre line, one bar per 100 ms poll scrolling left.

- **Tap pill** (a tap on the bubble starts a take; the pill replaces the bubble): 232 dp wide.
  Left to right: elapsed clock `0:07` in Plus Jakarta Sans semibold 15 sp `#ECE9F4` (48 dp min
  width), an 11-bar rail 66 dp wide and 22 dp tall, a 40 dp cancel circle `#2A2733` with a drawn
  white X, a 40 dp accept circle `#7C3AED` with a drawn white tick. When the bubble is docked LEFT
  the row is mirrored so accept is at the left edge under the thumb.
- **Hold pill** (the bubble is held; the finger is the control; release finishes): 168 dp wide,
  the rail alone, 22 bars in 134 dp, 28 dp tall. Nothing else.

## What to produce

`themes.html`, self-contained, opening to a gallery of the THREE themes. For each theme, at 3x
device scale, on the same three grounds as round 1 (white page, dark page, photo):

1. Idle bubble (reuse your round 1 candidate exactly).
2. Working bubble (rolling rainbow, reduced-motion fallback).
3. Tap pill, mid-take, docked right, with a few lit bars and the clock at `0:07`.
4. Hold pill, mid-take, docked right, bars lit.

Rules for the pills per theme:
- The pill must belong to its bubble: same ground treatment (none, clear white, smoke), same
  corner language, same border rule (no border in all three; the violet outline is retired).
- The rail's rainbow is the one live element; the clock and the two controls must stay readable on
  every ground. For **Bare lips**, decide what the pill stands on when there is no backing, and say
  so: a pill with no ground at all over a white page is not acceptable unless you show it works.
- Controls: cancel is quiet, accept is the one filled `#7C3AED` control, both 40 dp, both drawn
  glyphs.
- Keep every dimension above unless a theme needs a change; if it does, name the change and why.

Under each theme, three lines: what unifies the three surfaces; the one risk; the READMEs' worth of
exact CSS values (ground colour and alpha, corner radius, shadow, control colours, clock colour) so
an engineer can port each theme to Android as one palette object.

At the top: the NAMES you propose for the three themes as the user will read them in a settings
list, one or two words each, with a one-line description under each, plain English, no jargon.

Stop after writing `themes.html` and updating `README.md`. Do not review, do not summarise.
