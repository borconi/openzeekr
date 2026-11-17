package com.openzeekr.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.PersistableBundle

/**
 * Copies text to the clipboard flagged as sensitive (ClipDescription.EXTRA_IS_SENSITIVE), so
 * Android 13+ hides it from the clipboard preview and well-behaved keyboards / clipboard sync skip
 * it. Older releases ignore the extra. The string key is used because the constant is API 33 only.
 */
object SensitiveClipboard {
    private const val EXTRA_IS_SENSITIVE = "android.content.extra.IS_SENSITIVE"

    fun copy(context: Context, label: String, text: String) {
        val cm = context.getSystemService(ClipboardManager::class.java) ?: return
        val clip = ClipData.newPlainText(label, text)
        clip.description.extras = PersistableBundle().apply { putBoolean(EXTRA_IS_SENSITIVE, true) }
        cm.setPrimaryClip(clip)
    }
}
