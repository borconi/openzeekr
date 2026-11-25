package com.openzeekr.wear

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.wear.compose.material.Colors
import androidx.wear.compose.material.MaterialTheme

/*
 * The watch in the phone app's Studio look (dark). Same tokens and the same rules:
 *  - cards are a quiet raised panel with a hairline edge, 22dp corners;
 *  - colour says state: accent = the main action, energy = charging, heat, caution, good = safe or running,
 *    crit = failed or stop; an "on" surface is a tint of its colour with an edge of it, never a bright slab;
 *  - Space Grotesk for figures and titles, the system face for the rest (Inter is not bundled on the watch:
 *    it would add close to a megabyte for text set at 11-14sp).
 * The ground is true black rather than the phone's near-black: it costs nothing on an OLED watch and lets
 * the round screen disappear into the bezel. A watch is always dark, so there is no light variant.
 */
object Oz {
    val bg = Color(0xFF000000)
    val surface = Color(0xFF14181C)
    val surface2 = Color(0xFF1C2227)
    val surface3 = Color(0xFF232B31)
    val line = Color(0xFF2A3238)
    val text = Color(0xFFEAEEF1)
    val muted = Color(0xFF9AA6AD)
    val faint = Color(0xFF5E696F)
    val accent = Color(0xFF5AA9FF)
    val onAccent = Color(0xFF07121F)
    val energy = Color(0xFFF5B740)
    val onEnergy = Color(0xFF2A1E00)
    val good = Color(0xFF46D8A0)
    val onGood = Color(0xFF00251A)
    val crit = Color(0xFFFF6B6B)
    val neon = Color(0xFF31E08A)

    /** The soft light the rendered car stands in (the same one as on the phone's dark home screen). */
    val pool = Color(0xFF96ACC4)

    /** A quiet fill of [c] for "this is on" surfaces. */
    fun tint(c: Color): Color = Color(
        surface.red + (c.red - surface.red) * 0.18f,
        surface.green + (c.green - surface.green) * 0.18f,
        surface.blue + (c.blue - surface.blue) * 0.18f,
        1f,
    )

    /** Headline face. One variable font file; every weight used is declared so none is faked. */
    @OptIn(ExperimentalTextApi::class)
    val display: FontFamily = FontFamily(listOf(500, 600, 700).map { w ->
        Font(R.font.space_grotesk_variable, weight = FontWeight(w), variationSettings = FontVariation.Settings(FontVariation.weight(w)))
    })
}

@Composable
fun OzWearTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colors = Colors(
            primary = Oz.accent, primaryVariant = Oz.accent, onPrimary = Oz.onAccent,
            secondary = Oz.good, secondaryVariant = Oz.good, onSecondary = Oz.onGood,
            background = Oz.bg, onBackground = Oz.text,
            surface = Oz.surface, onSurface = Oz.text, onSurfaceVariant = Oz.muted,
            error = Oz.crit, onError = Color(0xFF2A0A0A),
        ),
        content = content,
    )
}
