# EnviousWispr Android bubble v2

Open `index.html` directly. Everything is inline; there are no network requests.

## Recommended: Lips cushion

A shallow capsule gives the rainbow a dark backing with less visible area than a square. It is a design recommendation, not a phone-validated implementation.

All CSS values below are at **1 CSS px = 1 Android dp**. The gallery scales the complete device crop by 3; do not port that scale factor.

```css
.target {
  width: 56px;
  height: 56px;
  background: transparent;
  border: 0;
  padding: 0;
  display: grid;
  place-items: center;
}
.surface {
  width: 50px;
  height: 36px;
  border-radius: 18px;
  background: rgba(19, 16, 25, 0.72);
  border: 0;
  box-shadow: 0 2px 4px rgba(19, 16, 25, 0.24);
  display: grid;
  place-items: center;
}
.lips {
  width: 40px;
  height: 40px;
  overflow: visible;
}
```

- Fill: recorder ground `#131019`, **72% alpha**, applied to the backing only. Lips stay fully opaque.
- Visible backing: **50 × 36 dp**, **18 dp** corner radius. Centered inside the **56 × 56 dp** target, giving 3 dp horizontal and 10 dp vertical insets.
- Shadow: offset **0, 2 dp**, blur **4 dp**, spread **0**, colour `#131019` at **24% alpha**. No stroke, inner shadow, backdrop blur or extra glow.
- Lips: original **256 × 256** SVG viewport displayed at **40 × 40 dp**, centered. The 18 painted bars occupy about **32.19 × 23.70 dp**; viewport padding is intentional. Keep the SVG unclipped: its viewport is taller than the backing, while all painted bars fit inside it.
- Geometry is reused from `../android-lips-bubble-v1/index.html`: original upper/lower arrays, x offset +8, y offset +13, bar width 14, radius 5. Original fills remain on every rectangle.
- Dock the complete target against either screen edge. The mock shows its bottom 17 dp above the crop bottom, with a 13 dp keyboard strip, giving a 4 dp gap above the keyboard.

## Working and reduced motion

Keep the backing and lips geometry unchanged. Animate **fill only** through the nine supplied palette colours, linearly over **1.8 seconds**, repeating. Each rectangle's delay is `-0.2 seconds × its original palette index`, so the original colour arrangement is preserved at the cycle start. No spinning, scaling, breathing, hue rotation or app blur.

With `prefers-reduced-motion: reduce`, disable the fill animation and retain the original static fills. Add an **8 × 2 dp** still status dash at **left 24 dp, bottom 5 dp** within the target: `#ECE9F4`, radius **1 dp**, shadow `0 0 1px 1px rgba(19,16,25,.75)`. The gallery's still-state button also demonstrates this fallback. On Android, expose the working state through the control's accessible description.

Touch feedback in this appearance mock scales the backing and lips to **0.96** while pressed. The 56 dp hit area stays fixed. Keyboard focus gets an external gallery focus indicator; it is not an idle border.

## Alternatives in the gallery

| Candidate | Backing | Lips viewport | Principal tradeoff |
| --- | --- | --- | --- |
| Bare lips | None, shadow only | 42 dp | Bright bars compete with bright backgrounds |
| Clear square | White at 28%, 48 dp square, 14 dp radius | 38 dp | Weak definition on white |
| Smoke square | `#131019` at 64%, 48 dp square, 14 dp radius | 38 dp | Can read as an app tile |
| Lips cushion | `#131019` at 72%, 50 × 36 dp, 18 dp radius | 40 dp | Looks smaller than its hit area |


# Round 2: appearance themes

Founder decision, 2026-09-14: offer **Bare**, **Clear** and **Smoke** in Settings > Appearance. The cushion recommendation above is retained as round 1 history and is superseded by this decision. `index.html` remains unchanged. Open `themes.html` for the three complete families, with inline SVG, CSS, script and embedded Plus Jakarta Sans semibold. No network or microphone is used.

## Shared dimensions and rendering

Values are at 1 CSS px = 1 dp, with the clock at 15 sp at default font scale. Only the gallery crop is scaled 3×. Each column is 744 CSS px wide so the full 696 CSS px tap pill fits; horizontal scrolling preserves the requested scale on smaller windows.

- Bubble target: 56 × 56 dp. Original round 1 geometry, fill, shadow and lips sizes are unchanged.
- Tap: 232 × 60 dp, radius 30 dp, horizontal padding 10 dp, gap 6 dp. Row widths: clock 48 + rail 66 + cancel 40 + accept 40 + three gaps 18 + padding 20 = 232 dp.
- Hold: 168 × 60 dp, radius 30 dp, centered 134 × 28 dp rail, 17 dp horizontal inset. No clock or controls.
- Both pills: border 0. The former violet outline and violet shadow are retired. All dimensions supplied in the brief are retained. Rounded square bubbles extend to fully rounded pills, matching the existing 60 dp pill shape.
- Clock: embedded Plus Jakarta Sans semibold 600, 15 px, fixed at `0:07` for comparison. Fill `#ECE9F4`, ink outline `#131019` at 1.5 dp, round joins, `paint-order: stroke fill`. This is an edge around the glyphs, not a border around the pill. It keeps the clock light against dark pages and defines it against white without switching palettes based on the app behind it.
- Cancel: 40 × 40 dp, radius 20 dp, opaque `#2A2733`. Accept: same geometry, opaque `#7C3AED`. Both use 20 dp SVG glyphs, white `#FFFFFF`, 2 dp strokes with round caps and joins. Cancel path `M5 5L15 15M15 5L5 15`; accept `M4 10L8 14L16 6`. No text glyph substitutes.
- Rail: tap 11 bars in 66 × 22 dp; hold 22 bars in 134 × 28 dp. Bars are 2 dp wide, radius 1 dp, positioned at `index × (railWidth - 2) / (barCount - 1)`. Height is `max(0.14, level) × railHeight`, centered vertically. Silent fill `#3A3547`; live fill is the supplied nine-stop rainbow, left to right, anchored to the rail width.
- Rail readability: every bar has a .65 dp `#131019` ink edge with `paint-order: stroke fill`, plus `drop-shadow(0 0 .3px rgba(236,233,244,.6))`. These treatments trace individual marks; they do not fill the rail rectangle or blur the underlying app.
- Rail demonstration: one new sample each 100 ms; history shifts left. This is an illustrative repeating sequence, not microphone input. Only the rail changes within each recording pill; controls and clock stay still.
- Right docking: surface right 0, bottom 17 dp above the crop bottom, above a 13 dp keyboard strip. Left docking on Android mirrors the tap row order to accept, cancel, rail, clock. Do not mirror the text or glyphs. Hold remains centered.
- Working bubbles use the unchanged 1.8 second colour roll. Reduced motion disables bubble animation, adds the original still status dash and freezes the rails. The page button also freezes motion. Android should retain accessible state descriptions.

