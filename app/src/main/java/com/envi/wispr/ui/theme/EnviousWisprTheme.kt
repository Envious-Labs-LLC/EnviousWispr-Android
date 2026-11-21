package com.envi.wispr.ui.theme

import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.SwitchColors
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.shape.RoundedCornerShape
import com.envi.wispr.R
import com.envi.wispr.settings.ThemeMode

/**
 * Plus Jakarta Sans, the brand face.
 *
 * Four weights, latin subset, 128 KB in total, converted from the same `@fontsource` files the website
 * serves. A missing face silently becomes the system default and no test catches that, so every
 * [Typography] style below names this family explicitly rather than inheriting it.
 */
private val BrandFontFamily = FontFamily(
    Font(R.font.plus_jakarta_sans_regular, FontWeight.Normal),
    Font(R.font.plus_jakarta_sans_medium, FontWeight.Medium),
    Font(R.font.plus_jakarta_sans_semibold, FontWeight.SemiBold),
    Font(R.font.plus_jakarta_sans_bold, FontWeight.Bold),
)

/**
 * The brand palette.
 *
 * MOST chromatic roles are GENERATED from the brand seed `#7c3aed` with the Material colour utilities,
 * `SchemeVibrant`, which is the variant that keeps the seed's chroma; the default `SchemeTonalSpot`
 * desaturates it to `#675788`, a grey-purple, which is the "looks like a Material sample" complaint in
 * issue #40.
 *
 * **`primary` and `onPrimary` are NOT generated, and neither is the neutral ground.** Both are macOS
 * values, and both were caught by the founder's eye rather than by a check: generated Vibrant gave
 * `#6F33D5` light and `#BD9DFF` dark, and his verdict on the built app was "the purple feels
 * different". See [BrandFill] for why macOS keeps two accents where Material has one slot.
 *
 * Regenerate rather than hand-editing a value:
 *
 * ```
 * pip install materialyoucolor
 * python scripts/generate-brand-palette.py
 * ```
 *
 * THE NEUTRAL GROUND IS THE SECOND EXCEPTION, alongside the accents above, and it is taken from the
 * same macOS file `Sources/EnviousWisprAppKit/Views/Settings/SettingsDesignTokens.swift`, because the
 * generated ground is a saturated purple-black rather than the near-neutral dark macOS uses.
 *
 * So: the accents and the ground come from macOS, and what is still generated from the seed is the
 * containers, the secondary and tertiary ramps, the error ramp, and the outlines.
 *
 * The generator prints the contrast ratio of every text-on-surface pair afterwards, which is the check
 * `design-language.md` RULE: derive-the-android-palette-from-the-seed-never-hand-pick-tones asks for.
 * It exits non-zero when any checked pair is below its bar, so run it rather than trusting a number
 * written here.
 */
private val EnviousLightColors = lightColorScheme(
    primary = Color(0xFF7C3AED),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFB28CFF),
    onPrimaryContainer = Color(0xFF2E006C),
    secondary = Color(0xFF7742A6),
    onSecondary = Color(0xFFFBEFFF),
    secondaryContainer = Color(0xFFE6C5FF),
    onSecondaryContainer = Color(0xFF612C90),
    tertiary = Color(0xFF9E3657),
    onTertiary = Color(0xFFFFEFF1),
    tertiaryContainer = Color(0xFFFF8EAC),
    onTertiaryContainer = Color(0xFF64042D),
    error = Color(0xFFB41340),
    onError = Color(0xFFFFEFEF),
    errorContainer = Color(0xFFF74B6D),
    onErrorContainer = Color(0xFF510017),
    background = Color(0xFFF8F5FF),
    onBackground = Color(0xFF0F0A1A),
    surface = Color(0xFFF8F5FF),
    onSurface = Color(0xFF0F0A1A),
    surfaceVariant = Color(0xFFE8E2F5),
    onSurfaceVariant = Color(0xFF4A3D60),
    surfaceContainerLowest = Color(0xFFDDD5EE),
    surfaceContainerLow = Color(0xFFF0ECF9),
    surfaceContainer = Color(0xFFE8E2F5),
    surfaceContainerHigh = Color(0xFFE2DAF2),
    surfaceContainerHighest = Color(0xFFDDD5EE),
    inverseSurface = Color(0xFF131019),
    inverseOnSurface = Color(0xFFECE9F4),
    inversePrimary = Color(0xFFA476FF),
    outline = Color(0xFF856E98),
    outlineVariant = Color(0xFFBDA3D1),
    scrim = Color(0xFF000000),
)

