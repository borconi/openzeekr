package com.openzeekr.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openzeekr.app.ui.theme.Brand

/*
 * Building blocks of the Studio look (theme keys studio_*). Screens use these only inside an
 * `if (Brand.isStudio)` branch; the Classic look keeps its own widgets and must not change.
 *
 * The visual language, so every page reads as one app:
 *  - Page: ground colour Brand.bg, 12dp side gutter, cards 12dp apart. A page opens with StudioPageHeader.
 *  - Card: Brand.surface, 22dp corners, 16dp padding (StudioCard). One subject per card.
 *  - Type: Space Grotesk (Brand.display) for page titles, card titles and big figures; Inter for the rest.
 *    Sizes: page title 26sp, card title 16sp, row title 15sp, supporting text 12.5-13sp, never under 11sp.
 *  - Colour says state, not decoration: accent = selected or primary action, energy = heat, charging, caution,
 *    good = safe or confirmed, crit = destructive. "On" surfaces are a quiet tint of the colour with a 1dp edge
 *    of it (Brand.tint), never a white or black slab.
 *  - Touch targets are at least 44dp. Nothing has a fixed height that text can outgrow: use heightIn(min = ..),
 *    weights and AutoSizeText, so a larger font or display size reflows instead of clipping.
 *  - No text is written in code: every label comes from strings.xml.
 */

/** The scrolling column a Studio page lives in. */
@Composable
fun StudioPage(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 12.dp).padding(top = 4.dp, bottom = 20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp), content = content,
    )
}

/** Page title with an optional line under it and an optional control or status at the end. */
@Composable
fun StudioPageHeader(title: String, subtitle: String? = null, modifier: Modifier = Modifier, trailing: (@Composable () -> Unit)? = null) {
    Row(modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, fontFamily = Brand.display, fontSize = 26.sp, fontWeight = FontWeight.Bold, color = Brand.text,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (subtitle != null) Text(subtitle, fontSize = 13.sp, color = Brand.muted)
        }
        trailing?.invoke()
    }
}

/** A content card. Pass [onClick] to make the whole card one button. */
@Composable
fun StudioCard(
    modifier: Modifier = Modifier,
    padding: PaddingValues = PaddingValues(16.dp),
    spacing: Dp = 12.dp,
    radius: Dp = 22.dp,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier.fillMaxWidth().cardSurface(radius).let { if (onClick != null) it.clickable(role = Role.Button, onClick = onClick) else it }.padding(padding),
        verticalArrangement = Arrangement.spacedBy(spacing), content = content,
    )
}

/** Heading inside a card: title, an optional explanation under it, an optional control or status at the end. */
@Composable
fun StudioCardTitle(title: String, hint: String? = null, modifier: Modifier = Modifier, trailing: (@Composable () -> Unit)? = null) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, fontFamily = Brand.display, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = Brand.text)
            if (hint != null) Text(hint, fontSize = 12.5.sp, color = Brand.muted)
        }
        trailing?.invoke()
    }
}

/** A quiet label that sits on the page ground above a card or a group of cards. */
@Composable
fun StudioGroupLabel(text: String, modifier: Modifier = Modifier) {
    Text(text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Brand.muted,
        modifier = modifier.padding(start = 8.dp, end = 8.dp, top = 6.dp))
}

/** Hairline between rows of one card. */
@Composable
fun StudioDivider(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(1.dp).background(Brand.line))
}

/** The round icon holder used by rows and tiles. [tint] set = "on": filled with that colour. */
@Composable
fun StudioIconDot(icon: ImageVector, modifier: Modifier = Modifier, tint: Color? = null, onTint: Color = Brand.onAccent, size: Dp = 40.dp) {
    Box(modifier.size(size).clip(CircleShape).background(tint ?: Brand.surface2), contentAlignment = Alignment.Center) {
        Icon(icon, null, tint = if (tint != null) onTint else Brand.text, modifier = Modifier.size(size * 0.5f))
    }
}

/**
 * One line of a card: optional icon, title, optional supporting text, optional trailing content. With [onClick]
 * the whole row is a button. Text wraps rather than clipping, and the row grows with it.
 */
