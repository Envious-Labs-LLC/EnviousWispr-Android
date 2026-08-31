#!/usr/bin/env python3
"""Generate the EnviousWispr Material 3 palette from the brand seed, and prove its contrast.

`design-language.md` RULE: derive-the-android-palette-from-the-seed-never-hand-pick-tones says to
generate the palette with the Material colour utilities and check the result against the brand, rather
than hand-writing colour roles. This is that generator.

MOST roles are generated. The PRIMARY ACCENTS and the NEUTRAL GROUND are deliberate macOS overrides,
each with its reasoning below, and each caught by the founder looking at the built app rather than by
a check. Do not read this file as "everything comes from the seed". Its output is pasted into
`app/src/main/java/com/envi/wispr/ui/theme/EnviousWisprTheme.kt`; this script is the receipt for how
those numbers were reached, so a future session can re-derive them instead of trusting them.

    python3 -m venv .palette && ./.palette/bin/pip install materialyoucolor
    ./.palette/bin/python scripts/generate-brand-palette.py

WHY `SchemeVibrant` AND NOT THE DEFAULT. Material's default `SchemeTonalSpot` desaturates the seed:
#7c3aed becomes #675788 in light and #CFBFEB in dark, a grey-purple that is the "app does not look like
EnviousWispr, it looks like a Material sample" complaint in issue #40. Measured 2026-08-31 across all
five variants, Vibrant is the one that lands nearest the brand and nearest the macOS accent:

    variant      light primary   dark primary   (brand #7C3AED, macOS dark accent #A78BFA)
    TonalSpot    #675788         #CFBFEB
    Vibrant      #6F33D5         #BD9DFF        <- chosen
    Expressive   #6B4FA3         #DAC5FF
    Fidelity     #630ED4         #D2BBFF
    Content      #630ED4         #D2BBFF

WHY THE GROUND IS NOT GENERATED. Vibrant's neutral ramp is a saturated purple-black, #180429 in dark.
macOS ships a near-neutral dark that the founder tuned by eye, and that is what the product looks like.
So the ground comes from macOS `Sources/EnviousWisprAppKit/Views/Settings/SettingsDesignTokens.swift`.
That is a deliberate departure from a generated scheme, which is why every text-on-surface pair is
re-checked below: a hand-placed ground is exactly the thing that silently breaks Material's contrast
guarantees.

The accent is the SECOND departure and it has its own reasoning beside `MACOS_ACCENT`. Read the two
together: what is left coming from the seed is the containers, the secondary and tertiary ramps, the
error ramp, and the outlines.

Exit: 0 every pair meets its bar · 1 at least one pair is below it, which is not a palette to ship.
"""

from __future__ import annotations

import sys

try:
    from materialyoucolor.hct import Hct
    from materialyoucolor.dynamiccolor.material_dynamic_colors import MaterialDynamicColors as M
    from materialyoucolor.scheme.scheme_vibrant import SchemeVibrant
except ImportError:
    sys.exit("materialyoucolor is not installed. See the header for the two commands.")

SEED = 0xFF7C3AED

# Measured from macOS SettingsDesignTokens.swift on 2026-08-31. Re-read that file rather than trusting
# these if they ever disagree; it is the source and this is the copy.
MACOS_GROUND = {
    "dark": {"page": "#131019", "section": "#201B2B", "sidebar": "#1A1623", "window": "#0D0B12",
             "text": "#ECE9F4", "text2": "#AAA2BF"},
    "light": {"page": "#F8F5FF", "section": "#FFFFFF", "sidebar": "#E8E2F5", "window": "#DDD5EE",
              "text": "#0F0A1A", "text2": "#4A3D60"},
}

