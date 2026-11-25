package com.openzeekr.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.collapse
import androidx.compose.ui.semantics.expand
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openzeekr.app.R
import com.openzeekr.app.ui.theme.Brand

/*
 * The Settings tab in the Studio look: a page header, then one card per subject (account, appearance, units,
 * region, the bring-your-own-keys cards of a clean build, logs and diagnostics, about), the version as a quiet
 * line that takes the developer-mode taps, and under it the developer card once that mode is on.
 *
 * It is layout only. SettingsScreen owns the state, the actions and the dialogs for both looks and hands them
 * in here, so the two cannot drift apart in what they do.
 */

/** Everything the Studio page draws, already resolved by SettingsScreen. */
internal class StudioSettingsState(
    val loggedIn: Boolean,
    /** The account's email, or "Signed in" / "Not signed in". */
    val accountTitle: String,
    /** "ID 123 · VIN ••••1234" when signed in, the invitation to sign in otherwise. */
    val accountLine: String,
    val initial: Char?,
    /** The credentials being typed. */
    val email: String,
    val password: String,
    val signingIn: Boolean,
    val canSignIn: Boolean,
    val signInLabel: String,
    val units: List<UnitChoice>,
    val logHttp: Boolean,
    val logBle: Boolean,
    val devMode: Boolean,
    /** The result of the last action, and the card it belongs to. */
    val status: String,
    val statusArea: SettingsStatusArea,
    val version: String,
    val build: String,
    /** The newer release that was found, or null. */
    val updateVersion: String?,
)

internal class StudioSettingsActions(
    val onEmail: (String) -> Unit,
    val onPassword: (String) -> Unit,
    val onSignIn: () -> Unit,
    val onCheckSharedCars: () -> Unit,
    val onSignOut: () -> Unit,
    val onHttpLogging: (Boolean) -> Unit,
    val onBleLogging: (Boolean) -> Unit,
    val onPing: () -> Unit,
    val onCcc: () -> Unit,
    val onHeroLab: () -> Unit,
    val onDevModeOff: () -> Unit,
    val onVersionTap: () -> Unit,
    val onOpenUpdate: () -> Unit,
    val onCheckUpdates: () -> Unit,
    val onDonate: () -> Unit,
)

/**
 * The page. [appearance], [region], [secrets] and [logViewer] are the parts that keep their own state in
 * SettingsScreen (the theme tiles, the region card, the two cards of a clean build, the live log); [secrets]
 * is null in a build with its keys baked in.
 */