@Composable
fun StudioRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    icon: ImageVector? = null,
    iconTint: Color? = null,
    titleColor: Color = Brand.text,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    Row(
        modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(12.dp))
            .let { if (onClick != null) it.clickable(enabled = enabled, role = Role.Button, onClick = onClick) else it },
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (icon != null) StudioIconDot(icon, tint = iconTint)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = if (enabled) titleColor else Brand.faint)
            if (subtitle != null) Text(subtitle, fontSize = 12.5.sp, color = if (enabled) Brand.muted else Brand.faint)
        }
        trailing?.invoke(this)
    }
}

/** A row that leads somewhere: [value] (optional, e.g. a count or state) and a chevron at the end. */
@Composable
fun StudioNavRow(
    title: String, modifier: Modifier = Modifier, subtitle: String? = null, icon: ImageVector? = null,
    value: String? = null, valueColor: Color = Brand.muted, onClick: () -> Unit,
) {
    StudioRow(title, modifier, subtitle, icon, onClick = onClick) {
        if (value != null) Text(value, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = valueColor, maxLines = 1)
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = Brand.faint, modifier = Modifier.size(22.dp))
    }
}

/** A row with a switch. The whole row toggles, and a screen reader hears it as one switch. */
@Composable
fun StudioSwitchRow(
    title: String, checked: Boolean, modifier: Modifier = Modifier, subtitle: String? = null, icon: ImageVector? = null,
    enabled: Boolean = true, onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(12.dp))
            .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onCheckedChange),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (icon != null) StudioIconDot(icon, tint = if (checked) Brand.accent else null)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = if (enabled) Brand.text else Brand.faint)
            if (subtitle != null) Text(subtitle, fontSize = 12.5.sp, color = if (enabled) Brand.muted else Brand.faint)
        }
        Switch(checked = checked, onCheckedChange = null, enabled = enabled, colors = brandSwitchColors())
    }
}

enum class StudioButtonStyle {
    /** The one main action of a card or page: solid accent. */
    Primary,
    /** A secondary action: raised panel, plain text. */
    Tonal,
    /** A quiet alternative: outline only. */
    Outline,
    /** Removes or stops something: red tint. */
    Danger,
}

@Composable
fun StudioButton(
    text: String,
    modifier: Modifier = Modifier,
    style: StudioButtonStyle = StudioButtonStyle.Primary,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    busy: Boolean = false,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(16.dp)
    val fill = when {
        !enabled -> Brand.surface3
        style == StudioButtonStyle.Primary -> Brand.accent
        style == StudioButtonStyle.Tonal -> Brand.surface2
        style == StudioButtonStyle.Danger -> Brand.tint(Brand.crit)
        else -> Color.Transparent
    }
    val on = when {
        !enabled -> Brand.faint
        style == StudioButtonStyle.Primary -> Brand.onAccent
        style == StudioButtonStyle.Danger -> Brand.crit
        else -> Brand.text
    }
    val edge = when {
        !enabled -> Color.Transparent
        style == StudioButtonStyle.Outline -> Brand.line
        style == StudioButtonStyle.Danger -> Brand.crit.copy(alpha = 0.55f)
        else -> Color.Transparent
    }
    Row(
        modifier.heightIn(min = 50.dp).clip(shape).background(fill).border(1.dp, edge, shape)
            .clickable(enabled = enabled && !busy, role = Role.Button, onClick = onClick).padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
    ) {
        if (busy) CircularProgressIndicator(Modifier.size(18.dp), color = on, strokeWidth = 2.dp)
        else if (icon != null) Icon(icon, null, tint = on, modifier = Modifier.size(19.dp))
        Text(text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = on, textAlign = TextAlign.Center)
    }
}

/** A round icon-only button. [description] is what a screen reader says. */
@Composable
fun StudioIconButton(icon: ImageVector, description: String, modifier: Modifier = Modifier, tint: Color = Brand.text, enabled: Boolean = true, onClick: () -> Unit) {
    // surface3 stands out both on the page ground and on a card, in light and in dark (surface2 equals the
    // light page ground, so a button filled with it would lose its shape there).
    Box(
        modifier.size(44.dp).clip(CircleShape).background(Brand.surface3)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick).semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) { Icon(icon, null, tint = if (enabled) tint else Brand.faint, modifier = Modifier.size(22.dp)) }
}

