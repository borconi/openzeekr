package com.openzeekr.app.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.core.view.WindowCompat
import com.openzeekr.app.R

/*
 * Two looks live side by side:
 *
 *  - CLASSIC: the original dark-committed "cockpit" (cool near-black ground, one electric-blue accent,
 *    semantic-only energy / secured / alert colours). Always dark.
 *  - STUDIO: the combined redesign (clean "Studio" layout with the "Night Deck" dark palette), in a light and
 *    a dark version, or following the system setting.
 *
 * Which one is shown is a user setting (SecretsConfig.theme, see [ThemeKeys]). Screens never test the theme
 * key: colours come from [Brand] (and MaterialTheme.colorScheme), which resolve against the active [Palette],
 * and the few places whose LAYOUT differs between the looks ask [Brand.isStudio].
 */

enum class AppStyle { CLASSIC, STUDIO }

/** Values of the persisted theme setting. */
object ThemeKeys {
    const val AUTO = "studio_auto"
    const val LIGHT = "studio_light"
    const val DARK = "studio_dark"
    const val CLASSIC = "classic"
    const val DEFAULT = AUTO
    /** In the order the picker shows them. */
    val ALL = listOf(AUTO, LIGHT, DARK, CLASSIC)
}

/** Every colour a screen may need, for one look in one brightness. */
@Immutable
class Palette(
    val style: AppStyle,
    val dark: Boolean,
    val bg: Color,
    val surface: Color,
    val surface2: Color,
    val surface3: Color,
    val line: Color,
    val text: Color,
    /** Secondary text. */
    val muted: Color,
    /** Tertiary text, disabled marks, idle dots. */
    val faint: Color,
    /** Interactive accent, and what sits on a solid fill of it. */
    val accent: Color,
    val onAccent: Color,
    /** Charging / energy / caution, and what sits on a solid fill of it. */
    val energy: Color,
    val onEnergy: Color,
    /** Secured / OK. */
    val good: Color,
    val onGood: Color,
    /** Alert / open. */
    val crit: Color,
    val onCrit: Color,
    /** Charge-progress green (drawn on the car and on dark fills, so it stays vivid in both brightnesses). */
    val neon: Color,
    /** The strongest fill: selected toggles, the lock slider's track, the climate power button. */
    val strong: Color,
    val onStrong: Color,
    /** The panel the hero car stands on. */
    val heroCard: Color,
    /** Outline drawn around cards; transparent where a card is told apart by its fill alone. */
    val cardBorder: Color,
    /** Solid fill of the lock slider's thumb when the car is locked, and its glyph colour. */
    val lockThumb: Color,
    val onLockThumb: Color,
)

private val ClassicDark = Palette(
    style = AppStyle.CLASSIC, dark = true,
    bg = Color(0xFF0C0F12), surface = Color(0xFF14181C), surface2 = Color(0xFF1C2227), surface3 = Color(0xFF232B31),
    line = Color(0xFF2A3238), text = Color(0xFFEAEEF1), muted = Color(0xFF8B979E), faint = Color(0xFF5E696F),
    accent = Color(0xFF5AA9FF), onAccent = Color(0xFF07121F),
    energy = Color(0xFFF5B740), onEnergy = Color(0xFF2A1E00),
    good = Color(0xFF46D8A0), onGood = Color(0xFF00251A),
    crit = Color(0xFFFF6B6B), onCrit = Color(0xFF2A0A0A),
    neon = Color(0xFF31E08A),
    strong = Color(0xFF5AA9FF), onStrong = Color(0xFF07121F),
    heroCard = Color(0xFF14181C), cardBorder = Color.Transparent,
    lockThumb = Color(0xFF46D8A0), onLockThumb = Color(0xFF00251A),
)