@Composable
internal fun StudioSettingsPage(
    modifier: Modifier,
    s: StudioSettingsState,
    a: StudioSettingsActions,
    appearance: @Composable () -> Unit,
    region: @Composable () -> Unit,
    secrets: (@Composable () -> Unit)?,
    logViewer: @Composable () -> Unit,
) {
    fun statusFor(area: SettingsStatusArea): String? = s.status.takeIf { s.statusArea == area && it.isNotBlank() }

    StudioPage(modifier) {
        StudioPageHeader(stringResource(R.string.nav_tab_settings), s.accountTitle)

        // ---- account ----
        StudioCard {
            if (s.loggedIn) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Box(
                        Modifier.size(44.dp).clip(CircleShape).background(Brand.tint(Brand.accent)).border(1.dp, Brand.accent, CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (s.initial != null) Text("${s.initial}", fontFamily = Brand.display, fontSize = 19.sp, fontWeight = FontWeight.Bold,
                            color = Brand.accent, maxLines = 1)
                        else Icon(Icons.Filled.Person, null, tint = Brand.accent, modifier = Modifier.size(24.dp))
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(s.accountTitle, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Brand.text, maxLines = 2,
                            overflow = TextOverflow.Ellipsis)
                        Text(s.accountLine, fontSize = 12.5.sp, color = Brand.muted)
                    }
                }
                statusFor(SettingsStatusArea.Account)?.let { StatusNote(it) }
                StudioButton(stringResource(R.string.settings_check_for_shared_cars), Modifier.fillMaxWidth(), StudioButtonStyle.Tonal,
                    onClick = a.onCheckSharedCars)
                StudioButton(stringResource(R.string.settings_sign_out), Modifier.fillMaxWidth(), StudioButtonStyle.Danger, onClick = a.onSignOut)
            } else {
                StudioCardTitle(stringResource(R.string.settings_sign_in), s.accountLine)
                LoginField(s.email, a.onEmail, LoginFieldKind.Email, Modifier.fillMaxWidth())
                LoginField(s.password, a.onPassword, LoginFieldKind.Password, Modifier.fillMaxWidth(), onDone = a.onSignIn)
                statusFor(SettingsStatusArea.Account)?.let { StatusNote(it) }
                StudioButton(s.signInLabel, Modifier.fillMaxWidth(), enabled = s.canSignIn || s.signingIn, busy = s.signingIn, onClick = a.onSignIn)
            }
        }

        // ---- appearance ----
        StudioCard {
            StudioCardTitle(stringResource(R.string.settings_appearance), stringResource(R.string.settings_appearance_hint))
            appearance()
        }

        // ---- units ----
        StudioCard {
            StudioCardTitle(stringResource(R.string.settings_units))
            s.units.forEach { u ->
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(u.label, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Brand.muted)
                    StudioSegmented(u.options.map { it.second }, u.selectedIndex) { i -> u.onSelect(u.options[i].first) }
                }
            }
        }

        region()
        secrets?.invoke()

        // ---- logs and diagnostics ----
        StudioCard {
            StudioCardTitle(stringResource(R.string.settings_studio_diagnostics))
            StudioSwitchRow(stringResource(R.string.settings_http_logging), s.logHttp,
                subtitle = stringResource(R.string.settings_http_logging_desc), onCheckedChange = a.onHttpLogging)
            StudioSwitchRow(stringResource(R.string.settings_ble_logging), s.logBle,
                subtitle = stringResource(R.string.settings_ble_logging_desc), onCheckedChange = a.onBleLogging)
            if (s.logHttp || s.logBle) logViewer()
            StudioDivider()
            StudioButton(stringResource(R.string.settings_ping_vehicle_btn), Modifier.fillMaxWidth(), StudioButtonStyle.Tonal, onClick = a.onPing)
            StudioButton(stringResource(R.string.settings_ccc_btn), Modifier.fillMaxWidth(), StudioButtonStyle.Tonal, onClick = a.onCcc)
            statusFor(SettingsStatusArea.Diagnostics)?.let { StatusNote(it) }
        }

        // ---- about ----
        StudioCard {
            StudioCardTitle(stringResource(R.string.settings_about))
            if (s.updateVersion != null) StudioRow(
                stringResource(R.string.settings_update_available),
                subtitle = stringResource(R.string.settings_update_download, s.updateVersion),
                icon = Icons.Filled.Download, iconTint = Brand.accent, titleColor = Brand.accent, onClick = a.onOpenUpdate,
            ) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = Brand.faint, modifier = Modifier.size(22.dp)) }
            StudioButton(stringResource(R.string.settings_check_updates_btn), Modifier.fillMaxWidth(), StudioButtonStyle.Tonal, onClick = a.onCheckUpdates)
            statusFor(SettingsStatusArea.About)?.let { StatusNote(it) }
            Text(stringResource(R.string.settings_about_blurb), fontSize = 13.sp, color = Brand.muted)
            Text(stringResource(R.string.settings_credits), fontSize = 12.5.sp, color = Brand.muted)
            StudioDivider()
            Row(
                Modifier.fillMaxWidth().heightIn(min = 44.dp).clip(RoundedCornerShape(12.dp)).clickable(role = Role.Button, onClick = a.onDonate),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Icon(Icons.Filled.Favorite, null, tint = Brand.crit, modifier = Modifier.size(18.dp))
                // The Classic layout pads this label with leading spaces; here the row's spacing does that.
                Text(stringResource(R.string.settings_donate).trim(), fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold, color = Brand.accent,
                    modifier = Modifier.weight(1f))
            }
        }

        // ---- version: ten taps turn developer mode on ----
        // The third line is always laid out, empty or not, and the developer card comes AFTER this block: nothing
        // may appear or move under a finger that is still tapping.
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).clickable(onClick = a.onVersionTap).heightIn(min = 56.dp)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterVertically),
        ) {
            Text(stringResource(R.string.settings_studio_version_line, s.version), fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                color = Brand.muted, textAlign = TextAlign.Center)
            Text(s.build, fontSize = 12.sp, color = Brand.muted, textAlign = TextAlign.Center)
            Text(statusFor(SettingsStatusArea.Developer).orEmpty(), fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, color = Brand.accent,
                textAlign = TextAlign.Center, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        }

        // ---- developer mode only ----
        if (s.devMode) StudioCard {
            StudioCardTitle(stringResource(R.string.settings_studio_dev_tools), stringResource(R.string.settings_studio_dev_hint)) {
                StudioPill(stringResource(R.string.settings_dev_badge), Brand.energy)
            }
            // Shown only while the mode is on, so the switch is always on: switching it off is the way out.
            StudioSwitchRow(stringResource(R.string.settings_developer_mode), true,
                subtitle = stringResource(R.string.settings_dev_mode_on_tap), onCheckedChange = { a.onDevModeOff() })
            StudioDivider()
            StudioNavRow(stringResource(R.string.settings_hero_lab), onClick = a.onHeroLab)
        }
    }
}