private val EnviousDarkColors = darkColorScheme(
    primary = Color(0xFFA78BFA),
    onPrimary = Color(0xFF3C0089),
    primaryContainer = Color(0xFFB28CFF),
    onPrimaryContainer = Color(0xFF2E006C),
    secondary = Color(0xFFC38BF5),
    onSecondary = Color(0xFF3B0065),
    secondaryContainer = Color(0xFF612B8F),
    onSecondaryContainer = Color(0xFFE5C4FF),
    tertiary = Color(0xFFFF97B2),
    onTertiary = Color(0xFF6A0A31),
    tertiaryContainer = Color(0xFFFE81A4),
    onTertiaryContainer = Color(0xFF5A0027),
    error = Color(0xFFFF6E84),
    onError = Color(0xFF490013),
    errorContainer = Color(0xFFA70138),
    onErrorContainer = Color(0xFFFFB2B9),
    background = Color(0xFF131019),
    onBackground = Color(0xFFECE9F4),
    surface = Color(0xFF131019),
    onSurface = Color(0xFFECE9F4),
    surfaceVariant = Color(0xFF282232),
    onSurfaceVariant = Color(0xFFAAA2BF),
    surfaceContainerLowest = Color(0xFF0D0B12),
    surfaceContainerLow = Color(0xFF1A1623),
    surfaceContainer = Color(0xFF201B2B),
    surfaceContainerHigh = Color(0xFF282232),
    surfaceContainerHighest = Color(0xFF312A3D),
    inverseSurface = Color(0xFFF8F5FF),
    inverseOnSurface = Color(0xFF0F0A1A),
    inversePrimary = Color(0xFF7337D9),
    outline = Color(0xFF846C96),
    outlineVariant = Color(0xFF543F66),
    scrim = Color(0xFF000000),
)

/**
 * The brand radii: card 16, button 12, pill fully round.
 *
 * Material spends five sizes where the brand names three, so `small` carries the button value and
 * `medium` the card value, which is where Material puts buttons and cards respectively. The app rounded
 * harder than the brand at every size before this.
 */
private val EnviousShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

/**
 * The brand face on every style.
 *
 * Built by mapping the Material default over [BrandFontFamily] and then overriding the handful of sizes
 * and weights this app tunes. Setting the family style by style is deliberate: a `Typography` with any
 * style left at the default renders that one line in the system face, and the difference is small
 * enough to ship unnoticed.
 */
private val EnviousTypography: Typography = Typography().let { base ->
    Typography(
        displayLarge = base.displayLarge.copy(fontFamily = BrandFontFamily),
        displayMedium = base.displayMedium.copy(fontFamily = BrandFontFamily),
        displaySmall = TextStyle(
            fontFamily = BrandFontFamily,
            fontWeight = FontWeight.Bold,
            fontSize = 36.sp,
            lineHeight = 42.sp,
            letterSpacing = (-0.4).sp,
        ),
        headlineLarge = TextStyle(
            fontFamily = BrandFontFamily,
            fontWeight = FontWeight.Bold,
            fontSize = 30.sp,
            lineHeight = 36.sp,
        ),
        headlineMedium = TextStyle(
            fontFamily = BrandFontFamily,
            fontWeight = FontWeight.Bold,
            fontSize = 25.sp,
            lineHeight = 31.sp,
        ),
        headlineSmall = base.headlineSmall.copy(fontFamily = BrandFontFamily),
        titleLarge = TextStyle(
            fontFamily = BrandFontFamily,
            fontWeight = FontWeight.SemiBold,
            fontSize = 21.sp,
            lineHeight = 27.sp,
        ),
        titleMedium = TextStyle(
            fontFamily = BrandFontFamily,
            fontWeight = FontWeight.SemiBold,
            fontSize = 16.sp,
            lineHeight = 22.sp,
        ),
        titleSmall = base.titleSmall.copy(fontFamily = BrandFontFamily),
        bodyLarge = TextStyle(
            fontFamily = BrandFontFamily,
            fontWeight = FontWeight.Normal,
            fontSize = 16.sp,
            lineHeight = 24.sp,
        ),
        bodyMedium = TextStyle(
            fontFamily = BrandFontFamily,
            fontWeight = FontWeight.Normal,
            fontSize = 14.sp,
            lineHeight = 20.sp,
        ),
        bodySmall = base.bodySmall.copy(fontFamily = BrandFontFamily),
        labelLarge = TextStyle(
            fontFamily = BrandFontFamily,
            fontWeight = FontWeight.SemiBold,
            fontSize = 14.sp,
            lineHeight = 20.sp,
        ),
        labelMedium = base.labelMedium.copy(fontFamily = BrandFontFamily),
        labelSmall = base.labelSmall.copy(fontFamily = BrandFontFamily),
    )
}

/**
 * The solid brand purple, for a FILLED surface carrying white content.
 *
 * macOS keeps two accents and Material has one slot for them. `stAccent` is for text, icons and
 * outlines; `stAccentSolid` is for filled surfaces, and its comment records the founder decision of
 * 2026-07-03 behind the split: the desaturated lavender "as a fill under white reads washed-out".
 *
 * `MaterialTheme.colorScheme.primary` takes the text-and-icon value, because that is most of the purple
 * on screen. This is the other half, and it is deliberately NOT a `ColorScheme` role, because Material
 * has no role that means "the brand's own fill" and inventing one inside `ColorScheme` would have every
 * component that reads that role start using it too.
 *
 * When the user turns Galaxy colours on, this follows the dynamic scheme instead: they asked for their
 * wallpaper's colours, and a brand-purple button in the middle of a wallpaper-derived screen is the
 * inconsistency they would notice.
 */
