package com.openzeekr.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openzeekr.app.R
import com.openzeekr.app.ui.theme.Brand

/*
 * First-run setup in the Studio look: one calm column per step - the app mark and how far along you are, a
 * headline, the step in a card, and the step's main action as a full-width button at the bottom of the screen.
 *
 * Layout only. The steps in OnboardingScreen own their state and actions for both looks; in Studio each of them
 * draws its whole page through one of the functions below, so its main action (log in, set up the key) can be
 * the bottom button.
 */

/** Where the wizard is and how to move, handed to the step that is on screen. */
internal class OnbStudioNav(
    val index: Int,
    val count: Int,
    /** Null on the first step. */
    val onBack: (() -> Unit)?,
    val onNext: () -> Unit,
    /** Leaves the wizard (Finish and Skip). */
    val onDone: () -> Unit,
)

@Composable
internal fun StudioOnbWelcome(nav: OnbStudioNav) {
    OnbStudioPage(
        nav, stringResource(R.string.onb_welcome_title), stringResource(R.string.onb_welcome_body),
        primary = stringResource(R.string.onb_get_started), onPrimary = nav.onNext,
    ) {
        StudioCard(spacing = 14.dp) {
            OnbNumberedRow(stringResource(R.string.onb_step_num_1), stringResource(R.string.onb_bullet_login_title), stringResource(R.string.onb_bullet_login_body))
            StudioDivider()
            OnbNumberedRow(stringResource(R.string.onb_step_num_2), stringResource(R.string.onb_bullet_key_title), stringResource(R.string.onb_bullet_key_body))
            StudioDivider()
            OnbNumberedRow(stringResource(R.string.onb_step_num_3), stringResource(R.string.onb_bullet_done_title), stringResource(R.string.onb_bullet_done_body))
        }
        OnbQuietLine(stringResource(R.string.onb_welcome_authorized))
    }
}

@Composable
internal fun StudioOnbRegion(
    nav: OnbStudioNav,
    codes: List<String>, currentCode: String, currentName: String, verified: Boolean, extractorRegion: String,
    onSelect: (String) -> Unit,
) {
    OnbStudioPage(
        nav, stringResource(R.string.onb_region_title), stringResource(R.string.onb_studio_region_lead),
        primary = stringResource(R.string.onb_next), onPrimary = nav.onNext,
    ) {
        StudioCard {
            StudioSegmented(codes, codes.indexOf(currentCode)) { onSelect(codes[it]) }
            StudioRow(currentName) {
                if (verified) StudioPill(stringResource(R.string.onb_studio_region_verified), Brand.good)
                else StudioPill(stringResource(R.string.onb_studio_region_experimental), Brand.energy)
            }
            if (!verified) StudioNote(stringResource(R.string.onb_region_unverified_warn), color = Brand.energy)
        }
        StudioNote(stringResource(R.string.onb_studio_region_keys_note, extractorRegion), icon = Icons.Filled.Key)
    }
}

@Composable
internal fun StudioOnbSecrets(
    nav: OnbStudioNav,
    text: String, onText: (String) -> Unit, status: String, valid: Boolean, onImport: () -> Unit,
) {
    OnbStudioPage(
        nav, stringResource(R.string.onb_secrets_title), stringResource(R.string.onb_secrets_desc),
        primary = stringResource(R.string.onb_next), onPrimary = nav.onNext, primaryEnabled = valid,
    ) {
        StudioCard {
            // Room for a few lines of JSON; a long paste scrolls inside the box instead of pushing the button away.
            StudioTextField(text, onText, stringResource(R.string.onb_secrets_hint), Modifier.heightIn(min = 132.dp, max = 260.dp), singleLine = false)
            // Until the keys are in, importing is the step's one action; after that it is Next.
            StudioButton(stringResource(R.string.onb_secrets_import_btn), Modifier.fillMaxWidth(),
                if (valid) StudioButtonStyle.Tonal else StudioButtonStyle.Primary, onClick = onImport)
            if (status.isNotBlank()) Text(status, fontSize = 13.sp, color = Brand.text)
        }
        if (valid) StudioNote(stringResource(R.string.onb_secrets_keys_valid), color = Brand.good)
    }
}