// ----------------------------------------------------------------------------------------------- region

@Composable
internal fun StudioRegionCard(
    codes: List<String>,
    selected: Int,
    name: String,
    verified: Boolean,
    /** The full note about per-region servers and keys. */
    description: String,
    /** Show [description] on the card itself rather than under "Advanced". */
    descriptionAlwaysShown: Boolean,
    showAdvanced: Boolean,
    onToggleAdvanced: () -> Unit,
    hostFields: List<SettingsField>,
    onSelect: (Int) -> Unit,
) {
    StudioCard {
        StudioCardTitle(stringResource(R.string.settings_region), stringResource(R.string.settings_studio_region_hint))
        StudioSegmented(codes, selected, onSelect = onSelect)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(name, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Brand.text, modifier = Modifier.weight(1f))
            StudioPill(
                stringResource(if (verified) R.string.settings_studio_region_verified else R.string.settings_studio_region_experimental),
                if (verified) Brand.good else Brand.energy,
            )
        }
        if (!verified) StudioNote(stringResource(R.string.settings_region_unverified_warn), color = Brand.energy)
        if (descriptionAlwaysShown) Text(description, fontSize = 12.5.sp, color = Brand.muted)
        StudioDivider()
        ExpandRow(stringResource(R.string.settings_advanced_hosts), showAdvanced, onToggleAdvanced)
        if (showAdvanced) {
            if (!descriptionAlwaysShown) Text(description, fontSize = 12.5.sp, color = Brand.muted)
            hostFields.forEach { SettingsTextField(it) }
            Text(stringResource(R.string.settings_region_reset_note), fontSize = 12.5.sp, color = Brand.muted)
        }
    }
}

// ------------------------------------------------------------------------ clean builds: secrets, import

@Composable
internal fun StudioSecretsCard(expanded: Boolean, onToggle: () -> Unit, fields: List<SettingsField>, status: String?, onSave: () -> Unit) {
    StudioCard {
        ExpandRow(stringResource(R.string.settings_app_secrets), expanded, onToggle, hint = stringResource(R.string.settings_app_secrets_desc), heading = true)
        if (expanded) {
            fields.forEach { SettingsTextField(it) }
            StudioButton(stringResource(R.string.settings_save_secrets), Modifier.fillMaxWidth(), onClick = onSave)
        }
        if (status != null) StatusNote(status)
    }
}

