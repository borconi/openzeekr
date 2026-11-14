package com.openzeekr.app.ui

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openzeekr.app.Deps
import com.openzeekr.app.R
import com.openzeekr.app.net.model.ShareGrant
import com.openzeekr.app.remote.CallResult
import com.openzeekr.app.ui.theme.Brand
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Car-sharing management (owner): list who the car is shared with and revoke access, so the owner
 * doesn't have to open the stock app (issue request). Read-only otherwise - inviting new people is a
 * later addition. Endpoints: GET/POST ms-tsp-user-vehicle/.../owner/authorize/{list,cancel}.
 */
@Composable
fun CarShareScreen(deps: Deps, onBack: () -> Unit, snackbar: (String) -> Unit, modifier: Modifier = Modifier) {
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val context = LocalContext.current
    BackHandler { onBack() }
    var shares by remember { mutableStateOf<List<ShareGrant>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var confirm by remember { mutableStateOf<ShareGrant?>(null) }   // revoke confirmation target
    var revoking by remember { mutableStateOf(false) }
    var showShare by remember { mutableStateOf(false) }             // the "share by email" dialog
    var shareEmail by remember { mutableStateOf("") }
    var sharing by remember { mutableStateOf(false) }

    suspend fun load() {
        loading = true; error = null
        when (val r = deps.share.myShares()) {
            is CallResult.Ok -> { shares = r.value; error = null }
            is CallResult.Err -> error = r.message
        }
        loading = false
    }
    LaunchedEffect(Unit) { load() }

    Column(modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Row(
            Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface)
                .padding(start = 6.dp, end = 12.dp, top = 6.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.ui_share_cd_back),
                tint = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(40.dp).clip(CircleShape).clickable { onBack() }.padding(8.dp),
            )
            Text(stringResource(R.string.ui_share_title), fontWeight = FontWeight.Bold, fontSize = 18.sp,
                color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f).padding(start = 4.dp))
            Icon(
                Icons.Filled.PersonAdd, stringResource(R.string.ui_share_cd_share_car),
                tint = Brand.accent,
                modifier = Modifier.size(40.dp).clip(CircleShape)
                    .clickable { shareEmail = ""; showShare = true }.padding(8.dp),
            )
        }

        when {
            loading && shares.isEmpty() -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                CircularProgressIndicator(color = Brand.accent)
            }
            error != null && shares.isEmpty() -> ShareEmpty(stringResource(R.string.ui_share_empty_load_failed), error ?: stringResource(R.string.ui_share_unknown_error)) {
                scope.launch { load() }
            }
            shares.isEmpty() -> ShareEmpty(stringResource(R.string.ui_share_empty_none_title),
                stringResource(R.string.ui_share_empty_none_desc)) {
                scope.launch { load() }
            }
            else -> LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(shares, key = { it.shareId }) { s -> ShareCard(s, onRevoke = { confirm = s }) }
            }
        }
    }

    confirm?.let { s ->
        AlertDialog(
            onDismissRequest = { if (!revoking) confirm = null },
            title = { Text(stringResource(R.string.ui_share_dialog_revoke_title)) },
            text = {
                val targetName = s.toName ?: s.toContact ?: stringResource(R.string.ui_share_this_person)
                Text(stringResource(R.string.ui_share_dialog_revoke_message, targetName))
            },
            confirmButton = {
                TextButton(enabled = !revoking, onClick = {
                    revoking = true
                    scope.launch {
                        when (val r = deps.share.revoke(s)) {
                            is CallResult.Ok -> { snackbar(context.getString(R.string.ui_share_snackbar_revoked)); load() }
                            is CallResult.Err -> snackbar(context.getString(R.string.ui_share_snackbar_revoke_failed, r.message ?: ""))
                        }
                        revoking = false
                        confirm = null
                    }
                }) { Text(if (revoking) stringResource(R.string.ui_share_revoking) else stringResource(R.string.ui_share_revoke)) }
            },
            dismissButton = { TextButton(enabled = !revoking, onClick = { confirm = null }) { Text(stringResource(R.string.ui_share_cancel)) } },
        )
    }

    if (showShare) {
        val canSend = shareEmail.trim().let { it.contains('@') && it.length >= 3 } && !sharing
        AlertDialog(
            onDismissRequest = { if (!sharing) showShare = false },
            title = { Text(stringResource(R.string.ui_share_dialog_share_title)) },
            text = {
                Column {
                    Text(stringResource(R.string.ui_share_dialog_share_desc),
                        color = Brand.muted, fontSize = 13.sp)
                    Spacer(Modifier.size(12.dp))
                    OutlinedTextField(
                        value = shareEmail,
                        onValueChange = { shareEmail = it },
                        singleLine = true,
                        enabled = !sharing,
                        label = { Text(stringResource(R.string.ui_share_email_label)) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                TextButton(enabled = canSend, onClick = {
                    sharing = true
                    val addr = shareEmail.trim()
                    scope.launch {
                        when (val r = deps.share.createShare(addr)) {
                            is CallResult.Ok -> { snackbar(context.getString(R.string.ui_share_snackbar_shared_success, addr)); showShare = false; load() }
                            is CallResult.Err -> snackbar(context.getString(R.string.ui_share_snackbar_share_failed, r.message ?: ""))
                        }
                        sharing = false
                    }
                }) { Text(if (sharing) stringResource(R.string.ui_share_sharing) else stringResource(R.string.ui_share_share)) }
            },
            dismissButton = { TextButton(enabled = !sharing, onClick = { showShare = false }) { Text(stringResource(R.string.ui_share_cancel)) } },
        )
    }
}

@Composable
private fun ShareCard(s: ShareGrant, onRevoke: () -> Unit) {
    val context = LocalContext.current
    CockpitCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(s.toName?.takeIf { it.isNotBlank() } ?: s.toContact ?: stringResource(R.string.ui_share_shared_user_fallback),
                    fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = MaterialTheme.colorScheme.onSurface)
                s.toContact?.takeIf { it != s.toName }?.let {
                    Text(it, color = Brand.muted, fontSize = 12.sp)
                }
                s.functionNames?.takeIf { it.isNotBlank() }?.let {
                    Text(it, color = Brand.muted, fontSize = 11.5.sp)
                }
                shareWindow(context, s)?.let { Text(it, color = Brand.faint, fontSize = 11.sp) }
            }
            TextButton(onClick = onRevoke) { Text(stringResource(R.string.ui_share_revoke), color = Brand.crit) }
        }
    }
}