internal class StudioOnbLoginState(
    val loggedIn: Boolean,
    /** "name@mail  ·  VIN ..." of the signed-in account. */
    val accountLine: String,
    val email: String,
    val password: String,
    val busy: Boolean,
    /** The last sign-in result ("Logging in…", "Login ✓", "Login ✗ reason"), or blank. */
    val status: String,
    val failed: Boolean,
    /** What sharing the diagnostic log answered, or blank before it was tried. */
    val shareMsg: String,
)

@Composable
internal fun StudioOnbLogin(
    nav: OnbStudioNav, s: StudioOnbLoginState,
    onEmail: (String) -> Unit, onPassword: (String) -> Unit, onSignIn: () -> Unit, onShareLog: () -> Unit,
) {
    OnbStudioPage(
        nav, stringResource(R.string.onb_login_title), stringResource(R.string.onb_bullet_login_body),
        // The bottom button is the step's action: sign in first, then move on.
        primary = stringResource(if (s.loggedIn) R.string.onb_next else if (s.busy) R.string.onb_login_logging_in else R.string.onb_login_title),
        onPrimary = if (s.loggedIn) nav.onNext else onSignIn,
        primaryEnabled = s.loggedIn || s.busy || (s.email.isNotBlank() && s.password.isNotBlank()),
        primaryBusy = !s.loggedIn && s.busy,
    ) {
        if (s.loggedIn) {
            StudioCard {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    StudioIconDot(Icons.Filled.Check, tint = Brand.good, onTint = Brand.onGood)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(stringResource(R.string.onb_login_logged_in), fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Brand.good)
                        Text(s.accountLine, fontSize = 13.sp, color = Brand.muted)
                    }
                }
            }
            OnbQuietLine(stringResource(R.string.onb_login_tap_next))
        } else {
            StudioCard {
                LoginField(s.email, onEmail, LoginFieldKind.Email, Modifier.fillMaxWidth())
                LoginField(s.password, onPassword, LoginFieldKind.Password, Modifier.fillMaxWidth(), onDone = onSignIn)
                if (s.status.isNotBlank()) {
                    if (s.failed && !s.busy) StudioNote(s.status, color = Brand.crit)
                    else Text(s.status, fontSize = 13.sp, color = Brand.muted)
                }
            }
            // After a failed sign-in: send us the captured log, so we can see what the server answered.
            if (s.failed) StudioCard {
                Text(s.shareMsg.ifBlank { stringResource(R.string.onb_login_share_hint) }, fontSize = 13.sp, color = Brand.muted)
                StudioButton(stringResource(R.string.onb_login_share_log), Modifier.fillMaxWidth(), StudioButtonStyle.Outline,
                    icon = Icons.Filled.Share, onClick = onShareLog)
            }
        }
    }
}

@Composable
internal fun StudioOnbKey(
    nav: OnbStudioNav,
    provisioned: Boolean, busy: Boolean,
    /** The provisioning step's own progress or error line, if it has one. */
    message: String?,
    onSetUp: () -> Unit,
) {
    OnbStudioPage(
        nav, stringResource(R.string.onb_key_title), stringResource(R.string.onb_key_desc),
        // The bottom button is the step's action: set the key up, then finish. Skipping is always offered.
        primary = stringResource(if (provisioned) R.string.onb_finish else if (busy) R.string.onb_key_setting_up else R.string.onb_key_setup_btn),
        onPrimary = if (provisioned) nav.onDone else onSetUp,
        primaryBusy = !provisioned && busy,
        secondary = stringResource(R.string.onb_skip_for_now), onSecondary = nav.onDone,
    ) {
        StudioCard {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                StudioIconDot(Icons.Filled.Key, tint = if (provisioned) Brand.good else null, onTint = Brand.onGood)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        stringResource(if (provisioned) R.string.onb_key_ready else if (busy) R.string.onb_key_setting_up else R.string.onb_studio_key_not_set),
                        fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = if (provisioned) Brand.good else Brand.text,
                    )
                    if (!provisioned && message != null) Text(stringResource(R.string.onb_key_prov_msg, message), fontSize = 12.5.sp, color = Brand.muted)
                }
                if (!provisioned && busy) CircularProgressIndicator(Modifier.size(22.dp), color = Brand.accent, strokeWidth = 2.dp)
            }
        }
        OnbQuietLine(stringResource(R.string.onb_key_finish_hint))
    }
}

