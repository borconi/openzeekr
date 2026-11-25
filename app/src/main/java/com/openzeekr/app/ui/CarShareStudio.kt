package com.openzeekr.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openzeekr.app.R
import com.openzeekr.app.net.model.ShareGrant
import com.openzeekr.app.ui.theme.Brand

/*
 * Car sharing in the Studio look. Layout only: CarShareScreen owns the list, the invite and the revoke for both
 * looks (and the "revoke access?" question) and hands them in here.
 */

internal class StudioCarShareState(
    val shares: List<ShareGrant>,
    val loading: Boolean,
    val error: String?,
    /** The invite form: the address typed so far, whether it may be sent, and whether it is being sent. */
    val email: String,
    val canSend: Boolean,
    val sharing: Boolean,
)

internal class StudioCarShareActions(
    val onBack: () -> Unit,
    val onReload: () -> Unit,
    val onEmail: (String) -> Unit,
    val onShare: () -> Unit,
    /** Asks first: the screen shows its "revoke access?" question. */
    val onRevoke: (ShareGrant) -> Unit,
)

@Composable
internal fun StudioCarShare(s: StudioCarShareState, a: StudioCarShareActions, modifier: Modifier = Modifier) {
    StudioPage(modifier) {
        PagesBackHeader(
            title = stringResource(R.string.carshare_title),
            backDescription = stringResource(R.string.carshare_cd_back),
            onBack = a.onBack,
            subtitle = if (s.shares.isNotEmpty()) pluralStringResource(R.plurals.carshare_studio_count, s.shares.size, s.shares.size) else null,
        )

        // Invite someone: the one thing to do on this page, so it leads.
        StudioCard {
            StudioCardTitle(stringResource(R.string.carshare_share_title), stringResource(R.string.carshare_share_body))
            StudioTextField(
                s.email, a.onEmail, stringResource(R.string.carshare_email_label), enabled = !s.sharing,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Done),
            )
            StudioButton(
                stringResource(if (s.sharing) R.string.carshare_sharing else R.string.carshare_share), Modifier.fillMaxWidth(),
                icon = Icons.Filled.PersonAdd, enabled = s.canSend || s.sharing, busy = s.sharing, onClick = a.onShare,
            )
        }

        when {
            s.loading && s.shares.isEmpty() -> StudioCard {
                Box(Modifier.fillMaxWidth().padding(vertical = 24.dp), Alignment.Center) { CircularProgressIndicator(color = Brand.accent) }
            }
            s.error != null && s.shares.isEmpty() -> PagesEmptyCard(
                Icons.Filled.Group, stringResource(R.string.carshare_load_failed_title),
                s.error.ifBlank { stringResource(R.string.carshare_unknown_error) }, tone = Brand.crit, onTone = Brand.onCrit,
                action = stringResource(R.string.carshare_reload), onAction = a.onReload,
            )
            s.shares.isEmpty() -> PagesEmptyCard(
                Icons.Filled.Group, stringResource(R.string.carshare_empty_title), stringResource(R.string.carshare_studio_empty_body),
                action = stringResource(R.string.carshare_reload), onAction = a.onReload,
            )
            else -> {
                Row(Modifier.fillMaxWidth().padding(start = 8.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(stringResource(R.string.carshare_studio_people), fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                        color = Brand.muted, modifier = Modifier.weight(1f))
                    StudioIconButton(Icons.Filled.Refresh, stringResource(R.string.carshare_reload), enabled = !s.loading, onClick = a.onReload)
                }
                s.shares.forEach { g -> key(g.shareId) { StudioShareCard(g) { a.onRevoke(g) } } }
            }
        }
    }
}

/** One person the car is shared with: who, what they may do, until when, and the way to end it. */
@Composable
private fun StudioShareCard(g: ShareGrant, onRevoke: () -> Unit) {
    val context = LocalContext.current
    StudioCard {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            StudioIconDot(Icons.Filled.Person)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(g.toName?.takeIf { it.isNotBlank() } ?: g.toContact ?: stringResource(R.string.carshare_shared_user),
                    fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Brand.text)
                g.toContact?.takeIf { it != g.toName }?.let { Text(it, fontSize = 13.sp, color = Brand.muted) }
            }
        }
        g.functionNames?.takeIf { it.isNotBlank() }?.let { Text(it, fontSize = 12.5.sp, color = Brand.muted) }
        carShareUntil(context, g)?.let { StudioPill(it) }
        StudioButton(stringResource(R.string.carshare_revoke), Modifier.fillMaxWidth(), StudioButtonStyle.Danger, onClick = onRevoke)
    }
}