@Composable
internal fun StudioImportExportCard(
    importText: String,
    onImportText: (String) -> Unit,
    includeCredentials: Boolean,
    onIncludeCredentials: (Boolean) -> Unit,
    message: String,
    onImport: () -> Unit,
    onExport: () -> Unit,
) {
    StudioCard {
        StudioCardTitle(stringResource(R.string.settings_import_export))
        StudioTextField(importText, onImportText, stringResource(R.string.settings_import_hint), Modifier.heightIn(min = 112.dp), singleLine = false)
        StudioButton(stringResource(R.string.settings_import_btn), Modifier.fillMaxWidth(), StudioButtonStyle.Tonal, onClick = onImport)
        StudioDivider()
        // The account password and the session tokens stay out of the export unless this is on.
        StudioSwitchRow(stringResource(R.string.settings_export_include_credentials), includeCredentials, onCheckedChange = onIncludeCredentials)
        StudioButton(stringResource(R.string.settings_export_btn), Modifier.fillMaxWidth(), StudioButtonStyle.Outline, onClick = onExport)
        if (message.isNotBlank()) StatusNote(message)
    }
}

// -------------------------------------------------------------------------------------------- live log

/** The on-device log, inside the diagnostics card: [lines] is the tail that is shown, [count] the size of the whole log. */
@Composable
internal fun StudioLogPanel(count: Int, lines: List<String>, message: String, onShare: () -> Unit, onCopy: () -> Unit, onClear: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Brand.surface2).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(stringResource(R.string.settings_logs_count, count), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Brand.text)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            LogAction(Icons.Filled.Share, stringResource(R.string.settings_log_share), Modifier.weight(1f), onShare)
            LogAction(Icons.Filled.ContentCopy, stringResource(R.string.settings_log_copy), Modifier.weight(1f), onCopy)
            LogAction(Icons.Filled.DeleteOutline, stringResource(R.string.settings_log_clear), Modifier.weight(1f), onClear)
        }
        // The result of the last share or copy, or else how to send a log.
        Text(message.ifBlank { stringResource(R.string.settings_log_share_hint) }, fontSize = 12.sp, color = Brand.muted,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        if (lines.isEmpty()) Text(stringResource(R.string.settings_log_empty), fontSize = 12.5.sp, color = Brand.muted)
        else Column {
            lines.forEach { Text(it, fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = Brand.muted) }
        }
    }
}

/** One of the three log actions: an icon over a label that shrinks to fit a third of the row. */
@Composable
private fun LogAction(icon: ImageVector, label: String, modifier: Modifier, onClick: () -> Unit) {
    Column(
        modifier.heightIn(min = 56.dp).clip(RoundedCornerShape(14.dp)).background(Brand.surface)
            .clickable(role = Role.Button, onClick = onClick).padding(horizontal = 6.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically),
    ) {
        Icon(icon, null, tint = Brand.text, modifier = Modifier.size(20.dp))
        AutoSizeText(label, color = Brand.text, maxFontSize = 13.sp, minFontSize = 9.5.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.fillMaxWidth())
    }
}

// ---------------------------------------------------------------------------------------- small pieces

/** The result of an action, shown in the card that caused it; a screen reader announces it when it changes. */
@Composable
private fun StatusNote(text: String) {
    StudioNote(text, Modifier.semantics { liveRegion = LiveRegionMode.Polite })
}

/** A row that opens and closes the part of the card under it. [heading] draws the title as a card title. */
@Composable
private fun ExpandRow(title: String, expanded: Boolean, onToggle: () -> Unit, hint: String? = null, heading: Boolean = false) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(12.dp))
            .clickable(role = Role.Button, onClick = onToggle)
            // Lets a screen reader say "expanded" / "collapsed" in its own words.
            .semantics { if (expanded) collapse { onToggle(); true } else expand { onToggle(); true } },
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            if (heading) Text(title, fontFamily = Brand.display, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = Brand.text)
            else Text(title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Brand.text)
            if (hint != null) Text(hint, fontSize = 12.5.sp, color = Brand.muted)
        }
        Icon(if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, null, tint = Brand.muted, modifier = Modifier.size(24.dp))
    }
}

/** A host override or a secret as a Studio text field. Secrets are masked and may wrap, as in the Classic layout. */
@Composable
private fun SettingsTextField(f: SettingsField) {
    StudioTextField(
        f.value, f.onChange, f.label, singleLine = !f.secret, isError = f.isError,
        visualTransformation = if (f.secret) PasswordVisualTransformation() else VisualTransformation.None,
        supportingText = f.supportingText,
    )
}
