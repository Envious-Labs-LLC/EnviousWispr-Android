package com.envi.wispr.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
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
 * Every chromatic role is GENERATED from the brand seed `#7c3aed` with the Material colour utilities,
 * `SchemeVibrant`, which is the variant that keeps the seed's chroma; the default `SchemeTonalSpot`
 * desaturates it to `#675788`, a grey-purple, which is the "looks like a Material sample" complaint in
 * issue #40. Regenerate rather than hand-editing a value:
 *
 * ```
 * pip install materialyoucolor
 * python scripts/generate-brand-palette.py
 * ```
 *
 * The NEUTRAL GROUND is the exception and it is taken from macOS
 * `Sources/EnviousWisprAppKit/Views/Settings/SettingsDesignTokens.swift`, because that is the one part
 * of the palette the founder has already tuned by eye on a shipping product, and the generated ground
 * is a saturated purple-black rather than the near-neutral dark macOS uses. The generator prints the
 * contrast ratio of every text-on-surface pair afterwards, which is the check
 * `design-language.md` RULE: derive-the-android-palette-from-the-seed-never-hand-pick-tones asks for.
 * The generator exits non-zero when any checked pair is below its bar, so run it rather than trusting
 * a number written here.
 */
private val EnviousLightColors = lightColorScheme(
    primary = Color(0xFF6F33D5),
    onPrimary = Color(0xFFF8F0FF),
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
    primary = Color(0xFFBD9DFF),
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

@Composable
internal fun EnviousWisprTheme(
    dynamicColor: Boolean = false,
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && darkTheme -> {
            dynamicDarkColorScheme(context)
        }
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            dynamicLightColorScheme(context)
        }
        darkTheme -> EnviousDarkColors
        else -> EnviousLightColors
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = EnviousTypography,
        shapes = EnviousShapes,
        content = content,
    )
}
