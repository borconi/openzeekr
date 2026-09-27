package com.openzeekr.app.ui

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.openzeekr.app.Deps
import com.openzeekr.app.net.model.SentryVideoDetail
import com.openzeekr.app.remote.CallResult
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun SentryScreen(deps: Deps, snackbar: (String) -> Unit, modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var events by remember { mutableStateOf<List<SentryVideoDetail>>(emptyList()) }
    var loaded by remember { mutableStateOf(false) }
    val fmt = remember { SimpleDateFormat("MMM d · HH:mm", Locale.getDefault()) }

    // Download a sentry clip: if the cloud URL is ready, enqueue it; otherwise ask
    // the car to upload it, poll until the URL appears, then enqueue. Saves to the
    // public Downloads folder via the system DownloadManager (its own scoped access).
    fun download(e: SentryVideoDetail) {
        val id = e.id ?: return
        // Capture into a local val: alarmVideoUrl is a property from another module
        // (:core) so Kotlin can't smart-cast it to non-null after the check.
        val ready = e.alarmVideoUrl
        if (!ready.isNullOrBlank()) {
            enqueueDownload(context, ready, "sentry_$id")
            snackbar("Downloading clip $id…")
            return
        }
        snackbar("Asking car to upload clip $id…")
        scope.launch {
            val end = System.currentTimeMillis()
            when (val r = deps.sentry.prepareDownload(id, end - 24L * 3600 * 1000, end)) {
                is CallResult.Ok -> { enqueueDownload(context, r.value, "sentry_$id"); snackbar("Downloading clip $id…") }
                is CallResult.Err -> snackbar("✗ ${r.message}")
            }
        }
    }

    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    onClick = {
                        snackbar("Loading events…")
                        scope.launch {
                            val end = System.currentTimeMillis()
                            when (val r = deps.sentry.events(end - 24L * 3600 * 1000, end)) {
                                is CallResult.Ok -> { events = r.value; loaded = true; snackbar("${r.value.size} events") }
                                is CallResult.Err -> snackbar("✗ ${r.message}")
                            }
                        }
                    },
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Filled.Refresh, null, modifier = Modifier.size(18.dp))
                    Text("  Last 24h")
                }
                OutlinedButton(
                    onClick = {
                        snackbar("Requesting live token…")
                        scope.launch {
                            when (val r = deps.sentry.liveToken(deps.config.current().vin)) {
                                is CallResult.Ok -> snackbar("Live token ✓ (needs RTC SDK)")
                                is CallResult.Err -> snackbar("✗ ${r.message}")
                            }
                        }
                    },
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Filled.Videocam, null, modifier = Modifier.size(18.dp))
                    Text("  Live")
                }
            }
        }

        if (events.isEmpty()) {
            item { EmptyState(loaded) }
        } else {
            items(events) { e ->
                SentryEventCard(e, fmt.format(Date(e.alarmTime ?: 0)), onDownload = { download(e) })
            }
        }
    }
}

@Composable
private fun SentryEventCard(e: SentryVideoDetail, time: String, onDownload: () -> Unit) {
    val ready = !e.alarmVideoUrl.isNullOrBlank()
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Row(
            Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                Modifier.size(44.dp).clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Filled.Shield, null, tint = MaterialTheme.colorScheme.primary) }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("Level ${e.alarmLevel ?: "?"} event", fontWeight = FontWeight.SemiBold)
                Text(
                    time, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                TextButton(onClick = onDownload, contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) {
                    Icon(if (ready) Icons.Filled.Download else Icons.Filled.CloudUpload, null, modifier = Modifier.size(16.dp))
                    Text(if (ready) "  Download clip" else "  Upload + download")
                }
            }
        }
    }
}

/** Enqueue a sentry clip download to the public Downloads folder. */
private fun enqueueDownload(context: Context, url: String, name: String) {
    runCatching {
        val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val req = DownloadManager.Request(Uri.parse(url))
            .setTitle("$name.mp4")
            .setDescription("OpenZeekr sentry clip")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, "$name.mp4")
        dm.enqueue(req)
    }
}

@Composable
private fun EmptyState(loaded: Boolean) {
    Column(
        Modifier.fillMaxWidth().padding(top = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            Modifier.size(72.dp).clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Filled.Videocam, null, tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(34.dp)
            )
        }
        Text(
            if (loaded) "No sentry events in range" else "Load sentry events to begin",
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            "Cloud footage via sentinel-monitoring-service. Live view needs the RTC provider SDK (not identified yet).",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 24.dp)
        )
    }
}
