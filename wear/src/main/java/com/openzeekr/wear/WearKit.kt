package com.openzeekr.wear

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material.CircularProgressIndicator
import androidx.wear.compose.material.Icon
import androidx.wear.compose.material.Text

/*
 * Building blocks of the watch page, the small-screen relatives of the phone's StudioKit.
 * Everything is as wide as the list allows and grows with its text (no fixed heights around text), so a
 * larger system font or a small round screen reflows instead of clipping. Touch targets are 48dp or more,
 * except the two stepper buttons (44dp), which sit apart with the value between them.
 */

/** A raised panel with a hairline edge: one subject per card. */
@Composable
fun OzCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(22.dp)
    Column(
        modifier.fillMaxWidth().clip(shape).background(Oz.surface).border(1.dp, Oz.line, shape).padding(horizontal = 12.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp), content = content,
    )
}

/** Card heading: the subject, an optional quiet detail under it, and its state as a word in its colour. */
@Composable
fun OzCardTitle(title: String, hint: String? = null, state: String? = null, stateColor: Color = Oz.muted) {
    // The title has a line to itself; the state word and the detail share the next one. A state word can be
    // long in some languages ("plugged in" is 16 letters in Bulgarian), and beside the title it would squeeze
    // the title away on a small watch. Here the state keeps its width and the detail gives way first.
    Column(Modifier.fillMaxWidth().padding(horizontal = 2.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, fontFamily = Oz.display, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Oz.text,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (state != null || hint != null) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (state != null) Text(
                state, fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold, color = stateColor, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false).clip(RoundedCornerShape(9.dp)).background(Oz.tint(stateColor))
                    .padding(horizontal = 8.dp, vertical = 3.dp),
            )
            if (hint != null) Text(hint, fontSize = 11.5.sp, color = Oz.muted, maxLines = 1, softWrap = false)
        }
    }
}

enum class OzButtonStyle {
    /** The main action: solid accent. */
    Primary,
    /** A secondary action: raised panel. */
    Tonal,
    /** Something that is running and can be stopped: green tint. */
    Good,
    /** Stops or removes something: red tint. */
    Danger,
}

/** A full-width pill button with an icon. [compact] is for the few places where a smaller one is right. */
@Composable
fun OzButton(
    label: String, icon: ImageVector, style: OzButtonStyle, modifier: Modifier = Modifier.fillMaxWidth(),
    enabled: Boolean = true, compact: Boolean = false, onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(24.dp)
    val fill = when {
        !enabled -> Oz.surface2
        style == OzButtonStyle.Primary -> Oz.accent
        style == OzButtonStyle.Good -> Oz.tint(Oz.good)
        style == OzButtonStyle.Danger -> Oz.tint(Oz.crit)
        else -> Oz.surface3
    }
    val ink = when {
        !enabled -> Oz.faint
        style == OzButtonStyle.Primary -> Oz.onAccent
        style == OzButtonStyle.Good -> Oz.good
        style == OzButtonStyle.Danger -> Oz.crit
        else -> Oz.text
    }
    val edge = when {
        !enabled -> Color.Transparent
        style == OzButtonStyle.Good -> Oz.good.copy(alpha = 0.6f)
        style == OzButtonStyle.Danger -> Oz.crit.copy(alpha = 0.6f)
        else -> Color.Transparent
    }
    Row(
        modifier.heightIn(min = if (compact) 40.dp else 48.dp).clip(shape).background(fill).border(1.dp, edge, shape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
    ) {
        Icon(icon, contentDescription = null, tint = ink, modifier = Modifier.size(18.dp))
        Text(label, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold, color = ink, textAlign = TextAlign.Center,
            maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

/** Minus, the value, plus. */
@Composable
fun OzStepper(
    value: String, enabled: Boolean, minus: String, plus: String, minusDescription: String, plusDescription: String,
    onMinus: () -> Unit, onPlus: () -> Unit,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        StepButton(minus, minusDescription, enabled, onMinus)
        Text(value, fontFamily = Oz.display, fontSize = 26.sp, fontWeight = FontWeight.Bold, color = if (enabled) Oz.text else Oz.faint,
            textAlign = TextAlign.Center, maxLines = 1, softWrap = false, modifier = Modifier.weight(1f))
        StepButton(plus, plusDescription, enabled, onPlus)
    }
}

@Composable
private fun StepButton(symbol: String, description: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.size(44.dp).clip(CircleShape).background(Oz.surface3)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick).semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) { Text(symbol, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, color = if (enabled) Oz.text else Oz.faint) }
}

/** A quiet explanation in a panel. */
@Composable
fun OzNote(text: String) {
    val shape = RoundedCornerShape(18.dp)
    Text(
        text, fontSize = 12.sp, color = Oz.muted, textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().clip(shape).background(Oz.surface).border(1.dp, Oz.line, shape).padding(12.dp),
    )
}

/**
 * The lock, the watch-sized relative of the phone's slide-to-lock: a pill that shows the state in words and
 * colour, with a round badge that carries the lock. One tap toggles it (a slide would fight the watch's own
 * swipe-to-go-back). Locked = a quiet panel with a green badge; unlocked = an amber tint with its edge, so
 * an open car is the state that stands out. The badge shows a spinner while a command is out.
 */
@Composable
fun LockPill(locked: Boolean, busy: Boolean, onToggle: () -> Unit) {
    val shape = RoundedCornerShape(30.dp)
    val state = stringResource(if (locked) R.string.wear_locked else R.string.wear_unlocked)
    val badge = if (locked) Oz.good else Oz.energy
    val onBadge = if (locked) Oz.onGood else Oz.onEnergy
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).clip(shape)
            .background(if (locked) Oz.surface2 else Oz.tint(Oz.energy))
            .border(1.dp, if (locked) Oz.line else Oz.energy, shape)
            .toggleable(value = locked, enabled = !busy, role = Role.Switch) { onToggle() }
            .semantics { stateDescription = state }
            .padding(start = 6.dp, end = 14.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(Modifier.size(44.dp).clip(CircleShape).background(badge), contentAlignment = Alignment.Center) {
            if (busy) CircularProgressIndicator(Modifier.size(20.dp), indicatorColor = onBadge, trackColor = Color.Transparent, strokeWidth = 2.5.dp)
            else Icon(if (locked) Icons.Filled.Lock else Icons.Filled.LockOpen, contentDescription = null, tint = onBadge, modifier = Modifier.size(22.dp))
        }
        Column(Modifier.weight(1f).defaultMinSize(minHeight = 1.dp)) {
            Text(state, fontFamily = Oz.display, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = Oz.text,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(stringResource(if (locked) R.string.wear_tap_to_unlock else R.string.wear_tap_to_lock), fontSize = 11.5.sp,
                color = Oz.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
