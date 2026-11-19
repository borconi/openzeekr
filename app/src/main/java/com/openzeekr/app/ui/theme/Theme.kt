package com.openzeekr.app.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * OpenZeekr is a dark-committed "cockpit" design (see the redesign mockup): a cool
 * near-black ground, one electric-blue interactive accent, and semantic-only energy/
 * secured/alert colours. We force the dark scheme regardless of system setting.
 */
private val Bg          = Color(0xFF0C0F12)
private val Surface     = Color(0xFF14181C)
private val Surface2    = Color(0xFF1C2227)
private val Surface3    = Color(0xFF232B31)
private val Line        = Color(0xFF2A3238)
private val TextC       = Color(0xFFEAEEF1)
private val Muted       = Color(0xFF8B979E)
private val Faint       = Color(0xFF5E696F)
private val Accent      = Color(0xFF5AA9FF)   // electric blue — interactive
private val Energy      = Color(0xFFF5B740)   // amber — charging/energy
private val Neon        = Color(0xFF31E08A)   // charge progress
private val Good        = Color(0xFF46D8A0)   // secured/ok
private val Crit        = Color(0xFFFF6B6B)   // alert/open

private val DarkColors = darkColorScheme(
    primary = Accent,
    onPrimary = Color(0xFF07121F),
    primaryContainer = Color(0xFF16324B),
    onPrimaryContainer = Color(0xFFCFE4FF),
    secondary = Good,
    onSecondary = Color(0xFF00251A),
    tertiary = Energy,
    onTertiary = Color(0xFF2A1E00),
    background = Bg,
    onBackground = TextC,
    surface = Surface,
    onSurface = TextC,
    surfaceVariant = Surface2,
    onSurfaceVariant = Muted,
    outline = Line,
    outlineVariant = Color(0xFF20272C),
    error = Crit,
    onError = Color(0xFF2A0A0A),
)

private val SigBg       = Color(0xFF07090C)
private val SigSurface  = Color(0xFF0D1014)
private val SigSurface2 = Color(0xFF13171D)
private val SigSurface3 = Color(0xFF1A2028)
private val SigLine     = Color(0xFF282D35)
private val SigTextC    = Color(0xFFECEFF3)
private val SigMuted    = Color(0xFF8E96A0)
private val SigFaint    = Color(0xFF5D656E)
private val SigAccent   = Color(0xFFD6B27C)   // champagne gold — Zeekr Signature
private val SigEnergy   = Color(0xFFE5A952)
private val SigGood     = Color(0xFF3CD09A)

private val SignatureDarkColors = darkColorScheme(
    primary = SigAccent,
    onPrimary = Color(0xFF0B0D10),
    primaryContainer = Color(0xFF2B2316),
    onPrimaryContainer = Color(0xFFF3DEC0),
    secondary = SigGood,
    onSecondary = Color(0xFF00251A),
    tertiary = SigEnergy,
    onTertiary = Color(0xFF2A1E00),
    background = SigBg,
    onBackground = SigTextC,
    surface = SigSurface,
    onSurface = SigTextC,
    surfaceVariant = SigSurface2,
    onSurfaceVariant = SigMuted,
    outline = SigLine,
    outlineVariant = Color(0xFF1E232A),
    error = Crit,
    onError = Color(0xFF2A0A0A),
)

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(10.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(30.dp),
)

/** Extra brand tokens the screens use directly (beyond the Material scheme). */
object Brand {
    var isSignature by mutableStateOf(false)
        internal set

    val accent get() = if (isSignature) SigAccent else Accent
    val energy get() = if (isSignature) SigEnergy else Energy
    val neon get() = if (isSignature) SigGood else Neon
    val good get() = if (isSignature) SigGood else Good
    val crit = Crit
    val surface2 get() = if (isSignature) SigSurface2 else Surface2
    val surface3 get() = if (isSignature) SigSurface3 else Surface3
    val line get() = if (isSignature) SigLine else Line
    val muted get() = if (isSignature) SigMuted else Muted
    val faint get() = if (isSignature) SigFaint else Faint
    val badge get() = if (isSignature) listOf(Color(0xFFE2C18E), Color(0xFFB3894E)) else listOf(Color(0xFF6DB4FF), Color(0xFF3A6FF0))
    /** Back-compat alias (used by BrandBadge / ParkingScreen). */
    val gradient get() = badge

    /** Metallic paint "identity card" gradient for a given paint colour. */
    fun paintCard(paint: Color): Brush = Brush.radialGradient(
        colors = listOf(
            blend(paint, Color.White, 0.26f),
            paint,
            blend(paint, Color.Black, 0.46f),
        ),
    )
    private fun blend(a: Color, b: Color, t: Float) = Color(
        a.red + (b.red - a.red) * t,
        a.green + (b.green - a.green) * t,
        a.blue + (b.blue - a.blue) * t,
        1f,
    )
}

/** Upper bound on the OS font scale the app honours. We follow the user's accessibility font size up to
 *  this, then stop: the dense cockpit dashboard (4-across stat rows, control tiles, tyre cards) can't
 *  absorb a 1.5–2× system font without wrapping/clipping. Below this cap, everything still scales. */
private const val MAX_FONT_SCALE = 1.30f

@Composable
fun OpenZeekrTheme(dark: Boolean = true, signature: Boolean = false, content: @Composable () -> Unit) {
    Brand.isSignature = signature
    val base = androidx.compose.ui.platform.LocalDensity.current
    val clamped = if (base.fontScale > MAX_FONT_SCALE)
        androidx.compose.ui.unit.Density(base.density, MAX_FONT_SCALE) else base
    androidx.compose.runtime.CompositionLocalProvider(
        androidx.compose.ui.platform.LocalDensity provides clamped,
    ) {
        MaterialTheme(
            colorScheme = if (signature) SignatureDarkColors else DarkColors,
            shapes = AppShapes,
            content = content,
        )
    }
}