# THE ACCENT IS NOT GENERATED EITHER, AND THIS IS THE SECOND THING THE FOUNDER CAUGHT BY EYE.
#
# Generated Vibrant gave #6F33D5 light and #BD9DFF dark. Neither is the brand purple, and the dark one
# is paler than anything macOS ships. Founder, 2026-08-31, looking at the built app: "the purple feels
# different". It was.
#
# macOS keeps TWO purples and Material has one slot for them, which is the whole difficulty:
#
#   stAccent       #7c3aed / #a78bfa   text, icons, outlines
#   stAccentSolid  #7c3aed / #8b46f0   FILLED surfaces carrying white content
#
# and its comment records why, from a founder decision on 2026-07-03: the desaturated lavender is for
# text and outlines only, because "as a fill under white it reads washed-out".
#
# Material's `primary` does both jobs at once, so one value cannot serve both. Measured on the dark
# ground #131019: #a78bfa reads 6.92 as an icon but only 2.72 under white; #8b46f0 reads 4.96 under
# white but only 3.79 as an icon, which is under the bar for the text buttons that use it.
#
# So `primary` takes the TEXT-AND-ICON value, because that is most of the purple on screen: the
# microphone, the drawer group headings, every text button. The handful of filled brand surfaces take
# BRAND_SOLID, emitted separately below.
MACOS_ACCENT = {
    "dark": {"accent": "#A78BFA", "on_accent": "#3C0089", "solid": "#8B46F0", "on_solid": "#FFFFFF"},
    "light": {"accent": "#7C3AED", "on_accent": "#FFFFFF", "solid": "#7C3AED", "on_solid": "#FFFFFF"},
}

GENERATED_ROLES = [
    "primary", "onPrimary", "primaryContainer", "onPrimaryContainer",
    "secondary", "onSecondary", "secondaryContainer", "onSecondaryContainer",
    "tertiary", "onTertiary", "tertiaryContainer", "onTertiaryContainer",
    "error", "onError", "errorContainer", "onErrorContainer",
    "inversePrimary", "outline", "outlineVariant",
]

EMIT_ORDER = [
    "primary", "onPrimary", "primaryContainer", "onPrimaryContainer",
    "secondary", "onSecondary", "secondaryContainer", "onSecondaryContainer",
    "tertiary", "onTertiary", "tertiaryContainer", "onTertiaryContainer",
    "error", "onError", "errorContainer", "onErrorContainer",
    "background", "onBackground", "surface", "onSurface",
    "surfaceVariant", "onSurfaceVariant",
    "surfaceContainerLowest", "surfaceContainerLow", "surfaceContainer",
    "surfaceContainerHigh", "surfaceContainerHighest",
    "inverseSurface", "inverseOnSurface", "inversePrimary",
    "outline", "outlineVariant", "scrim",
]

# Every pair a user reads, with the bar it must clear. 4.5 is WCAG AA for text; 3.0 is the bar for a
# non-text UI boundary, which is all `outline` is.
#
# ADD A ROW WHENEVER A SCREEN STARTS PAINTING A NEW COMBINATION. A pair this list does not name is a
# pair nothing checks, and the script still exits 0, which reads as "the palette is fine".
#
# The two card roles were MEASURED off the rendered app rather than read from the Material source,
# because the source is not in the Gradle cache and a guess here silently removes a check. Sampled
# from a dark screenshot on the Android 16 emulator, 2026-08-31:
#
#     page ground          #131019   surface
#     ElevatedCard         #1A1623   surfaceContainerLow
#     Card                 #312A3D   surfaceContainerHighest
#     NavigationBar        #201B2B   surfaceContainer
#
# `Card` is the one that matters most: `SettingsGroup` wraps nearly every row in the app in one, so
# `surfaceContainerHighest` is the background most text in this product is read against.
CONTRAST_PAIRS = [
    ("onSurface", "surface", 4.5),
    ("onSurfaceVariant", "surface", 4.5),
    ("onSurface", "surfaceContainer", 4.5),
    ("onSurfaceVariant", "surfaceContainer", 4.5),
    ("onSurface", "surfaceContainerHigh", 4.5),
    ("onSurfaceVariant", "surfaceContainerHigh", 4.5),
    ("onSurface", "surfaceContainerLowest", 4.5),
    ("onSurface", "surfaceContainerLow", 4.5),
    ("onSurfaceVariant", "surfaceContainerLow", 4.5),
    ("onSurface", "surfaceContainerHighest", 4.5),
    ("onSurfaceVariant", "surfaceContainerHighest", 4.5),
    ("primary", "surface", 4.5),
    ("primary", "surfaceContainer", 4.5),
    ("onPrimary", "primary", 4.5),
    ("error", "surface", 4.5),
    ("outline", "surface", 3.0),
    ("onPrimaryContainer", "primaryContainer", 4.5),
    ("onSecondaryContainer", "secondaryContainer", 4.5),
    ("onTertiaryContainer", "tertiaryContainer", 4.5),
    ("onErrorContainer", "errorContainer", 4.5),
]