// --------------------------------------------------------------------------------------------- the page

/**
 * One step of the wizard as a whole screen. The actions stay at the bottom of the screen while the step is
 * short, and follow the content when it is not (large font, small screen, keyboard up): the column scrolls and
 * is at least as tall as the screen, so nothing is ever squeezed or cut off.
 */
@Composable
private fun OnbStudioPage(
    nav: OnbStudioNav, title: String, lead: String?,
    primary: String, onPrimary: () -> Unit, primaryEnabled: Boolean = true, primaryBusy: Boolean = false,
    secondary: String? = null, onSecondary: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxSize().background(Brand.bg).systemBarsPadding().imePadding()) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).heightIn(min = maxHeight).padding(12.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                // The app mark, and how far along the setup is.
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    BrandBadge(size = 36)
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.app_name), fontFamily = Brand.display, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = Brand.text)
                        Text(stringResource(R.string.onb_setup_step_of, nav.index + 1, nav.count), fontSize = 12.5.sp, color = Brand.muted)
                    }
                }
                OnbProgress(nav.index, nav.count, Modifier.padding(horizontal = 8.dp))
                Column(Modifier.padding(horizontal = 8.dp).padding(top = 10.dp, bottom = 2.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(title, fontFamily = Brand.display, fontSize = 30.sp, lineHeight = 35.sp, fontWeight = FontWeight.Bold, color = Brand.text,
                        modifier = Modifier.semantics { heading() })
                    if (lead != null) Text(lead, fontSize = 14.sp, color = Brand.muted)
                }
                content()
            }
            Column(Modifier.fillMaxWidth().padding(top = 20.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                StudioButton(primary, Modifier.fillMaxWidth(), enabled = primaryEnabled, busy = primaryBusy, onClick = onPrimary)
                if (nav.onBack != null || (secondary != null && onSecondary != null)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        if (nav.onBack != null) OnbTextButton(stringResource(R.string.onb_back), Brand.muted, Modifier.weight(1f, fill = false),
                            icon = Icons.AutoMirrored.Filled.ArrowBack, onClick = nav.onBack)
                        else Box(Modifier.size(1.dp))
                        if (secondary != null && onSecondary != null) OnbTextButton(secondary, Brand.accent, Modifier.weight(1f, fill = false), onClick = onSecondary)
                    }
                }
            }
        }
    }
}

/** The steps as a row of short bars, filled up to the current one. Decoration: the count is written above it. */
@Composable
private fun OnbProgress(index: Int, count: Int, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        repeat(count) { i ->
            Box(Modifier.weight(1f).height(4.dp).clip(CircleShape).background(if (i <= index) Brand.accent else Brand.surface3))
        }
    }
}

/** A quiet secondary action under the main button: plain coloured text, still a full-size touch target. */
@Composable
private fun OnbTextButton(text: String, color: Color, modifier: Modifier = Modifier, icon: ImageVector? = null, onClick: () -> Unit) {
    Row(
        modifier.heightIn(min = 44.dp).clip(RoundedCornerShape(14.dp)).clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (icon != null) Icon(icon, null, tint = color, modifier = Modifier.size(18.dp))
        Text(text, fontSize = 14.5.sp, fontWeight = FontWeight.SemiBold, color = color)
    }
}

/** A numbered point of the welcome list. */
@Composable
private fun OnbNumberedRow(number: String, title: String, body: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(
            Modifier.defaultMinSize(minWidth = 32.dp, minHeight = 32.dp).clip(CircleShape).background(Brand.tint(Brand.accent))
                .border(1.dp, Brand.accent, CircleShape).padding(horizontal = 8.dp, vertical = 4.dp),
            contentAlignment = Alignment.Center,
        ) { Text(number, fontFamily = Brand.display, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Brand.accent) }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Brand.text)
            Text(body, fontSize = 13.sp, color = Brand.muted)
        }
    }
}

/** A line of small print that sits on the page ground under a card. */
@Composable
private fun OnbQuietLine(text: String) {
    Text(text, fontSize = 12.5.sp, color = Brand.muted, modifier = Modifier.padding(horizontal = 8.dp))
}