## Theme: Bare

Settings description: **Just the colour, with less around it.** Maps to round 1 **Bare lips**.

No shared backing: lips, outlined clock and outlined rail float independently, with rounded controls as anchors. Risk: Busy photos can make the separate parts feel scattered.

```css
/* One theme palette. Shared dimensions above apply. */
--surface-fill: transparent;
--surface-shadow: none;
--bubble-radius: 0px;
--bubble-visible-size: 48px;
--lips-size: 42px;
--lips-filter: drop-shadow(0 0 .65px rgba(19,16,25,.85)) drop-shadow(0 1px 2px rgba(19,16,25,.45));
--pill-radius: 30px;
--surface-border: 0;
--clock-color: #ECE9F4;
--clock-ink-edge: #131019;
--clock-ink-edge-width: 1.5px;
--cancel-fill: #2A2733;
--accept-fill: #7C3AED;
--control-glyph: #FFFFFF;
--control-radius: 20px;
--rail-silent: #3A3547;
--rail-ink-edge: #131019;
--rail-ink-edge-width: .65px;
--rail-filter: drop-shadow(0 0 .3px rgba(236,233,244,.6));
```

**What the pill stands on:** there is no shared rectangle or capsule fill. Its clock and rail stand on tightly outlined ink, while the cancel and accept circles provide separate touch anchors. On the white-page sample, dark ink edges define the light clock and bright rail colours; on dark, their light fills remain visible. This is an intentional no-ground design shown over all three grounds, not white text left unprotected. The hold pill keeps only that outlined rail. No component dimensions change.

## Theme: Clear

Settings description: **A light touch that lets the page show through.** Maps to round 1 **Clear square**.

The bubble and both pills share the same faint white fill, soft corners and quiet shadow. Risk: Text beneath the clear pill can compete with the rail.

```css
/* One theme palette. Shared dimensions above apply. */
--surface-fill: rgba(255,255,255,.28);
--surface-shadow: 0 2px 5px rgba(19,16,25,.18);
--bubble-radius: 14px;
--bubble-visible-size: 48px;
--lips-size: 38px;
--lips-filter: drop-shadow(0 .5px .5px rgba(19,16,25,.45));
--pill-radius: 30px;
--surface-border: 0;
--clock-color: #ECE9F4;
--clock-ink-edge: #131019;
--clock-ink-edge-width: 1.5px;
--cancel-fill: #2A2733;
--accept-fill: #7C3AED;
--control-glyph: #FFFFFF;
--control-radius: 20px;
--rail-silent: #3A3547;
--rail-ink-edge: #131019;
--rail-ink-edge-width: .65px;
--rail-filter: drop-shadow(0 0 .3px rgba(236,233,244,.6));
```

The same flat white at 28% fill and shadow apply to the bubble and both pills. Keep child colours fully opaque; do not apply opacity to the whole surface. The clock and rail retain the shared ink treatment for mixed backgrounds. No backdrop blur, adaptive page sampling or extra permission is needed.

## Theme: Smoke

Settings description: **A soft dark background behind the colour.** Maps to round 1 **Smoke square**.

The same translucent dark ground holds the lips, clock and rainbow together without an outline. Risk: The wider dark pill covers more of the page than the other looks.

```css
/* One theme palette. Shared dimensions above apply. */
--surface-fill: rgba(19,16,25,.64);
--surface-shadow: 0 2px 5px rgba(19,16,25,.20);
--bubble-radius: 14px;
--bubble-visible-size: 48px;
--lips-size: 38px;
--lips-filter: none;
--pill-radius: 30px;
--surface-border: 0;
--clock-color: #ECE9F4;
--clock-ink-edge: #131019;
--clock-ink-edge-width: 1.5px;
--cancel-fill: #2A2733;
--accept-fill: #7C3AED;
--control-glyph: #FFFFFF;
--control-radius: 20px;
--rail-silent: #3A3547;
--rail-ink-edge: #131019;
--rail-ink-edge-width: .65px;
--rail-filter: drop-shadow(0 0 .3px rgba(236,233,244,.6));
```

The same flat recorder ground #131019 at 64% fill and shadow apply to the bubble and both pills. Keep child colours fully opaque; do not apply opacity to the whole surface. The clock and rail retain the shared ink treatment for mixed backgrounds. No backdrop blur, adaptive page sampling or extra permission is needed.