private data class BrandFill(val container: Color, val content: Color)
private data class BrandGreen(val track: Color, val thumb: Color, val statusText: Color)

// No default. `Color.Unspecified` was one, and it is a PLAUSIBLE default rather than a sentinel: a
// button outside the theme would render invisible rather than fail, which is the shape
// `validation-discipline.md` warns about under plausible-value traps. Reading this without the theme
// is a programming error, so it throws.
private val LocalBrandFill = staticCompositionLocalOf<BrandFill> {
    error("LocalBrandFill must be provided by EnviousWisprTheme")
}
private val LocalBrandGreen = staticCompositionLocalOf<BrandGreen> {
    error("LocalBrandGreen must be provided by EnviousWisprTheme")
}
/** Null outside [EnviousWisprTheme] so standalone Compose tests keep following system dark mode. */
internal val LocalDarkTheme = staticCompositionLocalOf<Boolean?> { null }

/** Syncs status and navigation bar icon contrast to an app activity's resolved dark background. */
internal fun ComponentActivity.applySystemBarTheme(isDark: Boolean) {
    enableEdgeToEdge(
        statusBarStyle = SystemBarStyle.auto(
            android.graphics.Color.TRANSPARENT,
            android.graphics.Color.TRANSPARENT,
        ) { isDark },
        navigationBarStyle = SystemBarStyle.auto(
            android.graphics.Color.TRANSPARENT,
            android.graphics.Color.TRANSPARENT,
        ) { isDark },
    )
}

/** Colours for a primary action button, so a filled brand surface never renders as a pale lavender. */
@Composable
internal fun brandButtonColors(): ButtonColors {
    val fill = LocalBrandFill.current
    return ButtonDefaults.buttonColors(
        containerColor = fill.container,
        contentColor = fill.content,
    )
}

/**
 * Colours for toggle switches (#52): green when on in the brand palette (so enabled toggles stay
 * distinct from purple labels and buttons), or Galaxy dynamic colours when the user opted into those.
 * Verified in `scripts/generate-brand-palette.py` (`CONTRAST_PAIRS`).
 */
@Composable
internal fun brandSwitchColors(): SwitchColors {
    val green = LocalBrandGreen.current
    return SwitchDefaults.colors(
        checkedTrackColor = green.track,
        checkedThumbColor = green.thumb,
        checkedBorderColor = green.track,
        disabledCheckedTrackColor = green.track.copy(alpha = 0.38f),
        disabledCheckedThumbColor = green.thumb.copy(alpha = 0.6f),
    )
}

/** Colour for green ready-status indicators (dots and inline status text). */
@Composable
internal fun brandReadyColor(): Color = LocalBrandGreen.current.track

/** Accessible text colour for green inline ready status labels on card/page surfaces. */
@Composable
internal fun brandReadyTextColor(): Color = LocalBrandGreen.current.statusText

/** Thin violet card outline (#52), brighter when the card is selected/active. */
@Composable
internal fun brandCardBorder(selected: Boolean = false): BorderStroke = BorderStroke(
    width = if (selected) 1.5.dp else 1.dp,
    color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
)

@Composable
internal fun EnviousWisprTheme(
    dynamicColor: Boolean = false,
    themeMode: ThemeMode = ThemeMode.DEFAULT,
    darkTheme: Boolean = themeMode.resolveDarkTheme(isSystemInDarkTheme()),
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val usingDynamic = dynamicColor
    val colorScheme = when {
        usingDynamic && darkTheme -> dynamicDarkColorScheme(context)
        usingDynamic -> dynamicLightColorScheme(context)
        darkTheme -> EnviousDarkColors
        else -> EnviousLightColors
    }
    val brandFill = when {
        usingDynamic -> BrandFill(colorScheme.primary, colorScheme.onPrimary)
        darkTheme -> BrandFill(Color(0xFF8B46F0), Color(0xFFFFFFFF))
        else -> BrandFill(Color(0xFF7C3AED), Color(0xFFFFFFFF))
    }
    val brandGreen = when {
        usingDynamic -> BrandGreen(colorScheme.primary, colorScheme.onPrimary, colorScheme.primary)
        darkTheme -> BrandGreen(Color(0xFF5CC99A), Color(0xFF063825), Color(0xFF5CC99A))
        else -> BrandGreen(Color(0xFF008250), Color(0xFFFFFFFF), Color(0xFF006B42))
    }

    CompositionLocalProvider(
        LocalBrandFill provides brandFill,
        LocalBrandGreen provides brandGreen,
        LocalDarkTheme provides darkTheme,
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = EnviousTypography,
            shapes = EnviousShapes,
            content = content,
        )
    }
}
