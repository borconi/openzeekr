package com.openzeekr.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.PersistableBundle

/**
 * Copy [text] to the clipboard flagged as sensitive, so Android 13+ hides it from the clipboard
 * preview/overlay and keyboards that honour the flag skip it in their clipboard history. Uses the
 * literal extra key (ClipDescription.EXTRA_IS_SENSITIVE is API 33) so it also works below 33, where
 * the flag is simply ignored. Returns false if the clipboard was unavailable or the copy failed.
 */
fun copySensitiveToClipboard(ctx: Context, label: String, text: String): Boolean = runCatching {
    val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    val clip = ClipData.newPlainText(label, text)
    clip.description.extras = PersistableBundle().apply {
        putBoolean("android.content.extra.IS_SENSITIVE", true)
    }
    cm.setPrimaryClip(clip)
    true
}.getOrDefault(false)
