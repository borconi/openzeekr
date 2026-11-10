package com.openzeekr.app.ui

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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openzeekr.app.Deps
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
                Icons.AutoMirrored.Filled.ArrowBack, "Back",
                tint = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(40.dp).clip(CircleShape).clickable { onBack() }.padding(8.dp),
            )
            Text("Car sharing", fontWeight = FontWeight.Bold, fontSize = 18.sp,
                color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f).padding(start = 4.dp))
            Icon(
                Icons.Filled.PersonAdd, "Share car",
                tint = Brand.accent,
                modifier = Modifier.size(40.dp).clip(CircleShape)
                    .clickable { shareEmail = ""; showShare = true }.padding(8.dp),
            )
        }

        when {
            loading && shares.isEmpty() -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                CircularProgressIndicator(color = Brand.accent)
            }
            error != null && shares.isEmpty() -> ShareEmpty("Couldn't load shares", error ?: "Unknown error") {
                scope.launch { load() }
            }
            shares.isEmpty() -> ShareEmpty("Not shared with anyone",
                "Tap the add-person icon to share this car by email. Anyone you share with shows up here and you can revoke their access.") {
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
            title = { Text("Revoke access?") },
            text = { Text("End ${s.toName ?: s.toContact ?: "this person"}'s access to the car? They'll need a new invite to use it again.") },
            confirmButton = {
                TextButton(enabled = !revoking, onClick = {
                    revoking = true
                    scope.launch {
                        when (val r = deps.share.revoke(s)) {
                            is CallResult.Ok -> { snackbar("Access revoked"); load() }
                            is CallResult.Err -> snackbar("Couldn't revoke ✗ ${r.message}")
                        }
                        revoking = false
                        confirm = null
                    }
                }) { Text(if (revoking) "Revoking…" else "Revoke") }
            },
            dismissButton = { TextButton(enabled = !revoking, onClick = { confirm = null }) { Text("Cancel") } },
        )
    }

    if (showShare) {
        val canSend = shareEmail.trim().let { it.contains('@') && it.length >= 3 } && !sharing
        AlertDialog(
            onDismissRequest = { if (!sharing) showShare = false },
            title = { Text("Share this car") },
            text = {
                Column {
                    Text("Enter the email of the person to share with. They'll get an invite in their Zeekr app to accept.",
                        color = Brand.muted, fontSize = 13.sp)
                    Spacer(Modifier.size(12.dp))
                    OutlinedTextField(
                        value = shareEmail,
                        onValueChange = { shareEmail = it },
                        singleLine = true,
                        enabled = !sharing,
                        label = { Text("Email") },
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
                            is CallResult.Ok -> { snackbar("Shared with $addr ✓"); showShare = false; load() }
                            is CallResult.Err -> snackbar("Couldn't share ✗ ${r.message}")
                        }
                        sharing = false
                    }
                }) { Text(if (sharing) "Sharing…" else "Share") }
            },
            dismissButton = { TextButton(enabled = !sharing, onClick = { showShare = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun ShareCard(s: ShareGrant, onRevoke: () -> Unit) {
    CockpitCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(s.toName?.takeIf { it.isNotBlank() } ?: s.toContact ?: "Shared user",
                    fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = MaterialTheme.colorScheme.onSurface)
                s.toContact?.takeIf { it != s.toName }?.let {
                    Text(it, color = Brand.muted, fontSize = 12.sp)
                }
                s.functionNames?.takeIf { it.isNotBlank() }?.let {
                    Text(it, color = Brand.muted, fontSize = 11.5.sp)
                }
                shareWindow(s)?.let { Text(it, color = Brand.faint, fontSize = 11.sp) }
            }
            TextButton(onClick = onRevoke) { Text("Revoke", color = Brand.crit) }
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
            PrimaryButton("Reload", modifier = Modifier.fillMaxWidth(), onClick = onReload)
        }
    }
}

/** "Until 12 Mar 2027" from a genuine FUTURE end/expiry (epoch s-or-ms tolerant). Permanent digital-key
 *  shares (shareType 2) come back with a stale past `expireTime` that is not a real cutoff, so a past
 *  date is suppressed - showing "Until <past date>" on a still-active share reads as expired. */
private fun shareWindow(s: ShareGrant): String? {
    fun ms(t: Long?) = t?.let { if (it < 100_000_000_000L) it * 1000 else it }
    val end = ms(s.endTime) ?: ms(s.expireTime) ?: return null
    if (end <= System.currentTimeMillis()) return null
    val fmt = SimpleDateFormat("d MMM yyyy", Locale.getDefault())
    return "Until ${fmt.format(Date(end))}"
}