def opaque(hex_string: str) -> int:
    return 0xFF000000 | int(hex_string.lstrip("#"), 16)


def generated(role: str, dark: bool) -> int:
    r, g, b, _a = getattr(M, role).get_hct(SchemeVibrant(Hct.from_int(SEED), dark, 0.0)).to_rgba()
    return 0xFF000000 | (r << 16) | (g << 8) | b


def relative_luminance(argb: int) -> float:
    def channel(raw: int) -> float:
        c = raw / 255
        return c / 12.92 if c <= 0.03928 else ((c + 0.055) / 1.055) ** 2.4
    r, g, b = (argb >> 16) & 0xFF, (argb >> 8) & 0xFF, argb & 0xFF
    return 0.2126 * channel(r) + 0.7152 * channel(g) + 0.0722 * channel(b)


def contrast(a: int, b: int) -> float:
    la, lb = relative_luminance(a), relative_luminance(b)
    hi, lo = max(la, lb), min(la, lb)
    return (hi + 0.05) / (lo + 0.05)


def build(dark: bool) -> dict[str, int]:
    ground = MACOS_GROUND["dark" if dark else "light"]
    accent = MACOS_ACCENT["dark" if dark else "light"]
    scheme = {role: generated(role, dark) for role in GENERATED_ROLES}
    scheme["primary"] = opaque(accent["accent"])
    scheme["onPrimary"] = opaque(accent["on_accent"])
    scheme["background"] = opaque(ground["page"])
    scheme["surface"] = opaque(ground["page"])
    scheme["onBackground"] = opaque(ground["text"])
    scheme["onSurface"] = opaque(ground["text"])
    scheme["onSurfaceVariant"] = opaque(ground["text2"])
    scheme["surfaceContainerLowest"] = opaque(ground["window"])
    scheme["surfaceContainerLow"] = opaque(ground["sidebar"] if dark else "#F0ECF9")
    scheme["surfaceContainer"] = opaque(ground["section"] if dark else ground["sidebar"])
    scheme["surfaceContainerHigh"] = opaque("#282232" if dark else "#E2DAF2")
    scheme["surfaceContainerHighest"] = opaque("#312A3D" if dark else "#DDD5EE")
    scheme["surfaceVariant"] = opaque("#282232" if dark else "#E8E2F5")
    other = MACOS_GROUND["light" if dark else "dark"]
    scheme["inverseSurface"] = opaque(other["page"])
    scheme["inverseOnSurface"] = opaque(other["text"])
    scheme["scrim"] = 0xFF000000
    return scheme


def main() -> int:
    failures = 0
    for dark in (False, True):
        scheme = build(dark)
        print(f"\n// ---- {'DARK' if dark else 'LIGHT'} ----")
        for role in EMIT_ORDER:
            print(f"    {role} = Color(0x{scheme[role] & 0xFFFFFFFF:08X}),")
        solid = MACOS_ACCENT["dark" if dark else "light"]
        print(f"    // BrandSolid, for a FILLED brand surface under white content")
        print(f"    brandSolid = Color(0x{opaque(solid['solid']) & 0xFFFFFFFF:08X}),")
        print(f"    onBrandSolid = Color(0x{opaque(solid['on_solid']) & 0xFFFFFFFF:08X}),")
        print("  contrast:")
        # The solid pair is checked here rather than in CONTRAST_PAIRS because it is not a scheme role.
        solid_ratio = contrast(opaque(solid["solid"]), opaque(solid["on_solid"]))
        solid_ok = solid_ratio >= 4.5
        failures += 0 if solid_ok else 1
        print(
            f"    {'onBrandSolid / brandSolid':<42} {solid_ratio:5.2f}  bar 4.5  "
            f"{'OK' if solid_ok else 'FAIL'}   {solid['on_solid']} on {solid['solid']}"
        )
        for foreground, background, bar in CONTRAST_PAIRS:
            ratio = contrast(scheme[foreground], scheme[background])
            ok = ratio >= bar
            failures += 0 if ok else 1
            print(
                f"    {foreground + ' / ' + background:<42} {ratio:5.2f}  bar {bar:.1f}  "
                f"{'OK' if ok else 'FAIL'}   #{scheme[foreground] & 0xFFFFFF:06X} on "
                f"#{scheme[background] & 0xFFFFFF:06X}"
            )
    print(f"\npairs below their bar: {failures}")
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
