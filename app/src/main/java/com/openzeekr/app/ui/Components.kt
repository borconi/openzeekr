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
import androidx.compose.material3.TextButton
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openzeekr.core.R
import com.openzeekr.app.ui.theme.Brand

/**
 * One consistent Switch palette for the whole app. The Material3 defaults wash out on
 * the near-black cockpit ground (near-invisible OFF track, muddy ON thumb); this gives a
 * bright accent ON track with a dark thumb, and a legible OFF track with a clear border.
 */
@Composable
fun brandSwitchColors(checkedTrack: Color = Brand.accent): SwitchColors = SwitchDefaults.colors(
    checkedThumbColor = Color(0xFF07121F),
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

/** Rounded surface panel — the cockpit "card". */
@Composable
fun CockpitCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(MaterialTheme.colorScheme.surface).padding(16.dp),
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
    Box(
        modifier.clip(RoundedCornerShape(14.dp)).background(Brand.surface2)
            .border(1.dp, if (enabled) Brand.line else Brand.line.copy(alpha = 0.5f), RoundedCornerShape(14.dp))
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

/**
 * Shared informational/confirmation dialog for Demo Mode.
 */
@Composable
fun DemoModeDialog(
    title: String,
    confirmText: String,
    dismissText: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    isExitAction: Boolean = false,
) {
    val inlineContent = mapOf(
        "settings_icon" to InlineTextContent(
            Placeholder(
                width = 16.sp,
                height = 16.sp,
                placeholderVerticalAlign = PlaceholderVerticalAlign.Center
            )
        ) {
            Icon(
                Icons.Filled.Settings,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(16.dp)
            )
        }
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "Demo mode lets you explore the app's full UI with simulated car data and interactive controls. " +
                        "No keys are required, and no real API calls will be made."
                )
                if (!isExitAction) {
                    Text(
                        text = buildAnnotatedString {
                            append("How to exit: To return to the real setup flow later, go to the ")
                            appendInlineContent("settings_icon", "[icon]")
                            append(" Settings tab and click \"Exit demo mode\".")
                        },
                        inlineContent = inlineContent,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        confirmButton = {
            Button(onClick = onConfirm) { Text(confirmText) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(dismissText, color = if (isExitAction) Brand.crit else Color.Unspecified)
            }
        }
    )
}
