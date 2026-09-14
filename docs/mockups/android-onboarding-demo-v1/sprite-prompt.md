# Clay hand sprite sheet

Model requested: **gpt-image-2.5 on Azure**. This is a prompt only. No image generation or deployment lookup has been performed. Azure deployment availability: **UNCHECKED**.

## Exact image prompt

Create ONE 1536 × 1536 PNG with a true transparent alpha background, arranged as an exact 3 × 3 sprite sheet. Each cell is exactly 512 × 512 pixels. No gutters, borders, labels, text, numbers, phone, button, contact ring, scenery, or background. Do not draw a checkerboard. All nine cells contain the SAME isolated right hand and wrist, with identical lighting, camera, size, anatomy, skin tone, material, and sleeve.

Art direction: a friendly handmade clay character prop, matching EnviousWispr's rounded stop-motion clay people. Soft matte modeling clay, gently imperfect sculpted surfaces, plump simple forms, warm peach skin, rounded fingertips, short lavender sleeve cuff. Restrained tactile detail, no pores or photorealism, no nail polish or jewelry, no plastic gloss, no hard black outline. Soft studio light from upper left, gentle ambient shading within the hand, no cast shadow outside the silhouette. A grown-up, welcoming product illustration.

Camera: fixed three-quarter view of the back of a right hand with the index finger pointing toward the TOP of the image. The palm is below the fingertip, wrist exits toward the bottom, thumb folds naturally along the left side, other three fingers curl gently into the palm. The hand is pointing toward an imaginary flat screen parallel to the image plane. The application rotates the whole sprite slightly counterclockwise later; do not rotate the camera between cells.

Registration is critical: in every 512 × 512 cell, align the tip of the index finger at local pixel (256, 64), including poses where the finger is relaxed. Keep the hand's silhouette inside x=96..432 and y=48..486. Keep the wrist centered around x=320, y=446. Do not move or zoom the hand between cells. Approaching and lifting will be supplied by animation of the entire sprite. Show changes in finger curvature and soft clay compression only. No clipped fingertips or wrists except the clean sleeve end.

Read the cells left to right, top to bottom:
1. RESTING. Relaxed extended index, other fingers comfortably curled, soft neutral hand.
2. APPROACHING. Index slightly straighter and poised to touch, wrist steady.
3. TAP DOWN. Index fingertip gently flattened against the imaginary screen, a small believable squash at the contact pad.
4. TAP UP. Fingertip rounded again, finger relaxing just after one quick tap, no contact pressure.
5. PRESS AND HOLD A. Index pad in continuous contact, light pressure, slight flattening.
6. PRESS AND HOLD B. Same contact point, a tiny increase in pressure and knuckle bend. No positional drift.
7. PRESS AND HOLD C. Same contact point, pressure gently eases but contact remains. The three hold poses must loop smoothly without lifting.
8. LIFT. Index pad fully uncompressed, slightly curved as the hand leaves the screen. No motion trails.
9. RESTING. Match cell 1 exactly, for a clean ending and repeat.

Keep pose differences small, readable, and anatomically consistent. Exactly one hand per cell, five fingers per hand. Preserve ample transparent space around each hand. Deliver the sheet as one transparent PNG.

## Renderer swap

Save the generated image as `hand-sprite.png` beside this file. From either hand scene run:

```sh
node render.cjs --sprite=../hand-sprite.png
node render.cjs --theme=light --sprite=../hand-sprite.png
```

`render.cjs` reads the PNG, embeds it as a data URL, and calls `window.setHandSprite(url)` before capturing frames. The page decodes it, checks the square three-cell grid, hides `.css-hand`, and displays `.sprite-hand`. It uses `background-size: 300% 300%` and selects one of the nine cells from `window.frame(t)`; there is no independent sprite timer. Render only scenes 3 and 4 with this option.

The displayed cell is 190 × 190 px. Its box begins at (-55, -10) relative to the pointer container, putting the requested fingertip at approximately (40, 14), the CSS pointer's fixed contact point and transform origin. If the image model misses registration, align the cells to this anchor before rendering. Keep the contact ring separate from the hand.

The reference character animation's `animation.ffconcat` holds poses for 0.6, 0.2, 0.2, 0.2, 0.5, 0.2, 0.2, 0.2, and 0.6 seconds and repeats the last file. Here the same discrete-pose approach follows the gestures instead: tap down briefly, tap up away from the screen, then a second tap on the check; hold A/B/C changes every 0.3 seconds while the finger stays down. The authored scene clock is the only source of time.

CSS hands remain the default if no sprite argument is supplied. To preview a generated sheet interactively, call `window.setHandSprite(dataURL)` inside either hand scene; the renderer's data URL path avoids local-file fetch restrictions. The five-scene review page uses CSS hands by default.