private val StudioDark = Palette(
    style = AppStyle.STUDIO, dark = true,
    bg = Color(0xFF0C0F12), surface = Color(0xFF14181C), surface2 = Color(0xFF1C2227), surface3 = Color(0xFF232B31),
    line = Color(0xFF2A3238), text = Color(0xFFEAEEF1), muted = Color(0xFF9AA6AD), faint = Color(0xFF5E696F),
    accent = Color(0xFF5AA9FF), onAccent = Color(0xFF07121F),
    energy = Color(0xFFF5B740), onEnergy = Color(0xFF2A1E00),
    good = Color(0xFF46D8A0), onGood = Color(0xFF00251A),
    crit = Color(0xFFFF6B6B), onCrit = Color(0xFF2A0A0A),
    neon = Color(0xFF31E08A),
    strong = Color(0xFFEAEEF1), onStrong = Color(0xFF0C0F12),
    heroCard = Color(0xFF14181C), cardBorder = Color(0xFF2A3238),
    lockThumb = Color(0xFF46D8A0), onLockThumb = Color(0xFF00251A),
)

private val StudioLight = Palette(
    style = AppStyle.STUDIO, dark = false,
    bg = Color(0xFFEEF0F2), surface = Color(0xFFFFFFFF), surface2 = Color(0xFFEEF0F2), surface3 = Color(0xFFE1E5E9),
    line = Color(0xFFDDE1E6), text = Color(0xFF0F1419), muted = Color(0xFF5B6672), faint = Color(0xFF8E98A3),
    accent = Color(0xFF1B5FD9), onAccent = Color(0xFFFFFFFF),
    energy = Color(0xFF9A5B00), onEnergy = Color(0xFFFFFFFF),
    good = Color(0xFF0B7A4F), onGood = Color(0xFFFFFFFF),
    crit = Color(0xFFC0352B), onCrit = Color(0xFFFFFFFF),
    neon = Color(0xFF31E08A),
    strong = Color(0xFF0F1419), onStrong = Color(0xFFFFFFFF),
    heroCard = Color(0xFFE1E5E9), cardBorder = Color.Transparent,
    lockThumb = Color(0xFF5FE0A8), onLockThumb = Color(0xFF06281A),
)

/** The palette for a stored theme key. An unknown key (a value from a newer build) gets the default. */
fun paletteFor(themeKey: String, systemDark: Boolean): Palette = when (themeKey) {
    ThemeKeys.CLASSIC -> ClassicDark
    ThemeKeys.LIGHT -> StudioLight
    ThemeKeys.DARK -> StudioDark
    else -> if (systemDark) StudioDark else StudioLight
}

private fun schemeOf(p: Palette): ColorScheme {
    val container = lerp(p.surface, p.accent, if (p.dark) 0.22f else 0.12f)
    return if (p.dark) darkColorScheme(
        primary = p.accent, onPrimary = p.onAccent, primaryContainer = container, onPrimaryContainer = p.text,
        secondary = p.good, onSecondary = p.onGood,
        tertiary = p.energy, onTertiary = p.onEnergy,
        background = p.bg, onBackground = p.text,
        surface = p.surface, onSurface = p.text,
        surfaceVariant = p.surface2, onSurfaceVariant = p.muted,
        // surfaceContainerHigh is what Material gives dialogs. It is the card colour, not surface2, because
        // text fields are filled with surface2 and would vanish on a dialog of the same colour.
        surfaceContainerLowest = p.bg, surfaceContainerLow = p.surface, surfaceContainer = p.surface,
        surfaceContainerHigh = p.surface, surfaceContainerHighest = p.surface3,
        outline = p.line, outlineVariant = p.line,
        error = p.crit, onError = p.onCrit,
    ) else lightColorScheme(
        primary = p.accent, onPrimary = p.onAccent, primaryContainer = container, onPrimaryContainer = p.text,
        secondary = p.good, onSecondary = p.onGood,
        tertiary = p.energy, onTertiary = p.onEnergy,
        background = p.bg, onBackground = p.text,
        surface = p.surface, onSurface = p.text,
        surfaceVariant = p.surface2, onSurfaceVariant = p.muted,
        surfaceContainerLowest = p.surface, surfaceContainerLow = p.surface, surfaceContainer = p.surface,
        surfaceContainerHigh = p.surface, surfaceContainerHighest = p.surface3,
        outline = p.faint, outlineVariant = p.line,
        error = p.crit, onError = p.onCrit,
    )
}

