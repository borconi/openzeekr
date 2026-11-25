package com.openzeekr.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchColors
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openzeekr.core.R
import com.openzeekr.app.ui.theme.Brand

/**
 * Single-line text that shrinks its font until it fits the available width. Used for dense dashboard
 * values/labels (stat tiles, control captions) so they never wrap raggedly ("Locke\nd", "578\nkm") on
 * phones with a large display size or font scale. Version-agnostic (Compose 1.7 has no built-in
 * autoSize): it renders at [maxFontSize] and, on overflow, steps down ~8% per pass to [minFontSize].
 */
@Composable
fun AutoSizeText(
    text: String,
    color: Color,
    maxFontSize: TextUnit,
    modifier: Modifier = Modifier,
    minFontSize: TextUnit = 10.sp,
    fontWeight: FontWeight? = null,
    textAlign: TextAlign? = TextAlign.Center,
) {
    var size by remember(text, maxFontSize) { mutableStateOf(maxFontSize) }
    Text(
        text = text,
        color = color,
        modifier = modifier,
        fontSize = size,
        fontWeight = fontWeight,
        textAlign = textAlign,
        maxLines = 1,
        softWrap = false,
        overflow = TextOverflow.Visible,
        onTextLayout = { result ->
            if (result.hasVisualOverflow && size.value > minFontSize.value) {
                size = (size.value * 0.92f).coerceAtLeast(minFontSize.value).sp
            }
        },
    )
}

/**
 * One consistent Switch palette for the whole app. The Material3 defaults wash out on
 * the near-black cockpit ground (near-invisible OFF track, muddy ON thumb); this gives a
 * bright accent ON track with a dark thumb, and a legible OFF track with a clear border.
 */
@Composable
fun brandSwitchColors(checkedTrack: Color = Brand.accent): SwitchColors = SwitchDefaults.colors(
    checkedThumbColor = Brand.onAccent,
    checkedTrackColor = checkedTrack,
    checkedBorderColor = Color.Transparent,
    uncheckedThumbColor = Brand.muted,
    uncheckedTrackColor = Brand.surface3,
    uncheckedBorderColor = Brand.line,
    disabledCheckedTrackColor = checkedTrack.copy(alpha = 0.35f),
    disabledUncheckedTrackColor = Brand.surface2,
)

/** The app badge with the OpenZeekr mark — used in the top bar. Dark-green card + green mark,
 *  matching the launcher icon. */
@Composable
fun BrandBadge(size: Int = 30) {
    Box(
        modifier = Modifier
            .size(size.dp)
            .clip(RoundedCornerShape((size / 3.2f).dp))
            .background(Color(0xFF1F2825)), // the mark's dark-green card — matches the launcher icon
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_logo),
            contentDescription = null,
            tint = Color.Unspecified,
            modifier = Modifier.size(size.dp), // ic_logo carries its own padding
        )
    }
}

/** Corner radius of a content card in the active look. */
val cardRadius get() = if (Brand.isStudio) 22.dp else 18.dp

/** The fill, shape and (where the look has one) outline of a content card. */
fun Modifier.cardSurface(radius: androidx.compose.ui.unit.Dp = cardRadius): Modifier {
    val shape = RoundedCornerShape(radius)
    return clip(shape).background(Brand.surface).border(1.dp, Brand.cardBorder, shape)
}

/** Rounded surface panel — the "card". */
@Composable
fun CockpitCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier.fillMaxWidth().cardSurface().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        content = content,
    )
}

/**
 * Primary action — a muted accent-tinted button (NOT a loud solid-blue fill). Full-saturation
 * blue is reserved for the bottom nav; interactive surfaces use this restrained tint + accent
 * text/border so the cockpit stays calm. Disabled stays legible (raised surface + faint text).
 */
