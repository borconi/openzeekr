package com.openzeekr.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.PersistableBundle

/**
 * Copies [text] to the clipboard flagged as sensitive (ClipDescription.EXTRA_IS_SENSITIVE), so
 * Android 13+ hides it from the clipboard preview/overlay and keyboards that honour the flag skip
 * it in their clipboard history. The literal extra key is used because the constant is API 33 only;
 * older releases simply ignore it. Returns false if the clipboard was unavailable or the copy
 * failed (so callers never crash on a locked/absent clipboard service).
 */
object SensitiveClipboard {
    private const val EXTRA_IS_SENSITIVE = "android.content.extra.IS_SENSITIVE"

    fun copy(ctx: Context, label: String, text: String): Boolean = runCatching {
        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText(label, text)
        clip.description.extras = PersistableBundle().apply { putBoolean(EXTRA_IS_SENSITIVE, true) }
        cm.setPrimaryClip(clip)
        true
    }.getOrDefault(false)
}