private fun lerp(a: Color, b: Color, t: Float) = Color(
    a.red + (b.red - a.red) * t,
    a.green + (b.green - a.green) * t,
    a.blue + (b.blue - a.blue) * t,
    1f,
)

// Classic keeps exactly the Material roles it shipped with (and Material's own defaults for the rest, which is
// what its dialogs and menus have always used), so choosing Classic changes nothing.
private val ClassicScheme = darkColorScheme(
    primary = ClassicDark.accent,
    onPrimary = Color(0xFF07121F),
    primaryContainer = Color(0xFF16324B),
    onPrimaryContainer = Color(0xFFCFE4FF),
    secondary = ClassicDark.good,
    onSecondary = Color(0xFF00251A),
    tertiary = ClassicDark.energy,
    onTertiary = Color(0xFF2A1E00),
    background = ClassicDark.bg,
    onBackground = ClassicDark.text,
    surface = ClassicDark.surface,
    onSurface = ClassicDark.text,
    surfaceVariant = ClassicDark.surface2,
    onSurfaceVariant = ClassicDark.muted,
    outline = ClassicDark.line,
    outlineVariant = Color(0xFF20272C),
    error = ClassicDark.crit,
    onError = Color(0xFF2A0A0A),
)
private val StudioDarkScheme = schemeOf(StudioDark)
private val StudioLightScheme = schemeOf(StudioLight)