@Composable
fun PrimaryButton(text: String, modifier: Modifier = Modifier, enabled: Boolean = true, tint: Color = Brand.accent, onClick: () -> Unit) {
    if (Brand.isStudio) {
        // Studio: a solid fill. The accent keeps its own on-colour; any other tint gets the surface colour on top.
        val on = if (tint == Brand.accent) Brand.onAccent else Brand.surface
        Box(
            modifier.clip(RoundedCornerShape(16.dp))
                .background(if (enabled) tint else Brand.surface3)
                .let { if (enabled) it.clickable(onClick = onClick) else it }
                .padding(vertical = 14.dp, horizontal = 12.dp),
            contentAlignment = Alignment.Center,
        ) { Text(text, color = if (enabled) on else Brand.faint, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, textAlign = TextAlign.Center) }
        return
    }
    Box(
        modifier.clip(RoundedCornerShape(14.dp))
            .background(if (enabled) tint.copy(alpha = 0.16f) else Brand.surface3)
            .border(1.dp, if (enabled) tint.copy(alpha = 0.55f) else Brand.line, RoundedCornerShape(14.dp))
            .let { if (enabled) it.clickable(onClick = onClick) else it }
            .padding(vertical = 14.dp),
        contentAlignment = Alignment.Center,
    ) { Text(text, color = if (enabled) tint else Brand.muted, fontWeight = FontWeight.Bold, fontSize = 15.sp) }
}

/** Muted segmented/toggle chip shared by the unit selectors and pickers (selected = accent
 *  tint + light text, not a loud fill). Use everywhere a "radio" choice appears. */
@Composable
fun SelectChip(label: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    if (Brand.isStudio) {
        // Studio: the chosen option is the strongest fill on the card, the others sit back.
        Box(
            modifier.clip(RoundedCornerShape(12.dp))
                .background(if (selected) Brand.strong else Brand.surface2)
                .clickable(onClick = onClick)
                .padding(horizontal = 14.dp, vertical = 8.dp),
        ) {
            Text(label, color = if (selected) Brand.onStrong else Brand.muted, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold)
        }
        return
    }
    Box(
        modifier.clip(RoundedCornerShape(10.dp))
            .background(if (selected) Brand.accent.copy(alpha = 0.18f) else Brand.surface2)
            .border(1.dp, if (selected) Brand.accent.copy(alpha = 0.5f) else Brand.line, RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 13.dp, vertical = 7.dp),
    ) {
        Text(
            label,
            color = if (selected) Brand.accent else Brand.muted,
            fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold,
        )
    }
}

/** Secondary / outline action, cockpit-styled with a visible disabled state. */
@Composable
fun GhostButton(text: String, modifier: Modifier = Modifier, enabled: Boolean = true, tint: Color = Brand.accent, onClick: () -> Unit) {
    val ghostRadius = if (Brand.isStudio) 16.dp else 14.dp
    Box(
        modifier.clip(RoundedCornerShape(ghostRadius)).background(Brand.surface2)
            .border(1.dp, if (enabled) Brand.line else Brand.line.copy(alpha = 0.5f), RoundedCornerShape(ghostRadius))
            .let { if (enabled) it.clickable(onClick = onClick) else it }
            .padding(vertical = 14.dp),
        contentAlignment = Alignment.Center,
    ) { Text(text, color = if (enabled) tint else Brand.faint, fontWeight = FontWeight.SemiBold, fontSize = 15.sp) }
}

/** Subtle uppercase section label. */
@Composable
fun SectionHeader(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 1.2.sp,
        modifier = modifier.padding(start = 4.dp, top = 4.dp, bottom = 2.dp),
    )
}

/** A compact tap-to-fire tile: icon over a short label. */
@Composable
fun CommandTile(
    label: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = MaterialTheme.colorScheme.primary,
) {
    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = modifier.width(104.dp),
    ) {
        Column(
            modifier = Modifier.padding(vertical = 14.dp, horizontal = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(26.dp))
            Text(
                label,
                style = MaterialTheme.typography.labelMedium,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}