/** A small status word in its colour, e.g. "Armed", "Open", "DEV". */
@Composable
fun StudioPill(text: String, color: Color = Brand.muted, modifier: Modifier = Modifier) {
    Text(
        text, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = color, maxLines = 1,
        modifier = modifier.clip(RoundedCornerShape(10.dp)).background(Brand.tint(color)).padding(horizontal = 10.dp, vertical = 4.dp),
    )
}

/** One choice out of a few, side by side (units, distances, modes). Labels shrink to fit their share. */
@Composable
fun StudioSegmented(options: List<String>, selected: Int, modifier: Modifier = Modifier, enabled: Boolean = true, onSelect: (Int) -> Unit) {
    Row(modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Brand.surface2).padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        options.forEachIndexed { i, label ->
            val on = i == selected
            val shape = RoundedCornerShape(11.dp)
            Box(
                Modifier.weight(1f).heightIn(min = 40.dp).clip(shape)
                    .background(if (on) Brand.tint(Brand.accent) else Color.Transparent)
                    .border(1.dp, if (on) Brand.accent else Color.Transparent, shape)
                    .selectable(selected = on, enabled = enabled, role = Role.RadioButton) { onSelect(i) }.padding(horizontal = 6.dp),
                contentAlignment = Alignment.Center,
            ) {
                AutoSizeText(label, color = if (on) Brand.accent else if (enabled) Brand.muted else Brand.faint, maxFontSize = 13.5.sp,
                    minFontSize = 10.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

/**
 * A tile that is one of a set of states or actions (window open / vent / close, a mode). On = a tint of [color]
 * with its edge; off = a plain card.
 */
@Composable
fun StudioChoiceTile(
    icon: ImageVector, label: String, selected: Boolean, modifier: Modifier = Modifier, color: Color = Brand.accent,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(18.dp)
    val tint = if (selected) color else Brand.text
    Column(
        modifier.heightIn(min = 76.dp).clip(shape).background(if (selected) Brand.tint(color) else Brand.surface2)
            .border(1.dp, if (selected) color else Color.Transparent, shape)
            .selectable(selected = selected, role = Role.Button, onClick = onClick).padding(horizontal = 6.dp, vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterVertically),
    ) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(22.dp))
        AutoSizeText(label, color = tint, maxFontSize = 13.sp, minFontSize = 9.5.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.fillMaxWidth())
    }
}

/** Minus, a value, plus. [minusDescription] / [plusDescription] are what a screen reader says for the buttons. */
@Composable
fun StudioStepper(
    value: String, minus: String, plus: String, minusDescription: String, plusDescription: String, modifier: Modifier = Modifier,
    minusEnabled: Boolean = true, plusEnabled: Boolean = true, onMinus: () -> Unit, onPlus: () -> Unit,
) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        StudioStepButton(minus, minusDescription, minusEnabled, onMinus)
        Text(value, fontFamily = Brand.display, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Brand.text,
            textAlign = TextAlign.Center, maxLines = 1, softWrap = false, modifier = Modifier.defaultMinSize(minWidth = 76.dp))
        StudioStepButton(plus, plusDescription, plusEnabled, onPlus)
    }
}

@Composable
private fun StudioStepButton(symbol: String, description: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.size(44.dp).clip(RoundedCornerShape(14.dp)).background(Brand.surface2)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick).semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) { Text(symbol, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, color = if (enabled) Brand.text else Brand.faint) }
}

