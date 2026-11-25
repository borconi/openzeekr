package com.openzeekr.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.NotificationsNone
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openzeekr.app.R
import com.openzeekr.app.net.model.InboxMessage
import com.openzeekr.app.ui.theme.Brand

/*
 * The message centre in the Studio look. Layout only: InboxScreen owns the messages, the loading and the
 * actions for both looks and hands them in here.
 */

internal class StudioInboxState(
    val messages: List<InboxMessage>,
    val loading: Boolean,
    val error: String?,
    /** Something is unread, on this page or a deeper one. */
    val hasUnread: Boolean,
    /** "Mark all read" is on its way to the server. */
    val markingAll: Boolean,
    /** The message that is open, and its web link if it has one. */
    val open: InboxMessage?,
    val openLink: String?,
)

internal class StudioInboxActions(
    val onBack: () -> Unit,
    val onReload: () -> Unit,
    val onMarkAll: () -> Unit,
    val onOpen: (InboxMessage) -> Unit,
    val onClose: () -> Unit,
    val onOpenLink: (String) -> Unit,
)

@Composable
internal fun StudioInbox(s: StudioInboxState, a: StudioInboxActions, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val firstLoad = s.loading && s.messages.isEmpty()
    Column(modifier.fillMaxSize().background(Brand.bg).statusBarsPadding()) {
        PagesBackHeader(
            title = stringResource(R.string.inbox_title),
            backDescription = stringResource(R.string.inbox_cd_back),
            onBack = a.onBack,
            modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 8.dp),
            subtitle = when {
                s.hasUnread -> stringResource(R.string.inbox_studio_has_unread)
                s.messages.isNotEmpty() -> stringResource(R.string.inbox_studio_all_read)
                else -> null
            },
        )
        if (firstLoad && !s.hasUnread) {
            Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator(color = Brand.accent) }
        } else {
            val navBar = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 20.dp + navBar),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                // Unread can sit on a deeper page than the one shown, so this is offered whatever the list holds.
                if (s.hasUnread) item(key = "mark-all") {
                    StudioButton(
                        stringResource(if (s.markingAll) R.string.inbox_marking else R.string.inbox_mark_all_read),
                        Modifier.fillMaxWidth(), StudioButtonStyle.Tonal, icon = Icons.Filled.DoneAll, busy = s.markingAll, onClick = a.onMarkAll,
                    )
                }
                when {
                    firstLoad -> item(key = "loading") {
                        Box(Modifier.fillMaxWidth().padding(vertical = 40.dp), Alignment.Center) { CircularProgressIndicator(color = Brand.accent) }
                    }
                    s.error != null && s.messages.isEmpty() -> item(key = "error") {
                        PagesEmptyCard(
                            Icons.Filled.NotificationsNone, stringResource(R.string.inbox_load_failed_title),
                            s.error.ifBlank { stringResource(R.string.inbox_unknown_error) }, tone = Brand.crit, onTone = Brand.onCrit,
                            action = stringResource(R.string.carshare_reload), onAction = a.onReload,
                        )
                    }
                    s.messages.isEmpty() -> item(key = "empty") {
                        PagesEmptyCard(Icons.Filled.NotificationsNone, stringResource(R.string.inbox_empty_title),
                            stringResource(R.string.inbox_empty_subtitle))
                    }
                    else -> items(s.messages, key = { it.id ?: it.hashCode().toString() }) { m ->
                        StudioMessageCard(m, m.timeMs?.let { inboxRelativeTime(ctx, it) }) { a.onOpen(m) }
                    }
                }
            }
        }
    }

    s.open?.let { m ->
        StudioSheet(
            onDismiss = a.onClose,
            title = m.title?.takeIf { it.isNotBlank() } ?: stringResource(R.string.inbox_message_fallback_title),
            subtitle = m.timeMs?.let { inboxRelativeTime(ctx, it) },
        ) {
            StudioCard {
                Text(m.body?.takeIf { it.isNotBlank() } ?: stringResource(R.string.inbox_no_details), fontSize = 14.5.sp, color = Brand.text)
            }
            // Only a real web link is offered, and only on an explicit tap.
            if (s.openLink != null) StudioButton(
                stringResource(R.string.inbox_open_link), Modifier.fillMaxWidth(), icon = Icons.AutoMirrored.Filled.OpenInNew,
                onClick = { a.onOpenLink(s.openLink) },
            )
            StudioButton(stringResource(R.string.inbox_close), Modifier.fillMaxWidth(), StudioButtonStyle.Tonal, onClick = a.onClose)
        }
    }
}

/** One message: what it is about (icon), the title, a preview that wraps, and when it came. */
@Composable
private fun StudioMessageCard(m: InboxMessage, time: String?, onClick: () -> Unit) {
    val (icon, tint) = inboxCategoryLook(m)
    val unread = stringResource(R.string.inbox_studio_unread_cd)
    StudioCard(padding = PaddingValues(14.dp), onClick = onClick) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.size(40.dp).clip(CircleShape).background(Brand.tint(tint)), contentAlignment = Alignment.Center) {
                Icon(icon, null, tint = tint, modifier = Modifier.size(20.dp))
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        m.title ?: stringResource(R.string.inbox_message_fallback_title),
                        fontSize = 15.sp, fontWeight = if (m.read) FontWeight.SemiBold else FontWeight.Bold,
                        color = if (m.read) Brand.muted else Brand.text, maxLines = 2, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    // Unread shows as the dot and the heavier, darker title; a screen reader hears the word.
                    if (!m.read) Box(Modifier.size(9.dp).clip(CircleShape).background(Brand.accent).semantics { contentDescription = unread })
                }
                m.body?.let { Text(it, fontSize = 13.sp, color = Brand.muted, maxLines = 3, overflow = TextOverflow.Ellipsis) }
                if (time != null) Text(time, fontSize = 12.sp, color = Brand.faint)
            }
        }
    }
}

// ------------------------------------------------------------- shared by the Inbox and Car sharing pages

/** Page header of a page that is left with a back button: the button, then the usual Studio title block. */
@Composable
internal fun PagesBackHeader(
    title: String, backDescription: String, onBack: () -> Unit, modifier: Modifier = Modifier,
    subtitle: String? = null, trailing: (@Composable () -> Unit)? = null,
) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        StudioIconButton(Icons.AutoMirrored.Filled.ArrowBack, backDescription, onClick = onBack)
        StudioPageHeader(title, subtitle, Modifier.weight(1f), trailing)
    }
}

/**
 * What a list shows when it has nothing to show: an icon, what happened, a line of explanation and, if it can
 * help, a button to load again. [tone] set = something went wrong.
 */
@Composable
internal fun PagesEmptyCard(
    icon: ImageVector, title: String, body: String, modifier: Modifier = Modifier,
    tone: Color? = null, onTone: Color = Brand.onAccent, action: String? = null, onAction: (() -> Unit)? = null,
) {
    StudioCard(modifier, padding = PaddingValues(horizontal = 20.dp, vertical = 28.dp), spacing = 8.dp) {
        StudioIconDot(icon, Modifier.align(Alignment.CenterHorizontally).padding(bottom = 4.dp), tint = tone, onTint = onTone, size = 52.dp)
        Text(title, fontFamily = Brand.display, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = Brand.text,
            textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        Text(body, fontSize = 13.sp, color = Brand.muted, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        if (action != null && onAction != null) StudioButton(action, Modifier.fillMaxWidth().padding(top = 8.dp), StudioButtonStyle.Tonal,
            icon = Icons.Filled.Refresh, onClick = onAction)
    }
}