@Composable
private fun ShareEmpty(title: String, body: String, onReload: () -> Unit) {
    Box(Modifier.fillMaxSize(), Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(32.dp)) {
            Icon(Icons.Filled.Group, null, tint = Brand.muted, modifier = Modifier.size(40.dp))
            Spacer(Modifier.size(12.dp))
            Text(title, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = MaterialTheme.colorScheme.onSurface)
            Spacer(Modifier.size(4.dp))
            Text(body, color = Brand.muted, fontSize = 12.5.sp)
            Spacer(Modifier.size(16.dp))
            PrimaryButton(stringResource(R.string.ui_share_reload), modifier = Modifier.fillMaxWidth(), onClick = onReload)
        }
    }
}

/** "Until 12 Mar 2027" from a genuine FUTURE end/expiry (epoch s-or-ms tolerant). Permanent digital-key
 *  shares (shareType 2) come back with a stale past `expireTime` that is not a real cutoff, so a past
 *  date is suppressed - showing "Until <past date>" on a still-active share reads as expired. */
private fun shareWindow(context: Context, s: ShareGrant): String? {
    fun ms(t: Long?) = t?.let { if (it < 100_000_000_000L) it * 1000 else it }
    val end = ms(s.endTime) ?: ms(s.expireTime) ?: return null
    if (end <= System.currentTimeMillis()) return null
    val fmt = SimpleDateFormat("d MMM yyyy", Locale.getDefault())
    return context.getString(R.string.ui_share_until_date, fmt.format(Date(end)))
}