private val ClassicShapes = Shapes(
    extraSmall = RoundedCornerShape(10.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(30.dp),
)
private val StudioShapes = Shapes(
    extraSmall = RoundedCornerShape(10.dp),
    small = RoundedCornerShape(14.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

// Both families are variable fonts (one file each); every weight used is declared so text never gets a faked bold.
@OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)
private fun variable(resId: Int, vararg weights: Int) = FontFamily(weights.map { w ->
    Font(resId, weight = FontWeight(w), variationSettings = FontVariation.Settings(FontVariation.weight(w)))
})

private val Inter = variable(R.font.inter_variable, 400, 500, 600, 700)
private val SpaceGrotesk = variable(R.font.space_grotesk_variable, 500, 600, 700)

private val ClassicTypography = Typography()
private val StudioTypography = Typography().let { t ->
    // Line heights are kept as a ratio of the font size (em), so a Text that only overrides fontSize - most of
    // the app - gets a line that fits it instead of the 24sp line of the 16sp body style.
    fun TextStyle.rel() = copy(lineHeight = (lineHeight.value / fontSize.value).em)
    fun TextStyle.body() = rel().copy(fontFamily = Inter)
    fun TextStyle.display() = rel().copy(fontFamily = SpaceGrotesk)
    Typography(
        displayLarge = t.displayLarge.display(), displayMedium = t.displayMedium.display(), displaySmall = t.displaySmall.display(),
        headlineLarge = t.headlineLarge.display(), headlineMedium = t.headlineMedium.display(), headlineSmall = t.headlineSmall.display(),
        titleLarge = t.titleLarge.display(), titleMedium = t.titleMedium.body(), titleSmall = t.titleSmall.body(),
        bodyLarge = t.bodyLarge.body(), bodyMedium = t.bodyMedium.body(), bodySmall = t.bodySmall.body(),
        labelLarge = t.labelLarge.body(), labelMedium = t.labelMedium.body(), labelSmall = t.labelSmall.body(),
    )
}

/**
 * Brand tokens the screens use directly (beyond the Material scheme). Each one resolves against the active
 * palette, so a screen written with `Brand.accent` follows whatever theme the user picked.
 */
object Brand {
    /** The active palette. Set by [OpenZeekrTheme]; reads are tracked, so a change repaints everything. */
    var palette: Palette by mutableStateOf(StudioDark)
        internal set

    val isStudio: Boolean get() = palette.style == AppStyle.STUDIO
    val isDark: Boolean get() = palette.dark

    val bg: Color get() = palette.bg
    val surface: Color get() = palette.surface
    val surface2: Color get() = palette.surface2
    val surface3: Color get() = palette.surface3
    val line: Color get() = palette.line
    val text: Color get() = palette.text
    val muted: Color get() = palette.muted
    val faint: Color get() = palette.faint
    val accent: Color get() = palette.accent
    val onAccent: Color get() = palette.onAccent
    val energy: Color get() = palette.energy
    val onEnergy: Color get() = palette.onEnergy
    val good: Color get() = palette.good
    val onGood: Color get() = palette.onGood
    val crit: Color get() = palette.crit
    val onCrit: Color get() = palette.onCrit
    val neon: Color get() = palette.neon
    val strong: Color get() = palette.strong
    val onStrong: Color get() = palette.onStrong
    val heroCard: Color get() = palette.heroCard
    val cardBorder: Color get() = palette.cardBorder
    val lockThumb: Color get() = palette.lockThumb
    val onLockThumb: Color get() = palette.onLockThumb

    /** Headline family: Space Grotesk in the Studio look, the system face in Classic. */
    val display: FontFamily get() = if (isStudio) SpaceGrotesk else FontFamily.Default

    val badge = listOf(Color(0xFF6DB4FF), Color(0xFF3A6FF0))
    /** Back-compat alias (used by BrandBadge / ParkingScreen). */
    val gradient = badge

    /** A quiet tinted fill of [c] for "this is on" surfaces: stronger on dark grounds, lighter on light ones. */
    fun tint(c: Color): Color = lerp(palette.surface, c, if (palette.dark) 0.16f else 0.10f)

    /** Metallic paint "identity card" gradient for a given paint colour. */
    fun paintCard(paint: Color): Brush = Brush.radialGradient(
        colors = listOf(
            lerp(paint, Color.White, 0.26f),
            paint,
            lerp(paint, Color.Black, 0.46f),
        ),
    )
}

/** Upper bound on the OS font scale the app honours. We follow the user's accessibility font size up to
 *  this, then stop: the dense dashboard (4-across stat rows, control tiles, tyre cards) can't
 *  absorb a 1.5–2× system font without wrapping/clipping. Below this cap, everything still scales. */
private const val MAX_FONT_SCALE = 1.30f

/**
 * @param themeKey the stored setting (SecretsConfig.theme); see [ThemeKeys].
 */
@Composable
fun OpenZeekrTheme(themeKey: String = ThemeKeys.DEFAULT, content: @Composable () -> Unit) {
    val p = paletteFor(themeKey, isSystemInDarkTheme())
    // This is the root of the tree, so nothing below has read the palette yet in this pass.
    if (Brand.palette !== p) Brand.palette = p

    // Edge-to-edge draws behind the system bars; their icons must contrast with OUR ground, not the system theme's.
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            (view.context as? Activity)?.window?.let { w ->
                WindowCompat.getInsetsController(w, view).apply {
                    isAppearanceLightStatusBars = !p.dark
                    isAppearanceLightNavigationBars = !p.dark
                }
            }
        }
    }

    val base = LocalDensity.current
    val clamped = if (base.fontScale > MAX_FONT_SCALE) Density(base.density, MAX_FONT_SCALE) else base
    CompositionLocalProvider(LocalDensity provides clamped) {
        MaterialTheme(
            colorScheme = when (p) { ClassicDark -> ClassicScheme; StudioDark -> StudioDarkScheme; else -> StudioLightScheme },
            shapes = if (p.style == AppStyle.STUDIO) StudioShapes else ClassicShapes,
            typography = if (p.style == AppStyle.STUDIO) StudioTypography else ClassicTypography,
            content = content,
        )
    }
}