/** A small label over a value: one figure of a row of figures. The value shrinks to stay on one line. */
@Composable
fun StudioStat(label: String, value: String, modifier: Modifier = Modifier, color: Color = Brand.text, sub: String? = null) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(label, fontSize = 12.sp, color = Brand.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        AutoSizeText(value, color = color, maxFontSize = 16.sp, minFontSize = 11.sp, fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Start, modifier = Modifier.fillMaxWidth())
        if (sub != null) Text(sub, fontSize = 12.sp, color = Brand.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** A tinted note: an explanation, a warning ([color] = energy) or an error ([color] = crit). */
@Composable
fun StudioNote(text: String, modifier: Modifier = Modifier, color: Color = Brand.accent, icon: ImageVector? = null) {
    val shape = RoundedCornerShape(16.dp)
    Row(
        modifier.fillMaxWidth().clip(shape).background(Brand.tint(color)).border(1.dp, color.copy(alpha = 0.45f), shape).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (icon != null) Icon(icon, null, tint = color, modifier = Modifier.size(20.dp))
        Text(text, fontSize = 13.sp, color = Brand.text, modifier = Modifier.weight(1f))
    }
}

/** The app's slider: accent track and a round thumb ringed in the card colour. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StudioSlider(
    value: Float, onValueChange: (Float) -> Unit, modifier: Modifier = Modifier,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f, steps: Int = 0, enabled: Boolean = true,
    color: Color = Brand.accent, onValueChangeFinished: (() -> Unit)? = null,
) {
    Slider(
        value = value, onValueChange = onValueChange, modifier = modifier, enabled = enabled, valueRange = valueRange, steps = steps,
        onValueChangeFinished = onValueChangeFinished,
        colors = SliderDefaults.colors(thumbColor = color, activeTrackColor = color, inactiveTrackColor = Brand.surface3),
        thumb = {
            Box(Modifier.size(24.dp).clip(CircleShape).background(Brand.surface).padding(3.dp).clip(CircleShape)
                .background(if (enabled) color else Brand.faint))
        },
        track = { st ->
            val span = (st.valueRange.endInclusive - st.valueRange.start).takeIf { it > 0f } ?: 1f
            val f = ((st.value - st.valueRange.start) / span).coerceIn(0f, 1f)
            Box(Modifier.fillMaxWidth().height(6.dp).clip(CircleShape).background(Brand.surface3)) {
                Box(Modifier.fillMaxWidth(f).height(6.dp).clip(CircleShape).background(if (enabled) color else Brand.faint))
            }
        },
    )
}

/** The app's text field: rounded, filled with the raised panel colour, accent outline when focused. */
@Composable
fun StudioTextField(
    value: String, onValueChange: (String) -> Unit, label: String, modifier: Modifier = Modifier,
    singleLine: Boolean = true, enabled: Boolean = true, isError: Boolean = false,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    supportingText: String? = null,
    trailingIcon: (@Composable () -> Unit)? = null,
) {
    OutlinedTextField(
        value = value, onValueChange = onValueChange, modifier = modifier.fillMaxWidth(), singleLine = singleLine, enabled = enabled,
        isError = isError, label = { Text(label) }, keyboardOptions = keyboardOptions, visualTransformation = visualTransformation,
        supportingText = supportingText?.let { { Text(it) } }, trailingIcon = trailingIcon, shape = RoundedCornerShape(16.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedContainerColor = Brand.surface2, unfocusedContainerColor = Brand.surface2, disabledContainerColor = Brand.surface2,
            errorContainerColor = Brand.surface2,
            focusedBorderColor = Brand.accent, unfocusedBorderColor = Color.Transparent, disabledBorderColor = Color.Transparent,
            errorBorderColor = Brand.crit,
            focusedLabelColor = Brand.accent, unfocusedLabelColor = Brand.muted, cursorColor = Brand.accent,
            focusedTextColor = Brand.text, unfocusedTextColor = Brand.text,
        ),
    )
}

/**
 * A bottom sheet in the Studio look: page-coloured ground so the cards inside stand out, a title row, content
 * that scrolls and stays clear of the system navigation bar.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StudioSheet(
    onDismiss: () -> Unit, title: String, subtitle: String? = null,
    trailing: (@Composable () -> Unit)? = null, content: @Composable ColumnScope.() -> Unit,
) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet, containerColor = Brand.bg) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(horizontal = 12.dp).padding(bottom = 22.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            StudioSheetHeader(title, subtitle, trailing)
            content()
        }
    }
}

/** The title row of a sheet (for sheets that build their own ModalBottomSheet). */
@Composable
fun StudioSheetHeader(title: String, subtitle: String? = null, trailing: (@Composable () -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, fontFamily = Brand.display, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Brand.text)
            if (subtitle != null) Text(subtitle, fontSize = 13.sp, color = Brand.muted)
        }
        trailing?.invoke()
    }
}
